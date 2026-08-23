package flow.ui.views

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The bytes came from an extension, so they are not trusted to be an image at all: a decode that
 * fails is a view that drew something odd, not a reason to take the window down.
 */
actual fun decodePng(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
