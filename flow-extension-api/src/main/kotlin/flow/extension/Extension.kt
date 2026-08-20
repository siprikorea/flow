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

    /** Input port ids (the full/default set — see [inputsFor] for option-dependent ports). */
    val inputs: List<String>

    /** Output port ids (the full/default set — see [outputsFor] for option-dependent ports). */
    val outputs: List<String>

    /** Predefined options shown in the property panel (name → value). Empty by default. */
    val options: List<ExtensionOption> get() = emptyList()

    /**
     * Input port ids for the given option values, when a module's ports depend on an option
     * (e.g. a "verify" operation needing a signature input that "sign" doesn't). Defaults to the
     * fixed [inputs]. The host uses this to keep a node's actual ports in sync with its options —
     * ports are never user-added/removed/renamed on an extension module, only driven by this.
     */
    fun inputsFor(options: Map<String, String>): List<String> = inputs

    /** Output port ids for the given option values. Defaults to the fixed [outputs]. */
    fun outputsFor(options: Map<String, String>): List<String> = outputs

    /**
     * Options to show for the given option values, when one option decides whether others apply
     * (e.g. an iteration count that only a key-derivation algorithm uses). Defaults to the fixed
     * [options]. The host shows exactly this list in the property panel; values of options that
     * are not currently shown are kept, so they come back when the deciding option does.
     */
    fun optionsFor(values: Map<String, String>): List<ExtensionOption> = options

    /**
     * Process the port data. Values on ports are raw bytes:
     * input-port-id → bytes, plus the current option values, producing output-port-id → bytes.
     */
    fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
}
