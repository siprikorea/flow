package flow.ai

import java.io.File

/**
 * What the assistant is told before the user says anything.
 *
 * The reason to run Claude Code from inside Flow rather than leave the user to a terminal is that
 * everything can be arranged first: it already has Flow's tools, it is already in the open folder,
 * and it already knows what a flow is. Without that, the first several turns are the user
 * explaining it.
 */
internal object FlowPrompt {

    fun systemPrompt(projectRoot: String?): String = buildString {
        appendLine("You are working inside Flow, a dataflow editor. A flow is a .flow file: a graph")
        appendLine("of nodes and the edges between them, run left to right.")
        appendLine()
        appendLine("A node is one of three things:")
        appendLine("  cin  — an input to the flow. Its label is the port name.")
        appendLine("  cout — an output from the flow. Its label is the port name.")
        appendLine("  a processor, by id (flow.hash, flow.cipher, …), or comp:<path> for another")
        appendLine("  flow used as a sub-component.")
        appendLine()
        appendLine("Use the flow tools rather than writing .flow files by hand:")
        appendLine("  list_nodes     — everything that can be a node, with ports and option values")
        appendLine("  list_flows     — the flows in the open folder")
        appendLine("  read_flow      — a flow as the same spec build_flow takes, plus its faults")
        appendLine("  validate_flow  — check a saved flow")
        appendLine("  run_flow       — run a saved flow with real input and see its actual output")
        appendLine("  build_flow     — build a .flow file and return its contents")
        appendLine("  save_flow      — the same, written into the open folder")
        appendLine("  open_flow      — open an already-saved flow as-is, unchanged")
        appendLine()
        appendLine("Call list_nodes before building, so the ids and option values are the real ones")
        appendLine("rather than guesses.")
        appendLine()
        appendLine("When the user wants a flow, save_flow it — that puts it in the folder. build_flow is")
        appendLine("for showing one without keeping it. Do not print a flow's contents for the user to")
        appendLine("copy: saving is what they asked for.")
        appendLine()
        appendLine("Flow opens a file the moment save_flow actually changes it on disk — new, or new")
        appendLine("content for one that already existed. Calling save_flow again with identical content")
        appendLine("changes nothing and does not reopen it — use open_flow for that instead: the user")
        appendLine("asking to see or open a flow that's already exactly as wanted, with nothing to")
        appendLine("write, is exactly what it's for. Never tell the user a file is open on the strength")
        appendLine("of save_flow alone unless that call's content actually differed from before.")
        appendLine()
        appendLine("validate_flow only checks the wiring — it never runs anything. After saving a flow,")
        appendLine("run it with run_flow and representative input before telling the user it's done;")
        appendLine("that's the only way to confirm it actually produces the right output.")
        appendLine()
        appendLine("These tools are all you have here: nothing else writes files, and run_flow is the")
        appendLine("only thing that executes anything.")
        appendLine()
        if (projectRoot != null) {
            appendLine("The open folder is $projectRoot. Keep to it.")
        } else {
            appendLine("No folder is open, so there is nothing to read or save; build_flow still works")
            appendLine("and returns file contents the user can keep.")
        }
        appendLine()
        appendLine("Stay on flows. This is not a general coding session.")
    }

    /**
     * Points Claude Code at Flow's own MCP server.
     *
     * The server is this application's code, so it is started the same way this process was — no
     * separate install, and no version of the tools other than the one running.
     *
     * Headless, and no dock icon. It talks over a pipe and has no business on the screen; without
     * saying so, a Java icon appears in the dock for as long as a question takes to answer.
     */
    fun mcpConfig(projectRoot: String?): File? = runCatching {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path") ?: return null
        val config = File.createTempFile("flow-mcp", ".json").apply { deleteOnExit() }
        config.writeText(
            """
            {
              "mcpServers": {
                "flow": {
                  "command": ${quote(java)},
                  "args": [
                    "-Djava.awt.headless=true",
                    "-Dapple.awt.UIElement=true",
                    "-cp", ${quote(classpath)},
                    "flow.cli.CliKt", "--mcp"${projectRoot?.let { ", \"--project\", " + quote(it) } ?: ""}
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        config
    }.getOrNull()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
