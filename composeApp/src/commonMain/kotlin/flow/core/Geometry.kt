package flow.core

import androidx.compose.ui.geometry.Offset
import flow.model.Node
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

const val GRID = 20f

fun snapF(v: Float): Float = round(v / GRID) * GRID

// 포트 세로 위치: cy = 28 + (h-34)·(i+1)/(count+1) + 3 (균등 분배)
fun portY(node: Node, count: Int, idx: Int): Float =
    node.y + 28f + (node.h - 34f) * (idx + 1) / (count + 1) + 3f

fun portPos(node: Node, kind: String, idx: Int): Offset {
    val list = if (kind == "in") node.inputs else node.outputs
    val x = if (kind == "in") node.x - 0.5f else node.x + node.w + 0.5f
    return Offset(x, portY(node, list.size, idx))
}

// cubic bezier 제어점 오프셋: c = max(46, |bx-ax|/2)
fun bezierCtrl(a: Offset, b: Offset): Float = max(46f, abs(b.x - a.x) / 2f)

fun bezierPoint(a: Offset, b: Offset, t: Float): Offset {
    val c = bezierCtrl(a, b)
    val p1 = Offset(a.x + c, a.y)
    val p2 = Offset(b.x - c, b.y)
    val u = 1 - t
    return Offset(
        u * u * u * a.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * b.x,
        u * u * u * a.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * b.y,
    )
}

// 노드 기본 크기: w 180, h = ceil((44 + maxPorts·26 + 16)/grid)·grid
fun sizeForPorts(inCount: Int, outCount: Int): Pair<Float, Float> {
    val maxPorts = maxOf(inCount, outCount, 1)
    return 180f to ceil((44f + maxPorts * 26f + 16f) / GRID) * GRID
}
