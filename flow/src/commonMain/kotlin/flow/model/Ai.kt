package flow.model

/** One answer from the assistant, or the reason there is not one. */
data class AiReply(
    val text: String = "",
    /** Carries the conversation to the next turn. */
    val sessionId: String? = null,
    val error: String? = null,
)

/**
 * A line of the conversation, as it is shown.
 *
 * [provider] is who said it, kept per message rather than read off the current setting: switching
 * provider starts a new conversation but leaves what was already said on screen, so an answer has
 * to stay labelled with whoever actually gave it. Blank on a user's own line, and on an answer from
 * a session saved before this was recorded.
 */
data class AiMessage(val fromUser: Boolean, val text: String, val provider: String = "")

/**
 * Which assistant a turn goes to, and how to reach it.
 *
 * The two are not interchangeable in their parameters — one is a CLI with an account behind it and
 * a hosted model id, the other an HTTP server on this machine with whatever models were pulled onto
 * it — so the panel passes the whole setup rather than a model string that means something
 * different depending on who reads it.
 */
data class AiSetup(
    val provider: String,
    /** AI_VIA_CLI or AI_VIA_API: the command, or the model driven directly. */
    val transport: String = AI_VIA_API,
    /** The model for [provider]: a `claude --model` id, or a name the server offers. */
    val model: String,
    /** Where the server is. Empty for Claude, which is a command rather than an address. */
    val url: String = "",
    /** The API key, already resolved from Settings or the environment. Empty where none is needed. */
    val apiKey: String = "",
)

/**
 * Reasons a turn produced nothing, as tokens rather than sentences.
 *
 * The provider that raises them is platform code with no access to the string table, and the panel
 * is what knows the user's language — so these cross the gap as ids and are turned into text in
 * Workspace.aiErrorText. Anything else in AiReply.error is a message from the tool itself and is
 * shown as it came.
 */
const val AI_ERR_OLLAMA_DOWN = "ollama-not-running"
const val AI_ERR_NO_MODEL = "ai-no-model"
const val AI_ERR_STEPS = "ai-too-many-steps"
const val AI_ERR_NO_KEY = "ai-no-key"
