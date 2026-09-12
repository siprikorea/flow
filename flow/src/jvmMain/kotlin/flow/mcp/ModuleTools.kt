package flow.mcp

import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.OptType
import flow.platform.Platform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Each installed processor, as a tool of its own.
 *
 * The flow tools next door are for building a graph: describe nodes and edges, save the file, run
 * it. That is the right shape for a person at a canvas, and the wrong one for a model that wants a
 * hash of some bytes — which would otherwise have to author a two-node flow, save it, run it, and
 * read the output back.
 *
 * So every processor is also callable directly. The schema is generated from what the module
 * declares, which is what makes this worth doing: a model reads JSON Schema literally, so the
 * enums are the module's real choices, the required list is only what is really required, and each
 * port says what encoding it expects rather than leaving it to be guessed.
 */
internal object ModuleTools {

    /**
     * The tool name for a module id.
     *
     * Dots are legal in MCP but not everywhere downstream — OpenAI-style function calling, which is
     * what a LangChain4j client puts these through, accepts `[A-Za-z0-9_-]` only. So the id's dots
     * become underscores, which also keeps these clear of the flow tools' own names.
     */
    fun toolName(moduleId: String): String = moduleId.replace('.', '_').replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** The module a tool name came from, or null if it is not one of these. */
    fun moduleFor(toolName: String, modules: List<ModuleInfo>): ModuleInfo? =
        modules.find { toolName(it.id) == toolName }

    /**
     * What the tool does, in the terms a caller chooses tools by.
     *
     * The module's own description says what it is; this adds what a caller cannot see from a name
     * — the ports it will be given back, and that values are text unless prefixed.
     */
    fun description(module: ModuleInfo): String {
        val what = module.portDescriptions["_module"]
            ?: "Run the ${module.name} processor directly, without building a flow."
        val outputs = module.outputs.joinToString(", ").ifEmpty { "nothing" }
        return "$what Returns: $outputs. Byte values are UTF-8 text unless prefixed with 'hex:' or " +
            "'b64:'; outputs come back the same way, printable text as-is and anything else as 'hex:'."
    }

    /**
     * The schema, from what the module declares.
     *
     * Required is the honest minimum: the ports that exist for the default options, less the ones
     * the module says may be left out. An option is never required — every one of them has a
     * default, and a caller that omits it gets that default, which is the behaviour a person gets
     * on the canvas too.
     */
    fun schema(module: ModuleInfo): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            module.inputs.forEach { port ->
                putJsonObject(port) {
                    put("type", "string")
                    put("description", module.portDescriptions[port] ?: "the '$port' input, as text or 'hex:'/'b64:' bytes")
                }
            }
            module.options.forEach { option ->
                putJsonObject(option.name) { describeOption(option, module) }
            }
        }
        val required = module.inputs - module.optionalInputs.toSet()
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.describeOption(option: OptDef, module: ModuleInfo) {
        put("type", if (option.type == OptType.NUMBER) "number" else "string")
        if (option.choices.isNotEmpty()) {
            putJsonArray("enum") { option.choices.forEach { add(it) } }
        }
        if (option.default.isNotEmpty()) put("default", option.default)
        val said = module.optionDescriptions[option.name]
        put(
            "description",
            said ?: "the '${option.name}' option" + if (option.default.isNotEmpty()) " (default ${option.default})" else "",
        )
    }

    /**
     * Runs one module and returns its outputs as text.
     *
     * Arguments are strings because that is what a tool call carries; ports are bytes. The prefixes
     * are the bridge, and they are two-way: what comes back is printable text as itself and
     * anything else as `hex:`, so a caller can feed one module's output straight into the next
     * without knowing which it got.
     */
    fun call(module: ModuleInfo, args: JsonObject): String {
        // Two passes, because one option can decide another's choices and its default: cipher's
        // padding is PKCS5Padding for a block cipher and PKCS1Padding for RSA. Filling defaults
        // from the static list would hand an RSA call a padding RSA cannot use — and then refuse
        // it for a combination the caller never asked for.
        val given = module.options.mapNotNull { option -> args.text(option.name)?.let { option.name to it } }.toMap()
        val applicable = Platform.moduleOptionsFor(module.id, given) ?: module.options
        val options = applicable.associate { option -> option.name to (given[option.name] ?: option.default) }
        rejectUnknownChoices(applicable, options)

        // the ports that exist for *these* options, not the defaults the schema was built from —
        // cipher drops iv for RSA, signature adds one for verify
        val ports = Platform.moduleInputsFor(module.id, options) ?: module.inputs
        val optional = module.optionalInputs.toSet()
        val inputs = ports.associateWith { port ->
            val given = args.text(port)
            if (given == null) {
                if (port !in optional) {
                    throw ToolFailure(
                        code = ToolFailure.MISSING_PORT,
                        port = port,
                        hint = "This port is required for these options. " +
                            (module.portDescriptions[port] ?: "Give it as text, or as 'hex:'/'b64:' bytes."),
                        message = "'$port' was not given",
                    )
                }
                null
            } else {
                decode(given, port)
            }
        }

        val out = runCatching { flow.platform.ExtensionLoader.process(module.id, inputs, options) }
            .getOrElse { throw ToolFailure.fromCrypto(unwrap(it), module.name) }

        if (out.isEmpty()) return "(no output)"
        return out.entries.joinToString("\n") { (port, bytes) -> "$port: ${encode(bytes)}" }
    }

    /**
     * An option set to something the module does not offer.
     *
     * Caught here rather than left to the module, because a model picks from the enum it was shown
     * and a value outside it is nearly always a stale schema or a hallucinated name — worth saying
     * plainly, with the real choices, instead of whatever the module makes of it.
     */
    private fun rejectUnknownChoices(applicable: List<OptDef>, options: Map<String, String>) {
        applicable.forEach { option ->
            if (option.choices.isEmpty()) return@forEach
            val value = options[option.name].orEmpty()
            if (value.isNotEmpty() && value !in option.choices) {
                throw ToolFailure(
                    code = ToolFailure.INVALID_OPTION,
                    port = option.name,
                    hint = "'${option.name}' takes one of: ${option.choices.joinToString(", ")}.",
                    message = "'${option.name}' was set to a value it does not accept",
                )
            }
        }
    }

    /** `hex:`/`b64:` for bytes, anything else as UTF-8 — the convention every port description states. */
    private fun decode(value: String, port: String): ByteArray = when {
        value.startsWith("hex:") -> hex(value.removePrefix("hex:").trim(), port)
        value.startsWith("b64:") -> runCatching { Base64.getDecoder().decode(value.removePrefix("b64:").trim()) }
            .getOrElse {
                throw ToolFailure(
                    ToolFailure.DECODE_FAILED, "The value after 'b64:' is not valid base64.", port,
                    "'$port' could not be decoded as base64",
                )
            }
        else -> value.encodeToByteArray()
    }

    private fun hex(text: String, port: String): ByteArray {
        val clean = text.removePrefix("0x")
        if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw ToolFailure(
                ToolFailure.DECODE_FAILED,
                "The value after 'hex:' must be an even number of hex digits.",
                port,
                "'$port' could not be decoded as hex",
            )
        }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Printable text as itself; anything else as `hex:`, so it can be fed back in unchanged. */
    private fun encode(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val text = runCatching { bytes.decodeToString() }.getOrNull()
        val printable = text != null && text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } &&
            text.encodeToByteArray().contentEquals(bytes)
        return if (printable) text!! else "hex:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The exception the module actually threw.
     *
     * It crossed a process boundary as a message and comes back wrapped, so the type that would
     * have told ToolFailure what kind of failure it is has been lost. What survives is the class
     * name in the text, which is enough to put it back.
     */
    private fun unwrap(e: Throwable): Throwable {
        val said = e.message.orEmpty()
        return when {
            said.contains("BadPaddingException") -> javax.crypto.BadPaddingException(said.substringAfterLast(": "))
            said.contains("IllegalBlockSizeException") -> javax.crypto.IllegalBlockSizeException(said.substringAfterLast(": "))
            said.contains("InvalidKeyException") -> java.security.InvalidKeyException(said.substringAfterLast(": "))
            said.contains("InvalidAlgorithmParameterException") ->
                java.security.InvalidAlgorithmParameterException(said.substringAfterLast(": "))
            said.contains("NoSuchAlgorithmException") -> java.security.NoSuchAlgorithmException(said.substringAfterLast(": "))
            said.contains("NoSuchPaddingException") -> javax.crypto.NoSuchPaddingException(said.substringAfterLast(": "))
            else -> e
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
}
