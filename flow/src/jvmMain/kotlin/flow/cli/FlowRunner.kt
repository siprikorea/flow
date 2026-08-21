package flow.cli

import flow.engine.FlowEngine
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.serialization.json.Json
import java.io.File

// Loading and running components outside the UI, shared by the CLI and the MCP server.

internal val runnerJson = Json { ignoreUnknownKeys = true }

// A component that can be run: project flows live in the flows dir, installed ones run sandboxed.
internal class RunnableComponent(val ref: String, val comp: CompDef, val installed: Boolean)

// load a flow by name (project/installed component) or by file path.
// project flow files are ".flow"; installed components are always ".json" (see ExtensionLoader) —
// try both when no extension was given.
internal fun loadFlow(ref: String): FlowFile? {
    val names = if (ref.endsWith(".flow") || ref.endsWith(".json")) listOf(ref) else listOf("$ref.flow", "$ref.json")
    val raw = File(ref).takeIf { it.isFile }?.readText()
        ?: names.firstNotNullOfOrNull { name -> File(name).takeIf { it.isFile }?.readText() }
        ?: names.firstNotNullOfOrNull { name -> Platform.readFlow(name) }
        ?: names.firstNotNullOfOrNull { name -> Platform.readInstalledComponent(name) }
        ?: return null
    return runCatching { runnerJson.decodeFromString<FlowFile>(raw) }.getOrNull()
}

internal fun engine(): FlowEngine = FlowEngine(
    loadFlow = ::loadFlow,
    moduleIds = Platform.installedModuleInfos().map { it.id }.toSet(),
    moduleProcess = { id, inputs, options -> Platform.moduleProcess(id, inputs, options) },
)

// true when [ref] resolves only to an installed component (which runs in its own sandbox)
internal fun isInstalledComponent(ref: String): Boolean {
    val name = if (ref.endsWith(".json")) ref else "$ref.json"
    return File(ref).takeIf { it.isFile } == null &&
        Platform.readFlow(name) == null &&
        Platform.readInstalledComponent(name) != null
}

// every component that can be run: project flows first, then installed ones
internal fun listComponents(): List<RunnableComponent> {
    val project = Platform.listFlows().mapNotNull { file ->
        loadFlow(file)?.asComponent(file)?.let { RunnableComponent(file, it, installed = false) }
    }
    val installed = Platform.listInstalledComponents().mapNotNull { file ->
        loadFlow(file)?.asComponent(file)?.let { RunnableComponent(file, it, installed = true) }
    }
    return project + installed
}

// Run a component. Returns its outputs plus any per-node errors the engine recorded
// (installed components run sandboxed and report errors only by throwing).
internal fun runComponent(
    target: RunnableComponent,
    inputs: Map<String, ByteArray>,
): Pair<Map<String, ByteArray?>, Map<String, String>> {
    if (target.installed) {
        return Platform.runComponent(target.ref.removeSuffix(".json"), inputs) to emptyMap()
    }
    val flow = loadFlow(target.ref) ?: return emptyMap<String, ByteArray?>() to emptyMap()
    val eng = engine()
    val result = eng.run(flow, inputs)
    return result to eng.errors
}
