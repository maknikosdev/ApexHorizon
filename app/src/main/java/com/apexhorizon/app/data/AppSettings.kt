package com.apexhorizon.app.data

import android.content.Context

data class UiSettings(
    val useMph: Boolean = false,
    val useCamera: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)

/** Tiny SharedPreferences wrapper. Everything stays on the device. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)

    fun load() = UiSettings(
        useMph = prefs.getBoolean(KEY_MPH, false),
        useCamera = prefs.getBoolean(KEY_CAMERA, true),
        disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER, false),
    )

    fun save(s: UiSettings) {
        prefs.edit()
            .putBoolean(KEY_MPH, s.useMph)
            .putBoolean(KEY_CAMERA, s.useCamera)
            .putBoolean(KEY_DISCLAIMER, s.disclaimerAccepted)
            .apply()
    }

    private companion object {
        const val KEY_MPH = "use_mph"
        const val KEY_CAMERA = "use_camera"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
    }
}
