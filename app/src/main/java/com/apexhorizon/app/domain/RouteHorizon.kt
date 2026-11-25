package com.apexhorizon.app.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Road geometry ahead of the vehicle. The polyline is resampled to a uniform [STEP] spacing, which
 * makes heading-change (and therefore curve) detection a cheap array scan.
 */
class RouteHorizon private constructor(
    val name: String,
    val kind: Kind,
    val origin: LatLon,
    private val xs: DoubleArray,
    private val ys: DoubleArray,
) {
    enum class Kind { DEMO, IMPORTED, RECORDED, PLANNED }

    data class Match(
        val index: Int,
        val fraction: Double,
        val sM: Double,
        /** Positive when the vehicle is to the right of the route line. */
        val lateralM: Double,
        val headingDiffDeg: Double,
        val distM: Double,
        val point: XY,
    )

    val size: Int get() = xs.size
    val lengthM: Double get() = (xs.size - 1) * STEP

    /** Smoothed, unwrapped heading (degrees) of the segment starting at each sample. */
    private val heading = DoubleArray(xs.size)

    init {
        val n = xs.size
        val raw = DoubleArray(n)
        for (i in 0 until n - 1) {
            raw[i] = Geo.bearingOf(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        }
        raw[n - 1] = raw[n - 2]
        val unwrapped = DoubleArray(n)
        unwrapped[0] = raw[0]
        for (i in 1 until n) {
            unwrapped[i] = unwrapped[i - 1] + Geo.normalizeDeg(raw[i] - unwrapped[i - 1])
        }
        for (i in 0 until n) {
            val a = unwrapped[max(0, i - 1)]
            val b = unwrapped[i]
            val c = unwrapped[min(n - 1, i + 1)]
            heading[i] = (a + b + c) / 3.0
        }
    }

    fun toLocal(p: LatLon): XY = Geo.toLocal(origin, p)
    fun fromLocal(p: XY): LatLon = Geo.fromLocal(origin, p)

    fun pointAt(i: Int): XY = XY(xs[i], ys[i])
    fun headingAt(i: Int): Double = Geo.wrap360(heading[i])

    fun latLonPoints(): List<LatLon> = List(xs.size) { fromLocal(XY(xs[it], ys[it])) }

    fun reversed(): RouteHorizon? =
        create("$name (reversed)", latLonPoints().asReversed(), kind)

    /**
     * Finds the closest route segment to [p]. When [headingDeg] is given, segments that point in a
     * very different direction are ignored, so the opposite carriageway is not matched.
     */
    fun locate(p: XY, headingDeg: Double?, hint: Int?): Match? {
        val n = xs.size
        if (hint != null) {
            val m = search(p, headingDeg, max(0, hint - 30), min(n - 2, hint + 80))
            if (m != null && m.distM <= 60.0) return m
        }
        return search(p, headingDeg, 0, n - 2)
    }

    private fun search(p: XY, headingDeg: Double?, from: Int, to: Int): Match? {
        var best: Match? = null
        for (i in from..to) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-9) continue
            val vx = p.x - xs[i]
            val vy = p.y - ys[i]
            val t = ((vx * dx + vy * dy) / len2).coerceIn(0.0, 1.0)
            val px = xs[i] + t * dx
            val py = ys[i] + t * dy
            val ex = p.x - px
            val ey = p.y - py
            val dist = sqrt(ex * ex + ey * ey)
            val segHeading = Geo.bearingOf(dx, dy)
            val diff = if (headingDeg != null) Geo.normalizeDeg(headingDeg - segHeading) else 0.0
            if (headingDeg != null && abs(diff) > 100.0) continue
            if (best == null || dist < best.distM) {
                val len = sqrt(len2)
                val lateral = (vx * dy - vy * dx) / len
                best = Match(i, t, (i + t) * STEP, lateral, diff, dist, XY(px, py))
            }
        }
        return best
    }

    /** Route points ahead of [match] (world frame), starting with the matched point itself. */
    fun lookahead(match: Match, maxDistM: Double): List<XY> {
        val out = ArrayList<XY>()
        out.add(match.point)
        val count = ceil(maxDistM / STEP).toInt()
        var i = match.index + 1
        while (i < xs.size && out.size <= count) {
            out.add(XY(xs[i], ys[i]))
            i++
        }
        return out
    }

    /**
     * Looks for the next meaningful curve within [maxDistM]. A curve starts when the heading changes by
     * at least [TRIGGER_DEG] over [WINDOW] samples (~30 m) and counts when it adds up to
     * [MIN_TOTAL_DEG]. The suggested approach speed comes from a comfortable lateral acceleration.
     */
    fun curveAhead(match: Match, maxDistM: Double, currentKmh: Double): Pair<CurveAdvice, Int>? {
        val n = xs.size
        val last = min(n - 1 - WINDOW, match.index + ceil(maxDistM / STEP).toInt())
        var i = match.index
        while (i <= last) {
            val d0 = heading[i + WINDOW] - heading[i]
            if (abs(d0) < TRIGGER_DEG) {
                i++
                continue
            }
            val dir = if (d0 > 0) 1 else -1
            var j = i
            var maxAbs = 0.0
            var apexIdx = i
            while (j <= n - 1 - WINDOW) {
                val d = heading[j + WINDOW] - heading[j]
                if (d * dir < SUSTAIN_DEG) break
                if (abs(d) > maxAbs) {
                    maxAbs = abs(d)
                    apexIdx = j
                }
                j++
            }
            val total = abs(heading[min(n - 1, j - 1 + WINDOW)] - heading[i])
            if (total < MIN_TOTAL_DEG) {
                i = max(j, i + 1)
                continue
            }
            val radius = (WINDOW * STEP) / Geo.rad(maxAbs)
            val distance = max(0.0, (i - match.index - match.fraction) * STEP)
            val advised = advisedKmh(radius, currentKmh)
            val advice = CurveAdvice(
                turn = if (dir > 0) Turn.RIGHT else Turn.LEFT,
                distanceM = distance,
                radiusM = radius,
                angleDeg = total,
                advisedKmh = advised,
                apex = null,
            )
            return Pair(advice, apexIdx + WINDOW / 2)
        }
        return null
    }

    companion object {
        const val STEP = 10.0
        private const val WINDOW = 3
        private const val TRIGGER_DEG = 12.0
        private const val SUSTAIN_DEG = 5.0
        private const val MIN_TOTAL_DEG = 25.0

        /** Comfortable lateral acceleration used for the advisory speed (about 0.29 g). */
        const val COMFORT_LAT_ACCEL = 2.8

        fun advisedKmh(radiusM: Double, currentKmh: Double): Int? {
            val v = sqrt(COMFORT_LAT_ACCEL * radiusM) * 3.6
            val rounded = (min(100.0, max(20.0, v)) / 5.0).roundToInt() * 5
            return if (currentKmh > rounded + 7.0) rounded else null
        }

        /** Builds a resampled route. Returns null when the polyline is too short to be useful. */
        fun create(name: String, points: List<LatLon>, kind: Kind): RouteHorizon? {
            if (points.size < 2) return null
            val origin = points[0]
            val local = points.map { Geo.toLocal(origin, it) }
            val ox = ArrayList<Double>()
            val oy = ArrayList<Double>()
            ox.add(local[0].x)
            oy.add(local[0].y)
            var carry = 0.0
            for (k in 0 until local.size - 1) {
                val a = local[k]
                val b = local[k + 1]
                val segLen = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                if (segLen < 1e-6) continue
                var d = STEP - carry
                while (d <= segLen) {
                    val t = d / segLen
                    ox.add(a.x + t * (b.x - a.x))
                    oy.add(a.y + t * (b.y - a.y))
                    d += STEP
                }
                carry = segLen - (d - STEP)
            }
            if (ox.size < 8) return null
            return RouteHorizon(name, kind, origin, ox.toDoubleArray(), oy.toDoubleArray())
        }
    }
}
