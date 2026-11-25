package com.apexhorizon.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A time window (relative to the start of the replay) in which GPS fixes are withheld. */
data class Dropout(val fromMs: Long, val toMs: Long)

/**
 * Turns a recorded or synthetic track into the same fixed-rate [Sample] stream that live sensors
 * produce, so replays run through the real [DriveEngine] and exercise the real pipeline.
 */
object ReplayBuilder {
    const val TICK_MS = 200L

    fun samples(
        track: List<TrackPoint>,
        luma: ((Long) -> Double)? = null,
        dropout: Dropout? = null,
    ): List<Sample> {
        if (track.size < 2) return emptyList()
        val out = ArrayList<Sample>()
        val endMs = track.last().tMs
        var nextPoint = 0
        var seg = 0
        var t = 0L
        while (t <= endMs) {
            while (seg < track.size - 2 && track[seg + 1].tMs <= t) seg++
            val a = track[seg]
            val b = track[seg + 1]
            val span = max(1L, b.tMs - a.tMs).toDouble()
            val dtSec = span / 1000.0
            val yaw = Geo.normalizeDeg(b.bearingDeg - a.bearingDeg) / dtSec
            val accel = (b.speedMps - a.speedMps) / dtSec
            val v = (a.speedMps + b.speedMps) / 2.0
            val lat = v * Geo.rad(yaw)
            val horiz = sqrt(accel * accel + lat * lat)

            var fix: Fix? = null
            while (nextPoint < track.size && track[nextPoint].tMs <= t) {
                val p = track[nextPoint]
                val inDropout = dropout != null && p.tMs >= dropout.fromMs && p.tMs < dropout.toMs
                if (!inDropout && p.tMs + TICK_MS > t) {
                    fix = Fix(t, p.lat, p.lon, p.speedMps, p.bearingDeg, p.accuracyM)
                }
                nextPoint++
            }
            out.add(
                Sample(
                    tMs = t,
                    fix = fix,
                    yawRateDps = if (abs(yaw) < 90.0) yaw else 0.0,
                    horizAccel = horiz,
                    luma = luma?.invoke(t) ?: -1.0,
                    hasMotionSensors = true,
                ),
            )
            t += TICK_MS
        }
        return out
    }
}

/**
 * Synthetic mountain road used by Demo mode. It is generated geometry, not a real road: straights
 * joined by constant-radius curves, driven with a physically plausible speed profile.
 */
object DemoScenario {
    val START = LatLon(39.8890, 22.5950)
    const val NAME = "Demo route (synthetic mountain road)"

    /** Segment = length in metres and signed radius in metres (positive = right, 0 = straight). */
    private data class Seg(val length: Double, val radius: Double)

    private fun arc(radius: Double, angleDeg: Double) = Seg(Math.abs(radius) * Geo.rad(angleDeg), radius)

    private val segments = listOf(
        Seg(250.0, 0.0), arc(-110.0, 70.0), Seg(150.0, 0.0), arc(90.0, 100.0), Seg(120.0, 0.0),
        arc(-140.0, 55.0), Seg(200.0, 0.0), arc(70.0, 120.0), Seg(150.0, 0.0), arc(-100.0, 80.0),
        Seg(260.0, 0.0), arc(160.0, 45.0), arc(-130.0, 45.0), Seg(180.0, 0.0), arc(-85.0, 90.0),
        Seg(220.0, 0.0), arc(120.0, 60.0), Seg(300.0, 0.0),
    )

    private const val DS = 5.0

    /** Dark phase begins and ends (ms) and the GPS blackout window, used by the demo only. */
    val dropout = Dropout(95_000, 112_000)

    fun luma(tMs: Long): Double = when {
        tMs < 45_000 -> 0.55
        tMs < 75_000 -> 0.55 - 0.49 * ((tMs - 45_000) / 30_000.0)
        else -> 0.06
    }

    fun route(): RouteHorizon? = RouteHorizon.create(NAME, geometry().first, RouteHorizon.Kind.DEMO)

    fun track(): List<TrackPoint> = drive(geometry())

    fun samples(): List<Sample> = ReplayBuilder.samples(track(), ::luma, dropout)

    /** Returns the 5 m-spaced polyline and the radius (0 = straight) at each point. */
    private fun geometry(): Pair<List<LatLon>, DoubleArray> {
        val pts = ArrayList<LatLon>()
        val radii = ArrayList<Double>()
        var x = 0.0
        var y = 0.0
        var h = Geo.rad(25.0)
        pts.add(Geo.fromLocal(START, XY(x, y)))
        radii.add(0.0)
        for (seg in segments) {
            val steps = max(1, Math.round(seg.length / DS).toInt())
            for (i in 0 until steps) {
                if (seg.radius != 0.0) h += DS / seg.radius
                x += DS * Math.sin(h)
                y += DS * Math.cos(h)
                pts.add(Geo.fromLocal(START, XY(x, y)))
                radii.add(seg.radius)
            }
        }
        return Pair(pts, radii.toDoubleArray())
    }

    private fun drive(g: Pair<List<LatLon>, DoubleArray>): List<TrackPoint> {
        val pts = g.first
        val radii = g.second
        val n = pts.size
        val vMax = 24.0
        val aBrake = 2.3
        val aAccel = 1.6
        val hardZone = 150.0

        // Most curves are taken at ~2.9 m/s² lateral acceleration; the tightest one is pushed harder.
        val vLimit = DoubleArray(n) { i ->
            if (radii[i] == 0.0) vMax else min(vMax, sqrt((if (abs(radii[i]) < 75.0) 3.8 else 2.9) * abs(radii[i])))
        }
        // Make the tightest curve arrive with a firm brake so the demo shows a hard-braking event.
        var tightest = -1
        var minR = Double.MAX_VALUE
        for (i in 0 until n) {
            val r = abs(radii[i])
            if (r != 0.0 && r < minR) {
                minR = r
                tightest = i
            }
        }
        val v = DoubleArray(n) { vLimit[it] }
        v[0] = 2.0
        for (i in n - 2 downTo 0) {
            val brake = if ((tightest - i) * DS in 0.0..hardZone) 3.6 else aBrake
            v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * brake * DS))
        }
        for (i in 1 until n) {
            v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * aAccel * DS))
        }
        val out = ArrayList<TrackPoint>()
        var tAcc = 0.0
        val tAt = DoubleArray(n)
        for (i in 1 until n) {
            tAcc += DS / max(1.0, (v[i - 1] + v[i]) / 2.0)
            tAt[i] = tAcc
        }
        val total = tAcc
        var idx = 0
        var sec = 0
        while (sec <= total) {
            while (idx < n - 2 && tAt[idx + 1] < sec) idx++
            val span = max(1e-6, tAt[idx + 1] - tAt[idx])
            val f = ((sec - tAt[idx]) / span).coerceIn(0.0, 1.0)
            val a = pts[idx]
            val b = pts[idx + 1]
            val lat = a.lat + f * (b.lat - a.lat)
            val lon = a.lon + f * (b.lon - a.lon)
            val d = Geo.toLocal(a, b)
            val bearing = Geo.bearingOf(d.x, d.y)
            val speed = v[idx] + f * (v[idx + 1] - v[idx])
            // Deterministic, small "GPS noise" so the filters have something to do.
            val nz = Math.sin(sec * 1.7) * 1.8
            val noisy = Geo.fromLocal(LatLon(lat, lon), XY(nz, Math.cos(sec * 2.3) * 1.8))
            out.add(TrackPoint(sec * 1000L, noisy.lat, noisy.lon, speed, bearing, 6.0))
            sec++
        }
        return out
    }
}
