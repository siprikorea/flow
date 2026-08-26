package flow.cli

import flow.mcp.McpServer
import flow.model.asComponent
import flow.platform.Platform
import kotlin.system.exitProcess

private fun usage(): Nothing {
    System.err.println(
        """
        Flow CLI — run a component to compute input -> output.

        Usage:
          cli <component> <value>                 single input (when there is one port)
          cli <component> --in <port>=<value> ... per-port input
          cli --list                              list components (project + installed)
          cli --install <extension.jar> [--force]    install an extension JAR
          cli --mcp                               run as an MCP server on stdio
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
        "--mcp" -> {
            // Which folder to work in. The app does not remember one between runs — it opens with
            // none, by design — so a server started by the app has no way to find out for itself,
            // and without this every answer is "no project folder is open".
            args.indexOf("--project").takeIf { it >= 0 }?.let { at ->
                args.getOrNull(at + 1)?.let { Platform.openProject(it) }
            }
            McpServer.run()
            return
        }
        "--list" -> {
            listComponents().map { it.comp }
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
    val isInstalled = isInstalledComponent(ref)

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

    // the engine speaks bytes; the CLI's own boundary (args in, stdout out) is plain UTF-8 text
    val byteInputs = inputs.mapValues { it.value.encodeToByteArray() }
    val (result, errors) = runComponent(RunnableComponent(ref, comp, isInstalled), byteInputs)
    errors.forEach { (nodeId, msg) -> System.err.println("⚠ $nodeId: $msg") }
    comp.outs.forEach { out -> println("$out = ${result[out]?.decodeToString() ?: ""}") }
}
