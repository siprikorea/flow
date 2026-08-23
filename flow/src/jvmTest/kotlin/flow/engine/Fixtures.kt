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
    val moduleIds = setOf("test.upper", "test.reverse", "test.boom", "test.vandal", "test.witness", "test.two")

    /**
     * Stand-in modules. Deliberately includes ones that misbehave: an extension is arbitrary code
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
                else -> error("unexpected module '$id'")
            }
        }

    /** An engine over the fixtures, resolving comp: references to the fixture of that name. */
    fun engine(onSettled: (String, String?) -> Unit = { _, _ -> }) = FlowEngine(
        loadFlow = { name -> runCatching { load(name) }.getOrNull() },
        moduleIds = moduleIds,
        moduleProcess = modules(),
        onNodeSettled = onSettled,
    )
}
