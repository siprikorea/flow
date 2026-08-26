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
