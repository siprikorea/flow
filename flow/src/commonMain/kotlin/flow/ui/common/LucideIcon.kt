package flow.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import flow.ui.theme.Size

/**
 * One Lucide icon (com.composables:icons-lucide), tinted flat — the app's one icon language.
 * `Image(imageVector, ...)` rather than the Material `Icon` composable: this project doesn't
 * depend on compose-material, and a plain tinted image is all an outline icon needs.
 *
 * [size] defaults to the guide's 16dp base size; pass `Size.iconLarge` (20dp, activity bar) or
 * `Size.iconSmall` (12dp, inline chevrons) for the other two allowed sizes — no other size belongs
 * anywhere in the app.
 */
@Composable
fun LucideIcon(icon: ImageVector, tint: Color, size: Dp = Size.icon, modifier: Modifier = Modifier) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
