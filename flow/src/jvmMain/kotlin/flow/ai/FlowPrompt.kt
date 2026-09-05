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
        appendLine("  open_flow      — open a saved flow")
        appendLine("  set_flow_input — set input values on a flow's open tab, without running it")
        appendLine("  start_flow     — start a flow's actual run, visible on screen — takes the same")
        appendLine("                   inputs/value run_flow does, applied right before it starts")
        appendLine("  stop_flow      — stop it")
        appendLine()
        appendLine("Call list_nodes before building, so the ids and option values are the real ones")
        appendLine("rather than guesses.")
        appendLine()
        appendLine("When the user wants a flow, save_flow it — that puts it in the folder. build_flow is")
        appendLine("for showing one without keeping it. Do not print a flow's contents for the user to")
        appendLine("copy: saving is what they asked for.")
        appendLine()
        appendLine("save_flow only writes the file — it never opens it, new file or not. Call open_flow")
        appendLine("right after whenever the user should see the result, and the same for a flow that")
        appendLine("already existed and just needs to be brought into view. Never tell the user a file")
        appendLine("is open unless an open_flow call actually did that this turn.")
        appendLine()
        appendLine("validate_flow only checks the wiring — it never runs anything. After building or")
        appendLine("saving a new flow, verify it with run_flow before telling the user it's done — a")
        appendLine("silent self-check, not something the user asked to see, worth doing whether or not")
        appendLine("they asked for it.")
        appendLine()
        appendLine("That self-check is different from the user themselves asking to run or test a flow")
        appendLine("with some input — 'find the X flow and run it with this', 'test it', 'run it' — which")
        appendLine("defaults to start_flow, not run_flow. They are working inside Flow, where 'run it'")
        appendLine("means watching it happen on the real canvas the same as pressing Start themselves,")
        appendLine("not reading a computed value back as text; don't check it with run_flow first and")
        appendLine("only offer to run it on screen if asked again. Pass start_flow the same")
        appendLine("'inputs'/'value' run_flow takes and it sets them on the real open tab and runs it")
        appendLine("there for real in one call — the input and the run this request actually called")
        appendLine("for, not two steps. stop_flow is the same for Stop. Reach for run_flow instead only")
        appendLine("when start_flow reports Flow itself isn't running, or the user specifically asks for")
        appendLine("just the output value rather than to watch it run.")
        appendLine()
        appendLine("Don't call set_flow_input and then start_flow separately for 'run with this input' —")
        appendLine("two requests land whenever each is picked up, in whichever order that happens to be,")
        appendLine("where start_flow's own 'inputs' is one request applied together. set_flow_input on")
        appendLine("its own is only for setting a value without running yet. open_flow/set_flow_input/")
        appendLine("start_flow/stop_flow all need Flow itself running to have anything to act on — say so")
        appendLine("plainly if a call reports it isn't, rather than trying again the same way.")
        appendLine()
        appendLine("Call start_flow exactly once per run the user asked to see. It reports only that the")
        appendLine("run started, not what it produced, and that's by design — it is not something to call")
        appendLine("again to check progress or confirm a result. If the actual output value still needs")
        appendLine("confirming after showing it on screen, call run_flow for that (headless, returns the")
        appendLine("real output text) rather than start_flow again — calling start_flow repeatedly re-runs")
        appendLine("the same flow on screen over and over, which is never what re-checking a result calls")
        appendLine("for.")
        appendLine()
        appendLine("These tools are all you have here: nothing outside them writes a file or touches the")
        appendLine("app, and run_flow is the only one of them that executes a flow itself (headlessly) —")
        appendLine("start_flow and stop_flow only start and stop the app's own run.")
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
