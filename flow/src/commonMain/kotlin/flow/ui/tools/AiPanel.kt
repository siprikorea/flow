package flow.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Spacer
import flow.model.isHttpProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.platform.Platform
import flow.ui.common.FlowMarkdown
import flow.ui.common.Picker
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
    // Only the Claude provider is a command that has to be installed; the rest answer at an
    // address, and one being unreachable is something a turn reports rather than something that
    // hides the panel.
    val installed = remember(ws.aiProvider) { isHttpProvider(ws.aiProvider) || Platform.aiCliPath() != null }
    // what the picker offers, re-read whenever the provider, its address or its key changes
    LaunchedEffect(ws.aiProvider, ws.aiUrl, ws.aiApiKey) { ws.refreshAiModels() }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(ws.t("tabAi"))
        when {
            !installed -> Notice(ws.t("aiMissingTitle"), ws.t("aiMissingBody"), ws.t("aiMissingAfter")) {
                Command("npm install -g @anthropic-ai/claude-code")
            }
            // there is nowhere to read a flow from or write one to, and every answer would be an
            // apology for that — so ask for the folder instead of taking the question
            !ws.aiReady -> Notice(ws.t("aiNoProjectTitle"), ws.t("aiNoProjectBody"), null) {
                Box(
                    Modifier.background(Palette.accent, RoundedCornerShape(5.dp))
                        .plainClick { Platform.pickFolder()?.let { ws.openProject(it) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Txt(ws.t("openFolder"), 11.sp, Palette.holeBg, weight = FontWeight.Medium)
                }
            }
            else -> {
                Transcript(ws, Modifier.weight(1f))
                Composer(ws)
            }
        }
    }
}

/** Something the panel has to say instead of a conversation, with the way out of it underneath. */
@Composable
private fun Notice(title: String, body: String, footnote: String?, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(title, 13.sp, Palette.text, weight = FontWeight.SemiBold)
        Txt(body, 12.sp, Palette.subText)
        action()
        footnote?.let { Txt(it, 11.sp, Palette.faintText) }
    }
}

@Composable
private fun Command(text: String) {
    SelectionContainer {
        Box(
            Modifier.fillMaxWidth()
                .background(Palette.holeBg, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .padding(10.dp),
        ) {
            Txt(text, 12.sp, Palette.text, mono = true)
        }
    }
}

@Composable
private fun Transcript(ws: Workspace, modifier: Modifier) {
    val state = rememberLazyListState()

    // Follows new content only while already at the bottom — scrolling up to reread an earlier
    // answer shouldn't get yanked back down by the next streamed chunk. Starts true (a fresh
    // conversation opens at the bottom); flips whenever a scroll gesture ends, to wherever that
    // gesture actually left the view — including our own programmatic scrolls below, which is
    // what keeps this true again once the user scrolls back down themselves.
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) stickToBottom = !state.canScrollForward
        }
    }
    // A new turn, or the next chunk of a streaming one — scrollOffset is huge rather than the
    // default 0 so a reply taller than the panel lands on its bottom, not its top, and re-lands
    // there on every chunk while it keeps growing.
    LaunchedEffect(ws.aiMessages) {
        if (stickToBottom && ws.aiMessages.isNotEmpty()) {
            state.scrollToItem(ws.aiMessages.lastIndex, Int.MAX_VALUE)
        }
    }

    if (ws.aiMessages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(14.dp)) {
            Txt(ws.t("aiEmpty"), 12.sp, Palette.faintText)
        }
        return
    }

    // the selection container is the child the height belongs to: with the weight on the list
    // inside it, this grew to fit the conversation and pushed the composer off the bottom
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = state) {
            itemsIndexed(ws.aiMessages) { index, message ->
                val streaming = ws.aiStreaming && index == ws.aiMessages.lastIndex && !message.fromUser
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Txt(
                        if (message.fromUser) ws.t("aiYou") else ws.t("aiClaude"),
                        10.sp,
                        if (message.fromUser) Palette.accent else Palette.catPlugin,
                        weight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                if (message.fromUser) Palette.holeBg else Palette.dropdownBg,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(8.dp),
                    ) {
                        when {
                            message.text.isBlank() ->
                                Txt(if (streaming) ws.t("aiThinking") else "", 12.sp, Palette.faintText)
                            // the user's own question is plain text; Claude's answer is Markdown
                            message.fromUser -> Txt(message.text, 12.sp, Palette.text)
                            else -> FlowMarkdown(message.text, streaming = streaming)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The box a question is typed into, with the button that sends it inside the same border.
 *
 * One control rather than a field and a button beside it: while a turn is running that button is
 * how it is stopped, and having it in the same place either way is what makes stopping obvious.
 */
@Composable
private fun Composer(ws: Workspace) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    fun send() {
        val question = text.trim()
        if (question.isEmpty() || ws.aiStreaming) return
        text = ""
        ws.askAi(question)
    }

    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(Palette.holeBg, RoundedCornerShape(10.dp))
                .border(
                    if (focused || ws.aiStreaming) 1.5.dp else 1.dp,
                    if (focused || ws.aiStreaming) Palette.accent else Palette.border,
                    RoundedCornerShape(10.dp),
                )
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(end = 8.dp)) {
                if (text.isEmpty()) Txt(ws.t("aiPlaceholder"), 12.sp, Palette.faintText)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !ws.aiStreaming,
                    textStyle = TextStyle(color = Palette.text, fontSize = 12.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            // Enter sends; Shift-Enter is how a second line is written
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                send()
                                true
                            } else false
                        },
                )
            }
            SendButton(
                running = ws.aiStreaming,
                enabled = ws.aiStreaming || text.isNotBlank(),
                label = if (ws.aiStreaming) ws.t("aiStop") else ws.t("aiSend"),
            ) {
                if (ws.aiStreaming) ws.stopAi() else send()
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ModelPicker(ws)
            Spacer(Modifier.weight(1f))
            if (ws.aiMessages.isNotEmpty() && !ws.aiStreaming) {
                Txt(ws.t("aiClear"), 11.sp, Palette.dimText, modifier = Modifier.plainClick { ws.clearAi() })
            }
        }
    }
}

/**
 * Which model answers, under the box the question is typed into.
 *
 * Here rather than only in Settings because it is a per-question choice: a quick edit and a whole
 * flow to design want different models, and going through a settings screen between them is enough
 * friction that nobody does. It offers whatever the current provider has — the fixed Claude list,
 * or what the server says it can run — and writes to that provider's own setting, so switching
 * provider and back returns to the model that was in use, not the other one's id.
 */
@Composable
private fun ModelPicker(ws: Workspace) {
    val options = ws.aiModelOptions
    Picker(
        options = options,
        selected = ws.aiModelChoice,
        // the composer sits on the floor of the panel, so the menu belongs above it
        above = true,
        onSelect = { ws.aiModelChoice = it },
    ) { label, _ ->
        Row(
            Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(label.ifBlank { ws.t("aiModelAuto") }, 11.sp, Palette.dimText, maxLines = 1)
            Txt(" \u25be", 9.sp, Palette.faintText)
        }
    }
}

/** An arrow to send, a square to stop — one round button that swaps which it is. */
@Composable
private fun SendButton(running: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val background = when {
        running -> Palette.errorSoft
        enabled -> Palette.accent
        else -> Palette.border
    }
    Box(
        Modifier.size(26.dp)
            .background(background, CircleShape)
            .then(if (enabled) Modifier.plainClick(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val c = size.width / 2f
            if (running) {
                drawRect(
                    color = Palette.holeBg,
                    topLeft = Offset(c - size.width * 0.28f, c - size.width * 0.28f),
                    size = Size(size.width * 0.56f, size.width * 0.56f),
                )
            } else {
                // an arrow pointing up: the stem, then the two strokes of the head
                val stroke = size.width * 0.16f
                drawLine(Palette.holeBg, Offset(c, size.height * 0.86f), Offset(c, size.height * 0.16f), stroke, StrokeCap.Round)
                drawLine(Palette.holeBg, Offset(size.width * 0.18f, size.height * 0.48f), Offset(c, size.height * 0.14f), stroke, StrokeCap.Round)
                drawLine(Palette.holeBg, Offset(size.width * 0.82f, size.height * 0.48f), Offset(c, size.height * 0.14f), stroke, StrokeCap.Round)
            }
        }
    }
    // the label is not drawn; it is what the button is, for anything reading the interface aloud
    if (label.isEmpty()) Unit
}
