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
        appendLine("  build_flow     — build a .flow file and return its contents")
        appendLine()
        appendLine("Call list_nodes before building, so the ids and option values are the real ones")
        appendLine("rather than guesses.")
        appendLine()
        appendLine("These tools are all you have here: no files can be written and nothing can be run.")
        appendLine("build_flow returns the file's contents — show them to the user, who saves them.")
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
     */
    fun mcpConfig(): File? = runCatching {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path") ?: return null
        val config = File.createTempFile("flow-mcp", ".json").apply { deleteOnExit() }
        config.writeText(
            """
            {
              "mcpServers": {
                "flow": {
                  "command": ${quote(java)},
                  "args": ["-cp", ${quote(classpath)}, "flow.cli.CliKt", "--mcp"]
                }
              }
            }
            """.trimIndent(),
        )
        config
    }.getOrNull()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
