package flow.extension

/**
 * The near end of a flow: how the data going in is written.
 *
 * An input node holds bytes, and bytes have to be typed as something — as text, as hex digits, as
 * base64. That choice is what an input extension is: it turns what the user typed into the bytes it
 * stands for, and those bytes back into something to type over.
 *
 * The editing itself stays in the app, where selection, undo and IME already work. All that is
 * asked here is the conversion, which is the part that actually differs between one way of writing
 * bytes and another.
 *
 * Implementations must have a no-arg constructor and be registered under
 * `META-INF/services/flow.extension.InputExtension`.
 */
interface InputExtension {
    /** Identifier in package-name format (e.g. "com.example.base64input"). */
    val id: String

    /** Name shown where the input is picked. */
    val displayName: String

    /** Version, compared against a registry's to decide whether an update is on offer. */
    val version: String get() = "1.0.0"

    /** Options shown alongside the input, edited the same way a processor's are. */
    val options: List<ExtensionOption> get() = emptyList()

    /**
     * How many characters one byte is written as, when the writing is fixed width — three for hex
     * bytes written with a space between them. Zero when it is not fixed width, as text is not.
     *
     * The app uses it to work out which byte the caret is on without asking, which is what lets it
     * show a window onto a value far too large to put in a text field all at once.
     */
    val charsPerByte: Int get() = 0

    /**
     * The bytes [text] stands for.
     *
     * Called as the user types, so it sees text that is still half-written — a lone hex digit, an
     * unfinished escape. Read what is there and leave the rest; say what is wrong through [problem]
     * rather than by throwing, so that typing is never interrupted by an error the next keystroke
     * would have fixed. Throwing is for text that cannot be read at all.
     */
    fun parse(text: String, options: Map<String, String>): ByteArray

    /**
     * How [data] reads back in the editor.
     *
     * This is the other half of [parse] and has to agree with it: formatting bytes and parsing the
     * result must give back the same bytes, or editing one end of a value would quietly change the
     * other.
     */
    fun format(data: ByteArray, options: Map<String, String>): String

    /**
     * What is wrong with [text], or null when nothing is.
     *
     * Shown beside the editor as a note, not as a failure — the value is still whatever [parse]
     * made of it. This is for saying "'q' is not a hex digit" while the user is still typing.
     */
    fun problem(text: String, options: Map<String, String>): String? = null
}
