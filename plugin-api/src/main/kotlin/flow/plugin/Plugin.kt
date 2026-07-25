package flow.plugin

/**
 * A Flow module plugin: implements the input → output processing of a custom module.
 *
 * Plugins provide modules only — components are built inside the Flow tool and added there.
 *
 * Implementations must have a no-arg constructor and be registered under
 * `META-INF/services/flow.plugin.ModulePlugin`.
 */
interface ModulePlugin {
    /** Identifier in package-name format (e.g. "com.example.double"). */
    val id: String

    /** Name shown in the palette. */
    val displayName: String

    /** Input port ids. */
    val inputs: List<String>

    /** Output port ids. */
    val outputs: List<String>

    /** Process input-port-id → value into output-port-id → value. */
    fun process(inputs: Map<String, String?>): Map<String, String?>
}
