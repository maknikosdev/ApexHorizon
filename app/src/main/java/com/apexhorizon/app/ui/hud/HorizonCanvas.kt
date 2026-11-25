package com.apexhorizon.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.apexhorizon.app.domain.ConfidenceLevel
import com.apexhorizon.app.domain.Geo
import com.apexhorizon.app.domain.HudState
import com.apexhorizon.app.domain.PathPoint
import com.apexhorizon.app.ui.theme.Apex
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pin-hole projection of vehicle-frame points (x right, z forward) onto the screen. */
private class Projector(val w: Float, val h: Float) {
    val cx = w / 2f
    val horizonY = h * 0.40f
    val focal = 0.85f * min(w, 1.6f * h)
    private val camHeight = 1.25f

    fun x(px: Double, z: Double) = cx + focal * px.toFloat() / z.toFloat()
    fun y(z: Double) = horizonY + focal * camHeight / z.toFloat()
    fun scale(z: Double) = focal / z.toFloat()
    fun offset(px: Double, z: Double) = Offset(x(px, z), y(z))
}

private const val Z_MIN = 0.8
private const val HALF_WIDTH = 1.8
private const val DENSE_STEP = 2.0
private const val MAX_RANGE = 200.0

/** Same drawing language as the logo, used when no camera image is available (Sensor Mode). */
@Composable
fun HorizonScene(hud: HudState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Projector(size.width, size.height)
        // Sky
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Apex.NavyDeep, Apex.Navy, Apex.SurfaceHigh),
                startY = 0f, endY = p.horizonY,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, p.horizonY),
        )
        // Horizon glow in neon-orange
        drawRect(
            brush = Brush.radialGradient(
                listOf(Apex.NeonOrange.copy(alpha = 0.38f), Color.Transparent),
                center = Offset(p.cx, p.horizonY), radius = size.width * 0.75f,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, p.horizonY),
        )
        // Ground
        drawRect(
            brush = Brush.verticalGradient(listOf(Apex.Surface.copy(alpha = 0.9f), Apex.NavyDeep), startY = p.horizonY, endY = size.height),
            topLeft = Offset(0f, p.horizonY),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - p.horizonY),
        )
        // Horizon line with dial-like ticks
        drawLine(Apex.CyberBlue.copy(alpha = 0.7f), Offset(0f, p.horizonY), Offset(size.width, p.horizonY), strokeWidth = 2f)
        val tick = size.width / 40f
        var i = 0
        while (i <= 40) {
            val major = i % 5 == 0
            val len = if (major) 14f else 7f
            drawLine(Apex.CyberBlue.copy(alpha = if (major) 0.55f else 0.3f), Offset(i * tick, p.horizonY - len), Offset(i * tick, p.horizonY), strokeWidth = 1.5f)
            i++
        }
        // Receding ground lines that move with the distance travelled (gives a sense of speed).
        val spacing = 14.0
        val phase = hud.distanceM % spacing
        var z = spacing - phase
        while (z < MAX_RANGE) {
            val y = p.y(z)
            if (y < size.height) {
                val a = (0.22f * (1f - (z / MAX_RANGE).toFloat())).coerceIn(0.03f, 0.22f)
                drawLine(Apex.CyberBlue.copy(alpha = a), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.2f)
            }
            z += spacing
        }
    }
}

/** Predicted path, dashes, curve highlight and apex marker. Transparent, so it sits on camera or scene. */
@Composable
fun PathOverlay(hud: HudState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Projector(size.width, size.height)
        val dense = clipFront(densify(hud.path), Z_MIN)
        if (dense.size < 2) return@Canvas

        val low = hud.level == ConfidenceLevel.LOW
        val alphaScale = when (hud.level) {
            ConfidenceLevel.HIGH -> 1f
            ConfidenceLevel.MEDIUM -> 0.8f
            ConfidenceLevel.LOW -> 0.45f
        }

        // Frame the lane as left/right edge polylines.
        val left = ArrayList<PathPoint>(dense.size)
        val right = ArrayList<PathPoint>(dense.size)
        for (i in dense.indices) {
            val a = dense[max(0, i - 1)]
            val b = dense[min(dense.size - 1, i + 1)]
            val tx = b.x - a.x
            val tz = b.z - a.z
            val len = hypot(tx, tz).coerceAtLeast(1e-6)
            val nx = tz / len
            val nz = -tx / len
            left.add(PathPoint(dense[i].x - nx * HALF_WIDTH, dense[i].z - nz * HALF_WIDTH))
            right.add(PathPoint(dense[i].x + nx * HALF_WIDTH, dense[i].z + nz * HALF_WIDTH))
        }

        val ribbon = Path()
        left.forEachIndexed { i, q ->
            val o = p.offset(q.x, q.z)
            if (i == 0) ribbon.moveTo(o.x, o.y) else ribbon.lineTo(o.x, o.y)
        }
        for (i in right.indices.reversed()) {
            val o = p.offset(right[i].x, right[i].z)
            ribbon.lineTo(o.x, o.y)
        }
        ribbon.close()
        drawPath(
            ribbon,
            brush = Brush.verticalGradient(
                listOf(Apex.CyberBlue.copy(alpha = 0.0f), Apex.CyberBlue.copy(alpha = 0.32f * alphaScale)),
                startY = p.horizonY, endY = size.height,
            ),
        )

        // Edge glow + crisp edge. Dashed when confidence is low so it reads as an estimate.
        val dash = if (low) PathEffect.dashPathEffect(floatArrayOf(22f, 16f)) else null
        for (edge in listOf(left, right)) {
            val path = Path()
            edge.forEachIndexed { i, q ->
                val o = p.offset(q.x, q.z)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, Apex.CyberBlue.copy(alpha = 0.22f * alphaScale), style = Stroke(width = 14f, cap = StrokeCap.Round))
            drawPath(path, Apex.IceBlue.copy(alpha = 0.95f * alphaScale), style = Stroke(width = 4f, cap = StrokeCap.Round, pathEffect = dash))
        }

        // Centre dashes that flow towards the driver.
        var s = 0.0
        for (i in 0 until dense.size - 1) {
            val a = dense[i]
            val b = dense[i + 1]
            val segLen = hypot(b.x - a.x, b.z - a.z)
            val mid = s + segLen / 2.0
            if ((mid + hud.distanceM) % 12.0 < 5.0 && a.z > 1.5) {
                val width = (0.22f * p.scale(a.z)).coerceIn(1.5f, 36f)
                drawLine(
                    Color.White.copy(alpha = 0.75f * alphaScale),
                    p.offset(a.x, a.z), p.offset(b.x, b.z), strokeWidth = width, cap = StrokeCap.Butt,
                )
            }
            s += segLen
        }

        // Curve highlight in neon-orange along the part of the path that bends.
        hud.curve?.let { c ->
            val from = c.distanceM
            val arcLen = if (c.angleDeg > 0) c.radiusM * Geo.rad(c.angleDeg) else 40.0
            val to = from + arcLen.coerceIn(30.0, 160.0)
            var d = 0.0
            for (i in 0 until dense.size - 1) {
                val a = dense[i]
                val b = dense[i + 1]
                val segLen = hypot(b.x - a.x, b.z - a.z)
                if (d + segLen >= from && d <= to && a.z > 1.5) {
                    val w = (0.3f * p.scale(a.z)).coerceIn(2f, 40f)
                    drawLine(Apex.NeonOrange.copy(alpha = 0.28f * alphaScale), p.offset(a.x, a.z), p.offset(b.x, b.z), strokeWidth = w * 2.2f, cap = StrokeCap.Round)
                    drawLine(Apex.NeonOrange.copy(alpha = 0.9f * alphaScale), p.offset(a.x, a.z), p.offset(b.x, b.z), strokeWidth = w, cap = StrokeCap.Round)
                }
                d += segLen
            }
            c.apex?.let { ap -> if (ap.z >= 4.0 && ap.z <= MAX_RANGE) drawApex(p, ap, alphaScale) }
        }
    }
}

private fun DrawScope.drawApex(p: Projector, ap: PathPoint, alphaScale: Float) {
    val o = p.offset(ap.x, ap.z)
    val r = (1.1f * p.scale(ap.z)).coerceIn(9f, 46f)
    drawCircle(Apex.NeonOrange.copy(alpha = 0.22f * alphaScale), radius = r * 2.2f, center = o)
    val diamond = Path().apply {
        moveTo(o.x, o.y - r)
        lineTo(o.x + r * 0.8f, o.y)
        lineTo(o.x, o.y + r)
        lineTo(o.x - r * 0.8f, o.y)
        close()
    }
    drawPath(diamond, Apex.NeonOrange.copy(alpha = alphaScale))
    drawPath(diamond, Color.White.copy(alpha = 0.9f * alphaScale), style = Stroke(width = 2f))
}

/** Adds points so the path is sampled every [DENSE_STEP] metres and cuts it at [MAX_RANGE]. */
private fun densify(path: List<PathPoint>): List<PathPoint> {
    if (path.size < 2) return emptyList()
    val out = ArrayList<PathPoint>()
    out.add(path[0])
    var travelled = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val len = hypot(b.x - a.x, b.z - a.z)
        if (len < 1e-6) continue
        val n = max(1, Math.ceil(len / DENSE_STEP).toInt())
        for (k in 1..n) {
            val t = k.toDouble() / n
            out.add(PathPoint(a.x + t * (b.x - a.x), a.z + t * (b.z - a.z)))
        }
        travelled += len
        if (travelled > MAX_RANGE) break
    }
    return out
}

/** Removes the part of the path behind the camera plane, interpolating the crossing point. */
private fun clipFront(pts: List<PathPoint>, zMin: Double): List<PathPoint> {
    val out = ArrayList<PathPoint>()
    for (i in pts.indices) {
        val p = pts[i]
        if (p.z >= zMin) {
            if (out.isEmpty() && i > 0) {
                val q = pts[i - 1]
                val t = (zMin - q.z) / (p.z - q.z)
                out.add(PathPoint(q.x + t * (p.x - q.x), zMin))
            }
            out.add(p)
        }
    }
    return out
}
