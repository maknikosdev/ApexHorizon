package com.apexhorizon.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.R
import com.apexhorizon.app.domain.ConfidenceLevel
import com.apexhorizon.app.domain.CurveAdvice
import com.apexhorizon.app.domain.GpsStatus
import com.apexhorizon.app.domain.HudState
import com.apexhorizon.app.domain.Turn
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.DriveInfo
import com.apexhorizon.app.ui.DriveMode
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.hud.CameraLayer
import com.apexhorizon.app.ui.hud.HorizonScene
import com.apexhorizon.app.ui.hud.PathOverlay
import com.apexhorizon.app.ui.hud.SpeedDial
import com.apexhorizon.app.ui.theme.Apex
import kotlin.math.roundToInt

@Composable
fun DriveScreen(vm: AppViewModel) {
    val hud by vm.hud.collectAsStateWithLifecycle()
    val info by vm.drive.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val confirmEnd by vm.confirmEnd.collectAsStateWithLifecycle()
    val current = info

    // Keep the screen awake while driving, and release sensors when the app is not visible.
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onForeground(true)
                Lifecycle.Event.ON_STOP -> vm.onForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            view.keepScreenOn = false
            owner.lifecycle.removeObserver(observer)
        }
    }

    if (current != null) {
        Box(Modifier.fillMaxSize().background(Apex.NavyDeep)) {
            HorizonScene(hud, Modifier.fillMaxSize())
            if (current.cameraWanted) {
                CameraLayer(
                    modifier = Modifier.fillMaxSize(),
                    onLuma = { vm.onLuma(it) },
                    onActive = { vm.setCameraActive(it) },
                )
            }
            PathOverlay(hud, Modifier.fillMaxSize())
            HudChrome(
                hud = hud,
                info = current,
                mph = settings.useMph,
                onEnd = { vm.back() },
                onSpeed = { vm.setReplaySpeed(it) },
            )
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { vm.dismissEndDialog() },
            title = { Text(stringResource(R.string.end_drive_title)) },
            text = { Text(stringResource(R.string.end_drive_body)) },
            confirmButton = { TextButton(onClick = { vm.endDrive() }) { Text(stringResource(R.string.action_end_drive), color = Apex.NeonOrange) } },
            dismissButton = { TextButton(onClick = { vm.dismissEndDialog() }) { Text(stringResource(R.string.action_keep_driving)) } },
        )
    }
}

@Composable
private fun HudChrome(hud: HudState, info: DriveInfo, mph: Boolean, onEnd: () -> Unit, onSpeed: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val dial: Dp = minOf(230.dp, maxHeight * 0.40f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBar(hud, info)
            if (info.mode != DriveMode.LIVE) ReplayBar(info, onSpeed)
            AnimatedVisibility(hud.curve != null && hud.confidence > 0.05, enter = fadeIn(), exit = fadeOut()) {
                hud.curve?.let { CurveCard(it, hud, mph) }
            }
            AnimatedVisibility(hud.notice != null, enter = fadeIn(), exit = fadeOut()) {
                hud.notice?.let { NoticeBanner(it) }
            }
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                ConfidenceMeter(hud, Modifier.width(92.dp))
                SpeedDial(hud.speedMps, mph, hud.dynamics, dial)
                SideReadout(hud, info.routeTotalM, mph, Modifier.width(92.dp))
            }
            SecondaryButton(
                stringResource(if (info.mode == DriveMode.LIVE) R.string.action_end_drive else R.string.action_stop),
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun StatusBar(hud: HudState, info: DriveInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val cam = when {
            info.mode != DriveMode.LIVE -> "Simulated" to Apex.CyberBlue
            info.cameraActive -> "Active" to Apex.Good
            info.cameraWanted -> "Starting" to Apex.Amber
            else -> "Off · Sensor Mode" to Apex.CyberBlue
        }
        val gps = when (hud.gps) {
            GpsStatus.ACQUIRING -> "Acquiring" to Apex.Amber
            GpsStatus.STRONG -> "Strong" to Apex.Good
            GpsStatus.WEAK -> "Weak" to Apex.Amber
            GpsStatus.LOST -> "Lost" to Apex.NeonOrange
        }
        val route = when {
            hud.onRoute -> "On route" to Apex.Good
            info.hasRoute -> "Off route" to Apex.Amber
            else -> "Predictive" to Apex.CyberBlue
        }
        Chip("CAM", cam.first, cam.second, Modifier.weight(1f))
        Chip("GPS", gps.first, gps.second, Modifier.weight(1f))
        Chip("ROUTE", route.first, route.second, Modifier.weight(1f))
    }
}

@Composable
private fun Chip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Apex.NavyDeep.copy(alpha = 0.72f)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(color, 7.dp)
            Text(value, color = Apex.TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReplayBar(info: DriveInfo, onSpeed: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Apex.NavyDeep.copy(alpha = 0.72f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (info.mode == DriveMode.DEMO) stringResource(R.string.badge_demo) else stringResource(R.string.badge_replay),
                color = Apex.NavyDeep,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Apex.NeonOrange).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(info.routeLabel, color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOf(1, 2, 4).forEach { s ->
                val selected = info.replaySpeed == s
                Text(
                    "${s}x",
                    color = if (selected) Apex.NavyDeep else Apex.CyberBlue,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Apex.CyberBlue else Color.Transparent)
                        .clickable { onSpeed(s) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { info.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Apex.CyberBlue,
            trackColor = Apex.Outline,
        )
    }
}

@Composable
private fun CurveCard(curve: CurveAdvice, hud: HudState, mph: Boolean) {
    val estimated = hud.level == ConfidenceLevel.LOW
    val side = if (curve.turn == Turn.LEFT) stringResource(R.string.curve_left) else stringResource(R.string.curve_right)
    val headline = if (curve.distanceM < 15.0) {
        stringResource(R.string.curve_now, side)
    } else {
        stringResource(R.string.curve_in, side, Units.shortDistance(curve.distanceM, mph))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Apex.NavyDeep.copy(alpha = 0.8f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TurnArrow(curve.turn, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (estimated) "Est. " else "") + headline,
                color = Apex.TextPrimary, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp,
            )
            curve.advisedKmh?.let {
                Text(
                    stringResource(R.string.curve_advice, Units.advisedSpeed(it, mph), Units.speedLabel(mph)),
                    color = Apex.NeonOrange, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TurnArrow(turn: Turn, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val dir = if (turn == Turn.LEFT) -1f else 1f
        val c = Apex.NeonOrange
        // Bent arrow: up, then curving to the side, with an arrow head.
        val body = Path().apply {
            moveTo(s * 0.5f, s * 0.92f)
            lineTo(s * 0.5f, s * 0.5f)
            quadraticTo(s * 0.5f, s * 0.2f, s * (0.5f + 0.32f * dir), s * 0.2f)
        }
        drawPath(body, c, style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tipX = s * (0.5f + 0.42f * dir)
        val head = Path().apply {
            moveTo(tipX, s * 0.2f)
            lineTo(tipX - dir * s * 0.18f, s * 0.07f)
            lineTo(tipX - dir * s * 0.18f, s * 0.33f)
            close()
        }
        drawPath(head, c)
    }
}

@Composable
private fun NoticeBanner(text: String) {
    Text(
        text,
        color = Apex.TextPrimary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Apex.NeonOrange.copy(alpha = 0.22f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ConfidenceMeter(hud: HudState, modifier: Modifier = Modifier) {
    val color = when (hud.level) {
        ConfidenceLevel.HIGH -> Apex.CyberBlue
        ConfidenceLevel.MEDIUM -> Apex.Amber
        ConfidenceLevel.LOW -> Apex.NeonOrange
    }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Apex.NavyDeep.copy(alpha = 0.72f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.confidence), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
        Text("${(hud.confidence * 100).roundToInt()}%", color = color, style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { hud.confidence.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = color,
            trackColor = Apex.Outline,
        )
        Text(hud.level.name, color = color, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
    }
}

@Composable
private fun SideReadout(hud: HudState, routeTotalM: Double, mph: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Apex.NavyDeep.copy(alpha = 0.72f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.load), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
        Text(String.format(java.util.Locale.US, "%.2f g", hud.gLoad), color = Apex.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.trip), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
        Text(Units.distance(hud.distanceM, mph), color = Apex.TextPrimary, style = MaterialTheme.typography.labelMedium)
        if (routeTotalM > 0 && hud.routeProgress >= 0) {
            Text(stringResource(R.string.to_go), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp)
            Text(
                Units.distance((routeTotalM * (1.0 - hud.routeProgress)).coerceAtLeast(0.0), mph),
                color = Apex.NeonOrange, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
