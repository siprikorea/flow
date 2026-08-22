package flow.mcp

import flow.cli.loadFlow
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import flow.model.OptType
import flow.model.asComponent
import flow.model.compFile
import flow.model.findDef
import flow.model.isComp
import flow.platform.Platform

// Turning the node/edge spec the MCP tools accept into a real flow file.
//
// A client describes what the flow *does* — which modules, wired in what order — and everything
// mechanical is worked out here: each node's port list (which for a module depends on its option
// values), the edge ids, the node sizes, and the coordinates. That is what lets "hash this input
// and base64 the result" arrive as a handful of names rather than hand-assembled JSON.

// every generated node starts at the palette's uniform size (see EditorState.addNodeAt)
private const val NODE_W = 180f
private const val NODE_H = 100f
private const val COLUMN_STEP = 280f
private const val ROW_GAP = 50f
private const val MARGIN = 60f

// flow.core.GRID/snapF live beside Compose imports, and the MCP/CLI path deliberately ships
// without the Compose jars (see mcpbServerJars) — so the one line is repeated rather than imported.
private const val GRID = 20f
private fun snap(v: Float): Float = Math.round(v / GRID) * GRID

/** A node to place. [x]/[y] are optional: nodes without them are laid out automatically. */
internal class NodeSpec(
    val id: String,
    val type: String,
    val label: String?,
    val params: Map<String, String>,
    val x: Float? = null,
    val y: Float? = null,
)

/** An edge, each end written "node" or "node.port" (see [endpoint]). */
internal class EdgeSpec(val from: String, val to: String)

/** What a node type resolves to: the ports it actually has, plus its default label. */
internal class TypePorts(val ins: List<String>, val outs: List<String>, val label: String)

/**
 * The ports and default label for [type] under [params], or null if the type isn't known.
 *
 * A module's ports can depend on its options (flow.signature only takes "signature" when
 * operation=verify), so the module is asked rather than read off its static list.
 */
internal fun portsOf(type: String, params: Map<String, String>): TypePorts? {
    // cin/cout boundary nodes
    findDef(type)?.let { def ->
        return TypePorts(def.ins, def.outs, def.name["en"] ?: def.name["ko"] ?: type)
    }
    // another flow used as a sub-component
    if (isComp(type)) {
        val file = compFile(type)
        val comp = loadFlow(file)?.asComponent(file) ?: return null
        return TypePorts(comp.ins, comp.outs, comp.name)
    }
    val info = Platform.installedModuleInfos().find { it.id == type } ?: return null
    val values = info.options.associate { it.name to it.default } + params
    return TypePorts(
        Platform.moduleInputsFor(type, values) ?: info.inputs,
        Platform.moduleOutputsFor(type, values) ?: info.outputs,
        info.name,
    )
}

/**
 * What is wrong with [params] for a node of [type]: an option the module doesn't have, or a value
 * outside a choice list. Empty when the params are sound. Types with no option list of their own
 * (cin/cout carry editor-set params, a sub-component carries none) are left alone.
 */
internal fun paramProblems(type: String, params: Map<String, String>): List<String> {
    val info = Platform.installedModuleInfos().find { it.id == type } ?: return emptyList()
    val problems = mutableListOf<String>()
    val known = info.options.associateBy { it.name }
    (params.keys - known.keys).forEach { name ->
        problems += "no option '$name'" +
            (if (known.isEmpty()) " (it takes no options)" else " — it takes: ${known.keys.joinToString(", ")}")
    }
    params.forEach { (name, value) ->
        val opt = known[name] ?: return@forEach
        if (opt.type == OptType.SELECT && opt.choices.isNotEmpty() && value !in opt.choices) {
            problems += "option '$name' cannot be '$value' — it takes: ${opt.choices.joinToString(", ")}"
        }
    }
    return problems
}

/** Option values a node of [type] carries: the module's defaults with [params] applied over them. */
private fun paramsFor(type: String, params: Map<String, String>): Map<String, String> {
    val info = Platform.installedModuleInfos().find { it.id == type } ?: return params
    return info.options.associate { it.name to it.default } + params
}

/**
 * One edge endpoint as a [PortRef]. Written "node.port", or just "node" when that node has exactly
 * one port on the side in question — which is the common case and keeps simple chains readable.
 * A node id is tried whole first, since a component node's id may itself contain dots.
 */
private fun endpoint(raw: String, outgoing: Boolean, ports: Map<String, TypePorts>): PortRef {
    val text = raw.trim()
    val side = if (outgoing) "output" else "input"
    require(text.isNotEmpty()) { "an edge endpoint is empty" }

    ports[text]?.let { p ->
        val list = if (outgoing) p.outs else p.ins
        require(list.isNotEmpty()) { "node '$text' has no $side port to wire" }
        require(list.size == 1) {
            "node '$text' has ${list.size} $side ports (${list.joinToString(", ")}) — " +
                "name the one you mean as '$text.<port>'"
        }
        return PortRef(text, list.first())
    }

    val node = text.substringBeforeLast('.', "")
    val port = text.substringAfterLast('.')
    val p = ports[node]
        ?: error("edge endpoint '$text' names no node in this flow (nodes: ${ports.keys.joinToString(", ")})")
    val list = if (outgoing) p.outs else p.ins
    require(port in list) {
        "node '$node' has no $side port '$port'" +
            (if (list.isEmpty()) " (it has no $side ports)" else " — it has: ${list.joinToString(", ")}")
    }
    return PortRef(node, port)
}

/**
 * Left-to-right placement, so a flow drafted without coordinates still opens looking deliberate.
 *
 * A node's column is its longest path from a source, as in the editor's Auto Layout. Its row is
 * then centred on the inputs feeding it, which is what keeps the wires short when different
 * sources feed different stages — stacking every source in one column and every consumer at the
 * top, as the editor does, leaves long diagonals across the canvas instead.
 *
 * A caller that wants to arrange the graph itself sets x/y per node and those win — either axis on
 * its own, so a node can be nudged sideways while keeping its computed row.
 */
private fun layout(nodes: List<NodeSpec>, edges: List<Edge>): Map<String, Pair<Float, Float>> {
    val depth = nodes.associate { it.id to 0 }.toMutableMap()
    repeat(nodes.size) {
        var changed = false
        edges.forEach { e ->
            val from = depth[e.from.node] ?: return@forEach
            if (e.to.node !in depth) return@forEach
            val d = from + 1
            if (d > depth[e.to.node]!! && d <= nodes.size) {
                depth[e.to.node] = d
                changed = true
            }
        }
        if (!changed) return@repeat
    }

    val feeders = edges.groupBy({ it.to.node }, { it.from.node })
    val placed = mutableMapOf<String, Pair<Float, Float>>()

    // columns in order, so a node's feeders (always a shallower column) are placed before it
    nodes.groupBy { depth[it.id] ?: 0 }.toSortedMap().forEach { (d, column) ->
        var stacked = MARGIN
        val wanted = column.map { spec ->
            val from = feeders[spec.id].orEmpty().mapNotNull { placed[it] }
            // a source has nothing to centre on, so it just takes the next row down
            val y = spec.y ?: from.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                ?: stacked.also { stacked += NODE_H + ROW_GAP }
            // pinning only some nodes must not leave an auto one left of what feeds it, which
            // would draw the wire backwards
            val x = spec.x ?: maxOf(
                MARGIN + d * COLUMN_STEP,
                (from.maxOfOrNull { it.first } ?: Float.NEGATIVE_INFINITY) + COLUMN_STEP,
            )
            Triple(spec, x, y)
        }
        // two nodes can want the same row; push the later ones down, leaving pinned nodes put
        var floor = Float.NEGATIVE_INFINITY
        wanted.sortedBy { it.third }.forEach { (spec, x, y) ->
            val settled = if (spec.y != null) y else maxOf(y, floor)
            placed[spec.id] = snap(x) to snap(settled)
            floor = settled + NODE_H + ROW_GAP
        }
    }

    return placed
}

/**
 * Build a complete, editor-loadable flow from [nodes] and [edges]. Throws with a message aimed at
 * the caller (an MCP client) when a type, node id or port doesn't check out.
 */
internal fun buildFlow(nodes: List<NodeSpec>, edges: List<EdgeSpec>): FlowFile {
    require(nodes.isNotEmpty()) { "'nodes' is empty — a flow needs at least one node" }
    val duplicates = nodes.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) { "duplicate node id(s): ${duplicates.joinToString(", ")}" }

    val ports = nodes.associate { spec ->
        paramProblems(spec.type, spec.params).firstOrNull()?.let { error("node '${spec.id}': ${spec.type} $it") }
        spec.id to (portsOf(spec.type, spec.params)
            ?: error("node '${spec.id}': unknown type '${spec.type}' — call list_modules for what is available"))
    }
    val resolved = edges.mapIndexed { i, e ->
        Edge("e${i + 1}", endpoint(e.from, outgoing = true, ports), endpoint(e.to, outgoing = false, ports))
    }
    val placed = layout(nodes, resolved)

    val built = nodes.map { spec ->
        val p = ports.getValue(spec.id)
        val (x, y) = placed.getValue(spec.id)
        Node(
            id = spec.id,
            type = spec.type,
            // a cin/cout node's label is the component's external port name, so it carries meaning
            label = spec.label?.takeIf { it.isNotBlank() } ?: p.label,
            x = x, y = y, w = NODE_W, h = NODE_H,
            inputs = p.ins.map { Port(it) },
            outputs = p.outs.map { Port(it) },
            params = paramsFor(spec.type, spec.params),
        )
    }
    // ids here are caller-chosen, so leave seq past everything the editor might generate next
    return FlowFile(version = 1, nodes = built, edges = resolved, seq = built.size + resolved.size + 1)
}

/**
 * Wiring faults in a finished flow — what "review this flow's connections" answers.
 *
 * These are all structural, so they can be stated with certainty: whether a port is fed, whether
 * it is fed twice, whether the graph can be evaluated at all. Whether the *right* module was
 * chosen is a judgement for the caller, and is deliberately not guessed at here.
 */
internal fun flowProblems(flow: FlowFile): List<String> {
    val problems = mutableListOf<String>()

    val missing = listOf("cin" to "input", "cout" to "output")
        .filter { (type, _) -> flow.nodes.none { it.type == type } }
    if (missing.isNotEmpty()) {
        problems += "no ${missing.joinToString(" or ") { "'${it.first}'" }} node — a flow needs one " +
            "'cin' per input and one 'cout' per output before it can run as a component"
    }

    // an input port takes a single source; two edges into one is ambiguous, not a merge
    flow.edges.groupBy { it.to }
        .filterValues { it.size > 1 }
        .forEach { (target, dupes) ->
            problems += "'${target.node}.${target.port}' is fed by ${dupes.size} edges " +
                "(${dupes.joinToString(", ") { "${it.from.node}.${it.from.port}" }}) — an input takes one source"
        }

    flow.nodes.forEach { n ->
        val unfed = n.inputs.map { it.name }
            .filter { port -> flow.edges.none { it.to.node == n.id && it.to.port == port } }
        val unused = n.outputs.map { it.name }
            .filter { port -> flow.edges.none { it.from.node == n.id && it.from.port == port } }
        if (unfed.isNotEmpty()) problems += "'${n.id}' has no input on ${unfed.joinToString(", ")}"
        if (unused.isNotEmpty()) problems += "'${n.id}' leaves ${unused.joinToString(", ")} unconnected"
    }

    cycleThrough(flow)?.let { problems += "the wiring loops: ${it.joinToString(" → ")} — a flow has to run start to finish" }
    return problems
}

/**
 * Everything checkable about a flow already on disk — the answer to "verify this flow".
 *
 * On top of the wiring faults in [flowProblems], this catches what only a saved file can drift
 * into: a node whose module is no longer installed, an option value that is no longer valid, and
 * a port list that no longer matches what the node's own options say it should have (which happens
 * when a flow is hand-edited, or a module's ports change between versions).
 */
internal fun validateFlow(flow: FlowFile): List<String> {
    val problems = mutableListOf<String>()
    val known = mutableMapOf<String, TypePorts>()

    flow.nodes.forEach { n ->
        paramProblems(n.type, n.params).forEach { problems += "'${n.id}' (${n.type}): $it" }
        val ports = portsOf(n.type, n.params)
        if (ports == null) {
            problems += "'${n.id}' has unknown type '${n.type}' — the module may not be installed"
            return@forEach
        }
        known[n.id] = ports
        val ins = n.inputs.map { it.name }
        val outs = n.outputs.map { it.name }
        if (ins != ports.ins) {
            problems += "'${n.id}' stores inputs [${ins.joinToString(", ")}] but ${n.type} with these " +
                "options has [${ports.ins.joinToString(", ")}]"
        }
        if (outs != ports.outs) {
            problems += "'${n.id}' stores outputs [${outs.joinToString(", ")}] but ${n.type} with these " +
                "options has [${ports.outs.joinToString(", ")}]"
        }
    }

    flow.edges.forEach { e ->
        val from = flow.nodes.find { it.id == e.from.node }
        val to = flow.nodes.find { it.id == e.to.node }
        if (from == null) problems += "edge '${e.id}' starts at '${e.from.node}', which is not a node here"
        else if (from.outputs.none { it.name == e.from.port }) {
            problems += "edge '${e.id}' starts at '${e.from.node}.${e.from.port}', which is not an output of that node"
        }
        if (to == null) problems += "edge '${e.id}' ends at '${e.to.node}', which is not a node here"
        else if (to.inputs.none { it.name == e.to.port }) {
            problems += "edge '${e.id}' ends at '${e.to.node}.${e.to.port}', which is not an input of that node"
        }
    }

    return problems + flowProblems(flow)
}

/** One cycle's node ids in order, or null when the graph is acyclic. */
private fun cycleThrough(flow: FlowFile): List<String>? {
    val next = flow.edges.groupBy({ it.from.node }, { it.to.node })
    val done = mutableSetOf<String>()
    val onPath = mutableListOf<String>()

    fun walk(id: String): List<String>? {
        val seen = onPath.indexOf(id)
        if (seen >= 0) return onPath.subList(seen, onPath.size) + id
        if (id in done) return null
        onPath += id
        next[id].orEmpty().forEach { target -> walk(target)?.let { return it } }
        onPath.removeAt(onPath.size - 1)
        done += id
        return null
    }

    flow.nodes.forEach { n -> walk(n.id)?.let { return it } }
    return null
}
