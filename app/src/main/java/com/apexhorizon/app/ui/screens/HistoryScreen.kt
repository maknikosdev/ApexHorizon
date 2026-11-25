package com.apexhorizon.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.apexhorizon.app.domain.RunSummary
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.theme.Apex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<RunSummary?>(null) }

    ScreenScaffold(title = stringResource(R.string.history_title), onBack = { vm.back() }) {
        if (runs.isEmpty()) {
            Text(stringResource(R.string.history_empty), color = Apex.TextDim, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    ApexCard(Modifier.clickable { vm.openRun(run) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(run.title, style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                                Text(
                                    SimpleDateFormat("MMM d yyyy, HH:mm", Locale.US).format(Date(run.startedAtMs)),
                                    color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    Units.distance(run.distanceM, settings.useMph) + " · " + Units.duration(run.durationMs) +
                                        " · avg " + Units.speedText(run.avgSpeedMps, settings.useMph),
                                    color = Apex.CyberBlue, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            IconButton(onClick = { toDelete = run }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = Apex.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_run_title)) },
            text = { Text(stringResource(R.string.delete_run_body, run.title)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteRun(run.id); toDelete = null }) { Text(stringResource(R.string.action_delete), color = Apex.NeonOrange) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
