package com.apexhorizon.app.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Confidence of the visual prediction, computed from GPS quality, route match and light. */
object ConfidenceModel {
    fun gpsScore(accuracyM: Double, fixAgeMs: Long, everFixed: Boolean): Double {
        if (!everFixed) return 0.0
        val acc = Geo.clamp01(1.0 - (accuracyM - 5.0) / 45.0)
        val fresh = Geo.clamp01(1.0 - (fixAgeMs - 1500.0) / 6000.0)
        return acc * fresh
    }

    fun lightScore(luma: Double): Double = Geo.clamp01((luma - 0.08) / 0.22)

    fun routeScore(hasRoute: Boolean, onRoute: Boolean, offsetM: Double): Double {
        if (!hasRoute) return 0.8
        if (!onRoute) return 0.25
        return Geo.clamp01(1.0 - (offsetM - 15.0) / 45.0)
    }

    fun level(c: Double): ConfidenceLevel = when {
        c >= 0.70 -> ConfidenceLevel.HIGH
        c >= 0.45 -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }
}

/** Builds a predicted path from a constant curvature (positive = turning right). */
object PathPredictor {
    fun arc(kappa: Double, maxS: Double, step: Double): List<PathPoint> {
        val out = ArrayList<PathPoint>()
        var s = 0.0
        while (s <= maxS + 1e-9) {
            if (abs(kappa) < 1e-5) {
                out.add(PathPoint(0.0, s))
            } else {
                out.add(PathPoint((1.0 - cos(kappa * s)) / kappa, sin(kappa * s) / kappa))
            }
            s += step
        }
        return out
    }
}

/**
 * Fuses GPS, gyroscope-derived yaw rate, horizontal acceleration, optional camera brightness and
 * optional route geometry into a [HudState]. It has no Android dependencies, so the same engine runs
 * live, in demo mode and when replaying a saved run.
 */
class DriveEngine(route: RouteHorizon?) {

    private var route: RouteHorizon? = route

    private var origin: LatLon? = route?.origin
    private var pos = XY(0.0, 0.0)
    private var hasPos = false
    private var prevFixXY: XY? = null
    private var heading = 0.0
    private var headingKnown = false
    private val speed = SpeedFilter()

    private var startT = -1L
    private var lastT = -1L
    private var lastFixT = -1L
    private var lastFixAcc = 99.0
    private var everFixed = false

    private var yawLp = 0.0
    private var kTrack = 0.0
    private var lastBearing = Double.NaN
    private var lastBearingOdo = 0.0

    private var lumaSm = -1.0
    private var conf = 0.0
    private var routeHint: Int? = null
    private var lastMatchSM = 0.0

    private var odometer = 0.0
    private var maxSpeed = 0.0
    private var movingMs = 0L

    private var brakeMs = 0L
    private var cornerMs = 0L
    private var lastHardBrakeT = -100_000L
    private var lastCornerT = -100_000L
    private var wasHard = false
    private var lastTrackT = -10_000L

    private val eventList = ArrayList<RunEvent>()
    private val trackList = ArrayList<TrackPoint>()

    val events: List<RunEvent> get() = eventList
    val track: List<TrackPoint> get() = trackList
    val distanceM: Double get() = odometer
    val maxSpeedMps: Double get() = maxSpeed
    val elapsedMs: Long get() = if (startT < 0 || lastT < 0) 0 else lastT - startT
    val avgMovingSpeedMps: Double
        get() = if (movingMs > 1000) odometer / (movingMs / 1000.0) else 0.0

    fun process(s: Sample): HudState {
        if (startT < 0) startT = s.tMs
        val dt = if (lastT < 0) 0.0 else ((s.tMs - lastT) / 1000.0).coerceIn(0.0, 0.5)
        lastT = s.tMs
        val elapsed = s.tMs - startT

        if (s.luma >= 0.0) lumaSm = if (lumaSm < 0.0) s.luma else lumaSm + 0.2 * (s.luma - lumaSm)

        // 1) Predict: advance speed, heading and position with what the sensors say.
        speed.predict(dt)
        if (s.hasMotionSensors && headingKnown) heading = Geo.wrap360(heading + s.yawRateDps * dt)
        if (hasPos && speed.speed > 0.0) {
            val h = Geo.rad(heading)
            pos = XY(pos.x + speed.speed * dt * sin(h), pos.y + speed.speed * dt * cos(h))
        }
        val yawAlpha = 1.0 - exp(-dt / 0.6)
        yawLp += (s.yawRateDps - yawLp) * yawAlpha

        // 2) Correct with a new GPS fix, if there is one on this tick.
        s.fix?.let { applyFix(it, s.tMs, elapsed) }

        speed.smooth(dt)
        val v = speed.speed
        if (v > 0.0 && v < 90.0) {
            odometer += v * dt
            if (v > 1.0) movingMs += (dt * 1000).toLong()
        }

        // 3) Curvature of the path the vehicle is on right now.
        val kYaw = if (s.hasMotionSensors && v > 3.0) Geo.rad(yawLp) / v else 0.0
        val kappa = (if (s.hasMotionSensors) 0.6 * kYaw + 0.4 * kTrack else kTrack)
            .coerceIn(-1.0 / 15.0, 1.0 / 15.0)
            .let { if (v > 3.0) it else 0.0 }

        // 4) Route horizon: match to the route, otherwise predict from curvature.
        val kmh = v * 3.6
        var onRoute = false
        var offsetM = 0.0
        var routeProgress = -1.0
        var path: List<PathPoint> = emptyList()
        var curve: CurveAdvice? = null
        var pathSource = PathSource.PREDICTED

        val rt = route
        if (rt != null && hasPos) {
            val m = rt.locate(toRouteFrame(rt, pos), if (headingKnown && v > 2.0) heading else null, routeHint)
            if (m != null && m.distM <= 50.0) {
                onRoute = true
                offsetM = m.distM
                routeHint = m.index
                lastMatchSM = m.sM
                routeProgress = m.sM / rt.lengthM
                val h = if (headingKnown) heading else rt.headingAt(m.index)
                val world = rt.lookahead(m, PATH_RANGE_M)
                path = world.map { Geo.toVehicleFrame(it.x - m.point.x, it.y - m.point.y, h) }
                pathSource = PathSource.ROUTE
                rt.curveAhead(m, CURVE_RANGE_M, kmh)?.let { (c, apexIdx) ->
                    val ap = rt.pointAt(apexIdx)
                    curve = c.copy(apex = Geo.toVehicleFrame(ap.x - m.point.x, ap.y - m.point.y, h))
                }
            }
        }
        if (!onRoute) {
            path = PathPredictor.arc(kappa, PATH_RANGE_M, 5.0)
            val r = if (abs(kappa) > 1e-5) 1.0 / abs(kappa) else Double.MAX_VALUE
            if (v > 6.0 && r < 220.0) {
                curve = CurveAdvice(
                    turn = if (kappa > 0) Turn.RIGHT else Turn.LEFT,
                    distanceM = 0.0,
                    radiusM = r,
                    angleDeg = 0.0,
                    advisedKmh = RouteHorizon.advisedKmh(r, kmh),
                    apex = null,
                )
            }
        }

        // 5) Confidence and graceful-degradation notice.
        val fixAge = if (lastFixT < 0) Long.MAX_VALUE / 4 else s.tMs - lastFixT
        val gps = ConfidenceModel.gpsScore(lastFixAcc, fixAge, everFixed)
        val routeScore = ConfidenceModel.routeScore(route != null, onRoute, offsetM)
        val usesLight = lumaSm >= 0.0
        val light = if (usesLight) ConfidenceModel.lightScore(lumaSm) else 1.0
        val wg = 0.55
        val wr = 0.25
        val wl = if (usesLight) 0.20 else 0.0
        val target = (wg * gps + wr * routeScore + wl * light) / (wg + wr + wl)
        conf += (target - conf) * (1.0 - exp(-dt / 1.2))
        val level = ConfidenceModel.level(conf)

        val gpsStatus = when {
            !everFixed -> GpsStatus.ACQUIRING
            fixAge > 2500 -> GpsStatus.LOST
            lastFixAcc > 25.0 -> GpsStatus.WEAK
            else -> GpsStatus.STRONG
        }
        val lowLight = usesLight && light < 0.45
        val weakGps = gpsStatus == GpsStatus.WEAK || gpsStatus == GpsStatus.LOST
        val backup = if (route != null) "sensors & route" else "motion sensors"
        val relying = if (route != null) "GPS, sensors & route" else "GPS & motion sensors"
        val notice = when {
            gpsStatus == GpsStatus.ACQUIRING -> "Acquiring GPS — horizon will appear when position is locked"
            lowLight && weakGps -> "Low light / Weak GPS — estimating with $backup"
            weakGps -> "Weak GPS — estimating with $backup"
            lowLight -> "Low light — relying on $relying"
            level == ConfidenceLevel.LOW -> "Low confidence — treat the horizon as an estimate"
            else -> null
        }

        // 6) Driving dynamics and run events.
        val a = speed.accel
        val latAccel = abs(v * Geo.rad(yawLp))
        // GPS speed arrives at ~1 Hz, so its derivative lags. The accelerometer reacts instantly: use it
        // to confirm braking once the GPS-derived trend already says "decelerating".
        val latInst = abs(v * Geo.rad(s.yawRateDps))
        val longMag = if (s.hasMotionSensors) sqrt(max(0.0, s.horizAccel * s.horizAccel - latInst * latInst)) else 0.0
        val brakingNow = a < -1.5 || (a < -0.6 && longMag >= 1.8)
        if (brakingNow) brakeMs += (dt * 1000).toLong() else brakeMs = 0
        if (latAccel >= 3.5) cornerMs += (dt * 1000).toLong() else cornerMs = 0
        val hardNow = brakeMs >= 300 && (a < -3.0 || (a < -0.6 && longMag >= 2.8))
        val dynamics = when {
            hardNow -> Dynamics.HARD_BRAKING
            brakeMs >= 400 -> Dynamics.BRAKING
            latAccel >= 2.5 -> Dynamics.CORNERING
            a > 1.5 -> Dynamics.ACCELERATING
            else -> Dynamics.SMOOTH
        }
        if (hardNow && !wasHard && elapsed - lastHardBrakeT > 6000) {
            lastHardBrakeT = elapsed
            addEvent(EventType.HARD_BRAKE, elapsed, v)
        }
        wasHard = hardNow
        if (cornerMs >= 800 && elapsed - lastCornerT > 8000) {
            lastCornerT = elapsed
            addEvent(EventType.SHARP_CORNER, elapsed, v)
        }

        val g = max(s.horizAccel, latAccel) / 9.81

        return HudState(
            tMs = s.tMs,
            elapsedMs = elapsed,
            speedMps = speed.display,
            headingDeg = heading,
            dynamics = dynamics,
            gLoad = g,
            accelMps2 = a,
            confidence = conf,
            level = level,
            gps = gpsStatus,
            lowLight = lowLight,
            lightLevel = lumaSm,
            notice = notice,
            path = path,
            pathSource = pathSource,
            curve = curve,
            routeProgress = routeProgress,
            onRoute = onRoute,
            distanceM = odometer,
            maxSpeedMps = maxSpeed,
        )
    }

    private fun applyFix(f: Fix, tMs: Long, elapsed: Long) {
        val ll = LatLon(f.lat, f.lon)
        if (origin == null) origin = ll
        val o = origin!!
        val xy = Geo.toLocal(o, ll)
        val sinceLast = if (lastFixT < 0) 1.0 else (tMs - lastFixT) / 1000.0

        val gain = when {
            f.accuracyM <= 10.0 -> 0.8
            f.accuracyM <= 25.0 -> 0.5
            f.accuracyM <= 60.0 -> 0.25
            else -> 0.1
        }
        if (!hasPos) {
            pos = xy
            hasPos = true
        } else {
            pos = pos + (xy - pos) * gain
        }

        val prev = prevFixXY
        val disp = if (prev != null) sqrt((xy.x - prev.x) * (xy.x - prev.x) + (xy.y - prev.y) * (xy.y - prev.y)) else 0.0
        val measured = when {
            f.speedMps >= 0.0 -> f.speedMps
            prev != null && sinceLast > 0.05 -> disp / sinceLast
            else -> 0.0
        }
        speed.update(measured, sinceLast)
        if (measured < 90.0 && measured > maxSpeed) maxSpeed = measured

        var bearing = f.bearingDeg
        if (bearing.isNaN() && prev != null && disp > 5.0) {
            bearing = Geo.bearingOf(xy.x - prev.x, xy.y - prev.y)
        }
        if (!bearing.isNaN() && measured > 2.0) {
            heading = if (!headingKnown) bearing else Geo.wrap360(heading + 0.5 * Geo.normalizeDeg(bearing - heading))
            headingKnown = true
            if (!lastBearing.isNaN()) {
                val dd = odometer - lastBearingOdo
                if (dd >= 8.0) {
                    val k = Geo.rad(Geo.normalizeDeg(bearing - lastBearing)) / dd
                    kTrack = 0.5 * kTrack + 0.5 * k.coerceIn(-1.0 / 15.0, 1.0 / 15.0)
                    lastBearing = bearing
                    lastBearingOdo = odometer
                }
            } else {
                lastBearing = bearing
                lastBearingOdo = odometer
            }
        } else if (measured <= 2.0) {
            kTrack *= 0.5
        }

        lastFixT = tMs
        lastFixAcc = f.accuracyM
        everFixed = true
        prevFixXY = xy

        if (elapsed - lastTrackT >= 1000 && trackList.size < MAX_TRACK_POINTS) {
            lastTrackT = elapsed
            trackList.add(TrackPoint(elapsed, f.lat, f.lon, speed.speed, if (headingKnown) heading else 0.0, f.accuracyM))
        }
    }

    /** Swaps the route (e.g. after re-routing). Positions are converted between frames automatically. */
    fun setRoute(r: RouteHorizon?) {
        route = r
        routeHint = null
        if (origin == null) origin = r?.origin
    }

    /** Current fused position estimate, or null before the first fix. */
    fun currentPosition(): LatLon? {
        val o = origin ?: return null
        return if (hasPos) Geo.fromLocal(o, pos) else null
    }

    private fun toRouteFrame(r: RouteHorizon, p: XY): XY {
        val o = origin ?: return p
        return if (o == r.origin) p else Geo.toLocal(r.origin, Geo.fromLocal(o, p))
    }

    private fun addEvent(type: EventType, elapsed: Long, v: Double) {
        val o = origin ?: return
        val ll = Geo.fromLocal(o, pos)
        eventList.add(RunEvent(type, elapsed, odometer, v, ll.lat, ll.lon))
    }

    companion object {
        const val PATH_RANGE_M = 220.0
        const val CURVE_RANGE_M = 400.0
        const val MAX_TRACK_POINTS = 14_400
    }
}
