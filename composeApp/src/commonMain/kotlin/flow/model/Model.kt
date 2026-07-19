package flow.model

import kotlinx.serialization.Serializable

@Serializable
data class PortRef(val node: String, val port: String)

@Serializable
data class Edge(
    val id: String,
    val from: PortRef,
    val to: PortRef,
    val active: Boolean = false,
)

@Serializable
data class Node(
    val id: String,
    val type: String,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val inputs: List<String>,
    val outputs: List<String>,
    val params: Map<String, String> = emptyMap(),
    val status: String = "idle", // idle | running | done | error
)

// JSON 내보내기/히스토리 스냅샷 포맷
@Serializable
data class FlowFile(
    val version: Int = 1,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val seq: Int = 1,
)

// 세션 포맷: 열린 탭 목록 + 전역 UI 상태 (IntelliJ식 워크스페이스 복원)
@Serializable
data class Session(
    val openFiles: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val lang: String = "ko",
    val showLeft: Boolean = true,
    val leftTab: String = "project",
    val showProps: Boolean = true,
    val showMinimap: Boolean = true,
)
