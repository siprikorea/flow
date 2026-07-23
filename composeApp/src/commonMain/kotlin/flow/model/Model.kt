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

// JSON export / history snapshot format
@Serializable
data class FlowFile(
    val version: Int = 1,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val seq: Int = 1,
)

// Session format: open tabs + global UI state (IntelliJ-style workspace restore)
@Serializable
data class Session(
    val openFiles: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val lang: String = "ko",
    val showLeft: Boolean = true,
    val leftTab: String = "project",
    val showProps: Boolean = true,
    val showMinimap: Boolean = true,
    val leftWidth: Float = 240f,
    val propsWidth: Float = 268f,
)
