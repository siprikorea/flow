package flow.model

/** One answer from the assistant, or the reason there is not one. */
data class AiReply(
    val text: String = "",
    /** Carries the conversation to the next turn. */
    val sessionId: String? = null,
    val error: String? = null,
)

/** A line of the conversation, as it is shown. */
data class AiMessage(val fromUser: Boolean, val text: String)

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
    /** The model for [provider]: a `claude --model` id, or a name Ollama has pulled. */
    val model: String,
    val ollamaUrl: String,
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
const val AI_ERR_OLLAMA_NO_MODEL = "ollama-no-model"
const val AI_ERR_OLLAMA_STEPS = "ollama-too-many-steps"
