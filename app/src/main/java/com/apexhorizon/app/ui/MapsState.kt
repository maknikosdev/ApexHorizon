package com.apexhorizon.app.ui

import com.apexhorizon.app.data.InstalledRegion
import com.apexhorizon.app.data.RemoteRegion
import com.apexhorizon.app.domain.LatLon
import com.apexhorizon.app.domain.RouteHorizon
import com.apexhorizon.app.maps.MapPack
import com.apexhorizon.app.maps.RoutePlan
import com.apexhorizon.app.maps.Router
import com.apexhorizon.app.maps.TravelMode

data class RegionRow(
    val id: String,
    val name: String,
    val installed: InstalledRegion?,
    val remote: RemoteRegion?,
    val downloading: Boolean = false,
    val progress: Float = 0f,
) {
    val updateAvailable get() = installed != null && remote != null && remote.dataVersion > installed.dataVersion
}

data class MapsUi(
    val rows: List<RegionRow> = emptyList(),
    val checking: Boolean = false,
    val checkedOnce: Boolean = false,
    val error: String? = null,
)

/** An opened map pack with its router. */
class LoadedPack(val id: String, val pack: MapPack, val router: Router)

/** A point chosen on the map or found by search. */
data class Waypoint(val latLon: LatLon, val label: String)

data class PlanState(
    /** null = "My location". */
    val from: Waypoint? = null,
    val to: Waypoint? = null,
    val busy: Boolean = false,
    val result: RoutePlan? = null,
    val error: String? = null,
    val packId: String? = null,
    val mode: TravelMode = TravelMode.CAR,
)

/** The route the user is currently following, plus what is needed to re-route. */
class NavTarget(val destination: Waypoint, val packId: String, val route: RouteHorizon, val plan: RoutePlan, val mode: TravelMode = TravelMode.CAR)
