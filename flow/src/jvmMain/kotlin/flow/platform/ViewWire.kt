package flow.platform

import flow.extension.host.Wire
import flow.model.DrawOp
import flow.model.Drawing
import java.io.DataInputStream

/**
 * Reads back what a view drew.
 *
 * Kept apart from [ExtensionLoader] because this is the half of the drawing protocol the app owns,
 * and the worker's half is written in another language in another module: the two only agree
 * because a test drives a real worker through this.
 */
internal object ViewWire {

    fun readDrawing(input: DataInputStream): Drawing {
        val contentHeight = input.readFloat()
        val declared = input.readInt()
        val ops = ArrayList<DrawOp>(declared.coerceIn(0, 1024))
        // the count was written before the ops, but the stream is the authority on how many arrived
        while (input.available() > 0) ops.add(readOp(input))
        return Drawing(contentHeight, ops)
    }

    private fun readOp(input: DataInputStream): DrawOp = when (val op = input.readInt()) {
        Wire.DRAW_TEXT -> DrawOp.Text(
            input.readFloat(), input.readFloat(), Wire.readString(input),
            input.readFloat(), input.readInt(), input.readBoolean(),
        )
        Wire.DRAW_RECT -> DrawOp.Rect(
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
            input.readInt(), input.readBoolean(),
        )
        Wire.DRAW_LINE -> DrawOp.Line(
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
            input.readInt(), input.readFloat(),
        )
        Wire.DRAW_IMAGE -> DrawOp.Image(
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
            Wire.readBytes(input) ?: ByteArray(0),
        )
        // the rest of the stream is no longer parseable, so stop rather than guess
        else -> error("the view sent a drawing command this build does not know ($op)")
    }
}
