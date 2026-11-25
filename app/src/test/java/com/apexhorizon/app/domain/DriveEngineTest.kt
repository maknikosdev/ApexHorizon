package com.apexhorizon.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEngineTest {

    @Test
    fun demoRouteIsResampledAndLongEnough() {
        val route = DemoScenario.route()
        assertNotNull(route)
        assertTrue(route!!.lengthM > 2500.0)
    }

    @Test
    fun suggestedSpeedMatchesScenario() {
        // Left curve R≈100 m taken at 82 km/h should suggest ~60 km/h.
        assertEquals(60, RouteHorizon.advisedKmh(100.0, 82.0))
        // Already slow enough: no advice.
        assertNull(RouteHorizon.advisedKmh(100.0, 55.0))
    }

    @Test
    fun routeModeFindsCurveAheadAndAdvisesSpeed() {
        val route = DemoScenario.route()!!
        val engine = DriveEngine(route)
        var sawCurveAdvice = false
        var sawOnRoute = false
        for (s in DemoScenario.samples()) {
            val hud = engine.process(s)
            if (hud.onRoute) sawOnRoute = true
            val c = hud.curve
            if (c != null && c.distanceM > 50.0 && c.advisedKmh != null) sawCurveAdvice = true
        }
        assertTrue(sawOnRoute)
        assertTrue(sawCurveAdvice)
        assertTrue(engine.distanceM in 2800.0..3300.0)
    }

    @Test
    fun confidenceDropsDuringGpsBlackoutAndNoticeIsShown() {
        val engine = DriveEngine(DemoScenario.route())
        var minInBlackout = 1.0
        var noticeSeen = false
        var before = 0.0
        for (s in DemoScenario.samples()) {
            val hud = engine.process(s)
            if (s.tMs == 90_000L) before = hud.confidence
            if (s.tMs in 108_000..111_000) {
                minInBlackout = minOf(minInBlackout, hud.confidence)
                if (hud.notice?.contains("Weak GPS") == true) noticeSeen = true
            }
        }
        assertTrue("confidence should fall ($before -> $minInBlackout)", minInBlackout < before - 0.2)
        assertTrue(noticeSeen)
    }

    @Test
    fun hardBrakingIsDetectedInDemo() {
        val engine = DriveEngine(DemoScenario.route())
        DemoScenario.samples().forEach { engine.process(it) }
        assertTrue(engine.events.any { it.type == EventType.HARD_BRAKE })
    }

    @Test
    fun speedStaysSmoothBetweenFixes() {
        val engine = DriveEngine(null)
        var last = -1.0
        var maxJump = 0.0
        for (s in DemoScenario.samples()) {
            val v = engine.process(s).speedMps
            if (last >= 0) maxJump = maxOf(maxJump, kotlin.math.abs(v - last))
            last = v
        }
        // One tick is 200 ms; a jump above 2.5 m/s per tick would look nervous on screen.
        assertTrue("max per-tick jump was $maxJump", maxJump < 2.5)
    }

    @Test
    fun routeMatchingIgnoresOppositeDirection() {
        val route = DemoScenario.route()!!
        val p = route.pointAt(20)
        val heading = route.headingAt(20)
        val forward = route.locate(p, heading, null)
        assertNotNull(forward)
        val backward = route.locate(p, Geo.wrap360(heading + 180.0), null)
        assertTrue(backward == null || backward.distM > 40.0 || kotlin.math.abs(backward.index - 20) > 3)
    }

    @Test
    fun geoRoundTrip() {
        val o = LatLon(39.9, 22.6)
        val ll = Geo.fromLocal(o, Geo.toLocal(o, LatLon(39.905, 22.61)))
        assertEquals(39.905, ll.lat, 1e-9)
        assertEquals(22.61, ll.lon, 1e-9)
    }
}
