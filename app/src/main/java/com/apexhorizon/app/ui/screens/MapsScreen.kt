package com.apexhorizon.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.apexhorizon.app.ui.RegionRow
import com.apexhorizon.app.ui.theme.Apex
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)

@Composable
fun MapsScreen(vm: AppViewModel) {
    val ui by vm.maps.collectAsStateWithLifecycle()
    var confirmDownload by remember { mutableStateOf<RegionRow?>(null) }
    var confirmDelete by remember { mutableStateOf<RegionRow?>(null) }
    LaunchedEffect(Unit) { vm.refreshMaps() }

    ScreenScaffold(title = stringResource(R.string.maps_title), onBack = { vm.back() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ApexCard {
                Text(stringResource(R.string.maps_intro), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
                PrimaryButton(
                    stringResource(if (ui.checking) R.string.maps_checking else R.string.maps_check_updates),
                    onClick = { vm.checkForMapUpdates() },
                    enabled = !ui.checking,
                )
                ui.error?.let { Text(it, color = Apex.NeonOrange, style = MaterialTheme.typography.bodyMedium) }
                if (ui.checkedOnce && ui.error == null && ui.rows.none { it.updateAvailable }) {
                    Text(stringResource(R.string.maps_up_to_date), color = Apex.Good, style = MaterialTheme.typography.bodyMedium)
                }
                if (!ui.checkedOnce && ui.rows.none { it.remote != null }) {
                    Text(stringResource(R.string.maps_hint_check), color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ui.rows.forEach { row ->
                ApexCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                            val status = when {
                                row.updateAvailable -> stringResource(R.string.maps_update_available)
                                row.installed != null -> stringResource(R.string.maps_installed) + " · " + mb(row.installed.bytes)
                                else -> stringResource(R.string.maps_not_installed) + (row.remote?.let { " · " + mb(it.bytes) } ?: "")
                            }
                            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (row.updateAvailable) Apex.Amber else Apex.TextDim)
                        }
                        when {
                            row.downloading -> TextButton(onClick = { vm.cancelDownload(row.id) }) { Text(stringResource(R.string.maps_cancel), color = Apex.NeonOrange) }
                            row.updateAvailable -> TextButton(onClick = { confirmDownload = row }) { Text(stringResource(R.string.maps_update), color = Apex.CyberBlue) }
                            row.installed == null && row.remote != null -> TextButton(onClick = { confirmDownload = row }) { Text(stringResource(R.string.maps_download), color = Apex.CyberBlue) }
                        }
                    }
                    if (row.downloading) {
                        LinearProgressIndicator(progress = { row.progress }, modifier = Modifier.fillMaxWidth(), color = Apex.CyberBlue, trackColor = Apex.Outline)
                    }
                    if (row.installed != null && !row.downloading) {
                        TextButton(onClick = { confirmDelete = row }) { Text(stringResource(R.string.action_delete), color = Apex.TextDim) }
                    }
                }
            }
            Text(stringResource(R.string.maps_attribution), color = Apex.TextDim, style = MaterialTheme.typography.labelMedium)
        }
    }

    confirmDownload?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(stringResource(R.string.maps_confirm_title, row.name)) },
            text = { Text(stringResource(R.string.maps_confirm_body, mb(row.remote?.bytes ?: 0L))) },
            confirmButton = {
                TextButton(onClick = { vm.downloadRegion(row.id); confirmDownload = null }) {
                    Text(stringResource(if (row.installed != null) R.string.maps_update else R.string.maps_download), color = Apex.CyberBlue)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.maps_delete_title)) },
            text = { Text(stringResource(R.string.maps_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.deleteRegion(row.id); confirmDelete = null }) { Text(stringResource(R.string.action_delete), color = Apex.NeonOrange) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
