package flow.engine

import flow.model.FlowFile
import kotlinx.serialization.json.Json

/**
 * The .flow files under jvmTest/resources/flows, and the stand-in modules they name.
 *
 * The fixtures are real flow files rather than graphs built in code, so they also exercise the
 * format the app actually reads, and a change that breaks loading shows up here.
 */
object Fixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): FlowFile {
        val path = "/flows/$name"
        val text = Fixtures::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("fixture not found: $path")
        return json.decodeFromString(text)
    }

    /** Module ids the fixtures use. Anything outside this set is meant to be missing. */
    val moduleIds = setOf(
        "test.upper", "test.reverse", "test.boom", "test.vandal", "test.witness", "test.two",
        "test.branch", "test.effect", "test.either",
    )

    /**
     * Which inputs a module can be left without.
     *
     * The engine skips a node whose other inputs arrive with nothing on them, which is what makes a
     * branch stop the side it did not take. A module that means to accept a missing side — merge,
     * in the app — says so here.
     */
    val optionalInputs = mapOf("test.either" to setOf("left", "right"))

    /**
     * What test.effect was asked to do, in the order it was asked.
     *
     * Something has to stand in for a node with a consequence — posting to Slack, writing a file —
     * because "it produced nothing" and "it never ran" look identical from the outputs, and the
     * whole point of a branch is the difference between them.
     */
    val effects: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * Stand-in modules. Deliberately includes ones that misbehave: an module is arbitrary code
     * and the engine's job is to survive it, so the tests need code that does what a bad one does.
     */
    fun modules(): suspend (String, Map<String, ByteArray?>, Map<String, String>) -> Map<String, ByteArray?> =
        { id, inputs, _ ->
            val value = inputs["in"] ?: ByteArray(0)
            when (id) {
                "test.upper" -> mapOf("out" to value.decodeToString().uppercase().encodeToByteArray())
                "test.reverse" -> mapOf("out" to value.decodeToString().reversed().encodeToByteArray())
                "test.boom" -> error("boom")
                // writes through the array it was handed, which is what corrupted a sibling branch
                "test.vandal" -> {
                    if (value.isNotEmpty()) value[0] = 'X'.code.toByte()
                    mapOf("out" to value)
                }
                "test.witness" -> mapOf("out" to value.copyOf())
                "test.two" -> mapOf("out" to ((inputs["left"] ?: ByteArray(0)) + (inputs["right"] ?: ByteArray(0))))
                // both sides optional, like merge: one of them missing is not a reason to skip it
                "test.either" -> mapOf("out" to ((inputs["left"] ?: ByteArray(0)) + (inputs["right"] ?: ByteArray(0))))
                // yes goes one way, everything else the other
                "test.branch" -> {
                    val yes = value.decodeToString().startsWith("y")
                    mapOf("then" to value.takeIf { yes }, "else" to value.takeIf { !yes })
                }
                "test.effect" -> {
                    effects += value.decodeToString()
                    mapOf("out" to value.copyOf())
                }
                else -> error("unexpected module '$id'")
            }
        }

    /** An engine over the fixtures, resolving comp: references to the fixture of that name. */
    fun engine(onSettled: (String, String?) -> Unit = { _, _ -> }) = FlowEngine(
        loadFlow = { name -> runCatching { load(name) }.getOrNull() },
        moduleIds = moduleIds,
        moduleProcess = modules(),
        onNodeSettled = onSettled,
        optionalInputsOf = { id -> optionalInputs[id] ?: emptySet() },
    )
}
