package com.apexhorizon.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.R
import com.apexhorizon.app.sensors.Capabilities
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.Screen
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.theme.Apex

private enum class StartStep { Idle, Start, WaitLocation, WaitCamera }

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val route by vm.importedRoute.collectAsStateWithLifecycle()
    val trip by vm.navTarget.collectAsStateWithLifecycle()
    var caps by remember { mutableStateOf(Capabilities.read(context)) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) caps = Capabilities.read(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    var step by remember { mutableStateOf(StartStep.Idle) }
    var cameraAsked by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showGpsOff by remember { mutableStateOf(false) }
    var showLocationDenied by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        caps = Capabilities.read(context)
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || caps.locationGranted
        if (step == StartStep.WaitLocation) {
            if (granted) step = StartStep.Start else { step = StartStep.Idle; showLocationDenied = true }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        caps = Capabilities.read(context)
        if (step == StartStep.WaitCamera) {
            cameraAsked = true
            step = StartStep.Start
        }
    }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    LaunchedEffect(step) {
        if (step != StartStep.Start) return@LaunchedEffect
        val c = Capabilities.read(context)
        caps = c
        when {
            !settings.disclaimerAccepted -> { step = StartStep.Idle; showDisclaimer = true }
            !c.locationGranted -> {
                step = StartStep.WaitLocation
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            !c.locationEnabled -> { step = StartStep.Idle; showGpsOff = true }
            settings.useCamera && c.hasCamera && !c.cameraGranted && !cameraAsked -> {
                step = StartStep.WaitCamera
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                step = StartStep.Idle
                vm.startLive(settings.useCamera && c.hasCamera && c.cameraGranted)
            }
        }
    }

    ApexBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(112.dp))
            Text(stringResource(R.string.app_name).uppercase(), style = MaterialTheme.typography.headlineLarge, color = Apex.TextPrimary)
            Text(stringResource(R.string.tagline), color = Apex.CyberBlue, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

            ApexCard {
                Text(stringResource(R.string.readiness_title), style = MaterialTheme.typography.labelLarge, color = Apex.TextDim)
                ReadinessRow(
                    title = stringResource(R.string.camera_pipeline),
                    status = when {
                        !settings.useCamera -> stringResource(R.string.cam_off_setting)
                        !caps.hasCamera -> stringResource(R.string.cam_missing)
                        !caps.cameraGranted -> stringResource(R.string.cam_needs_permission)
                        else -> stringResource(R.string.cam_ready)
                    },
                    ok = settings.useCamera && caps.hasCamera && caps.cameraGranted,
                    actionLabel = if (settings.useCamera && caps.hasCamera && !caps.cameraGranted) stringResource(R.string.action_allow) else null,
                    onAction = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
                ReadinessRow(
                    title = stringResource(R.string.location_motion),
                    status = when {
                        !caps.locationGranted -> stringResource(R.string.loc_needs_permission)
                        !caps.locationEnabled -> stringResource(R.string.loc_gps_off)
                        caps.motionReady -> stringResource(R.string.loc_ready_full)
                        else -> stringResource(R.string.loc_ready_gps_only)
                    },
                    ok = caps.locationGranted && caps.locationEnabled,
                    actionLabel = when {
                        !caps.locationGranted -> stringResource(R.string.action_allow)
                        !caps.locationEnabled -> stringResource(R.string.action_open_settings)
                        else -> null
                    },
                    onAction = {
                        if (!caps.locationGranted) {
                            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
                ReadinessRow(
                    title = stringResource(R.string.route_horizon),
                    status = trip?.let { stringResource(R.string.route_loaded, it.route.name, Units.distance(it.route.lengthM, settings.useMph)) }
                        ?: route?.let { stringResource(R.string.route_loaded, it.name, Units.distance(it.lengthM, settings.useMph)) }
                        ?: stringResource(R.string.route_predictive),
                    ok = trip != null || route != null,
                    neutral = trip == null && route == null,
                    actionLabel = if (trip != null) stringResource(R.string.plan_clear)
                        else stringResource(if (route == null) R.string.action_import_gpx else R.string.action_replace),
                    onAction = { if (trip != null) vm.clearTrip() else gpxLauncher.launch(arrayOf("*/*")) },
                )
            }

            PrimaryButton(stringResource(R.string.action_start_drive), onClick = { cameraAsked = false; step = StartStep.Start })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.action_plan_trip), onClick = { vm.openPlan() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.action_offline_maps), onClick = { vm.navigate(Screen.Maps) }, modifier = Modifier.weight(1f))
            }
            SecondaryButton(stringResource(R.string.action_demo), onClick = { vm.startDemo() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.action_history), onClick = { vm.navigate(Screen.History) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.action_settings), onClick = { vm.navigate(Screen.Settings) }, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { vm.navigate(Screen.About) }) {
                Text(stringResource(R.string.action_about), color = Apex.TextDim)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(disclaimerAccepted = true) }
                    showDisclaimer = false
                    cameraAsked = false
                    step = StartStep.Start
                }) { Text(stringResource(R.string.action_understand)) }
            },
            dismissButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showGpsOff) {
        AlertDialog(
            onDismissRequest = { showGpsOff = false },
            title = { Text(stringResource(R.string.gps_off_title)) },
            text = { Text(stringResource(R.string.gps_off_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showGpsOff = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showGpsOff = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showLocationDenied) {
        AlertDialog(
            onDismissRequest = { showLocationDenied = false },
            title = { Text(stringResource(R.string.loc_denied_title)) },
            text = { Text(stringResource(R.string.loc_denied_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.action_app_settings)) }
            },
            dismissButton = { TextButton(onClick = { showLocationDenied = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ReadinessRow(
    title: String,
    status: String,
    ok: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    neutral: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusDot(if (ok) Apex.Good else if (neutral) Apex.CyberBlue else Apex.NeonOrange)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = Apex.CyberBlue) }
        }
    }
}
