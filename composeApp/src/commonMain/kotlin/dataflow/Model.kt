package dataflow

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

// 자동 저장 포맷
@Serializable
data class SavedState(
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val seq: Int = 1,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    val lang: String = "ko",
)

data class Sel(val kind: String, val id: String) // kind: node | edge
