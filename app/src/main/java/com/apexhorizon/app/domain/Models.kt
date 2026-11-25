package com.apexhorizon.app.domain

/** A GPS fix delivered by the platform (or by the replay engine). */
data class Fix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    /** Ground speed in m/s, or a negative value when unknown. */
    val speedMps: Double,
    /** Course over ground in degrees, or NaN when unknown. */
    val bearingDeg: Double,
    val accuracyM: Double,
)

/**
 * One fixed-rate tick of all inputs. [fix] is non-null only on the tick where a new GPS fix arrived.
 * [luma] is mean frame brightness 0..1, or a negative value when no camera is in use.
 */
data class Sample(
    val tMs: Long,
    val fix: Fix?,
    /** Rotation rate around the vertical axis in deg/s, positive = turning right. */
    val yawRateDps: Double,
    /** Magnitude of horizontal acceleration in m/s². */
    val horizAccel: Double,
    val luma: Double,
    val hasMotionSensors: Boolean,
)

enum class Dynamics { SMOOTH, ACCELERATING, BRAKING, HARD_BRAKING, CORNERING }
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }
enum class GpsStatus { ACQUIRING, STRONG, WEAK, LOST }
enum class Turn { LEFT, RIGHT }
enum class PathSource { ROUTE, PREDICTED }

/** Point in the vehicle frame: x metres to the right, z metres forward. */
data class PathPoint(val x: Double, val z: Double)

data class CurveAdvice(
    val turn: Turn,
    /** Distance from the vehicle to the start of the curve, in metres. 0 = in the curve. */
    val distanceM: Double,
    val radiusM: Double,
    val angleDeg: Double,
    /** Suggested approach speed in km/h; null when the current speed is already fine. */
    val advisedKmh: Int?,
    /** Apex position in the vehicle frame (route mode only). */
    val apex: PathPoint?,
)

data class HudState(
    val tMs: Long = 0,
    val elapsedMs: Long = 0,
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    val dynamics: Dynamics = Dynamics.SMOOTH,
    val gLoad: Double = 0.0,
    /** Longitudinal acceleration estimate in m/s² (negative = braking). */
    val accelMps2: Double = 0.0,
    val confidence: Double = 0.0,
    val level: ConfidenceLevel = ConfidenceLevel.LOW,
    val gps: GpsStatus = GpsStatus.ACQUIRING,
    val lowLight: Boolean = false,
    val lightLevel: Double = -1.0,
    val notice: String? = null,
    val path: List<PathPoint> = emptyList(),
    val pathSource: PathSource = PathSource.PREDICTED,
    val curve: CurveAdvice? = null,
    val routeProgress: Double = -1.0,
    val onRoute: Boolean = false,
    val distanceM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
)

enum class EventType { HARD_BRAKE, SHARP_CORNER }

data class RunEvent(
    val type: EventType,
    /** Time since the start of the run. */
    val tMs: Long,
    val distanceM: Double,
    val speedMps: Double,
    val lat: Double,
    val lon: Double,
)

data class TrackPoint(
    /** Time since the start of the run. */
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val accuracyM: Double,
)

data class RunSummary(
    val id: String,
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val events: List<RunEvent>,
    val usedCamera: Boolean,
    val isDemo: Boolean,
    /** Downsampled track (kept in memory for the summary charts and replay). */
    val track: List<TrackPoint>,
)
