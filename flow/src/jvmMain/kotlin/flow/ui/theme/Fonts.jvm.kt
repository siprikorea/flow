package flow.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * JetBrains Mono, read out of the classpath.
 *
 * It is packaged in flow-module-api rather than here on purpose. A view module draws its
 * window in its own process, whose classpath is the worker, the module contract, the Kotlin
 * runtime and the lent Compose — the app's own jar is deliberately not on it. The contract jar is
 * the only place both sides can see, so that is where the face lives and both sides name the same
 * path.
 *
 * Three weights: the interface asks for normal, medium and bold, and a weight that is missing is
 * faked by smearing the outline — which is exactly what a column of hex shows up.
 */
actual val Mono: FontFamily = FontFamily(
    Font("flow/fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
    Font("flow/fonts/JetBrainsMono-Medium.ttf", FontWeight.Medium),
    Font("flow/fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold),
)
