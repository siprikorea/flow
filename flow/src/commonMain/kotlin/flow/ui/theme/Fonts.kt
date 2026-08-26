package flow.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The typeface everything that has to line up is set in: hex dumps, node ids, option values,
 * the assistant's answers.
 *
 * It is JetBrains Mono rather than whatever the machine calls "monospace", because the platform
 * default is a different face on every one of them — and the hex editor's columns, the node's
 * type line and the ASN.1 view's offsets are all drawn on the assumption that a digit is exactly
 * as wide as the digit above it. Shipping the face is what makes that assumption true everywhere.
 *
 * The file lives in the extension contract's jar, which is the one thing the app and every
 * extension worker both have on their classpath; see the loader for why that matters.
 */
expect val Mono: FontFamily
