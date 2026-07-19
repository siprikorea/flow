package dataflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dataflow.core.Workspace
import dataflow.ui.common.Txt
import dataflow.ui.common.plainClick
import dataflow.ui.common.rememberHover
import dataflow.ui.theme.Palette

@Composable
fun EditorTabs(ws: Workspace) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Palette.tabBarBg)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ws.docs.forEachIndexed { i, doc ->
                Tab(
                    name = doc.fileName.removeSuffix(".json"),
                    active = i == ws.activeIndex,
                    isComp = ws.isComponentFile(doc.fileName),
                    dirty = doc.dirty,
                    onSelect = { ws.select(i) },
                    onClose = { ws.requestClose(i) },
                )
            }
            Box(
                Modifier.plainClick { ws.newDoc() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Txt("+", 15.sp, Palette.subText) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

@Composable
private fun Tab(name: String, active: Boolean, isComp: Boolean, dirty: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val (hoverSrc, hovered) = rememberHover()
    Column {
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) Palette.accent else Color.Transparent))
        Row(
            Modifier
                .height(32.dp)
                .background(if (active) Palette.tabActiveBg else if (hovered) Palette.hoverBg else Palette.tabBarBg)
                .hoverable(hoverSrc)
                .plainClick(onSelect)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier.width(8.dp).height(8.dp)
                    .background(if (isComp) Palette.catComponent else Palette.dimText, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            )
            Txt(
                name, 12.sp,
                if (active) Palette.text else Palette.menuText,
                weight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            // 수정됨: 평소 점(•), hover 시 닫기(×)
            if (dirty && !hovered) {
                Txt("●", 10.sp, Palette.accentSoft, modifier = Modifier.padding(horizontal = 3.dp))
            } else {
                Txt(
                    "×", 13.sp, if (hovered || active) Palette.subText else Color.Transparent,
                    modifier = Modifier.plainClick(onClose).padding(horizontal = 3.dp),
                )
            }
        }
    }
}
