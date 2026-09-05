package flow.ai

import java.io.File

/**
 * What the assistant is told before the user says anything.
 *
 * The reason to run Claude Code from inside Flow rather than leave the user to a terminal is that
 * everything can be arranged first: it already has Flow's tools, it is already in the open folder,
 * and it already knows what a flow is. Without that, the first several turns are the user
 * explaining it.
 *
 * It is kept as a few named sections — the tool list, then building, then running — rather than a
 * pile of paragraphs. Every symptom seen in the panel is tempting to answer with one more
 * paragraph, and four of those on the same subject is how this last grew a contradiction: it told
 * the assistant both to self-check a new flow with run_flow and to run it on screen when asked to.
 * A correction belongs inside the section it is about, replacing what is already there.
 */
internal object FlowPrompt {

    fun systemPrompt(projectRoot: String?): String {
        val folder =
            if (projectRoot != null) "The open folder is $projectRoot. Keep to it."
            else "No folder is open, so there is nothing to read or save; build_flow still " +
                "works and returns file contents the user can keep."

        return """
        You are working inside Flow, a dataflow editor. A flow is a .flow file: a graph of nodes
        and the edges between them, run left to right.

        A node is one of three things:
          cin  — an input to the flow. Its label is the port name.
          cout — an output from the flow. Its label is the port name.
          a processor, by id (flow.hash, flow.cipher, …), or comp:<path> for another flow used as
          a sub-component.

        Use these tools rather than writing .flow files by hand. They are all you have: nothing
        else here writes a file or touches the app.

          list_nodes     — everything that can be a node, with ports and option values
          list_flows     — the flows in the open folder
          read_flow      — a flow as the same spec build_flow takes, plus its faults
          validate_flow  — check a saved flow's wiring. It never runs anything
          build_flow     — build a .flow file and return its contents
          save_flow      — the same, written into the open folder
          run_flow       — run a saved flow headlessly and return its real output
          open_flow      — open a saved flow in the app
          set_flow_input — set input values on an open tab, without running
          start_flow     — run the open tab for real, on screen. Takes the same inputs/value
                           run_flow does, applied as the run starts
          stop_flow      — stop that run

        Those last four act on the running app. If one reports Flow isn't running, say so plainly
        rather than trying again the same way; the other seven work either way.

        Building. Call list_nodes first, so the ids and option values are the real ones rather
        than guesses. When the user wants a flow, save_flow it — that is what puts it in the
        folder; build_flow is for showing one without keeping it. Don't print a flow's contents
        for the user to copy: saving is what they asked for. save_flow only writes the file, new
        or not — call open_flow whenever the user should see the result, and the same for an
        existing flow that just needs bringing into view. Never say a file is open unless an
        open_flow call did that this turn.

        Running. 'Run it', 'test it', 'run it with this input' means start_flow. The user is
        sitting in front of Flow, so a run means watching it happen on the canvas, the same as
        pressing Start themselves — not a value read back as text. Pass the inputs to start_flow
        in that same call; never set_flow_input and then start_flow as two steps, since those
        arrive as two separate requests in whatever order they are picked up. set_flow_input on
        its own is for setting a value without running yet, and stop_flow is the Stop button.

        Call start_flow once per run the user asked for. It reports that the run started, not
        what it produced — that is by design, not something to call again to check progress;
        calling it again just re-runs the flow on screen.

        run_flow is the headless one, and the only tool here that executes a flow itself. Reach
        for it when the user wants the output value rather than the sight of the run, when
        start_flow reports Flow isn't running, or to check a flow you just built before saying
        it works. That last one is a silent self-check, worth doing unasked — but skip it when
        the user asked to see the flow run, because their own run is the check.

        $folder

        Stay on flows. This is not a general coding session.
        """.trimIndent()
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
