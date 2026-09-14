package flow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import flow.model.AiMessage
import flow.model.CATEGORIES
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.ModuleInfo
import flow.model.ViewInfo
import flow.model.OptDef
import flow.model.Node
import flow.model.Session
import flow.model.Settings
import flow.model.AI_CLAUDE
import flow.model.AI_ERR_OLLAMA_DOWN
import flow.model.AI_ERR_EMPTY
import flow.model.AI_ERR_NO_KEY
import flow.model.AI_ERR_NO_MODEL
import flow.model.AI_ERR_STEPS
import flow.model.AI_MODELS
import flow.model.AI_OLLAMA
import flow.model.AI_PROVIDERS
import flow.model.AiSetup
import flow.model.AI_GEMINI
import flow.model.AI_KEY_GEMINI
import flow.model.AI_KEY_OPENAI
import flow.model.AI_OPENAI
import flow.model.AI_KEY_CLAUDE
import flow.model.AI_VIA_API
import flow.model.AI_VIA_CLI
import flow.model.DEFAULT_CLAUDE_URL
import flow.model.DEFAULT_GEMINI_URL
import flow.model.defaultTransport
import flow.model.DEFAULT_OLLAMA_URL
import flow.model.DEFAULT_OPENAI_URL
import flow.model.apiKeyEnvVar
import flow.model.apiKeySecret
import flow.model.DEFAULT_REGISTRY_URL
import flow.model.RegistryEntry
import flow.model.RegistryIndex
import flow.model.RegistryState
import flow.model.compareVersions
import flow.model.asComponent
import flow.platform.Platform
import flow.ui.theme.Theme
import flow.util.flowLabel
import flow.util.isValidSegment
import flow.util.pathAncestors
import flow.util.pathJoin
import flow.util.pathName
import flow.util.pathParent
import flow.util.pathUnder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// pending install-overwrite confirmation (label = conflicting id, commit = perform overwrite)
class InstallPending(val label: String, val commit: () -> Unit)

// An open data-editor window: edits the sample data of a component boundary (cin/cout) node.
class DataTab(val doc: EditorState, val nodeId: String) {
    val node: Node? get() = doc.nodeById(nodeId)
    val title: String get() = node?.label ?: "?"

    /**
     * What an output has made of being clicked on: which branch is open, which row is picked.
     *
     * It lives here rather than in the node because it is not part of the flow. Putting it there
     * made every click on a tree a document edit — marking the file changed, redrawing the canvas,
     * and re-serialising the whole flow to see whether it still matched what was saved — which is
     * a great deal of work for opening a row. It lasts as long as the tab is open, which is as long
     * as it means anything.
     */
    var outputState by mutableStateOf<Map<String, String>>(emptyMap())
}

// Workspace: global UI state + open documents (tabs) + project file list + component registry
/**
 * The part of the window the keyboard belongs to while a shortcut is pressed.
 *
 * TEXT is every field everywhere — the AI composer, a node's options, a rename — because what they
 * all have in common is that a key typed in one is a character, not a command.
 */
enum class FocusRegion { CANVAS, PROJECT, TEXT }

class Workspace(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // global UI (shared across documents)
    var lang by mutableStateOf("en") // default language: English
    var theme by mutableStateOf(Theme.SYSTEM) // system | dark | light
    var registryUrl by mutableStateOf(DEFAULT_REGISTRY_URL)
    // Which assistant the AI panel talks to, and a model per provider — the two name nothing in
    // common, so switching provider and switching back finds the model it was left on rather than
    // an id the other one has never heard of.
    var aiProvider by mutableStateOf(AI_CLAUDE)
    // blank = whatever `claude` itself defaults to; see AI_MODELS for the choices offered
    var aiModel by mutableStateOf("")
    var ollamaUrl by mutableStateOf(DEFAULT_OLLAMA_URL)
    // blank = the first model the server reports, which on a machine with one is the one
    var ollamaModel by mutableStateOf("")
    var openaiUrl by mutableStateOf(DEFAULT_OPENAI_URL)
    var openaiModel by mutableStateOf("")
    var geminiUrl by mutableStateOf(DEFAULT_GEMINI_URL)
    var geminiModel by mutableStateOf("")
    var claudeUrl by mutableStateOf(DEFAULT_CLAUDE_URL)

    /**
     * How each provider is reached — its command, or its API.
     *
     * Per provider rather than one setting for the panel: a machine often has one of the two set up
     * and not the other, and which it is differs by provider. Unset means that provider's own
     * default (defaultTransport).
     */
    var aiTransports by mutableStateOf<Map<String, String>>(emptyMap())

    val aiTransport: String get() = transportOf(aiProvider)

    fun transportOf(provider: String): String = aiTransports[provider] ?: defaultTransport(provider)

    fun setTransport(provider: String, transport: String) {
        aiTransports = aiTransports + (provider to transport)
    }

    // Kept apart from the settings file (Platform.loadSecret) and loaded once at startup. Blank
    // here does not mean "no key": the environment is consulted too — see aiApiKey.
    var openaiKey by mutableStateOf(Platform.loadSecret(AI_KEY_OPENAI).orEmpty())
    var geminiKey by mutableStateOf(Platform.loadSecret(AI_KEY_GEMINI).orEmpty())
    var claudeKey by mutableStateOf(Platform.loadSecret(AI_KEY_CLAUDE).orEmpty())
    /**
     * What each installed module's own settings are set to, by module id.
     *
     * The module declares which settings it has (ModuleInfo.settings); this is what they are.
     * Writing it pushes the whole lot to the platform, which is what a run reads — the alternative,
     * reading a file per node, would charge every run for a setting almost nothing uses.
     */
    var extensionSettings by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
        private set

    fun setExtensionSetting(extensionId: String, name: String, value: String, secret: Boolean = false) {
        val forExtension = (extensionSettings[extensionId] ?: emptyMap()) + (name to value)
        extensionSettings = extensionSettings + (extensionId to forExtension)
        // a key does not go in the settings file — that one is rewritten on every preference change
        // and is meant to be readable — so it is written where the panel's keys are written
        if (secret) Platform.saveSecret(extensionSecretName(extensionId, name), value)
        pushExtensionSettings()
    }

    /**
     * Hands the platform what a run should see, secrets included.
     *
     * They are kept apart on disk and put back together here: an module asked to do its work
     * needs the key as much as the rest, and the place they are kept apart is a storage decision,
     * not something a module should have to know about.
     */
    private fun pushExtensionSettings() {
        val full = installedModules.associate { module ->
            module.id to settingValues(module.id, extensionSettingSpecs[module.id] ?: module.settings)
        }
        Platform.setExtensionSettings(full)
    }

    /** Where an module's secret setting is kept, namespaced so two modules cannot collide. */
    private fun extensionSecretName(extensionId: String, name: String) = "ext:$extensionId:$name"

    /**
     * The settings an module offers right now, and what they are set to.
     *
     * Asked of the module rather than read off what it declared, because it may answer with a
     * list it had to fetch — which is what makes a model a menu rather than a box to type a name
     * into. Cached per module and re-asked whenever a value changes, off the UI thread; secret
     * values are filled in from where they are kept, so the module is asked with the key it
     * would actually use.
     */
    var extensionSettingSpecs by mutableStateOf<Map<String, List<OptDef>>>(emptyMap())
        private set

    fun refreshExtensionSettings(extensionId: String) {
        val known = installedModules.find { it.id == extensionId }?.settings.orEmpty()
        val values = settingValues(extensionId, known)
        scope.launch {
            val offered = Platform.extensionSettingsFor(extensionId, values)
            if (offered.isNotEmpty()) {
                extensionSettingSpecs = extensionSettingSpecs + (extensionId to offered)
                // a setting the module only offers once it knows the others — a model list, say
                // — is one a run has to be given too
                pushExtensionSettings()
            }
        }
    }

    /** What one module's settings are set to, secrets included, ready to hand to it. */
    fun settingValues(extensionId: String, specs: List<OptDef>): Map<String, String> {
        val stored = extensionSettings[extensionId] ?: emptyMap()
        return specs.associate { spec ->
            val value = when {
                spec.secret -> Platform.loadSecret(extensionSecretName(extensionId, spec.name)).orEmpty()
                else -> stored[spec.name] ?: spec.default
            }
            spec.name to value
        }
    }

    var showLeft by mutableStateOf(true)
    var leftTab by mutableStateOf("project") // project | modules | ai
    // palette sections left open, by key
    var expandedSections by mutableStateOf(setOf<String>())

    fun isSectionOpen(key: String) = key in expandedSections
    fun toggleSection(key: String) {
        expandedSections = if (key in expandedSections) expandedSections - key else expandedSections + key
    }

    // VS Code-style activity bar click: open that panel, or collapse when the
    // same icon is clicked while its panel is already open.
    fun clickActivity(tab: String) {
        if (showLeft && leftTab == tab) {
            showLeft = false
        } else {
            leftTab = tab
            showLeft = true
        }
    }
    var showProps by mutableStateOf(true)
    var showMinimap by mutableStateOf(true)
    // resizable panel widths (dp), persisted in the session
    var leftWidth by mutableStateOf(240f)
    var propsWidth by mutableStateOf(268f)
    // run animation duration per step, in seconds (larger = slower), persisted in the config
    var animSeconds by mutableStateOf(0.25f)
    // main window bounds (dp), persisted in the session; null = no saved bounds yet (Main.kt falls
    // back to its own default centered/clamped-to-screen size)
    var windowX by mutableStateOf<Float?>(null)
    var windowY by mutableStateOf<Float?>(null)
    var windowWidth by mutableStateOf<Float?>(null)
    var windowHeight by mutableStateOf<Float?>(null)
    var windowMaximized by mutableStateOf(false)
    // where the window is right now, maximized or not — not persisted (the fields above are what
    // gets restored); a viewer opens centred on this
    var liveWindowX by mutableStateOf(0f)
    var liveWindowY by mutableStateOf(0f)
    var liveWindowWidth by mutableStateOf(0f)
    var liveWindowHeight by mutableStateOf(0f)
    var menu by mutableStateOf<String?>(null)
    var dragModule by mutableStateOf<DragModule?>(null)
    var saveTime by mutableStateOf<String?>(null)

    // close-confirm target (tab index being closed). null = no dialog.
    var closeConfirm by mutableStateOf<Int?>(null)

    // whether the settings screen is shown (logo menu > Settings), and which category it opens on
    var showSettings by mutableStateOf(false)
    var settingsCategory by mutableStateOf("appearance")

    fun openSettings(category: String = "appearance") {
        settingsCategory = category
        showSettings = true
    }

    // internal clipboard for module copy/paste between documents
    var clipboard by mutableStateOf<FlowFile?>(null)

    // message dialog (null = none)
    var saveError by mutableStateOf<String?>(null)
    // true when the message is a non-blocking warning
    var saveWarn by mutableStateOf(false)
    var errorTitleKey by mutableStateOf("saveErrorTitle")

    fun showError(message: String, titleKey: String = "saveErrorTitle") {
        saveWarn = false
        errorTitleKey = titleKey
        saveError = message
    }

    /**
     * Where the keys are going: the part of the window the user last put themselves in.
     *
     * A shortcut with no modifier belongs to one region and not to the others. Space runs the flow
     * — on the canvas. In a text field it is a space, and the flow running because a question was
     * being typed in the AI panel is the bug this exists to stop.
     *
     * Written as "which region", not "is a field focused", on purpose: a text field nobody
     * remembered to mark here then costs a shortcut that does nothing, rather than a flow that
     * runs while you type.
     */
    var focus by mutableStateOf(FocusRegion.CANVAS)

    /** True while the project tree is the region — the scope its rename/delete shortcuts act on. */
    val projectFocused: Boolean get() = focus == FocusRegion.PROJECT

    // action id -> shortcut, editable in Settings > Keymap
    var keymap by mutableStateOf(DEFAULT_KEYMAP)

    fun shortcut(action: String): Shortcut? = keymap[action]
    fun shortcutLabel(action: String): String = keymap[action]?.label(Platform.metaKeyLabel()) ?: ""
    fun setShortcut(action: String, shortcut: Shortcut) {
        // a shortcut belongs to one action: drop it from whichever action held it
        keymap = keymap.filterValues { it != shortcut } + (action to shortcut)
    }
    fun resetKeymap() { keymap = DEFAULT_KEYMAP }

    // a modal is up: shortcuts belong to it, not to the tool windows
    val dialogOpen: Boolean
        get() = renameTarget != null || newFolderParent != null || fileDeleteConfirm != null ||
            closeConfirm != null || installConfirm != null || saveError != null
    fun actionFor(ev: androidx.compose.ui.input.key.KeyEvent): String? {
        val pressed = Shortcut.of(ev) ?: return null
        return keymap.entries.find { it.value == pressed }?.key
    }

    // project tree multi-selection, by path
    var projectSelected by mutableStateOf(setOf<String>())
    // item whose context menu is open, and where it was opened (window px)
    var projectMenuFor by mutableStateOf<String?>(null)
    var projectMenuPos by mutableStateOf(Offset.Zero)

    fun openProjectMenu(path: String, pos: Offset) {
        projectMenuPos = pos
        projectMenuFor = path
    }

    fun closeProjectMenu() { projectMenuFor = null }
    // items pending delete-confirmation
    var fileDeleteConfirm by mutableStateOf<Set<String>?>(null)
    // item pending rename
    var renameTarget by mutableStateOf<String?>(null)
    // parent of a pending new folder
    var newFolderParent by mutableStateOf<String?>(null)
    // install-overwrite confirmation (null = none)
    var installConfirm by mutableStateOf<InstallPending?>(null)

    // open tabs
    val docs = mutableStateListOf<EditorState>()
    var activeIndex by mutableStateOf(0)
    val active: EditorState? get() = docs.getOrNull(activeIndex)

    /**
     * Data editors on show, one window each (in/out sample data).
     *
     * A window rather than a tab: the data behind a boundary node is what you read while working
     * on the canvas, so covering the canvas with it was the wrong way round — now it sits beside
     * the flow it belongs to, and several can be open at once.
     */
    val dataWindows = mutableStateListOf<DataTab>()

    fun openDataEditor(doc: EditorState, nodeId: String) {
        if (dataWindows.none { it.doc === doc && it.nodeId == nodeId }) dataWindows.add(DataTab(doc, nodeId))
    }

    /** Whether a canvas is on screen to receive a dropped module. */
    val canvasOpen: Boolean get() = active != null

    fun closeDataWindow(tab: DataTab) {
        dataWindows.remove(tab)
    }

    // project tree; paths are relative to the flows root
    var files by mutableStateOf<List<String>>(emptyList())
    var folders by mutableStateOf<List<String>>(emptyList())
    // folders drawn open; "" is the root row
    var expandedDirs by mutableStateOf(setOf(""))
    var components by mutableStateOf<List<CompDef>>(emptyList())
    var installedModules by mutableStateOf<List<ModuleInfo>>(emptyList())
    var installedViews by mutableStateOf<List<ViewInfo>>(emptyList())

    /**
     * Bumped whenever the install store has been read again.
     *
     * What is installed is read once and kept, so anything showing a list of modules needs to
     * be told when that list has moved.
     */
    var extensionsRevision by mutableStateOf(0)
        private set
    // the open folder, or null. Kept as state so the tree and the menus follow it.
    var projectRoot by mutableStateOf<String?>(null)
    val rootLabel: String get() = Platform.projectName() ?: ""
    val dirLabel: String get() = projectRoot ?: ""
    val hasProject: Boolean get() = projectRoot != null

    /** Opens [path] as the project folder, or closes the current one when null. */
    fun openProject(path: String?) {
        Platform.openProject(path)
        projectRoot = Platform.projectRoot()
        projectSelected = emptySet()
        expandedDirs = setOf("")
        refreshFiles()
    }

    fun t(key: String) = flow.i18n.tr(lang, key)

    init {
        // no folder is open at startup, the way an editor starts with none; the session restores
        // preferences and window state but not a project
        refreshFiles()
        loadSession()
        // open_flow/set_flow_input/start_flow/stop_flow are meant for an external MCP client
        // (Claude Desktop, claude.ai) as much as Flow's own AI panel — a client like that never
        // drives an askAi turn here to piggyback the pickup on, so this polls independently of one.
        // askAi still applies a request the instant its own turn ends, same as always; this is
        // only what covers the case nothing else does.
        scope.launch {
            while (true) {
                delay(750)
                applyPendingFlowRequests()
            }
        }
    }

    /* ───────── project files + installed ───────── */

    fun refreshFiles() {
        files = Platform.listProjectFiles()
        folders = Platform.listFlowDirs()
        // sorted here rather than in each place that lists them: the install store hands them back
        // in whatever order the filesystem walked, which shuffles as modules come and go
        installedModules = Platform.installedModuleInfos().sortedBy { it.name.lowercase() }
        installedViews = Platform.installedViewInfos().sortedBy { it.name.lowercase() }
        extensionsRevision++
        // what a run sees is built from what is installed, so it is rebuilt whenever that changes —
        // including at startup, which is the first time this list exists at all
        pushExtensionSettings()
        // Components on offer as a building block (the palette, comp:<ref> autocomplete-ish spots)
        // are the installed ones only — a flow that merely lives in the open folder isn't one until
        // explicitly installed (Settings ▸ Modules ▸ Flows), the same as a module or view
        // isn't on the palette just because its jar exists somewhere. It can still be *referenced*
        // as comp:<path> directly (the engine resolves either store), just not auto-discovered.
        components = Platform.listInstalledComponents().mapNotNull { name ->
            val raw = Platform.readInstalledComponent(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)?.copy(installed = true)
        }.sortedBy { it.name.lowercase() }
    }

    /** A project flow's ports, read fresh — independent of [components], which only lists the
     * ones installed. Settings ▸ Modules ▸ Flows shows this for every project flow so it can
     * offer Install for one that qualifies, whether or not it already is. Null when the file is
     * missing, unparseable, or has no cin/cout boundary — nothing an install would do anything with.
     */
    fun flowPorts(path: String): CompDef? {
        val raw = Platform.readFlow(path) ?: return null
        val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return null
        return flow.asComponent(path)
    }

    // the id installComponent/uninstallComponent store this project flow under — a plain folder
    // name (installComponent refuses one with a slash), so a path under a subfolder is flattened
    // rather than rejected
    private fun componentId(path: String): String = path.removeSuffix(".flow").replace('/', '-')

    fun isInstalledAsComponent(path: String): Boolean =
        Platform.listInstalledComponents().contains("${componentId(path)}.json")

    /** Registers a project flow as a comp: building block — see [components]. */
    fun installAsComponent(path: String) {
        val raw = Platform.readFlow(path) ?: return
        Platform.installComponent(componentId(path), raw, overwrite = true)
        refreshFiles()
    }

    fun uninstallComponentFile(path: String) {
        Platform.uninstallComponent(componentId(path))
        refreshFiles()
    }

    fun moduleInfo(type: String): ModuleInfo? = installedModules.find { it.id == type }

    fun viewInfo(id: String): ViewInfo? = installedViews.find { it.id == id }

    /* ───────── the assistant ───────── */

    // internal rather than private so a test can seed a conversation: the panel's one real bug
    // only appeared once there was one in it, and an empty transcript proves nothing
    var aiMessages by mutableStateOf<List<AiMessage>>(emptyList())
        internal set
    var aiStreaming by mutableStateOf(false)
        private set

    /** What carries the conversation from one turn to the next; null starts a new one. */
    private var aiSession: String? = null

    /** Visible to a test, which is the only way to see that a session was dropped rather than reused. */
    internal val aiSessionForTest: String? get() = aiSession

    /**
     * Which assistant that session belongs to — provider and transport both.
     *
     * They mint session ids that mean nothing to each other: Claude Code's is a conversation on
     * disk it can resume, the HTTP agents' is a key into a transcript held in this process. Handing
     * one to the other is not a degraded conversation, it is an error — `claude --resume
     * claude-1a2b3c` fails outright, and that is a turn that answers nothing at all.
     *
     * Transport counts as much as provider, because the same provider mints both kinds: reaching
     * Claude by API and then by its CLI hands the CLI an id from the wrong world.
     */
    private var aiSessionOwner: String? = null

    /**
     * The model in use, and where to set it: whichever provider is in force keeps its own.
     *
     * Writing it goes to that provider's own field, so the composer's picker and the Settings
     * screen are two views of one value rather than two settings that can disagree. (Saving is
     * App.kt's snapshotFlow on settingsJson(); nothing here has to ask for it.)
     */
    var aiModelChoice: String
        get() = when (aiProvider) {
            AI_OLLAMA -> ollamaModel
            AI_OPENAI -> openaiModel
            AI_GEMINI -> geminiModel
            else -> aiModel
        }
        set(value) {
            when (aiProvider) {
                AI_OLLAMA -> ollamaModel = value
                AI_OPENAI -> openaiModel = value
                AI_GEMINI -> geminiModel = value
                else -> aiModel = value
            }
        }

    /**
     * What an assistant is called, for the name over each answer.
     *
     * One place decides it, rather than a string in the table: there was one of those reading
     * "Claude", and it went on saying so after the panel grew three other providers to be.
     */
    val aiProviderName: String get() = providerName(aiProvider)

    fun providerName(provider: String): String =
        AI_PROVIDERS.find { it.first == provider }?.second ?: provider

    /** Where the provider in force answers. Empty for Claude, which is a command, not an address. */
    val aiUrl: String
        get() = when (aiProvider) {
            AI_OLLAMA -> ollamaUrl
            AI_OPENAI -> openaiUrl
            AI_GEMINI -> geminiUrl
            AI_CLAUDE -> claudeUrl
            else -> ""
        }

    /**
     * The key for the provider in force.
     *
     * What was typed into Settings wins; otherwise the provider's own environment variable, so a
     * machine that already exports OPENAI_API_KEY for other tools does not have to have it entered
     * again. Blank when the provider needs none.
     */
    val aiApiKey: String get() = apiKeyFor(aiProvider)

    fun apiKeyFor(provider: String): String {
        val typed = when (provider) {
            AI_OPENAI -> openaiKey
            AI_GEMINI -> geminiKey
            AI_CLAUDE -> claudeKey
            else -> return ""
        }
        return typed.ifBlank { apiKeyEnvVar(provider)?.let { Platform.env(it) }.orEmpty() }
    }

    /**
     * What the model picker offers: a fixed list for Claude, whatever the server says otherwise.
     *
     * The server half is a network call, so it is a cached list refreshed by [refreshAiModels]
     * rather than something read on every recomposition. An empty one is not an error to report —
     * a machine that hasn't started Ollama, or a key not entered yet, is the ordinary case, and the
     * picker says so.
     */
    var serverModels by mutableStateOf<List<String>>(emptyList())
        private set

    val aiModelOptions: List<Pair<String, String>>
        get() = if (aiProvider == AI_CLAUDE && aiTransport == AI_VIA_CLI) {
            // the CLI takes an id rather than offering a list, so this is the one place a set of
            // names is written down instead of asked for
            AI_MODELS
        } else {
            // whatever is configured stays in the list even if the server is unreachable, so the
            // picker still shows what a turn would actually use
            val names = (serverModels + aiModelChoice).filter { it.isNotBlank() }
            names.distinct().map { it to it }
        }

    /**
     * Re-reads the model list. Cheap to call: it is one request, and the panel and the Settings
     * screen both ask whenever the provider, address or key they are showing changes.
     */
    fun refreshAiModels() {
        // A CLI is asked for nothing: it has its own idea of which models it can run, and the one
        // it is told to use is passed through to it.
        if (aiTransport == AI_VIA_CLI) {
            serverModels = emptyList()
            return
        }
        val setup = aiSetup()
        scope.launch { serverModels = Platform.aiModels(setup) }
    }

    /**
     * Sets a provider's API key, and by default writes it where keys live.
     *
     * [save] is false while a Settings field is being typed into: the model list has to follow what
     * is in the box to be about the right server, but a half-typed key is not something to put on
     * disk. Apply/OK calls this again with save on.
     */
    fun setApiKey(provider: String, value: String, save: Boolean = true) {
        when (provider) {
            AI_OPENAI -> openaiKey = value
            AI_GEMINI -> geminiKey = value
            AI_CLAUDE -> claudeKey = value
            else -> return
        }
        if (save) apiKeySecret(provider)?.let { Platform.saveSecret(it, value) }
    }

    /** A setup for one turn, as the provider in force now describes it. */
    private fun aiSetup() = AiSetup(
        provider = aiProvider,
        transport = aiTransport,
        model = aiModelChoice,
        url = aiUrl,
        apiKey = aiApiKey,
    )

    /**
     * Asks one question.
     *
     * The answer is appended to the last message as it arrives, so a run that takes a minute shows
     * its working. Only one turn at a time — the CLI is a process per turn and two at once would be
     * two conversations.
     */
    /** Whether a question can be asked at all: without a folder there is nowhere to work. */
    val aiReady: Boolean get() = hasProject

    fun askAi(question: String) {
        if (aiStreaming || !aiReady) return
        val owner = "$aiProvider/$aiTransport"
        if (aiSessionOwner != null && aiSessionOwner != owner) {
            Platform.forgetAi(aiSession)
            aiSession = null
        }
        aiSessionOwner = owner
        val answering = aiProvider
        aiMessages = aiMessages + AiMessage(fromUser = true, text = question) +
            AiMessage(fromUser = false, text = "", provider = answering)
        aiStreaming = true
        scope.launch {
            val reply = runCatching {
                withContext(Dispatchers.Default) {
                    Platform.askAi(question, aiSession, aiSetup()) { chunk ->
                        scope.launch { appendToLastAnswer(chunk) }
                    }
                }
            }.getOrElse { flow.model.AiReply(error = it.message ?: "the assistant could not be reached") }

            reply.sessionId?.let { aiSession = it }
            // both, when there are both: a turn that failed partway still said something, and
            // replacing it with the failure throws away the half that worked
            val failure = reply.error?.let { t("aiFailed") + "\n" + aiErrorText(it) }
            val text = listOfNotNull(reply.text.takeIf { it.isNotBlank() }, failure).joinToString("\n\n")
            aiMessages = aiMessages.dropLast(1) + AiMessage(fromUser = false, text = text, provider = answering)
            aiStreaming = false

            // save_flow only writes — it never opens anything, however new the file — so the tree
            // is re-read rather than waited on, and open_flow/set_flow_input/start_flow/stop_flow
            // are what actually reach the screen (see applyPendingFlowRequests). Also polled on a
            // timer (see init) for a client that never drives a turn here; done immediately too so
            // Flow's own AI panel doesn't wait out that poll interval for its own requests.
            refreshFiles()
            applyPendingFlowRequests()
        }
    }

    private fun applyPendingFlowRequests() {
        Platform.takePendingOpenFlow()?.let { openFile(it, reportError = false) }
        // each names its own flow and opens it if it isn't already, same as open_flow above — a
        // value or a run on a tab that doesn't exist yet has nothing to land on.
        Platform.takePendingFlowInput()?.let { (path, inputs) ->
            openFile(path, reportError = false)
            docs.find { it.fileName == path }?.setCinInputs(inputs)
        }
        Platform.takePendingFlowRun()?.let { req ->
            openFile(req.path, reportError = false)
            docs.find { it.fileName == req.path }?.let { doc ->
                // inputs land before the run they're for even starts — one request, so nothing
                // (another poll tick, a second call) can land between setting a value and running
                // with it.
                if (req.inputs.isNotEmpty()) doc.setCinInputs(req.inputs)
                if (req.start) doc.startRun() else doc.stopRun()
            }
        }
    }

    /**
     * A failure the user can act on.
     *
     * The providers raise the few they can predict as ids (AI_ERR_*), because they run where the
     * string table isn't; anything else came from the tool and is already a sentence.
     */
    private fun aiErrorText(error: String): String = when {
        error.startsWith(AI_ERR_OLLAMA_DOWN) ->
            t("aiOllamaMissingBody") + error.removePrefix(AI_ERR_OLLAMA_DOWN)
        error == AI_ERR_NO_MODEL -> t("aiNoModelBody")
        error == AI_ERR_NO_KEY -> t("aiNoKeyBody")
        error == AI_ERR_STEPS -> t("aiTooManySteps")
        error == AI_ERR_EMPTY -> t("aiSaidNothing")
        else -> error
    }

    /** Ends the run. What it had said by then stays. */
    fun stopAi() {
        if (!aiStreaming) return
        Platform.stopAi()
    }

    private fun appendToLastAnswer(chunk: String) {
        val last = aiMessages.lastOrNull() ?: return
        if (last.fromUser) return
        aiMessages = aiMessages.dropLast(1) + last.copy(text = last.text + chunk)
    }

    /** Starts again: a new conversation, with nothing carried over. */
    fun clearAi() {
        if (aiStreaming) return
        aiMessages = emptyList()
        // Claude Code keeps its own transcripts and a session id simply stops being used; Ollama's
        // lives in this process, so dropping it is something that has to be said.
        Platform.forgetAi(aiSession)
        aiSession = null
        aiSessionOwner = null
    }

    /**
     * Views that have been opened this session.
     *
     * There is no telling from here whether a window is still up — it belongs to another process
     * and the user may have closed it. Asking one that is gone does nothing, which is cheaper than
     * keeping a tally that could be wrong anyway.
     */
    var openedViews by mutableStateOf<List<String>>(emptyList())
        private set

    /** Brings a view's window forward, which is how a keyboard reaches one. */
    fun focusView(id: String) {
        scope.launch { runCatching { withContext(Dispatchers.Default) { Platform.focusView(id) } } }
    }


    /**
     * Opens a view on [data], in the view's own window.
     *
     * The theme goes with it so the window can open in the same colours as the app that opened it,
     * and this window's bounds so it can open centred on it (see ViewWindow in the module API).
     * Nothing comes back: a window belongs to the process that opened it, and lives as long as it
     * likes.
     */
    fun openView(id: String, data: ByteArray, params: Map<String, String>) {
        val where = mapOf(
            "theme" to theme,
            "hostX" to liveWindowX.toString(),
            "hostY" to liveWindowY.toString(),
            "hostWidth" to liveWindowWidth.toString(),
            "hostHeight" to liveWindowHeight.toString(),
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) { Platform.openView(id, data, params + where) }
            }.onFailure { showError(it.message ?: t("viewFailed")) }
        }
    }

    // options a module shows for the values a node currently holds
    fun moduleOptions(type: String, values: Map<String, String>): List<OptDef> =
        Platform.moduleOptionsFor(type, values) ?: moduleInfo(type)?.options ?: emptyList()

    /* ───────── install ───────── */

    fun installJarFlow(path: String) {
        val r = Platform.installJar(path, overwrite = false)
        if (r.conflicts.isNotEmpty()) {
            installConfirm = InstallPending(r.conflicts.joinToString(", ")) {
                Platform.installJar(path, overwrite = true); refreshFiles()
            }
        } else refreshFiles()
    }

    /* ───────── module registry ───────── */

    var registry by mutableStateOf<List<RegistryEntry>>(emptyList())
    var registryLoading by mutableStateOf(false)
    var registryError by mutableStateOf<String?>(null)
    // ids currently downloading, so each row can show its own progress
    var registryBusy by mutableStateOf(setOf<String>())

    // A jar path in the manifest is relative to the manifest itself, so a registry can be moved or
    // mirrored without rewriting every entry.
    private fun entryUrl(file: String): String =
        if (file.startsWith("https://")) file else registryUrl.substringBeforeLast('/') + "/" + file.removePrefix("./")

    fun loadRegistry() {
        if (registryLoading) return
        registryLoading = true
        registryError = null
        scope.launch {
            val raw = withContext(Dispatchers.Default) { Platform.fetchText(registryUrl) }
            val index = raw?.let { runCatching { json.decodeFromString<RegistryIndex>(it) }.getOrNull() }
            registry = index?.extensions.orEmpty()
            registryError = when {
                raw == null -> t("registryUnreachable")
                index == null -> t("registryBroken")
                else -> null
            }
            registryLoading = false
        }
    }

    /**
     * Whether [entry] is installable, already installed, or has a newer version on offer.
     *
     * Views and modules share one install store and one registry, so both lists are searched: an
     * entry's own `kind` says where it is shown, not where it might be found.
     */
    /** The version of [id] on disk, whichever kind it is, or null when it is not installed. */
    fun installedVersion(id: String): String? =
        installedModules.find { it.id == id }?.version ?: installedViews.find { it.id == id }?.version

    fun registryState(entry: RegistryEntry): RegistryState {
        val version = installedVersion(entry.id) ?: return RegistryState.AVAILABLE
        return if (compareVersions(entry.version, version) > 0) RegistryState.UPDATABLE
        else RegistryState.INSTALLED
    }

    /** Download and install (or update) one registry entry. Overwrites, since that is the point. */
    fun installFromRegistry(entry: RegistryEntry) {
        if (entry.id in registryBusy) return
        registryBusy = registryBusy + entry.id
        scope.launch {
            val r = withContext(Dispatchers.Default) { Platform.installFromUrl(entryUrl(entry.file), overwrite = true) }
            registryBusy = registryBusy - entry.id
            if (r.installed.isEmpty()) {
                showError(t("registryInstallFailed").replace("{name}", entry.name.ifBlank { entry.id }))
            }
            refreshFiles()
        }
    }

    /**
     * How far a whole-list install has got, and whether one is running at all.
     *
     * One counter rather than a flag per module: what a person wants to know while twenty jars
     * come down is how many are left, and the rows already say which one is being fetched.
     */
    var bulkDone by mutableStateOf(0)
        private set
    var bulkTotal by mutableStateOf(0)
        private set
    val bulkInstalling: Boolean get() = bulkTotal > 0

    /**
     * Install or update every one of [entries], one after another.
     *
     * Sequentially on purpose: these are downloads, and twenty at once is slower than twenty in a
     * row on anything but a perfect connection — and impossible to report honestly. A failure does
     * not stop the rest; what could not be fetched is named once at the end rather than as twenty
     * dialogs.
     */
    fun installAll(entries: List<RegistryEntry>) {
        if (bulkInstalling || entries.isEmpty()) return
        bulkTotal = entries.size
        bulkDone = 0
        scope.launch {
            val failed = mutableListOf<String>()
            entries.forEach { entry ->
                registryBusy = registryBusy + entry.id
                val result = withContext(Dispatchers.Default) {
                    Platform.installFromUrl(entryUrl(entry.file), overwrite = true)
                }
                registryBusy = registryBusy - entry.id
                if (result.installed.isEmpty()) failed += entry.name.ifBlank { entry.id }
                bulkDone += 1
                refreshFiles() // the rows say "installed" as each one lands, not all at the end
            }
            // said before the counter stops, so that anything watching the run for "is it over"
            // finds the outcome already there rather than a gap where it has not been said yet
            if (failed.isNotEmpty()) {
                showError(
                    t("installAllFailed")
                        .replace("{n}", failed.size.toString())
                        .replace("{names}", failed.joinToString(", ")),
                )
            }
            bulkTotal = 0
            bulkDone = 0
        }
    }

    /** Install a .flow chosen from disk: it goes where flows live, alongside the rest of them. */
    fun installLocalFlow(path: String) {
        copyFlowIntoFolder(path, "")
    }

    fun confirmInstall() { installConfirm?.commit?.invoke(); installConfirm = null }
    fun cancelInstall() { installConfirm = null }

    fun uninstallModule(id: String) {
        Platform.uninstallModule(id)
        refreshFiles()
    }

    // Open a component file: installed ones (components/) as a read-only copy, project files as-is
    fun openComponentFile(file: String) {
        if (components.find { it.file == file }?.installed == true) openInstalledComponent(file)
        else openFile(file)
        warnUnconnected()
    }

    // Show a warning dialog if the active doc still has unconnected modules
    // (used on user-initiated open/save; not during silent session restore).
    private fun warnUnconnected() {
        active?.connectionWarning()?.let { saveWarn = true; saveError = it }
    }

    // Open an installed component for editing: as a new read-only copy (saved into flows/)
    fun openInstalledComponent(file: String) {
        val raw = Platform.readInstalledComponent(file) ?: return
        val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return
        val name = nextName("", file.removeSuffix(".flow").substringAfterLast('.').ifBlank { "flow" })
        val doc = EditorState(scope, this, name).also { it.load(flow); it.persisted = false; it.showValidation = true }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    fun findComp(file: String): CompDef? = components.find { it.file == file }

    fun isComponentFile(file: String): Boolean = components.any { it.file == file }

    /* ───────── project tree ───────── */

    class Row(val path: String, val name: String, val isDir: Boolean, val depth: Int)

    fun isDir(path: String): Boolean = path in folders

    fun isExpanded(dir: String): Boolean = dir in expandedDirs

    fun toggleExpand(dir: String) {
        expandedDirs = if (dir in expandedDirs) expandedDirs - dir else expandedDirs + dir
    }

    // open every folder down to [dir]
    fun revealDir(dir: String) { expandedDirs = expandedDirs + pathAncestors(dir) }

    // Tree order: folders first, then files, alphabetical; expanded folders include their children.
    fun projectRows(): List<Row> {
        val dirsByParent = folders.groupBy { pathParent(it) }
        val filesByParent = files.groupBy { pathParent(it) }
        val out = mutableListOf<Row>()
        fun walk(dir: String, depth: Int) {
            dirsByParent[dir].orEmpty().sortedBy { pathName(it).lowercase() }.forEach { d ->
                out += Row(d, pathName(d), isDir = true, depth = depth)
                if (isExpanded(d)) walk(d, depth + 1)
            }
            filesByParent[dir].orEmpty().sortedBy { pathName(it).lowercase() }.forEach { f ->
                out += Row(f, pathName(f), isDir = false, depth = depth)
            }
        }
        walk("", 0)
        return out
    }

    // where a new file/folder lands
    fun targetDir(): String {
        val sel = projectSelected.firstOrNull() ?: return ""
        return if (isDir(sel)) sel else pathParent(sel)
    }

    /* ───────── project select / open / delete (multi) ───────── */

    fun selectFile(name: String) { projectSelected = setOf(name) }
    fun toggleFileSelect(name: String) {
        projectSelected = if (name in projectSelected) projectSelected - name else projectSelected + name
    }

    fun openFiles(names: Collection<String>) {
        names.filterNot { isDir(it) }.forEach { openFile(it) }
        warnUnconnected()
    }

    // request delete-confirmation -> show the dialog
    fun requestDeleteFiles(names: Set<String>) {
        if (names.isNotEmpty()) fileDeleteConfirm = names
    }

    fun confirmDeleteFiles() {
        fileDeleteConfirm?.let { deleteFiles(it) }
        fileDeleteConfirm = null
    }

    fun cancelDeleteFiles() { fileDeleteConfirm = null }

    private fun deleteFiles(names: Set<String>) {
        names.forEach { path ->
            // close its tab, or every tab under the folder
            val folder = isDir(path)
            while (true) {
                val i = docs.indexOfFirst { if (folder) pathUnder(it.fileName, path) else it.fileName == path }
                if (i < 0) break
                removeDoc(i)
            }
            Platform.deleteFlowPath(path)
        }
        projectSelected = projectSelected - names
        refreshFiles()
    }

    /* ───────── new folder ───────── */

    fun requestNewFolder(parent: String = targetDir()) { newFolderParent = parent }
    fun cancelNewFolder() { newFolderParent = null }

    fun createFolder(rawName: String) {
        val parent = newFolderParent ?: return
        newFolderParent = null
        val name = rawName.trim()
        if (!isValidSegment(name)) return
        val path = pathJoin(parent, name)
        if (Platform.createFlowDir(path)) {
            revealDir(path)
            refreshFiles()
            selectFile(path)
        }
    }

    /* ───────── rename (file or folder) ───────── */

    fun requestRename(name: String) { renameTarget = name }
    fun cancelRename() { renameTarget = null }

    // folders keep their name, files drop the .flow suffix
    fun renameInitial(): String = renameTarget?.let { if (isDir(it)) pathName(it) else flowLabel(it) } ?: ""

    fun doRename(newBase: String) {
        val old = renameTarget ?: return
        renameTarget = null
        val dir = isDir(old)
        val typed = newBase.trim()
        // a flow file keeps its suffix; folders and other files are renamed as typed
        val name = if (!dir && old.endsWith(".flow") && !typed.endsWith(".flow")) "$typed.flow" else typed
        if (!isValidSegment(name)) return
        val new = pathJoin(pathParent(old), name)
        if (new == old) return
        if (!Platform.renameFlowPath(old, new)) return
        // re-point open tabs, expansion and selection
        fun moved(path: String) = if (path == old) new else new + path.substring(old.length)
        if (dir) {
            docs.forEach { if (pathUnder(it.fileName, old)) it.fileName = moved(it.fileName) }
            expandedDirs = expandedDirs.map { if (it.isNotEmpty() && pathUnder(it, old)) moved(it) else it }.toSet()
        } else {
            docs.find { it.fileName == old }?.fileName = new
        }
        projectSelected = projectSelected.map { if (pathUnder(it, old)) moved(it) else it }.toSet()
        refreshFiles()
    }

    /* ───────── open/close tabs ───────── */

    // Only a readable .flow file opens; anything else reports an error instead.
    fun openFile(name: String, reportError: Boolean = true) {
        val i = docs.indexOfFirst { it.fileName == name }
        if (i >= 0) { activeIndex = i; return }
        val label = pathName(name)
        if (!name.endsWith(".flow")) {
            if (reportError) showError(t("openErrorNotFlow").replace("{name}", label), "openErrorTitle")
            return
        }
        val raw = Platform.readFlow(name)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() }
        if (flow == null) {
            if (reportError) showError(t("openErrorBroken").replace("{name}", label), "openErrorTitle")
            return
        }
        val doc = EditorState(scope, this, name).also { it.load(flow); it.showValidation = true }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    // Copy an external .flow file (from a File > Open dialog or an OS drag-and-drop) into the
    // project under [dir], keeping its original name when free, else suffixed like a new flow's
    // ("name-1.flow", "name-2.flow", ...). Returns the new project-relative path, or null if
    // [path] isn't a readable, valid flow file (an error dialog is shown in that case).
    private fun copyFlowIn(path: String, dir: String): String? {
        val label = Platform.fileName(path)
        if (!path.endsWith(".flow")) {
            showError(t("openErrorNotFlow").replace("{name}", label), "openErrorTitle")
            return null
        }
        val raw = Platform.readExternalFlow(path)
        if (raw == null || runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() == null) {
            showError(t("openErrorBroken").replace("{name}", label), "openErrorTitle")
            return null
        }
        val base = label.removeSuffix(".flow")
        val preferred = pathJoin(dir, "$base.flow")
        val existing = (files + docs.map { it.fileName }).toSet()
        val dest = if (preferred !in existing) preferred else nextName(dir, base)
        Platform.writeFlow(dest, raw)
        refreshFiles()
        revealDir(dir)
        return dest
    }

    /**
     * A .flow chosen from disk, or dropped on the window.
     *
     * With a folder open it is copied in and opened from there, so it becomes part of the project.
     * With none, it is opened where it lies and saves back to the same file — an editor lets you
     * work on a single file without adopting its folder.
     */
    fun importFlow(path: String, dir: String = targetDir()) {
        if (hasProject) copyFlowIn(path, dir)?.let { openFile(it) } else openStandaloneFile(path)
    }

    /** Opens a .flow by absolute path, with no project around it. */
    fun openStandaloneFile(path: String) {
        docs.indexOfFirst { it.standalonePath == path }.takeIf { it >= 0 }?.let {
            activeIndex = it; return
        }
        val label = Platform.fileName(path)
        val raw = Platform.readExternalFlow(path)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() }
        if (flow == null) {
            showError(
                t(if (raw == null) "openErrorNotFlow" else "openErrorBroken").replace("{name}", label),
                "openErrorTitle",
            )
            return
        }
        val doc = EditorState(scope, this, path).also {
            it.standalonePath = path
            it.load(flow)
            it.showValidation = true
        }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    // A .flow file dropped onto a project folder (or the root row): copy it in, but leave the
    // editor's open tabs alone.
    fun copyFlowIntoFolder(path: String, dir: String) {
        copyFlowIn(path, dir)
    }

    // New component in [dir] (default: the selected folder). Written to a file only on
    // save, which validates the in/out contract.
    fun newComponent(dir: String = targetDir()) {
        val name = nextName(dir, "flow")
        val doc = EditorState(scope, this, name).also { it.load(FlowFile()); it.persisted = false }
        docs.add(doc)
        revealDir(dir)
        activeIndex = docs.lastIndex
    }

    fun select(i: Int) {
        if (docs.isEmpty()) return
        activeIndex = i.coerceIn(0, docs.lastIndex)
    }

    fun save(i: Int) {
        docs.getOrNull(i)?.save()
    }

    fun saveActive() {
        active?.save()
    }

    fun saveAll() {
        docs.forEach { if (it.dirty) it.save() }
    }

    // Close-tab request: if there are unsaved changes, ask whether to save first.
    fun requestClose(i: Int) {
        if (i !in docs.indices) return
        if (docs[i].dirty) closeConfirm = i else removeDoc(i)
    }

    fun confirmSaveAndClose() {
        closeConfirm?.let { i ->
            // keep the tab open when validation fails (the error dialog explains why)
            if (docs.getOrNull(i)?.save() == false) {
                closeConfirm = null
                return
            }
            removeDoc(i)
        }
        closeConfirm = null
    }

    fun confirmDiscardAndClose() {
        closeConfirm?.let { removeDoc(it) }
        closeConfirm = null
    }

    fun cancelClose() {
        closeConfirm = null
    }

    private fun removeDoc(i: Int) {
        if (i !in docs.indices) return
        val doc = docs[i]
        // close any data-editor windows that belonged to this document
        dataWindows.removeAll { it.doc === doc }
        docs.removeAt(i)
        // a tab before the active one shifts everything left by one, so follow it to stay on the same doc
        if (i < activeIndex) activeIndex--
        // closing the active tab itself lands on the next tab at the same index; clamp to the previous one if it was last
        if (activeIndex >= docs.size) activeIndex = (docs.size - 1).coerceAtLeast(0)
    }

    private fun nextName(dir: String, base: String): String {
        val existing = (files + docs.map { it.fileName }).toSet()
        var n = 1
        while (pathJoin(dir, "$base-$n.flow") in existing) n++
        return pathJoin(dir, "$base-$n.flow")
    }

    /* ───────── session ───────── */

    fun sessionJson(): String = json.encodeToString(
        Session(
            openFiles = docs.map { it.fileName },
            activeIndex = activeIndex,
            showLeft = showLeft,
            leftTab = leftTab,
            expandedDirs = expandedDirs.toList().sorted(),
            expandedSections = expandedSections.toList().sorted(),
            showProps = showProps,
            showMinimap = showMinimap,
            leftWidth = leftWidth,
            propsWidth = propsWidth,
            windowX = windowX,
            windowY = windowY,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            windowMaximized = windowMaximized,
        )
    )

    fun settingsJson(): String = json.encodeToString(
        Settings(
            lang, theme, keymap.mapValues { it.value.id() }, animSeconds, registryUrl,
            aiProvider, aiModel, ollamaUrl, ollamaModel, openaiUrl, openaiModel, geminiUrl, geminiModel,
            claudeUrl, aiTransports, extensionSettings,
        )
    )

    private fun loadSession() {
        val s = Platform.loadSession()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        // settings.json is authoritative; a session written before the split still carries them, so
        // it stands in once and is then superseded the next time settings are saved
        val saved = Platform.loadSettings()?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() }
            ?: s?.let { Settings(it.lang ?: "ko", it.theme ?: Theme.SYSTEM, it.keymap, it.animSeconds ?: 0.25f) }
        if (saved != null) {
            lang = saved.lang
            theme = saved.theme.takeIf { it in Theme.ALL } ?: Theme.SYSTEM
            animSeconds = saved.animSeconds.coerceIn(0.05f, 10f)
            registryUrl = saved.registryUrl.ifBlank { DEFAULT_REGISTRY_URL }
            // an id from an older build that's since been retired falls back to Auto rather than
            // silently passing something `claude --model` may no longer recognize
            aiModel = saved.aiModel.takeIf { id -> AI_MODELS.any { it.first == id } } ?: ""
            aiProvider = saved.aiProvider.takeIf { p -> AI_PROVIDERS.any { it.first == p } } ?: AI_CLAUDE
            ollamaUrl = saved.ollamaUrl.ifBlank { DEFAULT_OLLAMA_URL }
            // not checked against a list the way the Claude id is: what Ollama has pulled is
            // whatever this machine has, and the only way to know is to ask it
            ollamaModel = saved.ollamaModel
            openaiUrl = saved.openaiUrl.ifBlank { DEFAULT_OPENAI_URL }
            openaiModel = saved.openaiModel
            geminiUrl = saved.geminiUrl.ifBlank { DEFAULT_GEMINI_URL }
            geminiModel = saved.geminiModel
            claudeUrl = saved.claudeUrl.ifBlank { DEFAULT_CLAUDE_URL }
            aiTransports = saved.aiTransport.filterValues { it == AI_VIA_CLI || it == AI_VIA_API }
            extensionSettings = saved.extensionSettings
            // unknown/unparseable bindings fall back to the default for that action
            keymap = DEFAULT_KEYMAP + saved.keymap.mapNotNull { (action, id) ->
                Shortcut.parse(id)?.let { action to it }
            }.toMap()
        }
        if (s != null) {
            showLeft = s.showLeft
            leftTab = s.leftTab
            // an older session file has no such field; keep the root open
            expandedDirs = s.expandedDirs.toSet() + ""
            expandedSections = s.expandedSections.toSet().let { saved ->
                // The palette's one Modules section became one per category. Someone who had it
                // open had it open for a reason, so the sections that replaced it open with it
                // rather than the panel coming back looking empty.
                if ("modules" in saved) saved + CATEGORIES.map { "modules:$it" } else saved
            }
            showProps = s.showProps
            showMinimap = s.showMinimap
            leftWidth = s.leftWidth.coerceIn(160f, 500f)
            propsWidth = s.propsWidth.coerceIn(200f, 560f)
            windowX = s.windowX
            windowY = s.windowY
            windowWidth = s.windowWidth
            windowHeight = s.windowHeight
            windowMaximized = s.windowMaximized
            // openFiles named paths inside whichever folder was open, and none is at startup —
            // they are left for the user to reopen rather than guessed at against another folder
            activeIndex = 0
        }
        // and nothing is created either: an editor with no folder open shows an empty editor,
        // it does not invent a file
    }
}
