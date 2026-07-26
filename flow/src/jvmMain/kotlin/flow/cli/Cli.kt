package flow.cli

import flow.engine.FlowEngine
import flow.model.FlowFile
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true }

// load a flow by name (project/installed component) or by file path.
// project flow files are ".flow"; installed components are always ".json" (see ExtensionLoader) —
// try both when no extension was given.
private fun loadFlow(ref: String): FlowFile? {
    val names = if (ref.endsWith(".flow") || ref.endsWith(".json")) listOf(ref) else listOf("$ref.flow", "$ref.json")
    val raw = File(ref).takeIf { it.isFile }?.readText()
        ?: names.firstNotNullOfOrNull { name -> File(name).takeIf { it.isFile }?.readText() }
        ?: names.firstNotNullOfOrNull { name -> Platform.readFlow(name) }
        ?: names.firstNotNullOfOrNull { name -> Platform.readInstalledComponent(name) }
        ?: return null
    return runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull()
}

private fun engine(): FlowEngine {
    val moduleIds = Platform.installedModuleInfos().map { it.id }.toSet()
    return FlowEngine(
        loadFlow = ::loadFlow,
        moduleIds = moduleIds,
        moduleProcess = { id, inputs, options -> Platform.moduleProcess(id, inputs, options) },
    )
}

private fun usage(): Nothing {
    System.err.println(
        """
        Flow CLI — run a component to compute input -> output.

        Usage:
          cli <component> <value>                 single input (when there is one port)
          cli <component> --in <port>=<value> ... per-port input
          cli --list                              list components (project + installed)
          cli --install <extension.jar> [--force]    install an extension JAR
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()

    when (args[0]) {
        "--install" -> {
            val jar = args.getOrNull(1) ?: usage()
            val force = args.contains("--force")
            val r = Platform.installJar(jar, overwrite = force)
            if (r.conflicts.isNotEmpty()) {
                System.err.println("Already installed: ${r.conflicts.joinToString(", ")} — use --force to overwrite")
                exitProcess(1)
            }
            println("Installed: ${r.installed.joinToString(", ").ifBlank { "(no extensions)" }}")
            return
        }
        "--list" -> {
            (Platform.listFlows().mapNotNull { loadFlow(it)?.asComponent(it) } +
                Platform.listInstalledComponents().mapNotNull { loadFlow(it)?.asComponent(it) })
                .distinctBy { it.name }
                .forEach { println("${it.name}  (${it.ins.joinToString(",")} → ${it.outs.joinToString(",")})") }
            if (Platform.installedModuleInfos().isNotEmpty()) {
                println("-- installed modules --")
                Platform.installedModuleInfos().forEach { println("${it.id}  (${it.inputs.joinToString(",")} → ${it.outputs.joinToString(",")})") }
            }
            return
        }
    }

    val ref = args[0]
    // installed components run in their own folder sandbox; project/file flows use global modules
    val installedName = if (ref.endsWith(".json")) ref else "$ref.json"
    val isInstalled = File(ref).takeIf { it.isFile } == null &&
        Platform.readFlow(installedName) == null &&
        Platform.readInstalledComponent(installedName) != null

    val flow = loadFlow(ref) ?: run {
        System.err.println("Component not found: $ref")
        exitProcess(1)
    }
    val comp = flow.asComponent(ref) ?: run {
        System.err.println("'$ref' is not a component (no input/output boundary nodes).")
        exitProcess(1)
    }

    val inputs = HashMap<String, String>()
    var idx = 1
    val positional = ArrayList<String>()
    while (idx < args.size) {
        val a = args[idx]
        if (a == "--in") {
            val kv = args.getOrNull(idx + 1) ?: usage()
            val eq = kv.indexOf('=')
            if (eq <= 0) usage()
            inputs[kv.substring(0, eq)] = kv.substring(eq + 1)
            idx += 2
        } else {
            positional.add(a); idx++
        }
    }
    if (inputs.isEmpty() && positional.size == 1 && comp.ins.size == 1) {
        inputs[comp.ins.first()] = positional.first()
    }
    comp.ins.forEach { inputs.putIfAbsent(it, "") }

    val result = if (isInstalled) Platform.runComponent(ref.removeSuffix(".json"), inputs) // sandbox
    else engine().run(flow, inputs) // global modules
    comp.outs.forEach { out -> println("$out = ${result[out] ?: ""}") }
}
