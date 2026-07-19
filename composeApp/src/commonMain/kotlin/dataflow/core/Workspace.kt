package dataflow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dataflow.model.CompDef
import dataflow.model.FlowFile
import dataflow.model.Node
import dataflow.model.Session
import dataflow.model.asComponent
import dataflow.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

// 워크스페이스: 전역 UI 상태 + 열린 문서(탭) + 프로젝트 파일 목록 + 컴포넌트 레지스트리
class Workspace(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // 전역 UI (문서 간 공유)
    var lang by mutableStateOf("en") // 기본 언어: 영어
    var showLeft by mutableStateOf(true)
    var leftTab by mutableStateOf("project") // project | modules
    var showProps by mutableStateOf(true)
    var showMinimap by mutableStateOf(true)
    var menu by mutableStateOf<String?>(null)
    var dragModule by mutableStateOf<DragModule?>(null)
    var saveTime by mutableStateOf<String?>(null)

    // 저장 확인 다이얼로그 대상 (닫으려는 탭 인덱스). null 이면 다이얼로그 없음.
    var closeConfirm by mutableStateOf<Int?>(null)

    // 설정 화면 표시 여부 (로고 메뉴 > 설정)
    var showSettings by mutableStateOf(false)

    // 열린 탭
    val docs = mutableStateListOf<EditorState>()
    var activeIndex by mutableStateOf(0)
    val active: EditorState? get() = docs.getOrNull(activeIndex)

    // 프로젝트 파일 + 컴포넌트
    var files by mutableStateOf<List<String>>(emptyList())
    var components by mutableStateOf<List<CompDef>>(emptyList())
    val dirLabel: String = Platform.flowsDirLabel()

    fun t(key: String) = dataflow.i18n.tr(lang, key)

    init {
        Platform.loadPlugins() // 플러그인이 제공하는 컴포넌트를 프로젝트 폴더에 반영
        refreshFiles()
        loadSession()
    }

    /* ───────── 프로젝트 파일 ───────── */

    fun refreshFiles() {
        files = Platform.listFlows()
        components = files.mapNotNull { name ->
            val raw = Platform.readFlow(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)
        }
    }

    fun findComp(file: String): CompDef? = components.find { it.file == file }

    fun isComponentFile(file: String): Boolean = components.any { it.file == file }

    /* ───────── 탭 열기/닫기 ───────── */

    fun openFile(name: String) {
        val i = docs.indexOfFirst { it.fileName == name }
        if (i >= 0) { activeIndex = i; return }
        val raw = Platform.readFlow(name)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } ?: FlowFile()
        val doc = EditorState(scope, this, name).also { it.load(flow) }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    fun newDoc() {
        val name = nextName("flow")
        val doc = EditorState(scope, this, name).also { it.load(FlowFile()) }
        Platform.writeFlow(name, doc.flowJson())
        docs.add(doc)
        activeIndex = docs.lastIndex
        refreshFiles()
    }

    // 새 컴포넌트: 입력/출력 경계 노드를 미리 배치한 문서
    fun newComponent() {
        val name = nextName("comp")
        val cin = Node(
            id = "cin_1", type = "cin", label = "in",
            x = 60f, y = 120f, w = 160f, h = 80f, inputs = emptyList(), outputs = listOf("out"),
        )
        val cout = Node(
            id = "cout_2", type = "cout", label = "out",
            x = 420f, y = 120f, w = 160f, h = 80f, inputs = listOf("in"), outputs = emptyList(),
        )
        val doc = EditorState(scope, this, name).also { it.load(FlowFile(1, listOf(cin, cout), emptyList(), 3)) }
        Platform.writeFlow(name, doc.flowJson())
        docs.add(doc)
        activeIndex = docs.lastIndex
        refreshFiles()
    }

    fun select(i: Int) {
        if (docs.isEmpty()) return
        activeIndex = i.coerceIn(0, docs.lastIndex)
    }

    fun save(i: Int) {
        docs.getOrNull(i)?.save()
    }

    fun saveActive() {
        active?.save()
    }

    fun saveAll() {
        docs.forEach { if (it.dirty) it.save() }
    }

    // 탭 닫기 요청: 수정 사항이 있으면 저장 여부를 먼저 묻는다.
    fun requestClose(i: Int) {
        if (i !in docs.indices) return
        if (docs[i].dirty) closeConfirm = i else removeDoc(i)
    }

    fun confirmSaveAndClose() {
        closeConfirm?.let { i ->
            docs.getOrNull(i)?.save()
            removeDoc(i)
        }
        closeConfirm = null
    }

    fun confirmDiscardAndClose() {
        closeConfirm?.let { removeDoc(it) }
        closeConfirm = null
    }

    fun cancelClose() {
        closeConfirm = null
    }

    private fun removeDoc(i: Int) {
        if (i !in docs.indices) return
        docs.removeAt(i)
        if (activeIndex >= docs.size) activeIndex = (docs.size - 1).coerceAtLeast(0)
    }

    private fun nextName(base: String): String {
        val existing = (files + docs.map { it.fileName }).toSet()
        var n = 1
        while ("$base-$n.json" in existing) n++
        return "$base-$n.json"
    }

    /* ───────── 세션 ───────── */

    fun sessionJson(): String = json.encodeToString(
        Session(docs.map { it.fileName }, activeIndex, lang, showLeft, leftTab, showProps, showMinimap)
    )

    private fun loadSession() {
        val s = Platform.loadSession()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        if (s != null) {
            lang = s.lang
            showLeft = s.showLeft
            leftTab = s.leftTab
            showProps = s.showProps
            showMinimap = s.showMinimap
            s.openFiles.filter { Platform.readFlow(it) != null }.forEach { openFile(it) }
            activeIndex = s.activeIndex.coerceIn(0, (docs.size - 1).coerceAtLeast(0))
        }
        if (docs.isEmpty()) {
            if (files.isNotEmpty()) openFile(files.first()) else newDoc()
        }
    }
}
