package com.apexhorizon.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.apexhorizon.app.domain.LatLon

/** Last known position from the system (no new location request, no network). */
object LastLocation {
    @SuppressLint("MissingPermission")
    fun get(context: Context): LatLon? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return LatLon(best.latitude, best.longitude)
    }
}
