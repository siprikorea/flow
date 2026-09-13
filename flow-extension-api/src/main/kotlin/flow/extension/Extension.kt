package flow.extension

/**
 * The four kinds of extension.
 *
 * An extension is anything that can be installed into Flow. A [ProcessorExtension] does the work in
 * the middle of a flow, an [InputExtension] decides how the data going in is written, an
 * [OutputExtension] how what comes out is shown, and a flow file installed as a component is the
 * fourth — built in the tool rather than written in Kotlin, so it has no interface here.
 */

/** The kind of editor a processor option uses in the Flow property panel. */
enum class OptionType { TEXT, NUMBER, SELECT }

/**
 * A predefined option the user can edit in the property panel.
 * `default` seeds the value on node creation; `choices` lists the allowed values for SELECT.
 */
data class ExtensionOption(
    val name: String,
    val type: OptionType = OptionType.TEXT,
    val default: String = "",
    val choices: List<String> = emptyList(),
)

/**
 * A processor: the input → output work in the middle of a flow.
 *
 * This is what most extensions are — a hash, a cipher, an encoder. [InputExtension] and
 * [OutputExtension] are the two ends around it, and a component is a flow file installed as one
 * rather than a class.
 *
 * Implementations must have a no-arg constructor and be registered under
 * `META-INF/services/flow.extension.ProcessorExtension`.
 */
interface ProcessorExtension {
    /** Identifier in package-name format (e.g. "com.example.base64"). */
    val id: String

    /** Name shown in the palette. */
    val displayName: String

    /**
     * Version of this extension, as dot-separated numbers ("1.2.0"). Flow compares it against the
     * version a registry offers to decide whether an update is available, so it has to go up when
     * the extension changes. Defaults so that an extension written before versions existed still
     * compiles and reads as 1.0.0.
     */
    val version: String get() = "1.0.0"

    /**
     * What this extension is for: "crypto", "ai", "messaging", or "other".
     *
     * It decides which group the extension is listed under in the palette and in the Extensions
     * screen — nothing else; an extension works the same whichever category it is in. Declared
     * here rather than read from the registry so that the palette can group an extension installed
     * from a file, and so that grouping does not need the network.
     *
     * A name this build of Flow does not know, and the default of none, are both listed under
     * "other" rather than dropped: a category added to the contract later must not make an
     * extension built before it disappear.
     */
    val category: String get() = ""

    /** Input port ids (the full/default set — see [inputsFor] for option-dependent ports). */
    val inputs: List<String>

    /** Output port ids (the full/default set — see [outputsFor] for option-dependent ports). */
    val outputs: List<String>

    /** Predefined options shown in the property panel (name → value). */
    val options: List<ExtensionOption>

    /**
     * Settings for the extension as a whole, edited once in Settings ▸ Extensions rather than on
     * every node — the way an IDE plugin has its own settings page.
     *
     * They reach [process] through the same options map, underneath the node's own: a node option
     * left blank takes the value from here. So an extension that wants a setting a node can
     * override declares the same name in both, and one that wants a setting a node cannot touch
     * declares it only here.
     *
     * Defaults to none, so an extension written before this existed still compiles and still loads.
     */
    val settings: List<ExtensionOption> get() = emptyList()

    /**
     * Settings for the values they currently hold, when one setting decides another.
     *
     * The same idea as [optionsFor], and the way a list that has to be fetched is offered: an
     * extension that talks to a server can name the models that server has, once it knows which
     * server and which key. The host calls this off the UI thread and remembers the answer, so it
     * may do real work — but it is called again whenever a value changes, so it should not do more
     * than the question needs.
     */
    fun settingsFor(values: Map<String, String>): List<ExtensionOption> = settings

    /**
     * Inputs that may be left out, for the given option values.
     *
     * [inputsFor] says which ports exist; this says which of them a caller need not supply. The two
     * are different questions and both matter to a caller reading a schema: cipher's `iv` exists
     * for AES and is still optional, because leaving it out means "generate one and prepend it".
     *
     * Everything not named here is required. That is the safe default — a caller told a port is
     * optional when it is not gets a failure it cannot see coming.
     */
    fun optionalInputsFor(values: Map<String, String>): List<String> = emptyList()

    /**
     * One line per port: what it expects, in what encoding, and at what length.
     *
     * Written for something reading a generated schema rather than looking at the canvas, so it
     * says the things a name cannot — "AES key, 16/24/32 bytes; `hex:` or `b64:` prefix for
     * binary, otherwise UTF-8" rather than "key".
     *
     * A map keyed by port name rather than a field on the port, because ports are plain strings in
     * this contract and adding a parameter to anything here breaks every jar already built.
     */
    val portDescriptions: Map<String, String> get() = emptyMap()

    /** The same for options, and for what each choice of a SELECT is for. */
    val optionDescriptions: Map<String, String> get() = emptyMap()

    /**
     * Inputs that carry a secret: a key, a password, a token.
     *
     * Naming them lets the host keep them out of the places a value should never reach — a log, a
     * transcript, an error message, anything echoed back to a caller. It changes nothing about how
     * the module reads them.
     *
     * This is about the *port*, not the value: `key` on a cipher is sensitive whether or not the
     * key that arrives on it happens to be secret, because a port is what the host can reason
     * about before anything runs.
     */
    val sensitiveInputs: List<String> get() = emptyList()

    /**
     * Which of the [settings] hold a key: shown as dots, and kept where keys are kept rather than
     * in the settings file, which is written on every preference change and is meant to be read.
     *
     * By name rather than as a flag on the option itself, because ExtensionOption is a data class
     * every extension already calls the constructor of — adding a parameter to it changes a JVM
     * signature, and every jar built before that stops loading with a NoSuchMethodError. Which is
     * exactly what happened. Everything added to this contract has to be additive: a new member
     * with a default, never a new parameter on an existing one.
     *
     * Only settings can be secret. A node's options travel in the flow file, which is a document
     * people share, so a secret has no business being one.
     */
    val secretSettings: List<String> get() = emptyList()

    /**
     * Input port ids for the given option values, when a module's ports depend on an option
     * (e.g. a "verify" operation needing a signature input that "sign" doesn't). Defaults to the
     * fixed [inputs]. The host uses this to keep a node's actual ports in sync with its options —
     * ports are never user-added/removed/renamed on an extension module, only driven by this.
     */
    fun inputsFor(options: Map<String, String>): List<String> = inputs

    /** Output port ids for the given option values. Defaults to the fixed [outputs]. */
    fun outputsFor(options: Map<String, String>): List<String> = outputs

    /**
     * Options to show for the given option values, when one option decides whether others apply
     * (e.g. an iteration count that only a key-derivation algorithm uses). Defaults to the fixed
     * [options]. The host shows exactly this list in the property panel; values of options that
     * are not currently shown are kept, so they come back when the deciding option does.
     */
    fun optionsFor(values: Map<String, String>): List<ExtensionOption> = options

    /**
     * Process the port data. Values on ports are raw bytes:
     * input-port-id → bytes, plus the current option values, producing output-port-id → bytes.
     *
     * The options are the node's, over the extension's own [settings]: a node option that is blank
     * arrives here as whatever Settings ▸ Extensions was set to.
     *
     * Flow evaluates independent nodes at the same time, so this may be called concurrently on the
     * one instance. Everything it needs arrives in the arguments — keep no state between calls.
     */
    fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
}

/**
 * What a processor was called before the four kinds had names.
 *
 * Kept so that an extension built against the older contract still loads: it is the same interface
 * under a name that no longer says which of the four it is. Nothing new should implement it.
 */
@Deprecated("Renamed to ProcessorExtension", ReplaceWith("ProcessorExtension"))
interface ModuleExtension : ProcessorExtension
