package com.apexhorizon.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.R
import com.apexhorizon.app.domain.EventType
import com.apexhorizon.app.domain.RunSummary
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.theme.Apex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SummaryScreen(vm: AppViewModel) {
    val run by vm.summary.collectAsStateWithLifecycle()
    val saved by vm.summarySaved.collectAsStateWithLifecycle()
    val canSave by vm.summaryCanSave.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val r = run
    ScreenScaffold(title = stringResource(R.string.summary_title), onBack = { vm.back() }) {
        if (r == null) {
            Text(stringResource(R.string.summary_empty), color = Apex.TextDim, modifier = Modifier.padding(20.dp))
        } else {
            val mph = settings.useMph
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(r.title, style = MaterialTheme.typography.headlineMedium, color = Apex.TextPrimary)
                Text(
                    SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.US).format(Date(r.startedAtMs)) +
                        if (r.isDemo) " · Demo" else "",
                    color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(stringResource(R.string.stat_distance), Units.distance(r.distanceM, mph), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_duration), Units.duration(r.durationMs), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(stringResource(R.string.stat_avg), Units.speedText(r.avgSpeedMps, mph), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_max), Units.speedText(r.maxSpeedMps, mph), Modifier.weight(1f))
                }
                ApexCard {
                    Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                    Timeline(r, mph)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(Apex.NeonOrange)
                            Text(stringResource(R.string.event_hard_brake), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(Apex.IceBlue)
                            Text(stringResource(R.string.event_sharp_corner), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                ApexCard {
                    Text(stringResource(R.string.moments_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                    if (r.events.isEmpty()) {
                        Text(stringResource(R.string.moments_none), color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        r.events.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusDot(if (e.type == EventType.HARD_BRAKE) Apex.NeonOrange else Apex.IceBlue)
                                Text(
                                    stringResource(if (e.type == EventType.HARD_BRAKE) R.string.event_hard_brake else R.string.event_sharp_corner) +
                                        " · " + Units.duration(e.tMs) + " · " + Units.speedText(e.speedMps, mph) +
                                        " · " + Units.distance(e.distanceM, mph),
                                    color = Apex.TextPrimary, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                if (canSave) {
                    PrimaryButton(
                        stringResource(if (saved) R.string.saved_to_history else R.string.action_save_run),
                        onClick = { vm.saveSummary() },
                        enabled = !saved,
                    )
                } else if (!r.isDemo && !saved) {
                    Text(stringResource(R.string.run_too_short), color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
                if (r.track.size >= 2) {
                    SecondaryButton(stringResource(R.string.action_replay), onClick = { vm.replayRun(r) })
                }
                SecondaryButton(stringResource(R.string.action_done), onClick = { vm.back() })
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    ApexCard(modifier) {
        Text(label, color = Apex.TextDim, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Apex.TextPrimary, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun Timeline(run: RunSummary, mph: Boolean) {
    val track = run.track
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        if (track.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val tMax = track.last().tMs.toFloat().coerceAtLeast(1f)
        val vMax = (track.maxOf { it.speedMps }.toFloat() * 1.15f).coerceAtLeast(5f)
        fun px(t: Long) = w * (t / tMax)
        fun py(v: Double) = h - h * (v.toFloat() / vMax)

        // Horizontal guides
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Apex.Outline.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val line = Path()
        val area = Path()
        track.forEachIndexed { i, p ->
            val x = px(p.tMs)
            val y = py(p.speedMps)
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(px(track.last().tMs), h)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(Apex.CyberBlue.copy(alpha = 0.4f), Apex.CyberBlue.copy(alpha = 0.02f))))
        drawPath(line, Apex.IceBlue, style = Stroke(width = 3f))
        run.events.forEach { e ->
            val x = px(e.tMs)
            val nearest = track.minByOrNull { kotlin.math.abs(it.tMs - e.tMs) }
            val y = if (nearest != null) py(nearest.speedMps) else h / 2
            val c = if (e.type == EventType.HARD_BRAKE) Apex.NeonOrange else Apex.IceBlue
            drawCircle(c.copy(alpha = 0.3f), radius = 16f, center = Offset(x, y))
            drawCircle(c, radius = 8f, center = Offset(x, y))
        }
    }
}
