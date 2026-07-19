package flow

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

// App icon (logo): gradient rounded square + flow (node-connection) motif. Generated in code.
object AppIcon {
    fun image(size: Int = 256): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val s = size.toFloat()
        // leave margin per macOS icon conventions (filling the canvas looks bigger than other icons)
        val pad = s * 0.11f
        val inner = s - pad * 2
        val radius = inner * 0.28f
        g.paint = GradientPaint(pad, pad, Color(0x5B, 0x8C, 0xFF), s - pad, s - pad, Color(0x22, 0xC3, 0xA6))
        g.fill(RoundRectangle2D.Float(pad, pad, inner, inner, radius, radius))

        // flow motif: two nodes + S-curve connection (relative to the inner box)
        fun px(f: Float) = pad + inner * f
        g.color = Color(255, 255, 255, 235)
        g.stroke = BasicStroke(inner * 0.06f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val ax = px(0.30f); val ay = px(0.34f)
        val cx = px(0.70f); val cy = px(0.66f)
        val path = Path2D.Float().apply {
            moveTo(ax.toDouble(), ay.toDouble())
            curveTo(px(0.55f).toDouble(), ay.toDouble(), px(0.45f).toDouble(), cy.toDouble(), cx.toDouble(), cy.toDouble())
        }
        g.draw(path)
        val d = inner * 0.14f
        g.fill(Ellipse2D.Float(ax - d / 2, ay - d / 2, d, d))
        g.fill(Ellipse2D.Float(cx - d / 2, cy - d / 2, d, d))

        g.dispose()
        return img
    }
}
