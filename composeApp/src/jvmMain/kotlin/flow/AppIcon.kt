package flow

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

// 앱 아이콘(로고): 그라디언트 라운드 사각형 + 흐름(노드-연결) 모티프. 코드로 생성.
object AppIcon {
    fun image(size: Int = 256): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val s = size.toFloat()
        val radius = s * 0.22f
        g.paint = GradientPaint(0f, 0f, Color(0x5B, 0x8C, 0xFF), s, s, Color(0x22, 0xC3, 0xA6))
        g.fill(RoundRectangle2D.Float(0f, 0f, s, s, radius, radius))

        // 흐름 모티프: 두 노드 + S 곡선 연결
        g.color = Color(255, 255, 255, 235)
        g.stroke = BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val ax = s * 0.30f; val ay = s * 0.34f
        val cx = s * 0.70f; val cy = s * 0.66f
        val path = Path2D.Float().apply {
            moveTo(ax.toDouble(), ay.toDouble())
            curveTo((s * 0.55).toDouble(), ay.toDouble(), (s * 0.45).toDouble(), cy.toDouble(), cx.toDouble(), cy.toDouble())
        }
        g.draw(path)
        val d = s * 0.13f
        g.fill(Ellipse2D.Float(ax - d / 2, ay - d / 2, d, d))
        g.fill(Ellipse2D.Float(cx - d / 2, cy - d / 2, d, d))

        g.dispose()
        return img
    }
}
