package flow.cli

import flow.engine.FlowEngine
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.runBlocking

// Loading and running components outside the UI, shared by the CLI and the MCP server.

internal val runnerJson = Json { ignoreUnknownKeys = true }

// A component that can be run: project flows live in the flows dir, installed ones run sandboxed.
internal class RunnableComponent(val ref: String, val comp: CompDef, val installed: Boolean)

// load a flow by name (project/installed component) or by file path.
// project flow files are ".flow"; installed components are always ".json" (see ModuleLoader) —
// try both when no module was given.
internal fun loadFlow(ref: String): FlowFile? {
    val names = if (ref.endsWith(".flow") || ref.endsWith(".json")) listOf(ref) else listOf("$ref.flow", "$ref.json")
    val raw = File(ref).takeIf { it.isFile }?.readText()
        ?: names.firstNotNullOfOrNull { name -> File(name).takeIf { it.isFile }?.readText() }
        ?: names.firstNotNullOfOrNull { name -> Platform.readFlow(name) }
        ?: names.firstNotNullOfOrNull { name -> Platform.readInstalledComponent(name) }
        ?: return null
    return runCatching { runnerJson.decodeFromString<FlowFile>(raw) }.getOrNull()
}

internal fun engine(): FlowEngine {
    val installed = Platform.installedModuleInfos()
    return FlowEngine(
        loadFlow = ::loadFlow,
        moduleIds = installed.map { it.id }.toSet(),
        moduleProcess = { id, inputs, options -> Platform.moduleProcess(id, inputs, options) },
        optionalInputsOf = { id -> installed.find { it.id == id }?.optionalInputs?.toSet() },
    )
}

// true when [ref] resolves only to an installed component (which runs in its own sandbox)
internal fun isInstalledComponent(ref: String): Boolean {
    val name = if (ref.endsWith(".json")) ref else "$ref.json"
    return File(ref).takeIf { it.isFile } == null &&
        Platform.readFlow(name) == null &&
        Platform.readInstalledComponent(name) != null
}

// every component available as a comp: node — installed ones only. A project flow becomes one of
// these by being explicitly installed (Settings ▸ Modules ▸ Flows), same as a module or
// view; merely existing in the open folder does not, any more than a module is on the palette
// just because its jar is somewhere on disk. Running a flow directly by path (run_flow, the CLI's
// own positional-arg mode) is unaffected — this list is only what's offered as a *building block*.
internal fun listComponents(): List<RunnableComponent> {
    val installed = Platform.listInstalledComponents().mapNotNull { file ->
        loadFlow(file)?.asComponent(file)?.let { RunnableComponent(file, it, installed = true) }
    }
    return installed
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
    // the CLI has no event loop of its own, so it blocks here while the engine runs its nodes
    val result = runBlocking { eng.run(flow, inputs) }
    return result to eng.errors
}
