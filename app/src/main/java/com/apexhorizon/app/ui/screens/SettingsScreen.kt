package com.apexhorizon.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.R
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.theme.Apex

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val route by vm.importedRoute.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = { vm.back() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ApexCard {
                Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = !settings.useMph,
                        onClick = { vm.updateSettings { it.copy(useMph = false) } },
                        label = { Text("km/h") },
                    )
                    FilterChip(
                        selected = settings.useMph,
                        onClick = { vm.updateSettings { it.copy(useMph = true) } },
                        label = { Text("mph") },
                    )
                }
            }
            ApexCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_camera), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                        Text(stringResource(R.string.settings_camera_desc), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
                    }
                    Switch(checked = settings.useCamera, onCheckedChange = { c -> vm.updateSettings { it.copy(useCamera = c) } })
                }
            }
            ApexCard {
                Text(stringResource(R.string.route_horizon), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Text(
                    route?.let { stringResource(R.string.route_loaded, it.name, Units.distance(it.lengthM, settings.useMph)) }
                        ?: stringResource(R.string.route_predictive_long),
                    style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim,
                )
                SecondaryButton(stringResource(R.string.action_import_gpx), onClick = { gpxLauncher.launch(arrayOf("*/*")) })
                if (route != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(stringResource(R.string.action_reverse), onClick = { vm.reverseRoute() }, modifier = Modifier.weight(1f))
                        SecondaryButton(stringResource(R.string.action_remove), onClick = { vm.clearRoute() }, modifier = Modifier.weight(1f))
                    }
                }
            }
            ApexCard {
                Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Text(stringResource(R.string.settings_data_desc), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
                SecondaryButton(stringResource(R.string.action_delete_all), onClick = { confirmDeleteAll = true })
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.delete_all_title)) },
            text = { Text(stringResource(R.string.delete_all_body)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteAllRuns(); confirmDeleteAll = false }) { Text(stringResource(R.string.action_delete), color = Apex.NeonOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
