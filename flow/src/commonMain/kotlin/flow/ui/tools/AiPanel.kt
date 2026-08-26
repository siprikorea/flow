package flow.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.platform.Platform
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette

/**
 * Claude Code, in a panel.
 *
 * It is a conversation rather than a terminal because a conversation is what it is for: each turn
 * runs the CLI once and shows what it said. What makes it worth having here rather than in a
 * terminal window is that Flow has already handed it the flow tools, pointed it at the open folder,
 * and told it what a flow is — so the first thing asked can be the actual question.
 */
@Composable
fun AiPanel(ws: Workspace) {
    val installed = remember { Platform.aiCliPath() != null }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(ws.t("tabAi"))
        if (!installed) {
            NotInstalled(ws)
            return@Column
        }
        Transcript(ws, Modifier.weight(1f))
        Composer(ws)
    }
}

/**
 * What to say when the CLI is not there.
 *
 * Everything else in this panel needs it, so this is not an error to report in passing — it is the
 * whole of what the panel can say, and it should say what to do about it.
 */
@Composable
private fun NotInstalled(ws: Workspace) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(ws.t("aiMissingTitle"), 13.sp, Palette.text, weight = FontWeight.SemiBold)
        Txt(ws.t("aiMissingBody"), 12.sp, Palette.subText)
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth()
                    .background(Palette.holeBg, RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                    .padding(10.dp),
            ) {
                Txt("npm install -g @anthropic-ai/claude-code", 12.sp, Palette.text, mono = true)
            }
        }
        Txt(ws.t("aiMissingAfter"), 11.sp, Palette.faintText)
    }
}

@Composable
private fun Transcript(ws: Workspace, modifier: Modifier) {
    val state = rememberLazyListState()
    // a new turn should be the one you are looking at
    LaunchedEffect(ws.aiMessages.size, ws.aiStreaming) {
        if (ws.aiMessages.isNotEmpty()) state.scrollToItem(ws.aiMessages.lastIndex)
    }

    if (ws.aiMessages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(14.dp)) {
            Txt(ws.t("aiEmpty"), 12.sp, Palette.faintText)
        }
        return
    }

    SelectionContainer {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 10.dp), state = state) {
            itemsIndexed(ws.aiMessages) { index, message ->
                val streaming = ws.aiStreaming && index == ws.aiMessages.lastIndex && !message.fromUser
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Txt(
                        if (message.fromUser) ws.t("aiYou") else ws.t("aiClaude"),
                        10.sp,
                        if (message.fromUser) Palette.accent else Palette.catPlugin,
                        weight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                if (message.fromUser) Palette.holeBg else Palette.dropdownBg,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Txt(
                            message.text.ifBlank { if (streaming) ws.t("aiThinking") else "" },
                            12.sp,
                            if (message.text.isBlank()) Palette.faintText else Palette.text,
                            mono = !message.fromUser,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(ws: Workspace) {
    var text by remember { mutableStateOf("") }

    fun send() {
        val question = text.trim()
        if (question.isEmpty() || ws.aiStreaming) return
        text = ""
        ws.askAi(question)
    }

    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .background(Palette.holeBg, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .padding(8.dp),
        ) {
            if (text.isEmpty()) Txt(ws.t("aiPlaceholder"), 12.sp, Palette.faintText)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Default),
                cursorBrush = SolidColor(Palette.text),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    // Enter sends; Shift-Enter is how you write a second line
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        send()
                        true
                    } else false
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ws.aiStreaming) {
                Txt(ws.t("aiWorking"), 11.sp, Palette.accentSoft)
            } else {
                Box(
                    Modifier.background(Palette.accent, RoundedCornerShape(5.dp))
                        .plainClick { send() }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Txt(ws.t("aiSend"), 11.sp, Palette.holeBg, weight = FontWeight.Medium)
                }
            }
            if (ws.aiMessages.isNotEmpty() && !ws.aiStreaming) {
                Txt(
                    ws.t("aiClear"),
                    11.sp,
                    Palette.dimText,
                    modifier = Modifier.plainClick { ws.clearAi() },
                )
            }
        }
    }
}
