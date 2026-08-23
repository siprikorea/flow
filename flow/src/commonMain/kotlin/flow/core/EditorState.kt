package flow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.OptType
import flow.model.Port
import flow.model.PortRef
import flow.engine.FlowEngine
import flow.model.compFile
import flow.model.findDef
import flow.model.indexOfPort
import flow.model.isComp
import flow.model.portNames
import flow.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.hypot

private const val HISTORY_MAX = 60

data class Wire(val node: String, val port: String, val pos: Offset)
data class DragModule(val type: String, val pos: Offset) // pos: window coordinates (px)

// State of a single document (tab). Global UI (language, panels, menu) is delegated to Workspace.
class EditorState(
    private val scope: CoroutineScope,
    val ws: Workspace,
    var fileName: String,
) {
    var nodes by mutableStateOf(listOf<Node>())
    var edges by mutableStateOf(listOf<Edge>())
    var seq by mutableStateOf(1)
    var pan by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(1f)
    // multi-selection: sets of node/edge ids
    var selNodes by mutableStateOf(setOf<String>())
    var selEdges by mutableStateOf(setOf<String>())
    var selRect by mutableStateOf<Rect?>(null) // rubber-band selection rect (world dp)
    var wire by mutableStateOf<Wire?>(null)
    var running by mutableStateOf(false)
    // transient run outputs: cout node id -> output bytes (not persisted to the file)
    var runOutputs by mutableStateOf<Map<String, ByteArray>>(emptyMap())
    // transient per-node processing errors from the last run (not persisted); node id -> message
    var nodeErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var spaceDown by mutableStateOf(false)
    var canvasSize by mutableStateOf(IntSize.Zero)
    var canvasOrigin by mutableStateOf(Offset.Zero) // window coordinates
    var cursorWorld by mutableStateOf(Offset(200f, 200f)) // last pointer position on the canvas (world dp), paste target
    var density by mutableStateOf(1f)
    var textEditing by mutableStateOf(false)
    // once a component has been opened or saved, unconnected modules are flagged
    // on the canvas (red border + message). New blank docs stay quiet until saved.
    var showValidation by mutableStateOf(false)

    // delegated global UI — so components keep using state.xxx unchanged
    var lang: String
        get() = ws.lang
        set(v) { ws.lang = v }
    var menu: String?
        get() = ws.menu
        set(v) { ws.menu = v }
    var dragModule: DragModule?
        get() = ws.dragModule
        set(v) { ws.dragModule = v }
    var showMinimap: Boolean
        get() = ws.showMinimap
        set(v) { ws.showMinimap = v }
    var saveTime: String?
        get() = ws.saveTime
        set(v) { ws.saveTime = v }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private val simJobs = mutableSetOf<Job>()
    private var computeJob: Job? = null

    // normalized signature at the last save (or open); basis for the dirty check.
    var savedSig by mutableStateOf("")
        private set

    // whether it has ever been saved to a file. New (unsaved) docs are false -> always dirty.
    var persisted by mutableStateOf(true)

    // initialize the document from file contents
    fun load(flow: FlowFile) {
        nodes = flow.nodes
        edges = flow.edges
        seq = flow.seq
        pan = Offset.Zero
        zoom = 1f
        clearSel()
        wire = null
        running = false
        past.clear()
        future.clear()
        savedSig = flowJson()
    }

    /* ───────── selection ───────── */

    fun clearSel() { selNodes = emptySet(); selEdges = emptySet() }
    fun selectNode(id: String) { selNodes = setOf(id); selEdges = emptySet() }
    fun toggleNode(id: String) { selNodes = if (id in selNodes) selNodes - id else selNodes + id }
    fun selectEdge(id: String) { selEdges = setOf(id); selNodes = emptySet() }
    val isNodeSelected: (String) -> Boolean get() = { it in selNodes }

    // select nodes/edges overlapping the rubber-band rect
    fun selectInRect(r: Rect) {
        selNodes = nodes.filter { r.overlaps(Rect(it.x, it.y, it.x + it.w, it.y + it.h)) }.map { it.id }.toSet()
        selEdges = edges.filter { e ->
            val from = nodeById(e.from.node); val to = nodeById(e.to.node)
            if (from == null || to == null) false
            else {
                val a = portPos(from, "out", from.outputs.indexOfPort(e.from.port).coerceAtLeast(0))
                val b = portPos(to, "in", to.inputs.indexOfPort(e.to.port).coerceAtLeast(0))
                (0..24).any { r.contains(bezierPoint(a, b, it / 24f)) }
            }
        }.map { it.id }.toSet()
    }

    // param keys common to the selected nodes (intersection)
    fun commonParamKeys(): List<String> {
        val sel = nodes.filter { it.id in selNodes }
        if (sel.isEmpty()) return emptyList()
        return sel.map { it.params.keys }.reduce { acc, keys -> acc intersect keys }.toList().sorted()
    }

    fun setParamForSelected(key: String, value: String) {
        nodes = nodes.map { if (it.id in selNodes && it.params.containsKey(key)) it.copy(params = it.params + (key to value)) else it }
    }

    // JSON to write to file — run state (status/active) is reset and not persisted.
    fun flowJson(): String = json.encodeToString(
        FlowFile(
            version = 1,
            nodes = nodes.map { if (it.status == "idle") it else it.copy(status = "idle") },
            edges = edges.map { if (it.active) it.copy(active = false) else it },
            seq = seq,
        )
    )

    // whether there are unsaved edits (new unsaved docs are always dirty; run-state changes excluded)
    val dirty: Boolean get() = !persisted || flowJson() != savedSig

    // Blocking error: a component needs at least one input and one output boundary node.
    fun validateComponent(): String? {
        val cins = nodes.filter { it.type == "cin" }
        val couts = nodes.filter { it.type == "cout" }
        if (cins.isEmpty() || couts.isEmpty()) return t("valNeedIo")
        return null
    }

    // Per-node connection error message (null = fully connected). Used on the
    // canvas to flag unconnected modules after an open/save validation.
    fun nodeConnectionError(node: Node): String? {
        val missIn = node.inputs.any { port -> edges.none { it.to.node == node.id && it.to.port == port.name } }
        val missOut = node.outputs.any { port -> edges.none { it.from.node == node.id && it.from.port == port.name } }
        return when {
            missIn && missOut -> t("valNodeInOut")
            missIn -> t("valNodeIn")
            missOut -> t("valNodeOut")
            else -> null
        }
    }

    // Non-blocking warning: any node port (boundary or module/component) left unconnected.
    fun connectionWarning(): String? {
        val hasUnconnectedInput = nodes.any { n ->
            n.inputs.any { port -> edges.none { it.to.node == n.id && it.to.port == port.name } }
        }
        val hasUnconnectedOutput = nodes.any { n ->
            n.outputs.any { port -> edges.none { it.from.node == n.id && it.from.port == port.name } }
        }
        return if (hasUnconnectedInput || hasUnconnectedOutput) t("valNotConnected") else null
    }

    /**
     * Where this document lives when it is not inside the project folder — a file opened on its own
     * with no folder open, the way an editor lets you work on one file. Null means [fileName] is a
     * path relative to the project.
     */
    var standalonePath: String? = null

    // save to file; missing in/out boundary blocks the save, unconnected ports only warn
    fun save(): Boolean {
        showValidation = true // validate on save: flag unconnected modules on the canvas
        // clear any leftover run state so its status borders (e.g. green "done")
        // don't mask the red validation borders on problem modules
        resetRun()
        running = false
        validateComponent()?.let { ws.showError(it); return false }
        val standalone = standalonePath
        if (standalone != null) {
            if (!Platform.writeExternalFlow(standalone, flowJson())) {
                ws.showError(ws.t("saveFailed").replace("{name}", Platform.fileName(standalone)))
                return false
            }
        } else if (ws.hasProject) {
            Platform.writeFlow(fileName, flowJson())
        } else {
            // nowhere to put it yet: ask, and remember the answer for the next save
            val picked = Platform.pickFileSave(Platform.fileName(fileName)) ?: return false
            val target = if (picked.endsWith(".flow")) picked else "$picked.flow"
            if (!Platform.writeExternalFlow(target, flowJson())) {
                ws.showError(ws.t("saveFailed").replace("{name}", Platform.fileName(target)))
                return false
            }
            standalonePath = target
            fileName = target
        }
        persisted = true
        savedSig = flowJson()
        saveTime = Platform.currentTimeHms()
        ws.refreshFiles()
        connectionWarning()?.let { ws.saveWarn = true; ws.saveError = it } // warn but keep the save
        return true
    }

    fun t(key: String) = flow.i18n.tr(lang, key)

    fun nodeById(id: String) = nodes.find { it.id == id }

    /* ───────── history ───────── */

    fun snapshot(): String = json.encodeToString(FlowFile(1, nodes, edges, seq))

    fun pushHistory(snap: String? = null) {
        past.addLast(snap ?: snapshot())
        while (past.size > HISTORY_MAX) past.removeFirst()
        future.clear()
    }

    private fun applySnapshot(s: String) {
        val f = json.decodeFromString<FlowFile>(s)
        nodes = f.nodes
        edges = f.edges
        seq = f.seq
        clearSel()
    }

    fun undo() {
        if (past.isEmpty()) return
        future.addLast(snapshot())
        applySnapshot(past.removeLast())
    }

    fun redo() {
        if (future.isEmpty()) return
        past.addLast(snapshot())
        applySnapshot(future.removeLast())
    }

    /* ───────── coordinate transforms ───────── */

    // world unit is dp. screen px = world·density·zoom + pan
    fun screenToWorld(px: Offset): Offset =
        Offset((px.x - pan.x) / (zoom * density), (px.y - pan.y) / (zoom * density))

    /* ───────── node create/delete/edit ───────── */

    fun addNodeAt(type: String, world: Offset) {
        val label: String
        val ins: List<String>
        val outs: List<String>
        val params: Map<String, String>
        val idBase: String
        val moduleInfo = ws.moduleInfo(type)
        if (isComp(type)) {
            val c = ws.findComp(compFile(type)) ?: return
            label = c.name; ins = c.ins; outs = c.outs; params = emptyMap(); idBase = "comp"
        } else if (moduleInfo != null) {
            label = moduleInfo.name
            params = moduleInfo.options.associate { it.name to it.default } // seed extension option defaults
            // ports for a module can depend on its (default) option values (e.g. flow.signature's
            // "signature" input only applies to verify) — ask the extension, not the static list
            ins = Platform.moduleInputsFor(type, params) ?: moduleInfo.inputs
            outs = Platform.moduleOutputsFor(type, params) ?: moduleInfo.outputs
            idBase = type.substringAfterLast('.').ifBlank { "mod" }
        } else {
            val def = findDef(type) ?: return
            // labels are purely for the user to tell nodes apart on the canvas — duplicates are
            // fine anywhere (including cin/cout) since the engine identifies nodes by id, never
            // by label (see computeOutputs() and Engine.evalNode's cin lookup)
            label = def.name[lang] ?: def.name["ko"] ?: type
            ins = def.ins; outs = def.outs; params = def.defaultParams(); idBase = type
        }
        pushHistory()
        // every palette drop starts at the same uniform size
        val w = 180f
        val h = 100f
        val id = "${idBase}_$seq"
        nodes = nodes + Node(
            id = id, type = type, label = label,
            x = snapF(world.x - w / 2), y = snapF(world.y - 20f),
            w = w, h = h, inputs = ins.map { Port(it) }, outputs = outs.map { Port(it) }, params = params,
        )
        seq += 1
        selectNode(id)
    }

    fun updateNode(id: String, transform: (Node) -> Node) {
        nodes = nodes.map { if (it.id == id) transform(it) else it }
    }

    fun moveNode(id: String, x: Float, y: Float) = updateNode(id) { it.copy(x = x, y = y) }

    fun resizeNode(id: String, w: Float, h: Float) = updateNode(id) { it.copy(w = w, h = h) }

    fun deleteNode(id: String) {
        pushHistory()
        nodes = nodes.filter { it.id != id }
        edges = edges.filter { it.from.node != id && it.to.node != id }
        clearSel()
    }

    fun deleteEdge(id: String) {
        pushHistory()
        edges = edges.filter { it.id != id }
        clearSel()
    }

    // delete all selected nodes/edges (including edges attached to the nodes)
    fun deleteSelection() {
        if (selNodes.isEmpty() && selEdges.isEmpty()) return
        pushHistory()
        val ns = selNodes
        val es = selEdges
        nodes = nodes.filter { it.id !in ns }
        edges = edges.filter { it.id !in es && it.from.node !in ns && it.to.node !in ns }
        clearSel()
    }

    /* ───────── copy / paste ───────── */

    // copy the selected modules (and the edges between them) to the workspace clipboard
    fun copySelection() {
        val ns = nodes.filter { it.id in selNodes }
        if (ns.isEmpty()) return
        val es = edges.filter { it.from.node in selNodes && it.to.node in selNodes }
        ws.clipboard = FlowFile(1, ns, es, 0)
    }

    // paste the clipboard centered at the current cursor position, with fresh ids
    fun paste() {
        val clip = ws.clipboard ?: return
        if (clip.nodes.isEmpty()) return
        pushHistory()
        val minX = clip.nodes.minOf { it.x }
        val minY = clip.nodes.minOf { it.y }
        val maxX = clip.nodes.maxOf { it.x + it.w }
        val maxY = clip.nodes.maxOf { it.y + it.h }
        val dx = cursorWorld.x - (minX + maxX) / 2
        val dy = cursorWorld.y - (minY + maxY) / 2
        var s = seq
        val idMap = clip.nodes.associate { it.id to "${it.id}_${s++}" }
        nodes = nodes + clip.nodes.map {
            it.copy(id = idMap[it.id]!!, x = snapF(it.x + dx), y = snapF(it.y + dy), status = "idle")
        }
        edges = edges + clip.edges.map { e ->
            Edge("e_${s++}", PortRef(idMap[e.from.node]!!, e.from.port), PortRef(idMap[e.to.node]!!, e.to.port))
        }
        seq = s
        selNodes = idMap.values.toSet()
        selEdges = emptySet()
    }

    fun renamePort(nodeId: String, kind: String, idx: Int, name: String) {
        val node = nodeById(nodeId) ?: return
        val list = if (kind == "in") node.inputs else node.outputs
        val old = list.getOrNull(idx) ?: return
        if (old.name == name) return
        val next = list.toMutableList().also { it[idx] = old.withName(name) } // keep the port's data
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = next) else it.copy(outputs = next) }
        edges = edges.map {
            when {
                kind == "in" && it.to.node == nodeId && it.to.port == old.name -> it.copy(to = it.to.copy(port = name))
                kind == "out" && it.from.node == nodeId && it.from.port == old.name -> it.copy(from = it.from.copy(port = name))
                else -> it
            }
        }
    }

    fun removePort(nodeId: String, kind: String, idx: Int) {
        val node = nodeById(nodeId) ?: return
        val list = if (kind == "in") node.inputs else node.outputs
        val port = list.getOrNull(idx) ?: return
        pushHistory()
        val next = list.filterIndexed { i, _ -> i != idx }
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = next) else it.copy(outputs = next) }
        edges = edges.filter {
            !((kind == "in" && it.to.node == nodeId && it.to.port == port.name) ||
                (kind == "out" && it.from.node == nodeId && it.from.port == port.name))
        }
    }

    fun addPort(nodeId: String, kind: String) {
        val node = nodeById(nodeId) ?: return
        pushHistory()
        val list = if (kind == "in") node.inputs else node.outputs
        val base = if (kind == "in") "in" else "out"
        var i = list.size + 1
        var name = "$base$i"
        while (list.any { it.name == name }) name = "$base${++i}"
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = list + Port(name)) else it.copy(outputs = list + Port(name)) }
    }

    // Sets an extension module option and, if that option changes which ports the module declares
    // (e.g. flow.signature's "signature" input only applies when operation=verify), resyncs the
    // node's actual ports to match — adding newly-relevant ports, dropping ones that no longer
    // apply (and any edge wired to them). Ports on module nodes are never user-added/renamed/removed
    // directly; this is the only thing that changes them, driven entirely by the option value.
    fun setModuleOption(nodeId: String, optName: String, value: String) {
        val node = nodeById(nodeId) ?: return
        var newParams = node.params + (optName to value)
        // one option can change which options apply and what they offer, so a stored choice can
        // become invalid (e.g. a symmetric padding once the algorithm turns to RSA) — put those
        // back on the new option's default
        ws.moduleOptions(node.type, newParams).forEach { opt ->
            if (opt.type == OptType.SELECT && opt.choices.isNotEmpty() && newParams[opt.name] !in opt.choices) {
                newParams = newParams + (opt.name to opt.choices.firstOrNull { it == opt.default }.orEmpty().ifEmpty { opt.choices.first() })
            }
        }
        val newInNames = Platform.moduleInputsFor(node.type, newParams)
        val newOutNames = Platform.moduleOutputsFor(node.type, newParams)
        if (newInNames == null || newOutNames == null) {
            // not a recognized extension module — just record the value, no ports to resync
            updateNode(nodeId) { it.copy(params = newParams) }
            return
        }
        val portsChanged = newInNames != node.inputs.portNames() || newOutNames != node.outputs.portNames()
        if (portsChanged) pushHistory()
        val newIns = newInNames.map { name -> node.inputs.find { it.name == name } ?: Port(name) }
        val newOuts = newOutNames.map { name -> node.outputs.find { it.name == name } ?: Port(name) }
        updateNode(nodeId) { it.copy(params = newParams, inputs = newIns, outputs = newOuts) }
        if (portsChanged) {
            val keepIn = newInNames.toSet()
            val keepOut = newOutNames.toSet()
            edges = edges.filter {
                !(it.to.node == nodeId && it.to.port !in keepIn) && !(it.from.node == nodeId && it.from.port !in keepOut)
            }
        }
    }

    /* ───────── connections ───────── */

    // finish a wire drag: snap to the nearest input port in world coordinates
    fun completeWire(world: Offset) {
        val w = wire ?: return
        wire = null
        for (node in nodes) {
            if (node.id == w.node) continue // no self-connection
            node.inputs.forEachIndexed { idx, port ->
                val p = portPos(node, "in", idx)
                if (hypot(p.x - world.x, p.y - world.y) <= 12f) {
                    pushHistory()
                    edges = edges.filter { !(it.to.node == node.id && it.to.port == port.name) } + // one edge per input port — replace
                        Edge("e_$seq", PortRef(w.node, w.port), PortRef(node.id, port.name))
                    seq += 1
                    return
                }
            }
        }
    }

    // find the edge near the click point (sampling the curve at 32 points)
    fun edgeAt(world: Offset): String? {
        for (e in edges) {
            val from = nodeById(e.from.node) ?: continue
            val to = nodeById(e.to.node) ?: continue
            // anchors sit at the port circle's outer edge (see CanvasView) so the hit path matches the drawing
            val a = portPos(from, "out", from.outputs.indexOfPort(e.from.port).coerceAtLeast(0)).let { it.copy(x = it.x + 8f) }
            val b = portPos(to, "in", to.inputs.indexOfPort(e.to.port).coerceAtLeast(0)).let { it.copy(x = it.x - 8f) }
            for (i in 0..32) {
                val p = bezierPoint(a, b, i / 32f)
                if (hypot(p.x - world.x, p.y - world.y) <= 8f) return e.id
            }
        }
        return null
    }

    /* ───────── pan/zoom ───────── */

    fun zoomAt(pointerPx: Offset, scrollY: Float) {
        val z1 = (zoom * exp(-scrollY * 0.12f)).coerceIn(0.3f, 2.5f)
        if (z1 == zoom) return
        val k = z1 / zoom
        pan = Offset(pointerPx.x - (pointerPx.x - pan.x) * k, pointerPx.y - (pointerPx.y - pan.y) * k)
        zoom = z1
    }

    fun minimapJump(world: Offset) {
        pan = Offset(
            canvasSize.width / 2f - world.x * zoom * density,
            canvasSize.height / 2f - world.y * zoom * density,
        )
    }

    /* ───────── run simulation ───────── */

    private fun setStatus(id: String, status: String) = updateNode(id) { it.copy(status = status) }

    private fun setEdgeActive(id: String, active: Boolean) {
        edges = edges.map { if (it.id == id) it.copy(active = active) else it }
    }

    private fun later(ms: Long, block: () -> Unit) {
        val job = scope.launch {
            delay(ms)
            block()
        }
        simJobs.add(job)
        job.invokeOnCompletion {
            simJobs.remove(job)
            // stop running automatically once no timers remain
            if (running && simJobs.isEmpty()) running = false
        }
    }

    // node id -> error (or null) as the engine finishes each one. The animation waits on its own
    // node rather than the whole pass, so a slow module holds up only what comes after it.
    private val settled = MutableStateFlow<Map<String, String?>>(emptyMap())

    private fun runNode(id: String) {
        val node = nodeById(id) ?: return
        if (node.status == "running" || node.status == "done") return
        val incoming = edges.filter { it.to.node == id }
        // error if it has input ports but no incoming connection
        if (node.inputs.isNotEmpty() && incoming.isEmpty()) {
            setStatus(id, "error")
            return
        }
        // wait until every input is ready: each incoming edge's source node must be done.
        // (re-triggered as each edge completes, so a merge starts only once all arrive)
        if (incoming.any { nodeById(it.from.node)?.status != "done" }) return
        setStatus(id, "running")
        // per-step animation duration in ms (from the seconds setting; larger = slower). Every
        // node gets the same step: what a module does with its own options is the module's
        // business, and naming particular ids here made the host carry one extension's behaviour.
        val stepMs = (ws.animSeconds.coerceIn(0.05f, 10f) * 1000).toLong()
        val job = scope.launch {
            // stay on "running" until the engine has actually finished this node, so a module that
            // takes real time is shown taking it, then hold the step so the animation stays legible
            val error = settled.first { id in it }[id]
            if (error != null) {
                setStatus(id, "error")
                return@launch
            }
            delay(stepMs)
            setStatus(id, "done")
            edges.filter { it.from.node == id }.forEach { e ->
                setEdgeActive(e.id, true)
                later(stepMs) {
                    setEdgeActive(e.id, false)
                    runNode(e.to.node)
                }
            }
        }
        simJobs.add(job)
        job.invokeOnCompletion {
            simJobs.remove(job)
            if (running && simJobs.isEmpty()) running = false
        }
    }

    private fun resetRun() {
        // the engine pass too, not only the animation: leaving it running meant Stop looked like it
        // had worked while a module carried on and its results still landed when it finished
        computeJob?.cancel()
        simJobs.toList().forEach { it.cancel() }
        simJobs.clear()
        nodes = nodes.map { if (it.status == "idle") it else it.copy(status = "idle") }
        edges = edges.map { if (it.active) it.copy(active = false) else it }
    }

    fun startRun() {
        if (nodes.isEmpty()) return
        resetRun()
        showValidation = false // validation is only for open/save; run shows only run status
        running = true
        computeOutputs() // compute real data so the output (cout) editors show the result
        // start from source nodes (no incoming edges); otherwise the first node
        val sources = nodes.filter { n -> edges.none { it.to.node == n.id } }
        val starts = sources.ifEmpty { listOf(nodes.first()) }
        later(0) { starts.forEach { runNode(it.id) } }
    }

    // Run the flow engine over the current graph and store cout outputs (transient).
    // Reads file-backed cin data and runs the engine off the UI thread (Dispatchers.IO), so a
    // large file (or a slow module) doesn't freeze the canvas while it's read and processed.
    // Snapshots everything nodes/edges-derived synchronously first so a concurrent edit mid-run
    // can't tear the computation; a newer call cancels whatever the previous one had in flight.
    private fun computeOutputs() {
        computeJob?.cancel()
        settled.value = emptyMap()
        val flow = FlowFile(1, nodes, edges, seq)
        val cins = nodes.filter { it.type == "cin" }
        val coutIds = nodes.filter { it.type == "cout" }.map { it.id }
        computeJob = scope.launch(Dispatchers.IO) {
            val inputs = cins.associate { n ->
                // file-backed cin: read the whole file for processing; else the inline port bytes
                val size = cinFileSize(n)
                val data = if (size != null && size <= Int.MAX_VALUE.toLong()) {
                    Platform.readFileRange(n.params["dataFile"]!!, 0, size.toInt())
                } else (n.outputs.firstOrNull()?.data ?: ByteArray(0))
                // key by cin node id (not label) so two inputs never share a value — see
                // Engine.evalNode's cin lookup, which tries id before falling back to label
                n.id to data
            }
            val engine = FlowEngine(
                loadFlow = { name ->
                    (Platform.readFlow(name) ?: Platform.readInstalledComponent(name))
                        ?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() }
                },
                moduleIds = runCatching { Platform.installedModuleInfos().map { it.id }.toSet() }.getOrDefault(emptySet()),
                // let a module's exception propagate — the engine attributes it to the failing node
                moduleProcess = { id, ins, params -> Platform.moduleProcess(id, ins, params) },
                onNodeSettled = { nid, err -> settled.update { it + (nid to err) } },
            )
            // key by cout node id (not label) so two outputs never share a value
            val outcome = runCatching { engine.runByNode(flow, inputs) }
            val result = outcome.getOrDefault(emptyMap())
            // If the pass died before reaching some node, nothing would ever settle it and the
            // animation waiting on it would sit at "running" for good. Settle the remainder as
            // failed: the run did not produce their values, whatever the reason.
            val reason = outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "error" }
            settled.update { done ->
                done + flow.nodes.filter { it.id !in done }.associate { it.id to (reason ?: "run did not complete") }
            }
            withContext(Dispatchers.Main) {
                nodeErrors = engine.errors
                runOutputs = coutIds.associateWith { id -> result[id] ?: ByteArray(0) }
            }
        }
    }

    fun runFromSelection(id: String? = null) {
        val nodeId = id ?: selNodes.firstOrNull() ?: return
        resetRun()
        showValidation = false // validation is only for open/save; run shows only run status
        running = true
        computeOutputs()
        // a node with input ports but no incoming connection fails here too (marked red),
        // just like a full run — it can't run without its input
        later(0) { runNode(nodeId) }
    }

    fun stopRun() {
        computeJob?.cancel()
        simJobs.toList().forEach { it.cancel() }
        simJobs.clear()
        running = false
        nodes = nodes.map { if (it.status == "running") it.copy(status = "idle") else it }
        edges = edges.map { if (it.active) it.copy(active = false) else it }
    }

    /* ───────── auto layout (BFS depth columns) ───────── */

    fun autoLayout() {
        if (nodes.isEmpty()) return
        pushHistory()
        val depth = nodes.associate { it.id to 0 }.toMutableMap()
        repeat(nodes.size) {
            var changed = false
            edges.forEach { e ->
                val df = depth[e.from.node] ?: return@forEach
                if (e.to.node !in depth) return@forEach
                val d = df + 1
                if (d > depth[e.to.node]!! && d <= nodes.size) {
                    depth[e.to.node] = d
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        val cursorY = mutableMapOf<Int, Float>()
        nodes = nodes.map { n ->
            val d = depth[n.id] ?: 0
            val y = cursorY.getOrElse(d) { 60f }
            cursorY[d] = y + n.h + 50f
            n.copy(x = snapF(60f + d * 280f), y = snapF(y))
        }
    }

    /* ───────── sidebar drag & drop ───────── */

    fun dropModule() {
        val d = dragModule ?: return
        dragModule = null
        val local = d.pos - canvasOrigin
        val inCanvas = local.x >= 0 && local.y >= 0 &&
            local.x <= canvasSize.width && local.y <= canvasSize.height
        if (inCanvas) addNodeAt(d.type, screenToWorld(local))
    }
}

/**
 * The size of the file a cin node reads from, or null when it has none — including when the path it
 * remembers no longer resolves.
 *
 * A file that cannot be read is not a file. Treating a dangling path as one made a run quietly use
 * no data at all, while the data editor — which decides the same way — showed the bytes held on the
 * port and let them be edited. The two have to answer this question identically or the canvas shows
 * one input and the run uses another.
 */
fun cinFileSize(node: Node): Long? =
    node.params["dataFile"]?.let { Platform.fileSize(it) }?.takeIf { it >= 0 }
