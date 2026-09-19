package flow.engine

import flow.model.FlowFile
import flow.model.Node
import flow.model.compFile
import flow.model.isComp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Flow execution engine (UI-independent).
 * Takes cin (input boundary) values, evaluates nodes in topological order, and returns cout (output boundary) values.
 * Ports carry raw bytes end to end — no text/charset conversion happens inside the engine, so
 * binary module output (e.g. Crypto ciphertext) flows between nodes without any risk of corruption.
 *
 * @param loadFlow loader used to expand nested components (comp:<file>)
 * @param moduleIds set of installed module module ids (nodes of that type are evaluated via process)
 * @param moduleProcess processing function of installed modules (id, inputs, options -> outputs)
 */
class FlowEngine(
    private val loadFlow: (String) -> FlowFile?,
    private val moduleIds: Set<String> = emptySet(),
    private val moduleProcess: suspend (String, Map<String, ByteArray?>, Map<String, String>) -> Map<String, ByteArray?> = { _, _, _ -> emptyMap() },
    // Called as each node finishes, with its error or null — on the evaluating thread, in
    // topological order. The canvas animation follows this so a node that really is working (a
    // sleep, a big file) is shown working, instead of the whole run waiting for the total.
    private val onNodeSettled: (String, String?) -> Unit = { _, _ -> },
    // Which of a module's input ports are allowed to arrive with nothing on them. Merge says both
    // of its sides are; most modules say none. This is what lets a branch work: the side that was
    // not taken carries nothing, and a node whose input needs something is skipped rather than run
    // on emptiness. Returning null means "no idea" — an unknown module is run as it always was.
    private val optionalInputsOf: (String) -> Set<String>? = { null },
    // How many components deep this engine already is. A sub-component is evaluated by its own
    // engine one level down, and past [MAX_COMPONENT_DEPTH] the nesting is refused — a component
    // that names itself would otherwise recurse until the process dies.
    private val depth: Int = 0,
) {
    // node id -> error message, from the most recent evaluate() call (run/runByNode)
    @Volatile
    private var _errors: Map<String, String> = emptyMap()
    val errors: Map<String, String> get() = _errors

    /**
     * Evaluate every node; returns (nodeId, portName) -> value for all outputs.
     *
     * Nodes run concurrently, each starting once the nodes feeding it have finished, rather than
     * one after another down a topological list. Sequential evaluation made a slow module hold up
     * everything ordered behind it whether or not anything depended on it — a sleep on one branch
     * stalled an unrelated branch that had no reason to wait.
     *
     * This calls [moduleProcess] from several threads at once, which the module contract already
     * expects: process() is handed everything it needs and keeps nothing between calls.
     *
     * [only] narrows the pass to the nodes named in it; every other node keeps the value [known]
     * already has for it and is never called. That is what lets a change to one input re-run the
     * modules it reaches and leave the rest of the flow alone — which matters beyond speed, because
     * a module can send a message or write a file, and doing that again for a branch the change
     * never touched would be wrong. Null runs everything, as it always did.
     */
    private suspend fun evaluate(
        flow: FlowFile,
        inputs: Map<String, ByteArray>,
        known: Map<Pair<String, String>, ByteArray?> = emptyMap(),
        only: Set<String>? = null,
    ): Map<Pair<String, String>, ByteArray?> = coroutineScope {
        val byId = flow.nodes.associateBy { it.id }
        // built up here and published at the end, so a caller reading errors never sees a
        // half-filled map and two runs cannot tread on each other's results
        val errs = LinkedHashMap<String, String>()
        val outVals = HashMap<Pair<String, String>, ByteArray?>()
        // guards the three maps below; each node touches them only when it finishes, so the lock is
        // held briefly and never across a module call
        val lock = Mutex()
        // a node stops the branch it is on: whatever it fed cannot be computed from real values, so
        // running those anyway would either fail again for a derived reason or, worse, succeed on
        // nulls. Branches that never touched the failure are unaffected.
        val blocked = HashSet<String>()

        // Ports whose value goes to more than one input. Modules are arbitrary code and some
        // write through the arrays they are given; handing the same array to two nodes let one of
        // them corrupt the other's input, differently on each run now that they overlap in time.
        // Copying only where a value is actually shared keeps the common straight chain free of it.
        val shared = flow.edges.groupBy { it.from.node to it.from.port }
            .filterValues { it.size > 1 }.keys

        val started = LinkedHashMap<String, Deferred<Unit>>()
        // topological order only decides who waits for whom; it no longer decides who runs when
        for (nid in topoOrder(flow)) {
            val node = byId[nid] ?: continue
            val upstream = flow.edges.filter { it.to.node == nid }.map { it.from.node }.distinct()
            val waitFor = upstream.mapNotNull { started[it] }
            started[nid] = async(Dispatchers.Default) {
                waitFor.forEach { it.await() }
                if (only != null && nid !in only) {
                    // outside this pass: what it produced last time is what it still produces
                    lock.withLock { node.outputs.forEach { p -> outVals[nid to p.name] = known[nid to p.name] } }
                    onNodeSettled(nid, null)
                    return@async
                }
                val stopped = lock.withLock { upstream.any { it in blocked } }
                if (stopped) {
                    lock.withLock { blocked += nid }
                    onNodeSettled(nid, null) // never ran; the branch already stopped upstream
                    return@async
                }
                val connected = flow.edges.filter { it.to.node == nid }.map { it.to.port }.toSet()
                val inVals: Map<String, ByteArray?> = lock.withLock {
                    node.inputs.associate { port ->
                        val e = flow.edges.firstOrNull { it.to.node == nid && it.to.port == port.name }
                        val from = e?.let { it.from.node to it.from.port }
                        val value = from?.let { outVals[it] }
                        port.name to if (from in shared) value?.copyOf() else value
                    }
                }
                // Nothing arrived on a port that needs something, so this node is not part of the
                // run: a branch sends its value down one side and nothing down the other, and
                // everything on the other side is skipped. Running them instead would be wrong in
                // both directions — a pure module would compute a result from emptiness, and one
                // with an effect would send a message about a branch that was never taken.
                val nothingToDo = node.type in moduleIds && optionalInputsOf(node.type)?.let { optional ->
                    node.inputs.any { it.name in connected && inVals[it.name] == null && it.name !in optional }
                } == true
                if (nothingToDo) {
                    lock.withLock {
                        blocked += nid
                        node.outputs.forEach { p -> outVals[nid to p.name] = null }
                    }
                    onNodeSettled(nid, null) // skipped, not failed: an untaken branch is not an error
                    return@async
                }

                // a module throwing (bad key/IV size, etc.) is attributed to this node, not swallowed silently
                val outcome = runCatching { evalNode(node, inVals, inputs) }
                val error = outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "error" }
                val result = outcome.getOrElse { node.outputs.associate { p -> p.name to null } }
                lock.withLock {
                    if (error != null) {
                        errs[nid] = error
                        blocked += nid
                    }
                    result.forEach { (p, v) -> outVals[nid to p] = v }
                }
                onNodeSettled(nid, error)
            }
        }
        started.values.forEach { it.await() }
        _errors = errs
        outVals
    }

    // the value arriving at a cout node (following its single input edge)
    private fun coutValue(flow: FlowFile, cout: Node, outVals: Map<Pair<String, String>, ByteArray?>): ByteArray? {
        val e = flow.edges.firstOrNull { it.to.node == cout.id && it.to.port == (cout.inputs.firstOrNull()?.name ?: "in") }
        return if (e != null) outVals[e.from.node to e.from.port] else null
    }

    // cin label -> value. Returns: cout label -> value (labels must be unique)
    suspend fun run(flow: FlowFile, inputs: Map<String, ByteArray>): Map<String, ByteArray?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.label to coutValue(flow, it, outVals) }
    }

    // cout node id -> value (no label-collision; used by the in-app output editors)
    suspend fun runByNode(flow: FlowFile, inputs: Map<String, ByteArray>): Map<String, ByteArray?> {
        val outVals = evaluate(flow, inputs)
        return flow.nodes.filter { it.type == "cout" }.associate { it.id to coutValue(flow, it, outVals) }
    }

    /**
     * Every port's value, rather than only the flow's outputs — see [evaluate] for [known]/[only].
     *
     * The editor keeps what comes back, so the next change can be run as the part of the flow it
     * reaches: the values on the way in are the ones the last pass produced.
     */
    suspend fun values(
        flow: FlowFile,
        inputs: Map<String, ByteArray>,
        known: Map<Pair<String, String>, ByteArray?> = emptyMap(),
        only: Set<String>? = null,
    ): Map<Pair<String, String>, ByteArray?> = evaluate(flow, inputs, known, only)

    /** What each cout node ends up showing, given every port's value. */
    fun coutValues(flow: FlowFile, values: Map<Pair<String, String>, ByteArray?>): Map<String, ByteArray?> =
        flow.nodes.filter { it.type == "cout" }.associate { it.id to coutValue(flow, it, values) }

    private suspend fun evalNode(node: Node, inVals: Map<String, ByteArray?>, externalInputs: Map<String, ByteArray?>): Map<String, ByteArray?> {
        return when {
            // top-level (in-app Input tabs) callers key externalInputs by node id, so two cin nodes
            // with the same display label never collide (labels are cosmetic only — see
            // EditorState.computeOutputs); nested-component invocation only knows the sub-flow's
            // cin labels (its public interface, see asComponent()), so that path stays label-keyed
            node.type == "cin" -> mapOf((node.outputs.firstOrNull()?.name ?: "out") to (externalInputs[node.id] ?: externalInputs[node.label]))
            node.type == "cout" -> emptyMap()
            node.type in moduleIds -> {
                // declared non-null, but an module is arbitrary code — one written in Java can
                // hand back null, and letting that through took the whole run down with an NPE
                // instead of failing the one node that broke its contract
                val out: Map<String, ByteArray?>? = moduleProcess(node.type, inVals, node.params)
                out ?: error("module '${node.type}' returned no result")
            }
            isComp(node.type) -> {
                val file = compFile(node.type)
                // a component that cannot be found is a broken reference, not an empty result
                val sub = loadFlow(file) ?: error("component '$file' was not found")
                require(depth < MAX_COMPONENT_DEPTH) {
                    "component '$file' nests more than $MAX_COMPONENT_DEPTH deep — does it refer to itself?"
                }
                // Its own engine, one level down: errors, and the per-node callback the canvas
                // follows, belong to the flow being run. Sharing them let a sub-flow wipe the
                // parent's errors and report node ids the parent has never heard of.
                val subEngine = FlowEngine(
                    loadFlow, moduleIds, moduleProcess,
                    optionalInputsOf = optionalInputsOf, depth = depth + 1,
                )
                // component input ports (node.inputs) = the sub-component's cin labels
                val subInputs = node.inputs.associate { it.name to (inVals[it.name] ?: ByteArray(0)) }
                val subOut = subEngine.run(sub, subInputs)
                // a component that failed inside has not produced its outputs; say so here rather
                // than handing on nulls that look like an answer. A failure that already names a
                // component is passed along as it stands — it identifies the component and node
                // that actually broke, and prefixing every level on the way out just buries it.
                subEngine.errors.entries.firstOrNull()?.let { (nid, message) ->
                    if (message.startsWith("component '")) error(message)
                    error("component '$file' failed at '$nid': $message")
                }
                node.outputs.associate { it.name to subOut[it.name] }
            }
            node.outputs.isEmpty() -> emptyMap() // sink
            // Every other type names a module, and reaching here means it is not installed. Passing
            // the input through would look like a successful run while quietly skipping the work —
            // a flow missing its hash module would report the plaintext as the digest.
            else -> error("module '${node.type}' is not installed")
        }
    }

    private companion object {
        // deep enough for any real composition, shallow enough to fail long before the stack does
        const val MAX_COMPONENT_DEPTH = 16
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
