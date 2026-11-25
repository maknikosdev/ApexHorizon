package com.apexhorizon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.R
import com.apexhorizon.app.domain.LatLon
import com.apexhorizon.app.maps.Place
import com.apexhorizon.app.maps.TravelMode
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.Screen
import com.apexhorizon.app.ui.Units
import com.apexhorizon.app.ui.Waypoint
import com.apexhorizon.app.ui.map.MapView
import com.apexhorizon.app.ui.map.rememberMapCamera
import com.apexhorizon.app.ui.theme.Apex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Target { From, To }

@Composable
fun PlanScreen(vm: AppViewModel) {
    val packs by vm.packs.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val camera = rememberMapCamera()
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf(Target.To) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val me = remember { vm.myLocation() }

    // Start the map where the user is (or on the first downloaded region).
    LaunchedEffect(packs.size) {
        val pack = packs.firstOrNull()?.pack ?: return@LaunchedEffect
        if (me != null && pack.contains(me.lat, me.lon)) {
            camera.lat = me.lat; camera.lon = me.lon; camera.zoom = 13.0
        } else {
            camera.lat = (pack.minLat + pack.maxLat) / 2; camera.lon = (pack.minLon + pack.maxLon) / 2; camera.zoom = 8.5
        }
    }
    LaunchedEffect(plan.result) {
        plan.result?.let { camera.fit(it.points, mapSize.width, mapSize.height) }
    }
    LaunchedEffect(query, packs) {
        if (query.trim().length < 2) { results = emptyList(); searched = false; return@LaunchedEffect }
        delay(250)
        val near = LatLon(camera.lat, camera.lon)
        results = withContext(Dispatchers.Default) {
            packs.flatMap { it.pack.search(query, 6, near) }.sortedBy { it.type }.take(8)
        }
        searched = true
    }

    fun choose(w: Waypoint) {
        if (target == Target.To) vm.setDestination(w) else vm.setOrigin(w)
        camera.lat = w.latLon.lat; camera.lon = w.latLon.lon; if (camera.zoom < 12.5) camera.zoom = 13.0
    }

    ScreenScaffold(title = stringResource(R.string.plan_title), onBack = { vm.back() }) {
        if (packs.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ApexCard {
                    Text(stringResource(R.string.plan_no_maps_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                    Text(stringResource(R.string.plan_no_maps_body), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
                    PrimaryButton(stringResource(R.string.action_offline_maps), onClick = { vm.navigate(Screen.Maps) })
                }
            }
            return@ScreenScaffold
        }

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = target == Target.From,
                        onClick = { target = Target.From },
                        label = { Text(stringResource(R.string.plan_from) + ": " + (plan.from?.label ?: stringResource(R.string.plan_my_location))) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = target == Target.To,
                        onClick = { target = Target.To },
                        label = { Text(stringResource(R.string.plan_to) + ": " + (plan.to?.label ?: "—"), maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = plan.mode == TravelMode.CAR,
                        onClick = { vm.setMode(TravelMode.CAR) },
                        label = { Text(stringResource(R.string.mode_car)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = plan.mode == TravelMode.WALK,
                        onClick = { vm.setMode(TravelMode.WALK) },
                        label = { Text(stringResource(R.string.mode_walk)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.plan_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Apex.TextPrimary, unfocusedTextColor = Apex.TextPrimary,
                        focusedBorderColor = Apex.CyberBlue, unfocusedBorderColor = Apex.Outline,
                        cursorColor = Apex.CyberBlue,
                    ),
                )
                if (results.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(Apex.Surface).verticalScrollCompat(),
                    ) {
                        results.forEach { p ->
                            Text(
                                p.name + "  ·  " + typeName(p.type),
                                color = Apex.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    choose(Waypoint(p.latLon, p.name))
                                    query = ""; results = emptyList()
                                }.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                } else if (searched) {
                    Text(stringResource(R.string.plan_no_results), color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }

            MapView(
                packs = packs,
                camera = camera,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
                route = plan.result?.points,
                from = plan.from?.latLon,
                to = plan.to?.latLon,
                me = me,
                onLongPress = { p -> choose(Waypoint(p, "Pin %.4f, %.4f".format(java.util.Locale.US, p.lat, p.lon))) },
                onSize = { mapSize = it },
            )

            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val result = plan.result
                when {
                    plan.error != null -> Text(plan.error!!, color = Apex.NeonOrange, style = MaterialTheme.typography.bodyMedium)
                    result != null -> Text(
                        stringResource(
                            R.string.plan_route_summary,
                            Units.distance(result.distanceM, settings.useMph),
                            formatEta(result.timeS),
                        ),
                        color = Apex.TextPrimary, style = MaterialTheme.typography.titleMedium,
                    )
                    else -> Text(stringResource(R.string.plan_tap_hint), color = Apex.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
                if (result == null) {
                    PrimaryButton(
                        stringResource(R.string.plan_calculate),
                        onClick = { vm.computeRoute() },
                        enabled = plan.to != null && !plan.busy,
                    )
                } else {
                    PrimaryButton(stringResource(R.string.plan_start), onClick = { vm.commitPlan() })
                }
                if (plan.to != null || plan.from != null) {
                    TextButton(onClick = { vm.clearPlan() }) { Text(stringResource(R.string.plan_clear), color = Apex.TextDim) }
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())

private fun typeName(t: Int) = when (t) {
    Place.CITY -> "city"
    Place.TOWN -> "town"
    Place.VILLAGE -> "village"
    Place.AREA -> "area"
    else -> "street"
}

private fun formatEta(seconds: Double): String {
    val min = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}
