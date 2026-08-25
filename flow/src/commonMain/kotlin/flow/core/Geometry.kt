package flow.core

import androidx.compose.ui.geometry.Offset
import flow.model.Node
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

const val GRID = 20f

fun snapF(v: Float): Float = round(v / GRID) * GRID

// port vertical position: cy = 28 + (h-34)·(i+1)/(count+1) + 3 (evenly distributed)
fun portY(node: Node, count: Int, idx: Int): Float =
    node.y + 28f + (node.h - 34f) * (idx + 1) / (count + 1) + 3f

/**
 * The port circle, and how far in from the node's edge it sits.
 *
 * Here rather than beside the drawing, because where the circle is and where an edge ends have to
 * be the same number — they are drawn by different code and would drift apart as two.
 */
const val PORT_SIZE = 15f
const val PORT_INSET = 8f

fun portPos(node: Node, kind: String, idx: Int): Offset {
    val list = if (kind == "in") node.inputs else node.outputs
    // the centre of the port circle, which sits inside the node — an edge that stopped at the
    // node's edge would stop short of the thing it connects to
    val centre = PORT_INSET + PORT_SIZE / 2f
    val x = if (kind == "in") node.x + centre else node.x + node.w - centre
    return Offset(x, portY(node, list.size, idx))
}

// cubic bezier control-point offset: c = max(46, |bx-ax|/2)
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

// default node size: w 180, h = ceil((44 + maxPorts·26 + 16)/grid)·grid
fun sizeForPorts(inCount: Int, outCount: Int): Pair<Float, Float> {
    val maxPorts = maxOf(inCount, outCount, 1)
    return 180f to ceil((44f + maxPorts * 26f + 16f) / GRID) * GRID
}
