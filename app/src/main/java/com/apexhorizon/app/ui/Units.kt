package com.apexhorizon.app.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Formatting helpers. Always English / US number format so the UI is consistent. */
object Units {
    fun speed(mps: Double, mph: Boolean): Int = (mps * if (mph) 2.23694 else 3.6).roundToInt()
    fun speedLabel(mph: Boolean) = if (mph) "mph" else "km/h"
    fun speedText(mps: Double, mph: Boolean) = "${speed(mps, mph)} ${speedLabel(mph)}"

    fun distance(m: Double, mph: Boolean): String =
        if (mph) {
            val miles = m / 1609.344
            String.format(Locale.US, "%.1f mi", miles)
        } else if (m < 1000) {
            "${m.roundToInt()} m"
        } else {
            String.format(Locale.US, "%.2f km", m / 1000.0)
        }

    /** Short distance for in-drive hints, rounded to 10 m (or 5 m when close). */
    fun shortDistance(m: Double, mph: Boolean): String {
        if (mph) {
            val ft = m * 3.28084
            return if (ft < 1000) "${(ft / 10.0).roundToInt() * 10} ft" else String.format(Locale.US, "%.1f mi", m / 1609.344)
        }
        val step = if (m < 60) 5.0 else 10.0
        return "${((m / step).roundToInt() * step).roundToInt()} m"
    }

    fun advisedSpeed(kmh: Int, mph: Boolean): Int =
        if (mph) ((kmh * 0.621371) / 5.0).roundToInt() * 5 else kmh

    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
