package flow.ui.views

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes the PNG a view drew with `image()`. Null when the bytes are not an image we can read. */
expect fun decodePng(bytes: ByteArray): ImageBitmap?
