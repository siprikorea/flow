package flow.platform

import androidx.compose.ui.draganddrop.DragAndDropEvent

// Extract the first dropped file's absolute path from an external drag event (or null).
expect fun droppedFilePath(event: DragAndDropEvent): String?
