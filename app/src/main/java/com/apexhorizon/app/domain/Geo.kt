package com.apexhorizon.app.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/** WGS84 coordinate in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/** Local planar coordinate in metres: x = east, y = north. */
data class XY(val x: Double, val y: Double) {
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)
    operator fun minus(o: XY) = XY(x - o.x, y - o.y)
    operator fun times(k: Double) = XY(x * k, y * k)
}

object Geo {
    private const val EARTH_R = 6_371_000.0

    fun rad(deg: Double) = deg * PI / 180.0
    fun deg(rad: Double) = rad * 180.0 / PI

    fun clamp01(v: Double) = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    /** Normalises an angle to the range (-180, 180]. */
    fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }

    /** Normalises an angle to [0, 360). */
    fun wrap360(d: Double): Double {
        var x = d % 360.0
        if (x < 0.0) x += 360.0
        return x
    }

    /** Compass bearing (0 = north, clockwise) of a vector given in east/north metres. */
    fun bearingOf(dx: Double, dy: Double): Double = wrap360(deg(atan2(dx, dy)))

    fun toLocal(origin: LatLon, p: LatLon): XY {
        val x = rad(p.lon - origin.lon) * cos(rad(origin.lat)) * EARTH_R
        val y = rad(p.lat - origin.lat) * EARTH_R
        return XY(x, y)
    }

    fun fromLocal(origin: LatLon, p: XY): LatLon {
        val lat = origin.lat + deg(p.y / EARTH_R)
        val lon = origin.lon + deg(p.x / (EARTH_R * cos(rad(origin.lat))))
        return LatLon(lat, lon)
    }

    fun distance(a: LatLon, b: LatLon): Double {
        val o = toLocal(a, b)
        return sqrt(o.x * o.x + o.y * o.y)
    }

    /** Converts a world vector (east/north) into the vehicle frame: x = right, z = forward. */
    fun toVehicleFrame(dx: Double, dy: Double, headingDeg: Double): PathPoint {
        val h = rad(headingDeg)
        val forward = dx * sin(h) + dy * cos(h)
        val right = dx * cos(h) - dy * sin(h)
        return PathPoint(right, forward)
    }
}
