package flow.mcp

import flow.cli.RunnableComponent
import flow.cli.isInstalledComponent
import flow.cli.listComponents
import flow.cli.loadFlow
import flow.cli.runComponent
import flow.cli.runnerJson
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.IO_DEFS
import flow.model.OptType
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

/**
 * Everything Flow offers an assistant, as tools: see what processors are available, draft a flow
 * from a description, read one back, review its wiring, write the edit out again, and run it for
 * real to check the output rather than just the wiring.
 *
 * Everything mechanical about the file format (port lists, edge ids, node sizes, and coordinates
 * when they aren't given) is worked out here, so a caller only ever describes what the flow does
 * and how its processors connect. list_nodes/list_flows/read_flow/validate_flow/build_flow/
 * save_flow/run_flow are pure file I/O and the CLI's own headless engine — they work whether or
 * not the app itself is running.
 *
 * open_flow/set_flow_input/start_flow/stop_flow are the other kind: served from the MCP server
 * this is a separate process from the app and can't reach into its open tabs directly, so each
 * leaves a request behind (see Platform.request*) for Workspace.askAi to pick up once the current
 * turn ends — the actual app, actually running the actual flow on screen, as opposed to run_flow's
 * headless check. They refuse outright when the app isn't running (Platform.isAppRunning), rather
 * than leaving a request nothing will ever read.
 *
 * Nothing here knows about MCP. These same tools are reached two ways: over stdio by an MCP client
 * (Claude Desktop/Code — [FlowMcpServer] puts them on the wire), and directly by the assistant
 * running inside the app, which is in this process and just calls [call]. A tool that leaves a
 * request behind for the app does that either way.
 */
object FlowTools {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // flow files on disk are pretty-printed (the editor writes them that way), and a spec read back
    // to an MCP client is far easier to edit indented than as one line
    private val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    // Reading a project needs one to be open. build_flow does not — it returns the file's contents
    // and never touches a folder — so it keeps working either way.
    private const val NO_PROJECT =
        "no project folder is open — open one in Flow (File \u25b8 Open Folder…), or use build_flow, " +
            "which returns the file's contents without needing one"

    // open_flow, set_flow_input, start_flow and stop_flow all act on the running app's own open
    // tabs; everything else here is plain file I/O and works with the app closed.
    private const val APP_NOT_RUNNING =
        "Flow isn't running, so there's no window for this to act on — open the app first. " +
            "list_nodes, list_flows, read_flow, validate_flow, run_flow, build_flow and save_flow " +
            "all work without it."

    /* ───────── tools ───────── */

    private class Tool(val name: String, val description: String, val schema: JsonObject, val call: (JsonObject) -> String)

    /** One tool as a caller sees it: what it is called, what it is for, what it takes. */
    class ToolSpec(val name: String, val description: String, val schema: JsonObject)

    /**
     * What a tool answered.
     *
     * A failure is both prose and fields: [text] is what any caller can show, and [failure] is the
     * same thing as an object — a code to branch on and a hint to act on — for one that reads it.
     * Both are filled from the one [ToolFailure], so neither kind of caller has to parse the
     * other's format.
     */
    class ToolAnswer(val text: String, val failure: JsonObject? = null) {
        val isError: Boolean get() = failure != null
    }

    fun specs(): List<ToolSpec> = allTools().map { ToolSpec(it.name, it.description, it.schema) }

    /**
     * Runs one, answering with what it said — including why it could not.
     *
     * Nothing thrown escapes: a tool's own refusal, a bad argument and a crash all come back as an
     * answer marked as a failure, because the caller on the other side is an assistant that has to
     * be told what went wrong in order to do something else.
     */
    fun call(name: String, args: JsonObject): ToolAnswer {
        val tool = allTools().find { it.name == name } ?: return failed(
            ToolFailure(
                ToolFailure.NOT_FOUND,
                "No tool by that name. List the tools to see what this server has.",
                message = "unknown tool: $name",
            ),
        )
        return runCatching { ToolAnswer(tool.call(args)) }.getOrElse { e -> failed(failureOf(e)) }
    }

    /** The same, for a caller that only shows text — the assistant running inside the app. */
    fun invoke(name: String, args: JsonObject): String = call(name, args).text

    private fun failed(failure: ToolFailure): ToolAnswer {
        val detail = failure.toJson()
        return ToolAnswer(prettyJson.encodeToString(JsonObject.serializer(), detail), detail)
    }

    // Rebuilt per request so flows and processors added while the server runs are seen without a restart.
    private fun tools(): List<Tool> = listOf(
        Tool(
            name = "list_nodes",
            description = "List everything that can be a node in a flow: the cin/cout boundary nodes, " +
                "each installed module with its ports and options, and each flow installed as a " +
                "component (Settings ▸ Modules ▸ Flows) usable as comp:<ref> — a flow that merely " +
                "exists in the open folder isn't one of these until installed; use comp:<path relative " +
                "to the folder> for a project flow that isn't. Call this before build_flow to get exact " +
                "type names and option values.",
            schema = objectSchema(emptyList()),
            call = { listNodes() },
        ),
        Tool(
            name = "save_flow",
            description = "Write a flow into the folder Flow has open, and return where it went. " +
                "Takes the same spec build_flow does, plus a name. Use this when the user wants the " +
                "flow kept rather than shown. This only writes the file — it never opens it, new " +
                "file or not. Follow it with open_flow when the user should see the result; don't " +
                "tell them a file is open on the strength of save_flow alone. Refuses when no folder " +
                "is open, and never writes outside it.",
            schema = buildFlowSchema(),
            call = { args -> saveFlowTool(args) },
        ),
        Tool(
            name = "open_flow",
            description = "Open a saved flow in Flow — the only tool here that does. save_flow never " +
                "opens what it writes, so call this right after it whenever the user should see the " +
                "result, as well as for a flow that already existed and just needs to be brought " +
                "into view. Opens by the time this turn ends. Needs Flow actually running (unlike " +
                "the authoring tools) — refuses if it isn't, if no folder is open, or if the path " +
                "isn't a flow file that exists.",
            schema = objectSchema(listOf(StringField("path", "path relative to the open folder, e.g. 'sub/a.flow' ('.flow' may be omitted)"))),
            call = { args -> openFlowTool(argText(args, "path")) },
        ),
        Tool(
            name = "set_flow_input",
            description = "Sets input values on a flow's open tab (opening it first if needed), " +
                "without running it — real ports on the actual canvas, the same fields the user's " +
                "own typing would land in. Only for setting a value ahead of time without running " +
                "yet; when the user wants to watch the flow actually run with that input, pass " +
                "'inputs'/'value' to start_flow directly instead of calling this first — one request " +
                "that sets and runs together, rather than two whose timings could land either order. " +
                "Needs Flow actually running.",
            schema = runFlowSchema(),
            call = { args -> setFlowInputTool(args) },
        ),
        Tool(
            name = "start_flow",
            description = "Starts a flow's actual run on screen, opening its tab first if needed — " +
                "the same as pressing the title bar's Start button by hand. Takes the same " +
                "'inputs'/'value' run_flow does, applied the instant before the run starts — this is " +
                "the one call to make when the user wants to watch a flow run on screen with real " +
                "input, not set_flow_input followed by this. Unlike run_flow, this doesn't wait for a " +
                "result or return one: the run plays out visibly in the app. Needs Flow actually " +
                "running.",
            schema = runFlowSchema(),
            call = { args -> startFlowTool(args) },
        ),
        Tool(
            name = "stop_flow",
            description = "Stops a flow's run in progress on screen — the same as pressing the title " +
                "bar's Stop button by hand. Needs Flow actually running.",
            schema = objectSchema(listOf(StringField("path", "path relative to the open folder, e.g. 'sub/a.flow' ('.flow' may be omitted)"))),
            call = { args -> stopFlowTool(argText(args, "path")) },
        ),
        Tool(
            name = "list_flows",
            description = "List the flow files in the folder Flow currently has open, with their " +
                "input/output ports — use it to find the flow to read or edit. Reports when no folder " +
                "is open.",
            schema = objectSchema(emptyList()),
            call = { listFlows() },
        ),
        Tool(
            name = "read_flow",
            description = "Read a flow from the project as the same {nodes, edges} spec build_flow accepts, " +
                "together with a 'problems' list naming any wiring faults (unconnected ports, an input fed " +
                "twice, a cycle, a missing boundary). Use it to review a flow, fix the spec, and pass it to " +
                "build_flow for the corrected file. Coordinates come back too — keep them to preserve the " +
                "arrangement, or change them to tidy the layout.",
            schema = objectSchema(listOf(StringField("path", "path relative to the open folder, e.g. 'sub/a.flow' ('.flow' may be omitted)"))),
            call = { args -> readFlowSpec(argText(args, "path")) },
        ),
        Tool(
            name = "validate_flow",
            description = "Verify a saved flow and report every fault found: a module that is not " +
                "installed, an option set to a value it does not accept, ports that no longer match the " +
                "node's options, an edge to a port that isn't there, an input fed twice or not at all, a " +
                "loop in the wiring, a missing cin/cout. Returns 'no problems found' when it is sound.",
            schema = objectSchema(listOf(StringField("path", "path relative to the open folder, e.g. 'sub/a.flow' ('.flow' may be omitted)"))),
            call = { args -> validateFlowTool(argText(args, "path")) },
        ),
        Tool(
            name = "run_flow",
            description = "Run a saved flow with real input and return what it actually outputs — the " +
                "only way to check a flow does what it's supposed to, since validate_flow only checks " +
                "the wiring and never executes anything. Use this after save_flow, with representative " +
                "input, to verify the result before telling the user it's done. Takes one value per " +
                "input port under 'inputs' (port names come from list_flows/read_flow); a flow with " +
                "exactly one input port may pass 'value' instead. Any port left out runs as empty input.",
            schema = runFlowSchema(),
            call = { args -> runFlowTool(args) },
        ),
        Tool(
            name = "build_flow",
            description = "Build a .flow file from a high-level spec and return its contents — name the " +
                "nodes and how they wire together, and port lists, edge ids and node sizes are worked out " +
                "here. Set x/y on a node to place it exactly; leave them off and the graph is laid out " +
                "left-to-right. Include a 'cin' node per input and a 'cout' per output — their labels " +
                "become the flow's port names. Wiring faults are reported alongside the file rather than " +
                "hidden. Nothing is saved: give the user the contents to keep as <name>.flow, which they " +
                "open in Flow through File ▸ Open or by dropping it on the window.",
            schema = buildFlowSchema(),
            call = { args -> buildFlowFile(args) },
        ),
    )

    /**
     * The flow tools, plus one per installed processor.
     *
     * Rebuilt per request like the rest, so a processor installed while the server runs is callable
     * without a restart — and so a client that lists tools twice sees what is actually there.
     */
    private fun allTools(): List<Tool> = tools() + moduleTools()

    private fun moduleTools(): List<Tool> = Platform.installedModuleInfos().map { module ->
        Tool(
            name = ModuleTools.toolName(module.id),
            description = ModuleTools.description(module),
            schema = ModuleTools.schema(module),
            call = { args -> ModuleTools.call(module, args) },
        )
    }

    /**
     * Whatever was thrown, as something a caller can act on.
     *
     * A ToolFailure already is one. Everything else is narrowed as far as its type honestly allows
     * — the JCE's exceptions say a good deal by their type alone — and what cannot be narrowed is
     * reported as itself rather than dressed up as a diagnosis.
     */
    private fun failureOf(e: Throwable): ToolFailure = when {
        e is ToolFailure -> e
        e is IllegalArgumentException || e is IllegalStateException -> ToolFailure(
            code = ToolFailure.INVALID_OPTION,
            hint = "The call was refused before anything ran. The message says which argument.",
            message = e.message ?: "the call was refused",
            cause = e,
        )
        else -> ToolFailure.fromCrypto(e, "the tool")
    }

    /* ───────── authoring: list / read / write ───────── */

    private fun listNodes(): String {
        val out = StringBuilder()
        out.append("── boundary nodes (a flow needs these to run as a component) ──\n")
        IO_DEFS.forEach { def ->
            val role = if (def.type == "cin") "one component input" else "one component output"
            out.append("${def.type}  $role; its label is the port name  ")
                .append("(${def.ins.joinToString(",").ifEmpty { "-" }} → ${def.outs.joinToString(",").ifEmpty { "-" }})\n")
        }

        out.append("\n── modules ──\n")
        Platform.installedModuleInfos().forEach { m ->
            out.append("${m.id}  '${m.name}'  ")
                .append("${m.inputs.joinToString(",").ifEmpty { "-" }} → ${m.outputs.joinToString(",").ifEmpty { "-" }}\n")
            m.options.forEach { opt ->
                out.append("    ${opt.name}: ${opt.type.name.lowercase()}")
                if (opt.default.isNotEmpty()) out.append(" (default: ${opt.default})")
                if (opt.type == OptType.SELECT && opt.choices.isNotEmpty()) {
                    out.append(" [${opt.choices.joinToString(", ")}]")
                }
                out.append("\n")
            }
        }

        val comps = listComponents()
        if (comps.isNotEmpty()) {
            out.append("\n── installed components, usable as a sub-component node ──\n")
            comps.forEach { t ->
                out.append("comp:${t.ref}  '${t.comp.name}'  ")
                    .append("${t.comp.ins.joinToString(",").ifEmpty { "-" }} → ${t.comp.outs.joinToString(",").ifEmpty { "-" }}\n")
            }
        }
        return out.toString().trimEnd()
    }

    private fun listFlows(): String {
        // the server runs beside the app but has no folder of its own open
        val root = Platform.projectRoot() ?: return NO_PROJECT
        val files = Platform.listFlows()
        if (files.isEmpty()) return "$root — (no flow files yet)"
        return files.joinToString("\n") { file ->
            val flow = loadFlow(file)
            val summary = when {
                flow == null -> "unreadable"
                else -> flow.asComponent(file)
                    ?.let { "${it.ins.joinToString(",")} → ${it.outs.joinToString(",")}" }
                    ?: "${flow.nodes.size} node(s), no cin/cout yet"
            }
            "$file  ($summary)"
        }
    }

    // project-relative flow paths may arrive without their ".flow" suffix — put it back
    private fun normalizeFlowPath(path: String): String {
        val p = path.trim()
        require(p.isNotEmpty()) { "set 'path' to a flow file, e.g. 'sub/a.flow'" }
        require(!p.startsWith("/")) { "'path' is relative to the project folder, not absolute: $p" }
        return if (p.endsWith(".flow")) p else "$p.flow"
    }

    private fun loadForEdit(path: String): Pair<String, FlowFile> {
        val name = normalizeFlowPath(path)
        Platform.projectRoot() ?: error(NO_PROJECT)
        val raw = Platform.readFlow(name) ?: error("flow file not found: $name")
        val flow = runCatching { runnerJson.decodeFromString<FlowFile>(raw) }
            .getOrElse { e -> error("$name is not valid flow JSON: ${e.message}") }
        return name to flow
    }

    private fun validateFlowTool(path: String): String {
        val (name, flow) = loadForEdit(path)
        val problems = validateFlow(flow)
        val head = "$name — ${flow.nodes.size} nodes, ${flow.edges.size} edges"
        if (problems.isEmpty()) return "$head\nno problems found"
        return "$head\n${problems.size} problem(s):\n" + problems.joinToString("\n") { "- $it" }
    }

    private fun runFlowSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "path relative to the open folder, e.g. 'sub/a.flow' ('.flow' may be omitted)")
            }
            putJsonObject("inputs") {
                put("type", "object")
                put("description", "input port name -> value, e.g. {\"text\": \"hello\"} — see the flow's input ports from list_flows/read_flow")
            }
            putJsonObject("value") {
                put("type", "string")
                put("description", "shorthand for 'inputs' when the flow has exactly one input port")
            }
        }
        putJsonArray("required") { add("path") }
    }

    /**
     * Runs a saved flow with real input and reports its actual output — the one execution-capable
     * tool this server exposes (see the class doc). Reuses the same [loadFlow]/[runComponent]
     * primitives the CLI runs on, so a flow behaves identically whether run from the CLI, the app,
     * or here.
     */
    /**
     * The 'inputs'/'value' args run_flow, set_flow_input and start_flow all take, parsed the same
     * way in all three: an explicit 'inputs' map, or 'value' as shorthand when there's exactly one
     * input port to put it in.
     */
    private fun parseInputArgs(args: JsonObject, comp: CompDef): Map<String, String> {
        val inputs = LinkedHashMap<String, String>()
        (args["inputs"] as? JsonObject)?.forEach { (k, v) -> inputs[k] = (v as? JsonPrimitive)?.content ?: v.toString() }
        if (inputs.isEmpty() && comp.ins.size == 1) {
            val value = argText(args, "value")
            if (value.isNotEmpty()) inputs[comp.ins.first()] = value
        }
        return inputs
    }

    private fun runFlowTool(args: JsonObject): String {
        Platform.projectRoot() ?: return NO_PROJECT
        val path = normalizeFlowPath(argText(args, "path"))
        val flowFile = loadFlow(path) ?: return "flow file not found: $path"
        val comp = flowFile.asComponent(path) ?: return "'$path' is not a component (no cin/cout boundary nodes) — nothing to run"

        val inputs = parseInputArgs(args, comp).toMutableMap()
        comp.ins.forEach { inputs.putIfAbsent(it, "") }

        val byteInputs = inputs.mapValues { it.value.encodeToByteArray() }
        val target = RunnableComponent(path, comp, isInstalledComponent(path))
        val (result, errors) = runComponent(target, byteInputs)

        val out = StringBuilder()
        out.append("ran $path with ")
            .append(if (inputs.values.any { it.isNotEmpty() }) inputs.entries.joinToString(", ") { "${it.key}=${it.value}" } else "no input")
            .append('\n')
        comp.outs.forEach { port -> out.append("$port = ${result[port]?.decodeToString() ?: "(no output)"}\n") }
        if (errors.isNotEmpty()) {
            out.append("\nerrors:\n")
            errors.forEach { (nodeId, msg) -> out.append("- $nodeId: $msg\n") }
        }
        return out.toString().trimEnd()
    }

    /**
     * Builds a flow and writes it into the open folder.
     *
     * The same work build_flow does, and then the step the user would otherwise do by hand. Only
     * inside the open folder: writeFlow refuses a path that climbs out of it, which is what makes
     * handing this to an assistant reasonable.
     */
    private fun saveFlowTool(args: JsonObject): String {
        val root = Platform.projectRoot() ?: return NO_PROJECT
        val built = buildFlowResult(args)
        Platform.writeFlow(built.fileName, built.content)
        return "${built.head}\nSaved as ${built.fileName} in $root. Not opened — call open_flow for " +
            "that." + built.notes
    }

    /**
     * Asks the app to bring a saved flow into view.
     *
     * The server is a separate process from the app and can't reach into its open tabs directly,
     * so this leaves a request behind (Platform.requestOpenFlow) for Workspace.askAi to pick up
     * once this turn ends. Only worth leaving at all when something is actually running to read
     * it back — see APP_NOT_RUNNING.
     */
    private fun openFlowTool(path: String): String {
        if (!Platform.isAppRunning()) return APP_NOT_RUNNING
        val (name, _) = loadForEdit(path)
        Platform.requestOpenFlow(name)
        return "$name will open in Flow"
    }

    // Ports named in `inputs` that comp doesn't actually have, formatted as the error to return —
    // or null when every key is real. Shared by set_flow_input and start_flow, the two tools that
    // take a caller-supplied port map rather than run_flow's fill-the-rest-with-empty one.
    private fun unknownPortError(path: String, inputs: Map<String, String>, comp: CompDef): String? {
        val unknown = inputs.keys - comp.ins.toSet()
        if (unknown.isEmpty()) return null
        return "not an input port of $path: ${unknown.joinToString(", ")} (ports: ${comp.ins.joinToString(", ")})"
    }

    /**
     * Sets a flow's input ports on its open tab, same request-and-pick-up shape as open_flow.
     * Reuses run_flow's own inputs/value parsing so the two tools take identical arguments for the
     * identical thing (which ports a flow has, and what to put in them) — the only difference is
     * what happens to those values: run_flow feeds them straight to the headless engine, this
     * leaves them for the open tab to actually display.
     */
    private fun setFlowInputTool(args: JsonObject): String {
        if (!Platform.isAppRunning()) return APP_NOT_RUNNING
        Platform.projectRoot() ?: return NO_PROJECT
        val path = normalizeFlowPath(argText(args, "path"))
        val flowFile = loadFlow(path) ?: return "flow file not found: $path"
        val comp = flowFile.asComponent(path) ?: return "'$path' is not a component (no cin/cout boundary nodes) — no inputs to set"

        val inputs = parseInputArgs(args, comp)
        if (inputs.isEmpty()) return "nothing to set — pass 'inputs' or 'value'"
        unknownPortError(path, inputs, comp)?.let { return it }

        Platform.requestFlowInput(path, inputs)
        return "$path will show ${inputs.entries.joinToString(", ") { "${it.key}=${it.value}" }} once open"
    }

    /**
     * Starts a flow's run on screen, optionally setting its inputs in the same request — one file
     * written and read back together, rather than a separate set_flow_input call this would have
     * to land before start_flow's own request is picked up. set_flow_input on its own remains for
     * setting a value without running yet.
     */
    private fun startFlowTool(args: JsonObject): String {
        if (!Platform.isAppRunning()) return APP_NOT_RUNNING
        val path = normalizeFlowPath(argText(args, "path"))
        val (name, flow) = loadForEdit(path)
        val comp = flow.asComponent(name)
        val inputs = comp?.let { parseInputArgs(args, it) } ?: emptyMap()
        if (comp != null) unknownPortError(name, inputs, comp)?.let { return it }

        Platform.requestFlowRun(name, start = true, inputs = inputs)
        return if (inputs.isEmpty()) "$name will start running in Flow" else {
            "$name will start running in Flow with ${inputs.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        }
    }

    private fun stopFlowTool(path: String): String {
        if (!Platform.isAppRunning()) return APP_NOT_RUNNING
        val (name, _) = loadForEdit(path)
        Platform.requestFlowRun(name, start = false)
        return "$name will stop running in Flow"
    }

    /** What a build produced, so building and saving are the same work done once. */
    private class BuiltFlow(
        val fileName: String,
        val content: String,
        val head: String,
        val notes: String,
    )

    /** A flow rendered back into the build_flow spec, so read → edit → rebuild round-trips. */
    private fun readFlowSpec(path: String): String {
        val (name, flow) = loadForEdit(path)
        val spec = buildJsonObject {
            put("path", name)
            putJsonArray("nodes") {
                flow.nodes.forEach { n ->
                    addJsonObject {
                        put("id", n.id)
                        put("type", n.type)
                        put("label", n.label)
                        put("x", n.x)
                        put("y", n.y)
                        if (n.params.isNotEmpty()) {
                            putJsonObject("params") { n.params.forEach { (k, v) -> put(k, v) } }
                        }
                    }
                }
            }
            putJsonArray("edges") {
                flow.edges.forEach { e ->
                    addJsonObject {
                        put("from", "${e.from.node}.${e.from.port}")
                        put("to", "${e.to.node}.${e.to.port}")
                    }
                }
            }
            // surfaced on read so reviewing a flow is the same call as fetching it to edit
            putJsonArray("problems") { validateFlow(flow).forEach { add(it) } }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), spec)
    }

    /**
     * Builds the flow and hands back its file content. Nothing is written: the project folder is
     * the user's, and what lands in it is their call — they save this and bring it in through the
     * app (File ▸ Open, or dropping it on the window).
     */
    private fun buildFlowFile(args: JsonObject): String {
        val built = buildFlowResult(args)
        return "${built.head}${built.notes}\n\n${built.content}"
    }

    private fun buildFlowResult(args: JsonObject): BuiltFlow {
        val nodes = argArray(args, "nodes").mapIndexed { i, element ->
            val o = element as? JsonObject ?: error("nodes[$i] is not an object")
            val id = (o["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
            require(id.isNotEmpty()) { "nodes[$i] has no 'id'" }
            val type = (o["type"] as? JsonPrimitive)?.content?.trim().orEmpty()
            require(type.isNotEmpty()) { "node '$id' has no 'type'" }
            NodeSpec(
                id = id,
                type = type,
                label = (o["label"] as? JsonPrimitive)?.content,
                params = (o["params"] as? JsonObject).orEmpty()
                    .mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() },
                x = (o["x"] as? JsonPrimitive)?.content?.toFloatOrNull(),
                y = (o["y"] as? JsonPrimitive)?.content?.toFloatOrNull(),
            )
        }
        val edges = argArray(args, "edges").mapIndexed { i, element ->
            val o = element as? JsonObject ?: error("edges[$i] is not an object")
            val from = (o["from"] as? JsonPrimitive)?.content.orEmpty()
            val to = (o["to"] as? JsonPrimitive)?.content.orEmpty()
            require(from.isNotBlank() && to.isNotBlank()) { "edges[$i] needs both 'from' and 'to'" }
            EdgeSpec(from, to)
        }

        val flow = buildFlow(nodes, edges)
        val fileName = argText(args, "name").trim().ifEmpty { "flow" }
            .removeSuffix(".flow").replace('/', '-') + ".flow"
        val content = prettyJson.encodeToString(FlowFile.serializer(), flow)

        val summary = flow.asComponent(fileName)
            ?.let { "${it.ins.joinToString(",")} → ${it.outs.joinToString(",")}" }
            ?: "no boundary ports yet"
        val head = "$fileName — ${flow.nodes.size} nodes, ${flow.edges.size} edges; $summary"
        // reported alongside the content rather than withheld: the caller still gets a file, and
        // can decide whether the faults matter
        val problems = flowProblems(flow)
        val notes = if (problems.isEmpty()) "" else "\n" + problems.joinToString("\n") { "problem: $it" }
        return BuiltFlow(fileName, content, head, notes)
    }

    /**
     * A JSON array argument. Some clients send a nested array/object as a JSON *string*, so a
     * string that parses as an array is accepted too rather than failing on a formatting detail.
     */
    private fun argArray(args: JsonObject, key: String): List<JsonElement> {
        return when (val value = args[key]) {
            null -> emptyList()
            is JsonArray -> value
            is JsonPrimitive -> {
                val text = value.content.trim()
                if (text.isEmpty()) return emptyList()
                runCatching { json.parseToJsonElement(text) as JsonArray }
                    .getOrElse { error("'$key' must be a JSON array") }
            }
            else -> error("'$key' must be a JSON array")
        }
    }

    // arguments arrive as JSON values; anything non-string is written out plainly
    private fun argText(args: JsonObject, key: String): String {
        val value = args[key] ?: return ""
        return (value as? JsonPrimitive)?.content ?: value.toString()
    }

    /* ───────── schema + result helpers ───────── */

    private class StringField(val name: String, val description: String)

    /**
     * build_flow's schema, written out rather than built from [StringField] because it is the one
     * tool taking arrays of objects — the shape that lets a client describe a whole flow in a
     * single call instead of assembling raw file JSON.
     */
    private fun buildFlowSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "what to call the file, e.g. 'decrypt-user-info' ('.flow' is added if absent)")
            }
            putJsonObject("nodes") {
                put("type", "array")
                put(
                    "description",
                    "the nodes to place. Coordinates are optional — leave them off and the graph is " +
                        "laid out automatically; pass the x/y from read_flow to keep an existing arrangement.",
                )
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "unique name for this node, used to wire it in 'edges' (e.g. 'hash1')")
                        }
                        putJsonObject("type") {
                            put("type", "string")
                            put("description", "'cin', 'cout', a module id like 'flow.hash', or 'comp:<flow path>' — see list_nodes")
                        }
                        putJsonObject("label") {
                            put("type", "string")
                            put("description", "display name; on a cin/cout node this is the component's port name, so name it meaningfully")
                        }
                        putJsonObject("params") {
                            put("type", "object")
                            put("description", "module option values, e.g. {\"algo\":\"SHA-256\"}; omitted options take their default")
                        }
                        putJsonObject("x") {
                            put("type", "number")
                            put(
                                "description",
                                "canvas x in dp, snapped to a 20 grid. Set x and y together to place the " +
                                    "node yourself — a readable graph runs left to right with about 280 " +
                                    "between columns and 150 between rows. Leave both off to have this " +
                                    "node placed automatically.",
                            )
                        }
                        putJsonObject("y") { put("type", "number"); put("description", "canvas y in dp, snapped to a 20 grid; see 'x'") }
                    }
                    putJsonArray("required") { add("id"); add("type") }
                }
            }
            putJsonObject("edges") {
                put("type", "array")
                put("description", "how the nodes are wired, output end to input end")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("from") {
                            put("type", "string")
                            put("description", "source as 'node.port', or just 'node' when it has a single output")
                        }
                        putJsonObject("to") {
                            put("type", "string")
                            put("description", "target as 'node.port', or just 'node' when it has a single input")
                        }
                    }
                    putJsonArray("required") { add("from"); add("to") }
                }
            }
        }
        putJsonArray("required") { add("nodes") }
    }

    // schema for the tools whose arguments are just required strings (build_flow has its own)
    private fun objectSchema(fields: List<StringField>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { field ->
                putJsonObject(field.name) {
                    put("type", "string")
                    put("description", field.description)
                }
            }
        }
        putJsonArray("required") { fields.forEach { add(it.name) } }
    }
}
