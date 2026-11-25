package com.apexhorizon.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexhorizon.app.R
import com.apexhorizon.app.domain.LatLon
import com.apexhorizon.app.maps.Place
import com.apexhorizon.app.ui.LoadedPack
import com.apexhorizon.app.ui.theme.Apex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Map viewport: centre plus zoom (zoom 12 ≈ 30 m per pixel at 40°N). */
@Stable
class MapCamera(lat: Double, lon: Double, zoom: Double) {
    var lat by mutableDoubleStateOf(lat)
    var lon by mutableDoubleStateOf(lon)
    var zoom by mutableDoubleStateOf(zoom)

    fun metersPerPx(): Double = 156_543.0 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)

    fun fit(points: List<LatLon>, widthPx: Int, heightPx: Int) {
        if (points.isEmpty() || widthPx <= 0) return
        val la0 = points.minOf { it.lat }; val la1 = points.maxOf { it.lat }
        val lo0 = points.minOf { it.lon }; val lo1 = points.maxOf { it.lon }
        lat = (la0 + la1) / 2
        lon = (lo0 + lo1) / 2
        val c = cos(Math.toRadians(lat))
        val needW = max(1.0, (lo1 - lo0) * c * 111_320.0 * 1.3) / widthPx
        val needH = max(1.0, (la1 - la0) * 111_320.0 * 1.3) / heightPx
        val mpp = max(needW, needH)
        zoom = (ln(156_543.0 * c / mpp) / ln(2.0)).coerceIn(5.0, 17.0)
    }
}

@Composable
fun rememberMapCamera(lat: Double = 39.0, lon: Double = 22.5, zoom: Double = 6.0) = remember { MapCamera(lat, lon, zoom) }

private fun classMinZoom(cls: Int): Double = when (cls) {
    0 -> 0.0
    1 -> 7.0
    2 -> 9.0
    3 -> 10.5
    4 -> 12.0
    5 -> 13.0
    6 -> 14.5
    else -> 14.0
}

private val classWidthDp = floatArrayOf(3.2f, 3.0f, 2.6f, 2.2f, 1.8f, 1.3f, 1.0f, 1.0f)

@Composable
fun MapView(
    packs: List<LoadedPack>,
    camera: MapCamera,
    modifier: Modifier = Modifier,
    route: List<LatLon>? = null,
    from: LatLon? = null,
    to: LatLon? = null,
    me: LatLon? = null,
    onLongPress: (LatLon) -> Unit = {},
    onSize: (IntSize) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Search index (names) is needed for place labels; load it off the main thread.
    var labelsReady by remember { mutableStateOf(0) }
    LaunchedEffect(packs) {
        withContext(Dispatchers.Default) { packs.forEach { it.pack.ensureSearchIndex() } }
        labelsReady++
    }

    Box(modifier.background(Apex.NavyDeep)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it; onSize(it) }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val mpp = camera.metersPerPx()
                        val c = cos(Math.toRadians(camera.lat))
                        // keep the point under the fingers fixed while zooming
                        val before = pxToLatLon(camera, viewSize, centroid)
                        camera.lon -= pan.x * mpp / (111_320.0 * c)
                        camera.lat += pan.y * mpp / 111_320.0
                        if (zoomChange != 1f) {
                            camera.zoom = (camera.zoom + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(5.0, 18.0)
                            val after = pxToLatLon(camera, viewSize, centroid)
                            camera.lat += before.lat - after.lat
                            camera.lon += before.lon - after.lon
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { off -> onLongPress(pxToLatLon(camera, viewSize, off)) })
                },
        ) {
            if (labelsReady < 0) return@Canvas
            drawMap(packs, camera, measurer, density.density, route, from, to, me)
        }
        Text(
            stringResource(R.string.maps_attribution),
            color = Apex.TextDim,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Apex.NavyDeep.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun pxToLatLon(cam: MapCamera, size: IntSize, p: Offset): LatLon {
    val mpp = cam.metersPerPx()
    val c = cos(Math.toRadians(cam.lat))
    val lon = cam.lon + (p.x - size.width / 2f) * mpp / (111_320.0 * c)
    val lat = cam.lat - (p.y - size.height / 2f) * mpp / 111_320.0
    return LatLon(lat, lon)
}

private fun DrawScope.drawMap(
    packs: List<LoadedPack>,
    cam: MapCamera,
    measurer: TextMeasurer,
    dens: Float,
    route: List<LatLon>?,
    from: LatLon?,
    to: LatLon?,
    me: LatLon?,
) {
    val w = size.width
    val h = size.height
    val mpp = cam.metersPerPx()
    val c = cos(Math.toRadians(cam.lat))
    val kx = 111_320.0 * c / mpp
    val ky = 111_320.0 / mpp
    fun sx(lon: Double) = ((lon - cam.lon) * kx + w / 2).toFloat()
    fun sy(lat: Double) = (-(lat - cam.lat) * ky + h / 2).toFloat()

    val la0 = cam.lat - (h / 2) / ky; val la1 = cam.lat + (h / 2) / ky
    val lo0 = cam.lon - (w / 2) / kx; val lo1 = cam.lon + (w / 2) / kx

    // Roads, bucketed by class so important roads are drawn on top.
    val paths = Array(8) { Path() }
    var budget = 9000
    for (lp in packs) {
        val pack = lp.pack
        if (la1 < pack.minLat || la0 > pack.maxLat || lo1 < pack.minLon || lo0 > pack.maxLon) continue
        pack.forEachEdgeInBox(la0, lo0, la1, lo1) { e ->
            val cls = pack.edgeClass(e)
            if (budget <= 0 || cam.zoom < classMinZoom(cls)) return@forEachEdgeInBox
            budget--
            val pts = pack.edgePoints(e)
            val p = paths[cls]
            p.moveTo(sx(pts[0].lon), sy(pts[0].lat))
            // Skip shape points at low zoom: the road looks the same and it is much cheaper.
            val step = if (cam.zoom < 12.0) max(1, pts.size / 3) else 1
            var i = 1
            while (i < pts.size - 1) {
                p.lineTo(sx(pts[i].lon), sy(pts[i].lat))
                i += step
            }
            p.lineTo(sx(pts[pts.size - 1].lon), sy(pts[pts.size - 1].lat))
        }
    }
    val roadColors = arrayOf(Apex.NeonOrange, Apex.Amber, Apex.IceBlue, Apex.CyberBlue, Color(0xFF4F7FA8), Color(0xFF3B6286), Color(0xFF2F4F6E), Apex.Good.copy(alpha = 0.55f))
    for (cls in 7 downTo 0) {
        val widthPx = classWidthDp[cls] * dens * (if (cam.zoom > 15) 1.5f else 1f)
        if (cls <= 2) drawPath(paths[cls], Apex.NavyDeep, style = Stroke(widthPx + 2f * dens, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(paths[cls], roadColors[cls], style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Planned route.
    if (route != null && route.size > 1) {
        val rp = Path()
        route.forEachIndexed { i, p -> if (i == 0) rp.moveTo(sx(p.lon), sy(p.lat)) else rp.lineTo(sx(p.lon), sy(p.lat)) }
        drawPath(rp, Apex.NavyDeep, style = Stroke(9f * dens, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(rp, Apex.NeonOrange, style = Stroke(5f * dens, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Place labels.
    if (cam.zoom >= 7.0) {
        val maxType = when {
            cam.zoom < 9.5 -> Place.CITY
            cam.zoom < 11.5 -> Place.TOWN
            cam.zoom < 13.0 -> Place.VILLAGE
            else -> Place.AREA
        }
        val style = TextStyle(color = Apex.TextPrimary, fontSize = (if (cam.zoom < 11) 13 else 12).sp)
        var drawn = 0
        for (lp in packs) {
            for (pl in lp.pack.placesInBox(la0, lo0, la1, lo1, maxType, 60)) {
                if (drawn >= 45) break
                val x = sx(pl.lon); val y = sy(pl.lat)
                drawCircle(Apex.TextDim, radius = 3f * dens, center = Offset(x, y))
                drawText(measurer, pl.name, Offset(x + 6f * dens, y - 8f * dens), style, maxLines = 1)
                drawn++
            }
        }
    }

    fun marker(p: LatLon?, color: Color) {
        if (p == null) return
        val o = Offset(sx(p.lon), sy(p.lat))
        drawCircle(Apex.NavyDeep, radius = 10f * dens, center = o)
        drawCircle(color, radius = 7f * dens, center = o)
    }
    marker(from, Apex.CyberBlue)
    marker(to, Apex.NeonOrange)
    if (me != null) {
        val o = Offset(sx(me.lon), sy(me.lat))
        drawCircle(Apex.Good.copy(alpha = 0.25f), radius = 14f * dens, center = o)
        drawCircle(Apex.NavyDeep, radius = 7f * dens, center = o)
        drawCircle(Apex.Good, radius = 5f * dens, center = o)
    }
}
