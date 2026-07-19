package flow.plugin

/**
 * Common Flow plugin contract.
 * A plugin is either a module plugin ([ModulePlugin]) or a component plugin ([ComponentPlugin]).
 * Implementations must have a no-arg constructor and be registered under `META-INF/services/`.
 */
interface DataflowPlugin {
    /** Identifier. Must be in package-name format (e.g. "com.example.double"). */
    val id: String

    /** Name shown in the palette. */
    val displayName: String

    /** Input port ids (required). */
    val inputs: List<String>

    /** Output port ids (required). */
    val outputs: List<String>
}

/**
 * Module plugin: carries the actual input/output processing implementation.
 * Given input-port-id → value, returns output-port-id → value.
 */
interface ModulePlugin : DataflowPlugin {
    fun process(inputs: Map<String, String?>): Map<String, String?>
}

/**
 * Component plugin: defines only the connections between modules (no size/coordinates).
 * Since there are no coordinates, it is auto-laid-out in connection order on install.
 */
interface ComponentPlugin : DataflowPlugin {
    /** Internal module nodes (cin/cout boundaries are generated from inputs/outputs). */
    fun nodes(): List<PluginNode>

    /** Connections between internal nodes. */
    fun connections(): List<PluginConn>

    /** External input id → "nodeId.port" (which internal port a component input feeds into). */
    fun inputBindings(): Map<String, String>

    /** External output id → "nodeId.port" (which internal port a component output comes from). */
    fun outputBindings(): Map<String, String>
}

/** A component's internal node definition (no coordinates). */
data class PluginNode(
    val id: String,
    val type: String, // built-in module type or an installed module id
    val inputs: List<String>,
    val outputs: List<String>,
    val params: Map<String, String> = emptyMap(),
)

/** A component's internal connection. */
data class PluginConn(
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
)
