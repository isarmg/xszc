package org.sarmg.xszc

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Client approximation of native continuous corners, separate from the server's Mac mini enclosure curve.
 *
 * Construction: cubic shoulders around a circular core, following the geometry in sections
 * 11–12 of https://www.figma.com/blog/desperately-seeking-squircles/ . The default 60% is an
 * initial visual parameter (Figma's iOS preset), not a measurement of SwiftUI's current Path.
 * Unlike the article's final 2:1 handle heuristic, the second handle is solved to match the
 * core's curvature. With room for smoothing, the ideal joins are G2, not G3. When the radius
 * reaches half the short side, smoothing disappears and the native circular limit is used.
 */
@Immutable
internal class IosLikeCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize = topStart,
    bottomEnd: CornerSize = topStart,
    bottomStart: CornerSize = topStart,
    private val smoothing: Float = 0.6f,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): IosLikeCornerShape = IosLikeCornerShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val path = Path()
        val width = size.width
        val height = size.height
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            return Outline.Generic(path)
        }
        val budget = min(width, height) / 2f
        val rtl = layoutDirection == LayoutDirection.Rtl
        val corners = listOf(
            corner(if (rtl) topStart else topEnd, budget),
            corner(if (rtl) bottomStart else bottomEnd, budget),
            corner(if (rtl) bottomEnd else bottomStart, budget),
            corner(if (rtl) topEnd else topStart, budget),
        )
        val vertices = listOf(Offset(width, 0f), Offset(width, height), Offset(0f, height), Offset.Zero)
        corners.forEachIndexed { rotation, corner ->
            val vertex = vertices[rotation]
            fun point(x: Float, y: Float): Offset = vertex + when (rotation) {
                0 -> Offset(x, y)
                1 -> Offset(-y, x)
                2 -> Offset(-x, -y)
                else -> Offset(y, -x)
            }
            val start = point(-corner.extent, 0f)
            if (rotation == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            if (corner.radius == 0f) {
                path.lineTo(vertex.x, vertex.y)
                return@forEachIndexed
            }
            if (corner.smoothed) {
                val first = point(-corner.extent + corner.a, 0f)
                val second = point(-corner.extent + corner.a + corner.b, 0f)
                val end = point(-corner.arcDiagonal - corner.d, corner.d)
                path.cubicTo(first.x, first.y, second.x, second.y, end.x, end.y)
            }
            val center = point(-corner.radius, corner.radius)
            path.arcTo(
                Rect(center.x - corner.radius, center.y - corner.radius,
                    center.x + corner.radius, center.y + corner.radius),
                -90f + corner.alphaDegrees + rotation * 90f,
                90f - 2f * corner.alphaDegrees,
                false,
            )
            if (corner.smoothed) {
                val first = point(0f, corner.extent - corner.a - corner.b)
                val second = point(0f, corner.extent - corner.a)
                val end = point(0f, corner.extent)
                path.cubicTo(first.x, first.y, second.x, second.y, end.x, end.y)
            }
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun corner(requested: Float, budget: Float): Corner {
        val radius = requested.coerceIn(0f, budget)
        if (radius == 0f) return Corner(0f, 0f)
        // Expanded shoulders must fit too. Reduce smoothing before reducing the radius.
        val amount = min(smoothing.toDouble().coerceIn(0.0, 1.0), budget / radius.toDouble() - 1.0)
            .coerceAtLeast(0.0)
        if (amount == 0.0) return Corner(radius, radius)
        val r = radius.toDouble()
        val alpha = PI * amount / 4.0
        val handle = r * tan(alpha / 2.0)
        val c = handle * cos(alpha)
        val d = handle * sin(alpha)
        val diagonal = r * (cos(alpha) - sin(alpha))
        val extent = (1.0 + amount) * r
        // At the cubic/core join, kappa = 2*b*d / (3*(c*c+d*d)^(3/2)) = 1/r.
        val b = 1.5 * handle * handle / (r * sin(alpha))
        val a = extent - diagonal - c - d - b
        return Corner(radius, extent.toFloat(), a.toFloat(), b.toFloat(), d.toFloat(),
            diagonal.toFloat(), (alpha * 180.0 / PI).toFloat(), true)
    }

    private data class Corner(
        val radius: Float,
        val extent: Float,
        val a: Float = 0f,
        val b: Float = 0f,
        val d: Float = 0f,
        val arcDiagonal: Float = 0f,
        val alphaDegrees: Float = 0f,
        val smoothed: Boolean = false,
    )
}
