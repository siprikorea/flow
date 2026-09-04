package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import flow.ui.theme.FlowType
import flow.ui.theme.Palette
import flow.ui.theme.Radius
import flow.ui.theme.Size

/**
 * The design guide's common components (CLAUDE.md §6): a closed set of button/row/header shapes
 * so a screen is assembled from these rather than a fresh Box+border every time. Reused by every
 * panel touched from here on — see the guide's per-screen rules for which variant goes where.
 */

enum class FlowButtonVariant { Primary, Secondary, Ghost, Danger }

// height 28dp, radius 4, body type — the one button shape in the app, in four colourings. At most
// one Primary belongs in any single region (guide P4).
@Composable
fun FlowButton(
    label: String,
    variant: FlowButtonVariant,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false, // 24dp + a 12dp icon, for a Ghost "+ Add" style link
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    val bg = when {
        !enabled && variant == FlowButtonVariant.Primary -> Palette.raised
        variant == FlowButtonVariant.Primary -> if (hovered) Palette.accentHover else Palette.accent
        variant == FlowButtonVariant.Danger && enabled && hovered -> Palette.danger.copy(alpha = 0.12f)
        variant == FlowButtonVariant.Secondary && enabled && hovered -> Palette.hoverOverlay
        variant == FlowButtonVariant.Ghost && enabled && hovered -> Palette.hoverOverlay
        else -> Color.Transparent
    }
    val borderColor = when {
        !enabled -> null
        variant == FlowButtonVariant.Secondary -> if (hovered) Palette.borderStrong else Palette.border
        variant == FlowButtonVariant.Danger -> if (hovered) Palette.danger else Palette.danger.copy(alpha = 0.4f)
        else -> null
    }
    val textColor = when {
        !enabled -> Palette.textDisabled
        variant == FlowButtonVariant.Primary -> Color.White
        variant == FlowButtonVariant.Secondary -> Palette.textPrimary
        variant == FlowButtonVariant.Danger -> Palette.danger
        else -> if (hovered) Palette.textPrimary else Palette.textSecondary // Ghost
    }

    var m = modifier
        .height(if (compact) Size.controlCompact else Size.control)
        .background(bg, RoundedCornerShape(Radius.control))
    if (borderColor != null) m = m.border(1.dp, borderColor, RoundedCornerShape(Radius.control))
    Row(
        m.hoverable(hoverSrc)
            .plainClick { if (enabled) onClick() }
            .padding(horizontal = if (icon != null) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) LucideIcon(icon, textColor, if (compact) Size.iconSmall else Size.icon)
        Txt(label, FlowType.body, textColor)
    }
}

// 28x28dp hit box, no default background — for a toolbar/panel action that is only ever an icon.
// [tint] overrides the default state colours while enabled (e.g. Stop reads danger only while a
// run is active — CLAUDE.md §5), and is ignored while disabled.
@Composable
fun FlowIconButton(
    icon: ImageVector,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    val bg = if (enabled && hovered) Palette.hoverOverlay else Color.Transparent
    val resolvedTint = when {
        !enabled -> Palette.textDisabled
        tint != null -> tint
        selected -> Palette.accent
        hovered -> Palette.textPrimary
        else -> Palette.textSecondary
    }
    Box(
        modifier
            .size(Size.iconButton)
            .background(bg, RoundedCornerShape(Radius.control))
            .hoverable(hoverSrc)
            .plainClick { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon, resolvedTint, Size.icon)
    }
}

// 28dp flat list row: transparent by default, hoverOverlay on hover, accentSubtle + a left 2dp
// accent bar when selected. The shape every left-panel / palette row now uses.
//
// [onClick] is nullable: leave it null when the row's own [modifier] already carries its own
// pointer-input gesture (e.g. a drag source) — attaching `clickable` as well would compete with
// it for the same down event and can swallow the gesture the caller actually wants.
@Composable
fun FlowListRow(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Palette.textSecondary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (hoverSrc, hovered) = rememberHover()
    val bg = when {
        selected -> Palette.accentSubtle
        hovered -> Palette.hoverOverlay
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(Size.row)
            .background(bg)
            .hoverable(hoverSrc)
            .then(if (onClick != null) Modifier.plainClick(onClick) else Modifier)
            .padding(start = if (selected) 10.dp else 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (selected) Box(Modifier.size(2.dp, 14.dp).background(Palette.accent))
        if (icon != null) LucideIcon(icon, iconTint, Size.icon)
        Txt(label, FlowType.body, if (selected) Palette.textPrimary else Palette.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        trailing?.invoke(this)
    }
}

// 24dp collapsible section header: chevron + uppercase caption label + optional trailing count.
@Composable
fun FlowSectionHeader(
    label: String,
    expanded: Boolean,
    count: Int? = null,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    Row(
        modifier
            .fillMaxWidth()
            .height(Size.groupHeader)
            .background(if (hovered) Palette.hoverOverlay else Color.Transparent)
            .hoverable(hoverSrc)
            .plainClick(onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, Palette.textTertiary, Size.iconSmall)
        Txt(label.uppercase(), FlowType.caption, Palette.textTertiary, modifier = Modifier.weight(1f))
        if (count != null) Txt(count.toString(), FlowType.mono, Palette.textTertiary)
    }
}

// 1dp hairline in the guide's subtle border tone — the only rule the app draws between logical
// groups inside a panel.
@Composable
fun FlowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.borderSubtle))
}

// A destructive icon action guarded by an anchored confirm popover — CLAUDE.md §6: "확인 팝오버:
// 파괴적 동작은 모달 대신 앵커 팝오버 ... 문장 한 줄 + Secondary/Danger 두 버튼". Replaces a full-width
// red-bordered button, which makes a delete look bigger than the primary action beside it.
@Composable
fun FlowDangerIconButton(icon: ImageVector, confirmText: String, cancelLabel: String, confirmLabel: String, onConfirm: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FlowIconButton(icon, onClick = { open = true })
        if (open) {
            Popup(
                offset = IntOffset(0, 34),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(220.dp)
                        .background(Palette.overlay, RoundedCornerShape(Radius.surface))
                        .border(1.dp, Palette.border, RoundedCornerShape(Radius.surface))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Txt(confirmText, FlowType.small, Palette.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowButton(cancelLabel, FlowButtonVariant.Secondary, modifier = Modifier.weight(1f)) { open = false }
                        FlowButton(confirmLabel, FlowButtonVariant.Danger, modifier = Modifier.weight(1f)) {
                            open = false
                            onConfirm()
                        }
                    }
                }
            }
        }
    }
}
