package com.apexhorizon.app.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Snapshot of what this device and the current permissions allow. */
data class Capabilities(
    val locationGranted: Boolean,
    val cameraGranted: Boolean,
    val locationEnabled: Boolean,
    val hasCamera: Boolean,
    val hasGyro: Boolean,
    val hasAccel: Boolean,
) {
    val motionReady: Boolean get() = hasGyro && hasAccel

    companion object {
        fun read(context: Context): Capabilities {
            val pm = context.packageManager
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            return Capabilities(
                locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                locationEnabled = lm != null && LocationManagerCompat.isLocationEnabled(lm),
                hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                hasGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                hasAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            )
        }
    }
}
