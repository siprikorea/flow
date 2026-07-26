package flow.engine

import flow.model.FlowFile
import flow.model.Node
import flow.model.compFile
import flow.model.isComp
import flow.platform.digest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Flow execution engine (UI-independent).
 * Takes cin (input boundary) values, evaluates nodes in topological order, and returns cout (output boundary) values.
 * Scalar (string/number) data model — expressions are evaluated by [Expr].
 *
 * @param loadFlow loader used to expand nested components (comp:<file>)
 * @param moduleIds set of installed module extension ids (nodes of that type are evaluated via process)
 * @param moduleProcess processing function of installed modules (id, inputs, options -> outputs)
 */
class FlowEngine(
    private val loadFlow: (String) -> FlowFile?,
    private val moduleIds: Set<String> = emptySet(),
    private val moduleProcess: (String, Map<String, String?>, Map<String, String>) -> Map<String, String?> = { _, _, _ -> emptyMap() },
) {

    // Evaluate every node; returns (nodeId, portName) -> value for all outputs.
    private fun evaluate(flow: FlowFile, inputs: Map<String, String>): Map<Pair<String, String>, String?> {
        val byId = flow.nodes.associateBy { it.id }
        val outVals = HashMap<Pair<String, String>, String?>()
        for (nid in topoOrder(flow)) {
            val node = byId[nid] ?: continue
            val inVals: Map<String, String?> = node.inputs.associate { port ->
                val e = flow.edges.firstOrNull { it.to.node == nid && it.to.port == port.name }
                port.name to (if (e == null) null else outVals[e.from.node to e.from.port])
            }
            evalNode(node, inVals, inputs).forEach { (p, v) -> outVals[nid to p] = v }
        }
        return outVals
    }

    // the value arriving at a cout node (following its single input edge)
    private fun coutValue(flow: FlowFile, cout: Node, outVals: Map<Pair<String, String>, String?>): String? {
        val e = flow.edges.firstOrNull { it.to.node == cout.id && it.to.port == (cout.inputs.firstOrNull()?.name ?: "in") }
        return if (e != null) outVals[e.from.node to e.from.port] else null
    }

    // cin label -> value. Returns: cout label -> value (labels must be unique)
    fun run(flow: FlowFile, inputs: Map<String, String>): Map<String, String?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.label to coutValue(flow, it, outVals) }
    }

    // cout node id -> value (no label-collision; used by the in-app output editors)
    fun runByNode(flow: FlowFile, inputs: Map<String, String>): Map<String, String?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.id to coutValue(flow, it, outVals) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun evalNode(node: Node, inVals: Map<String, String?>, externalInputs: Map<String, String?>): Map<String, String?> {
        fun single() = inVals.values.firstOrNull()
        return when {
            node.type == "cin" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to externalInputs[node.label])
            node.type == "cout" -> emptyMap()
            node.type in moduleIds -> moduleProcess(node.type, inVals, node.params) // installed module extension
            isComp(node.type) -> {
                val sub = loadFlow(compFile(node.type)) ?: return node.outputs.associate { it.name to null }
                // component input ports (node.inputs) = the sub-component's cin labels
                val subInputs = node.inputs.associate { it.name to (inVals[it.name] ?: "") }
                val subOut = run(sub, subInputs.mapValues { it.value ?: "" })
                node.outputs.associate { it.name to subOut[it.name] }
            }
            node.type == "map" -> mapOf("out" to Expr.evalToString(node.params["expr"] ?: "x", single()))
            node.type == "filter" -> {
                val v = single()
                val pass = Expr.evalToBool(node.params["expr"] ?: "value", v)
                mapOf("pass" to if (pass) v else null, "fail" to if (pass) null else v)
            }
            node.type == "split" -> {
                // split the input by the separator; part i -> output port i (remainder in the last)
                val input = single()
                if (input == null) node.outputs.associate { it.name to null }
                else {
                    val sep = node.params["sep"] ?: ","
                    val parts = if (sep.isEmpty()) listOf(input)
                    else input.split(sep, limit = node.outputs.size.coerceAtLeast(1))
                    node.outputs.mapIndexed { i, p -> p.name to parts.getOrNull(i) }.toMap()
                }
            }
            node.type == "merge" -> {
                // join all connected inputs in port order (a, b, ...) with the separator
                val vals = node.inputs.mapNotNull { inVals[it.name] }
                val sep = node.params["sep"] ?: ""
                mapOf((node.outputs.firstOrNull()?.name ?: "out") to (if (vals.isEmpty()) null else vals.joinToString(sep)))
            }
            node.type == "b64enc" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to
                single()?.let { Base64.encode(it.encodeToByteArray()) })
            node.type == "b64dec" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to
                single()?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() })
            node.type == "hash" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to
                single()?.let { s ->
                    digest(node.params["algo"] ?: "SHA-256", s.encodeToByteArray())
                        ?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                })
            node.type == "agg" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to single())
            node.type == "csv" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to (node.params["value"] ?: node.params["path"] ?: ""))
            node.outputs.isEmpty() -> emptyMap() // sink (log/fout, etc.)
            else -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to single()) // default: identity
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
