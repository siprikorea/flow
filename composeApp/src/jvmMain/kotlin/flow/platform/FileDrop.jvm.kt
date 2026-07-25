@file:OptIn(ExperimentalComposeUiApi::class)

package flow.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

actual fun droppedFilePath(event: DragAndDropEvent): String? = runCatching {
    val t = event.awtTransferable
    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        (files?.firstOrNull() as? File)?.absolutePath
    } else null
}.getOrNull()
