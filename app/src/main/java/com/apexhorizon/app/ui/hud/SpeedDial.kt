package com.apexhorizon.app.ui.hud

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexhorizon.app.domain.Dynamics
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.theme.Apex
import kotlin.math.cos
import kotlin.math.sin

/**
 * Speed read-out in the style of the logo dial: a 270° ticked arc that fades from cyber-blue to
 * neon-orange. Braking only shifts the colour tone — no alarms, no flashing.
 */
@Composable
fun SpeedDial(speedMps: Double, mph: Boolean, dynamics: Dynamics, size: Dp, modifier: Modifier = Modifier) {
    val maxScale = if (mph) 90f else 140f
    val shown = Units.speed(speedMps, mph)
    val fraction by animateFloatAsState((shown / maxScale).coerceIn(0f, 1f), tween(250), label = "dial")
    val tone by animateColorAsState(
        when (dynamics) {
            Dynamics.HARD_BRAKING -> Apex.NeonOrange
            Dynamics.BRAKING -> Apex.Amber
            Dynamics.CORNERING -> Apex.IceBlue
            else -> Apex.CyberBlue
        },
        tween(400), label = "tone",
    )
    val label = when (dynamics) {
        Dynamics.SMOOTH -> "STEADY"
        Dynamics.ACCELERATING -> "ACCELERATING"
        Dynamics.BRAKING -> "BRAKING"
        Dynamics.HARD_BRAKING -> "FIRM BRAKING"
        Dynamics.CORNERING -> "CORNERING"
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.045f
            val radius = this.size.minDimension / 2f - stroke * 1.6f
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            // Backplate
            drawCircle(Apex.NavyDeep.copy(alpha = 0.72f), radius = this.size.minDimension / 2f)
            // Track
            drawArc(Apex.Outline.copy(alpha = 0.6f), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // Ticks
            val ticks = 54
            for (i in 0..ticks) {
                val f = i / ticks.toFloat()
                val ang = Math.toRadians((135f + 270f * f).toDouble())
                val major = i % 6 == 0
                val outer = radius + stroke * 1.25f
                val inner = outer - if (major) stroke * 1.5f else stroke * 0.8f
                val c = lerp(Apex.CyberBlue, Apex.NeonOrange, f).copy(alpha = if (f <= fraction) 1f else 0.35f)
                drawLine(
                    c,
                    Offset(center.x + (inner * cos(ang)).toFloat(), center.y + (inner * sin(ang)).toFloat()),
                    Offset(center.x + (outer * cos(ang)).toFloat(), center.y + (outer * sin(ang)).toFloat()),
                    strokeWidth = if (major) 3f else 1.6f,
                )
            }
            // Progress arc
            rotate(135f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Apex.CyberBlue,
                        0.35f to Apex.IceBlue,
                        0.6f to Apex.Amber,
                        0.75f to Apex.NeonOrange,
                        1f to Apex.NeonOrange,
                    ),
                    startAngle = 0f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // Tone ring (changes colour with dynamics)
            drawCircle(tone.copy(alpha = 0.55f), radius = radius - stroke * 1.6f, style = Stroke(2.5f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "$shown",
                color = Apex.TextPrimary,
                fontSize = (size.value * 0.30f).sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(Units.speedLabel(mph).uppercase(), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium)
            Text(label, color = tone, style = MaterialTheme.typography.labelMedium, fontSize = (size.value * 0.05f).sp)
        }
    }
}
