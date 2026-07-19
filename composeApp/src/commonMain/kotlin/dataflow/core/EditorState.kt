package dataflow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dataflow.model.Edge
import dataflow.model.FlowFile
import dataflow.model.Node
import dataflow.model.PortRef
import dataflow.model.compFile
import dataflow.model.findDef
import dataflow.model.isComp
import dataflow.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.hypot

private const val HISTORY_MAX = 60
private const val SPEED = 1f

data class Wire(val node: String, val port: String, val pos: Offset)
data class DragModule(val type: String, val pos: Offset) // pos: 윈도 좌표(px)

// 문서(탭) 하나의 상태. 언어·패널·메뉴 등 전역 UI 는 Workspace 로 위임한다.
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
    // 다중 선택: 노드/엣지 id 집합
    var selNodes by mutableStateOf(setOf<String>())
    var selEdges by mutableStateOf(setOf<String>())
    var selRect by mutableStateOf<Rect?>(null) // 드래그 선택 사각형 (world dp)
    var wire by mutableStateOf<Wire?>(null)
    var running by mutableStateOf(false)
    var spaceDown by mutableStateOf(false)
    var canvasSize by mutableStateOf(IntSize.Zero)
    var canvasOrigin by mutableStateOf(Offset.Zero) // 윈도 좌표
    var density by mutableStateOf(1f)
    var textEditing by mutableStateOf(false)

    // 전역 UI 위임 — 기존 컴포넌트가 state.xxx 그대로 쓰도록 유지
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

    // 마지막으로 저장된(또는 열린) 시점의 정규화 시그니처. dirty 판정 기준.
    var savedSig by mutableStateOf("")
        private set

    // 파일 내용으로 문서 초기화
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

    /* ───────── 선택 ───────── */

    fun clearSel() { selNodes = emptySet(); selEdges = emptySet() }
    fun selectNode(id: String) { selNodes = setOf(id); selEdges = emptySet() }
    fun toggleNode(id: String) { selNodes = if (id in selNodes) selNodes - id else selNodes + id }
    fun selectEdge(id: String) { selEdges = setOf(id); selNodes = emptySet() }
    val isNodeSelected: (String) -> Boolean get() = { it in selNodes }

    // 드래그 사각형과 겹치는 노드/엣지 선택
    fun selectInRect(r: Rect) {
        selNodes = nodes.filter { r.overlaps(Rect(it.x, it.y, it.x + it.w, it.y + it.h)) }.map { it.id }.toSet()
        selEdges = edges.filter { e ->
            val from = nodeById(e.from.node); val to = nodeById(e.to.node)
            if (from == null || to == null) false
            else {
                val a = portPos(from, "out", from.outputs.indexOf(e.from.port).coerceAtLeast(0))
                val b = portPos(to, "in", to.inputs.indexOf(e.to.port).coerceAtLeast(0))
                (0..24).any { r.contains(bezierPoint(a, b, it / 24f)) }
            }
        }.map { it.id }.toSet()
    }

    // 여러 노드에 공통 파라미터 키 (교집합)
    fun commonParamKeys(): List<String> {
        val sel = nodes.filter { it.id in selNodes }
        if (sel.isEmpty()) return emptyList()
        return sel.map { it.params.keys }.reduce { acc, keys -> acc intersect keys }.toList().sorted()
    }

    fun setParamForSelected(key: String, value: String) {
        nodes = nodes.map { if (it.id in selNodes && it.params.containsKey(key)) it.copy(params = it.params + (key to value)) else it }
    }

    // 파일에 저장할 JSON — 실행 상태(status/active)는 초기화해 영속화하지 않는다.
    fun flowJson(): String = json.encodeToString(
        FlowFile(
            version = 1,
            nodes = nodes.map { if (it.status == "idle") it else it.copy(status = "idle") },
            edges = edges.map { if (it.active) it.copy(active = false) else it },
            seq = seq,
        )
    )

    // 저장되지 않은 수정이 있는가 (실행 상태 변화는 제외)
    val dirty: Boolean get() = flowJson() != savedSig

    // 파일에 저장하고 저장 시그니처·시각 갱신
    fun save() {
        Platform.writeFlow(fileName, flowJson())
        savedSig = flowJson()
        saveTime = Platform.currentTimeHms()
    }

    fun t(key: String) = dataflow.i18n.tr(lang, key)

    fun nodeById(id: String) = nodes.find { it.id == id }

    /* ───────── 히스토리 ───────── */

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

    /* ───────── 좌표 변환 ───────── */

    // world 단위는 dp. screen px = world·density·zoom + pan
    fun screenToWorld(px: Offset): Offset =
        Offset((px.x - pan.x) / (zoom * density), (px.y - pan.y) / (zoom * density))

    /* ───────── 노드 생성/삭제/편집 ───────── */

    fun addNodeAt(type: String, world: Offset) {
        val label: String
        val ins: List<String>
        val outs: List<String>
        val params: Map<String, String>
        val idBase: String
        if (isComp(type)) {
            val c = ws.findComp(compFile(type)) ?: return
            label = c.name; ins = c.ins; outs = c.outs; params = emptyMap(); idBase = "comp"
        } else {
            val def = findDef(type) ?: return
            label = def.name[lang] ?: def.name["ko"] ?: type
            ins = def.ins; outs = def.outs; params = def.params; idBase = type
        }
        pushHistory()
        val (w, h) = sizeForPorts(ins.size, outs.size)
        val id = "${idBase}_$seq"
        nodes = nodes + Node(
            id = id, type = type, label = label,
            x = snapF(world.x - w / 2), y = snapF(world.y - 20f),
            w = w, h = h, inputs = ins, outputs = outs, params = params,
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

    // 선택된 노드/엣지 전체 삭제 (노드에 연결된 엣지도 함께)
    fun deleteSelection() {
        if (selNodes.isEmpty() && selEdges.isEmpty()) return
        pushHistory()
        val ns = selNodes
        val es = selEdges
        nodes = nodes.filter { it.id !in ns }
        edges = edges.filter { it.id !in es && it.from.node !in ns && it.to.node !in ns }
        clearSel()
    }

    // 노드 id 변경 시 참조하는 모든 엣지 동기화
    fun renameNodeId(oldId: String, newId: String): Boolean {
        if (newId.isBlank() || newId == oldId || nodes.any { it.id == newId }) return false
        nodes = nodes.map { if (it.id == oldId) it.copy(id = newId) else it }
        edges = edges.map {
            it.copy(
                from = if (it.from.node == oldId) it.from.copy(node = newId) else it.from,
                to = if (it.to.node == oldId) it.to.copy(node = newId) else it.to,
            )
        }
        selectNode(newId)
        return true
    }

    fun renamePort(nodeId: String, kind: String, idx: Int, name: String) {
        val node = nodeById(nodeId) ?: return
        val list = if (kind == "in") node.inputs else node.outputs
        val old = list.getOrNull(idx) ?: return
        if (old == name) return
        val next = list.toMutableList().also { it[idx] = name }
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = next) else it.copy(outputs = next) }
        edges = edges.map {
            when {
                kind == "in" && it.to.node == nodeId && it.to.port == old -> it.copy(to = it.to.copy(port = name))
                kind == "out" && it.from.node == nodeId && it.from.port == old -> it.copy(from = it.from.copy(port = name))
                else -> it
            }
        }
    }

    fun removePort(nodeId: String, kind: String, idx: Int) {
        val node = nodeById(nodeId) ?: return
        val list = if (kind == "in") node.inputs else node.outputs
        val name = list.getOrNull(idx) ?: return
        pushHistory()
        val next = list.filterIndexed { i, _ -> i != idx }
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = next) else it.copy(outputs = next) }
        edges = edges.filter {
            !((kind == "in" && it.to.node == nodeId && it.to.port == name) ||
                (kind == "out" && it.from.node == nodeId && it.from.port == name))
        }
    }

    fun addPort(nodeId: String, kind: String) {
        val node = nodeById(nodeId) ?: return
        pushHistory()
        val list = if (kind == "in") node.inputs else node.outputs
        val base = if (kind == "in") "in" else "out"
        var i = list.size + 1
        var name = "$base$i"
        while (list.contains(name)) name = "$base${++i}"
        updateNode(nodeId) { if (kind == "in") it.copy(inputs = list + name) else it.copy(outputs = list + name) }
    }

    /* ───────── 연결 ───────── */

    // 연결 드래그 종료: world 좌표에서 가장 가까운 입력 포트에 스냅
    fun completeWire(world: Offset) {
        val w = wire ?: return
        wire = null
        for (node in nodes) {
            if (node.id == w.node) continue // 같은 노드 금지
            node.inputs.forEachIndexed { idx, port ->
                val p = portPos(node, "in", idx)
                if (hypot(p.x - world.x, p.y - world.y) <= 12f) {
                    pushHistory()
                    edges = edges.filter { !(it.to.node == node.id && it.to.port == port) } + // 입력 포트당 1개 — 교체
                        Edge("e_$seq", PortRef(w.node, w.port), PortRef(node.id, port))
                    seq += 1
                    return
                }
            }
        }
    }

    // 클릭 지점에서 가까운 엣지 검색 (곡선 32점 샘플링)
    fun edgeAt(world: Offset): String? {
        for (e in edges) {
            val from = nodeById(e.from.node) ?: continue
            val to = nodeById(e.to.node) ?: continue
            val a = portPos(from, "out", from.outputs.indexOf(e.from.port).coerceAtLeast(0))
            val b = portPos(to, "in", to.inputs.indexOf(e.to.port).coerceAtLeast(0))
            for (i in 0..32) {
                val p = bezierPoint(a, b, i / 32f)
                if (hypot(p.x - world.x, p.y - world.y) <= 8f) return e.id
            }
        }
        return null
    }

    /* ───────── 팬/줌 ───────── */

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

    /* ───────── 실행 시뮬레이션 ───────── */

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
            // 대기 타이머 0이 되면 자동으로 running 종료
            if (running && simJobs.isEmpty()) running = false
        }
    }

    private fun runNode(id: String, skipInputCheck: Boolean = false) {
        val node = nodeById(id) ?: return
        if (node.status == "running" || node.status == "done") return
        val incoming = edges.filter { it.to.node == id }
        // 입력 포트가 있는데 들어오는 연결이 하나도 없으면 error
        if (!skipInputCheck && node.inputs.isNotEmpty() && incoming.isEmpty()) {
            setStatus(id, "error")
            return
        }
        setStatus(id, "running")
        later((900 / SPEED).toLong()) {
            setStatus(id, "done")
            edges.filter { it.from.node == id }.forEach { e ->
                setEdgeActive(e.id, true)
                later((850 / SPEED).toLong()) {
                    setEdgeActive(e.id, false)
                    runNode(e.to.node)
                }
            }
        }
    }

    private fun resetRun() {
        simJobs.toList().forEach { it.cancel() }
        simJobs.clear()
        nodes = nodes.map { if (it.status == "idle") it else it.copy(status = "idle") }
        edges = edges.map { if (it.active) it.copy(active = false) else it }
    }

    fun startRun() {
        if (nodes.isEmpty()) return
        resetRun()
        running = true
        // 들어오는 엣지가 없는 소스 노드부터; 없으면 첫 노드
        val sources = nodes.filter { n -> edges.none { it.to.node == n.id } }
        val starts = sources.ifEmpty { listOf(nodes.first()) }
        later(0) { starts.forEach { runNode(it.id) } }
    }

    fun runFromSelection(id: String? = null) {
        val nodeId = id ?: selNodes.firstOrNull() ?: return
        resetRun()
        running = true
        later(0) { runNode(nodeId, skipInputCheck = true) } // 입력 미연결 검사 면제
    }

    fun stopRun() {
        simJobs.toList().forEach { it.cancel() }
        simJobs.clear()
        running = false
        nodes = nodes.map { if (it.status == "running") it.copy(status = "idle") else it }
        edges = edges.map { if (it.active) it.copy(active = false) else it }
    }

    /* ───────── 자동 배치 (BFS 깊이별 컬럼) ───────── */

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

    /* ───────── 파일 ───────── */

    fun exportJson() = Platform.exportJson(snapshot())

    fun importJson() = Platform.importJson { raw ->
        runCatching {
            val f = json.decodeFromString<FlowFile>(raw)
            pushHistory()
            stopRun()
            nodes = f.nodes
            edges = f.edges
            seq = f.seq
            clearSel()
        }
    }

    /* ───────── 사이드바 드래그 드랍 ───────── */

    fun dropModule() {
        val d = dragModule ?: return
        dragModule = null
        val local = d.pos - canvasOrigin
        val inCanvas = local.x >= 0 && local.y >= 0 &&
            local.x <= canvasSize.width && local.y <= canvasSize.height
        if (inCanvas) addNodeAt(d.type, screenToWorld(local))
    }
}
