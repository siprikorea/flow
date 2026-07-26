package flow.extension

/** The kind of editor a module option uses in the Flow property panel. */
enum class OptionType { TEXT, NUMBER, SELECT }

/**
 * A predefined module option the user can edit in the property panel.
 * `default` seeds the value on node creation; `choices` lists the allowed values for SELECT.
 */
data class ExtensionOption(
    val name: String,
    val type: OptionType = OptionType.TEXT,
    val default: String = "",
    val choices: List<String> = emptyList(),
)

/**
 * A Flow module extension: implements the input → output processing of a custom module.
 *
 * Extensions provide modules only — components are built inside the Flow tool and added there.
 *
 * Implementations must have a no-arg constructor and be registered under
 * `META-INF/services/flow.extension.ModuleExtension`.
 */
interface ModuleExtension {
    /** Identifier in package-name format (e.g. "com.example.base64"). */
    val id: String

    /** Name shown in the palette. */
    val displayName: String

    /** Input port ids. */
    val inputs: List<String>

    /** Output port ids. */
    val outputs: List<String>

    /** Predefined options shown in the property panel (name → value). Empty by default. */
    val options: List<ExtensionOption> get() = emptyList()

    /**
     * Process the port data. Values on ports are raw bytes:
     * input-port-id → bytes, plus the current option values, producing output-port-id → bytes.
     */
    fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
}
