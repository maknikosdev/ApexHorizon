package com.apexhorizon.app.domain

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Alpha-beta filter for ground speed. Between GPS fixes the speed is advanced with the estimated
 * acceleration so the displayed value moves smoothly instead of jumping once per second.
 */
class SpeedFilter(private val alpha: Double = 0.55, private val beta: Double = 0.2) {
    var speed = 0.0
        private set

    /** Estimated longitudinal acceleration in m/s². */
    var accel = 0.0
        private set

    /** Speed shown on screen: the filter output, eased so a GPS correction never makes the number jump. */
    var display = 0.0
        private set

    private var initialised = false

    fun smooth(dt: Double) {
        if (!initialised) return
        display += (speed - display) * (1.0 - exp(-dt / 0.45))
    }

    fun predict(dt: Double) {
        if (!initialised || dt <= 0.0) return
        speed = max(0.0, speed + accel * dt)
        accel *= exp(-dt / 5.0)
    }

    fun update(measured: Double, dtSinceLastFix: Double) {
        val m = max(0.0, measured)
        if (!initialised) {
            speed = m
            display = m
            accel = 0.0
            initialised = true
            return
        }
        val residual = m - speed
        speed += alpha * residual
        val dt = max(0.2, dtSinceLastFix)
        accel += beta * residual / dt
        accel = min(8.0, max(-9.0, accel))
    }
}
