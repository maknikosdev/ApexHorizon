package com.apexhorizon.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.apexhorizon.app.domain.Fix
import com.apexhorizon.app.domain.ReplayBuilder
import com.apexhorizon.app.domain.Sample
import kotlin.math.sqrt

/**
 * Collects GPS, gyroscope and accelerometer data and emits one [Sample] every 200 ms on the main
 * thread. Everything is released in [stop]; nothing runs in the background.
 *
 * Yaw rate is the gyroscope rotation projected on the gravity axis, so it is correct for any phone
 * orientation (portrait, landscape, tilted mount).
 */
class LiveSampleSource(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var onSample: ((Sample) -> Unit)? = null
    private var running = false

    @Volatile
    var luma: Double = -1.0

    private var pendingFix: Fix? = null
    private val gravity = FloatArray(3)
    private var gravityInit = false
    private var yawDps = 0.0
    private var horiz = 0.0
    private var gotGyro = false
    private var gotAccel = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            pendingFix = Fix(
                tMs = SystemClock.elapsedRealtime(),
                lat = location.latitude,
                lon = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0,
            )
        }

        @Deprecated("Required on older API levels")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccel(e.values)
                Sensor.TYPE_GYROSCOPE -> onGyro(e.values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val fix = pendingFix
            pendingFix = null
            onSample?.invoke(
                Sample(
                    tMs = SystemClock.elapsedRealtime(),
                    fix = fix,
                    yawRateDps = if (gotGyro && gotAccel) yawDps else 0.0,
                    horizAccel = if (gotAccel) horiz else 0.0,
                    luma = luma,
                    hasMotionSensors = gotGyro && gotAccel,
                ),
            )
            handler.postDelayed(this, ReplayBuilder.TICK_MS)
        }
    }

    private fun onAccel(v: FloatArray) {
        gotAccel = true
        if (!gravityInit) {
            gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2]
            gravityInit = true
        } else {
            for (i in 0..2) gravity[i] = GRAVITY_ALPHA * gravity[i] + (1 - GRAVITY_ALPHA) * v[i]
        }
        val gn = norm(gravity)
        if (gn < 1e-3f) return
        val ux = gravity[0] / gn
        val uy = gravity[1] / gn
        val uz = gravity[2] / gn
        val lx = v[0] - gravity[0]
        val ly = v[1] - gravity[1]
        val lz = v[2] - gravity[2]
        val vertical = lx * ux + ly * uy + lz * uz
        val hx = lx - vertical * ux
        val hy = ly - vertical * uy
        val hz = lz - vertical * uz
        val mag = sqrt((hx * hx + hy * hy + hz * hz).toDouble())
        horiz += (mag - horiz) * HORIZ_ALPHA
    }

    private fun onGyro(w: FloatArray) {
        gotGyro = true
        val gn = norm(gravity)
        if (!gravityInit || gn < 1e-3f) return
        val aroundUp = (w[0] * gravity[0] + w[1] * gravity[1] + w[2] * gravity[2]) / gn
        // Positive rotation about "up" is a left turn; the HUD uses positive = right.
        val dps = -Math.toDegrees(aroundUp.toDouble())
        yawDps += (dps - yawDps) * YAW_ALPHA
    }

    private fun norm(v: FloatArray) = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()

    /** Starts all sensors. Returns false when location permission is missing. */
    @SuppressLint("MissingPermission")
    fun start(listener: (Sample) -> Unit): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        onSample = listener
        running = true
        gravityInit = false
        gotGyro = false
        gotAccel = false
        yawDps = 0.0
        horiz = 0.0
        pendingFix = null
        try {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            provider?.let { locationManager.requestLocationUpdates(it, 1000L, 0f, locationListener, Looper.getMainLooper()) }
        } catch (_: SecurityException) {
            running = false
            return false
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(ticker)
        return true
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { sensorManager.unregisterListener(sensorListener) }
        onSample = null
    }

    private companion object {
        const val GRAVITY_ALPHA = 0.92f
        const val HORIZ_ALPHA = 0.12
        const val YAW_ALPHA = 0.3
    }
}
