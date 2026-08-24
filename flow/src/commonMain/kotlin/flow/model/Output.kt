package flow.model

/**
 * An installed view, as its worker described it.
 *
 * A view is a viewer of its own: the app shows a value as text or as hex without help, and anything
 * richer — a certificate as a tree beside its bytes, an image — is a program in its own process
 * that opens its own window. So there is nothing here about how it draws; only what it is called
 * and what it is for.
 */
data class ViewInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
)
