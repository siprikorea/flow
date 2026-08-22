package flow.engine

import flow.model.FlowFile
import flow.model.Node
import flow.model.compFile
import flow.model.isComp

/**
 * Flow execution engine (UI-independent).
 * Takes cin (input boundary) values, evaluates nodes in topological order, and returns cout (output boundary) values.
 * Ports carry raw bytes end to end — no text/charset conversion happens inside the engine, so
 * binary module output (e.g. Crypto ciphertext) flows between nodes without any risk of corruption.
 *
 * @param loadFlow loader used to expand nested components (comp:<file>)
 * @param moduleIds set of installed module extension ids (nodes of that type are evaluated via process)
 * @param moduleProcess processing function of installed modules (id, inputs, options -> outputs)
 */
class FlowEngine(
    private val loadFlow: (String) -> FlowFile?,
    private val moduleIds: Set<String> = emptySet(),
    private val moduleProcess: (String, Map<String, ByteArray?>, Map<String, String>) -> Map<String, ByteArray?> = { _, _, _ -> emptyMap() },
) {
    // node id -> error message, from the most recent evaluate() call (run/runByNode)
    private val _errors = LinkedHashMap<String, String>()
    val errors: Map<String, String> get() = _errors

    // Evaluate every node; returns (nodeId, portName) -> value for all outputs.
    private fun evaluate(flow: FlowFile, inputs: Map<String, ByteArray>): Map<Pair<String, String>, ByteArray?> {
        _errors.clear()
        val byId = flow.nodes.associateBy { it.id }
        val outVals = HashMap<Pair<String, String>, ByteArray?>()
        // a node stops the branch it is on: whatever it fed cannot be computed from real values, so
        // running those anyway would either fail again for a derived reason or, worse, succeed on
        // nulls. Branches that never touched the failure are unaffected — topological order means
        // everything feeding this node has already run.
        val blocked = HashSet<String>()
        for (nid in topoOrder(flow)) {
            val node = byId[nid] ?: continue
            val upstream = flow.edges.filter { it.to.node == nid }.map { it.from.node }
            if (upstream.any { it in blocked }) {
                blocked += nid
                continue
            }
            val inVals: Map<String, ByteArray?> = node.inputs.associate { port ->
                val e = flow.edges.firstOrNull { it.to.node == nid && it.to.port == port.name }
                port.name to (if (e == null) null else outVals[e.from.node to e.from.port])
            }
            // a module throwing (bad key/IV size, etc.) is attributed to this node, not swallowed silently
            val result = runCatching { evalNode(node, inVals, inputs) }.getOrElse { e ->
                _errors[nid] = e.message ?: e::class.simpleName ?: "error"
                blocked += nid
                node.outputs.associate { it.name to null }
            }
            result.forEach { (p, v) -> outVals[nid to p] = v }
        }
        return outVals
    }

    // the value arriving at a cout node (following its single input edge)
    private fun coutValue(flow: FlowFile, cout: Node, outVals: Map<Pair<String, String>, ByteArray?>): ByteArray? {
        val e = flow.edges.firstOrNull { it.to.node == cout.id && it.to.port == (cout.inputs.firstOrNull()?.name ?: "in") }
        return if (e != null) outVals[e.from.node to e.from.port] else null
    }

    // cin label -> value. Returns: cout label -> value (labels must be unique)
    fun run(flow: FlowFile, inputs: Map<String, ByteArray>): Map<String, ByteArray?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.label to coutValue(flow, it, outVals) }
    }

    // cout node id -> value (no label-collision; used by the in-app output editors)
    fun runByNode(flow: FlowFile, inputs: Map<String, ByteArray>): Map<String, ByteArray?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.id to coutValue(flow, it, outVals) }
    }

    private fun evalNode(node: Node, inVals: Map<String, ByteArray?>, externalInputs: Map<String, ByteArray?>): Map<String, ByteArray?> {
        return when {
            // top-level (in-app Input tabs) callers key externalInputs by node id, so two cin nodes
            // with the same display label never collide (labels are cosmetic only — see
            // EditorState.computeOutputs); nested-component invocation only knows the sub-flow's
            // cin labels (its public interface, see asComponent()), so that path stays label-keyed
            node.type == "cin" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to (externalInputs[node.id] ?: externalInputs[node.label]))
            node.type == "cout" -> emptyMap()
            node.type in moduleIds -> moduleProcess(node.type, inVals, node.params) // installed module extension
            isComp(node.type) -> {
                val sub = loadFlow(compFile(node.type)) ?: return node.outputs.associate { it.name to null }
                // component input ports (node.inputs) = the sub-component's cin labels
                val subInputs = node.inputs.associate { it.name to (inVals[it.name] ?: ByteArray(0)) }
                val subOut = run(sub, subInputs)
                node.outputs.associate { it.name to subOut[it.name] }
            }
            node.outputs.isEmpty() -> emptyMap() // sink
            // Every other type names a module, and reaching here means it is not installed. Passing
            // the input through would look like a successful run while quietly skipping the work —
            // a flow missing its hash module would report the plaintext as the digest.
            else -> error("module '${node.type}' is not installed")
        }
    }

    // Kahn topological sort (cycles: append remaining nodes in arbitrary order)
    private fun topoOrder(flow: FlowFile): List<String> {
        val ids = flow.nodes.map { it.id }
        val indeg = ids.associateWith { 0 }.toMutableMap()
        flow.edges.forEach { e -> if (e.to.node in indeg && e.from.node in indeg) indeg[e.to.node] = (indeg[e.to.node] ?: 0) + 1 }
        val queue = ArrayDeque(ids.filter { indeg[it] == 0 })
        val order = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            flow.edges.filter { it.from.node == n }.forEach { e ->
                val d = (indeg[e.to.node] ?: return@forEach) - 1
                indeg[e.to.node] = d
                if (d == 0) queue.add(e.to.node)
            }
        }
        ids.filter { it !in order }.forEach { order.add(it) }
        return order
    }
}
