package dataflow.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

// 좌측 툴윈도우: Project / Modules 탭 전환
@Composable
fun LeftToolWindow(ws: Workspace) {
    Row {
        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Palette.panelBg),
        ) {
            // 헤더 탭
            Row(
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToolTab(ws.t("tabProject"), ws.leftTab == "project") { ws.leftTab = "project" }
                ToolTab(ws.t("tabModules"), ws.leftTab == "modules") { ws.leftTab = "modules" }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
            when (ws.leftTab) {
                "modules" -> ModulePalette(ws)
                else -> ProjectPanel(ws)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
    }
}

@Composable
private fun ToolTab(text: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.plainClick(onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Txt(
                text.uppercase(), 11.sp,
                if (active) Palette.text else Palette.dimText,
                weight = FontWeight.Bold, letterSpacing = 0.5.sp,
            )
        }
        Box(
            Modifier.width(44.dp).height(2.dp)
                .background(if (active) Palette.accent else Color.Transparent)
        )
    }
}

@Composable
private fun ProjectPanel(ws: Workspace) {
    Column(Modifier.fillMaxWidth()) {
        // 헤더: 폴더 경로 + 새 플로우
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(ws.t("tabProject").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Txt(
                "+", 15.sp, Palette.subText,
                modifier = Modifier.plainClick { ws.newDoc() }.padding(horizontal = 6.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        ) {
            ws.files.forEach { name ->
                FileRow(ws, name)
            }
            Txt(
                ws.dirLabel, 10.sp, Palette.faintText, mono = true,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FileRow(ws: Workspace, name: String) {
    val (hoverSrc, hovered) = rememberHover()
    val isOpen = ws.docs.any { it.fileName == name }
    val isActive = ws.active?.fileName == name
    val isComp = ws.isComponentFile(name)
    val bg = when {
        isActive -> Palette.langActiveBg
        hovered -> Palette.hoverBg
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hoverSrc)
            .background(bg, RoundedCornerShape(5.dp))
            .plainClick { ws.openFile(name) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(9.dp).background(if (isComp) Palette.catComponent else Palette.dimText, RoundedCornerShape(2.dp)))
        Txt(
            name.removeSuffix(".json"), 12.sp,
            if (isActive || isOpen) Palette.text else Palette.menuText,
            weight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        if (isComp) Txt("C", 9.sp, Palette.catComponent, mono = true, weight = FontWeight.Bold)
    }
}
