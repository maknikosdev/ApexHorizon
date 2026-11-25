package com.apexhorizon.app.domain

import com.apexhorizon.app.maps.MapPack
import com.apexhorizon.app.maps.Place
import com.apexhorizon.app.maps.Router
import com.apexhorizon.app.maps.TravelMode
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs

class MapPackTest {

    companion object {
        lateinit var pack: MapPack
        lateinit var router: Router

        @JvmStatic
        @BeforeClass
        fun load() {
            val f = File(MapPackTest::class.java.getResource("/test.apxm")!!.toURI())
            pack = MapPack.open(f)
            router = Router(pack)
        }

        @JvmStatic
        @AfterClass
        fun close() = pack.close()

        private const val LAT0 = 39.6400
        private const val LON0 = 22.4100
        private const val STEP = 0.002
    }

    private fun nodeAt(r: Int, c: Int): Int {
        val lat = LAT0 + r * STEP + 0.0003 * Math.sin(c / 3.0)
        val lon = LON0 + c * STEP
        var best = -1
        var bd = Double.MAX_VALUE
        for (n in 0 until pack.nodeCount) {
            val d = Geo.distance(LatLon(lat, lon), LatLon(pack.nodeLat(n), pack.nodeLon(n)))
            if (d < bd) { bd = d; best = n }
        }
        assertTrue("node ($r,$c) must exist", bd < 2.0)
        return best
    }

    private fun ll(n: Int) = LatLon(pack.nodeLat(n), pack.nodeLon(n))

    /** Reference: plain Dijkstra on node costs only. */
    private fun dijkstra(src: Int, dst: Int): Double {
        val dist = DoubleArray(pack.nodeCount) { Double.MAX_VALUE }
        dist[src] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq.add(Pair(0.0, src))
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > dist[u]) continue
            if (u == dst) return d
            for (e in pack.edgeStart(u) until pack.edgeStart(u + 1)) {
                val v = pack.edgeTo(e)
                val c = d + pack.edgeLenM(e) / (pack.edgeSpeedKmh(e) / 3.6)
                if (c < dist[v]) { dist[v] = c; pq.add(Pair(c, v)) }
            }
        }
        return Double.MAX_VALUE
    }

    @Test
    fun headerAndSizes() {
        assertEquals(625, pack.nodeCount)
        assertEquals(20261125, pack.dataVersion)
        assertTrue(pack.contains(39.65, 22.42))
    }

    @Test
    fun curvyRoadHasShapePoints() {
        val e = (0 until pack.edgeCount).first { pack.edgeShapeCount(it) >= 10 }
        val pts = pack.edgePoints(e)
        assertTrue(pts.size >= 12)
        // reverse twin must contain the same points in opposite order
        val t = pack.edgeTwin(e)
        assertTrue(t >= 0)
        val rp = pack.edgePoints(t)
        assertEquals(pts.size, rp.size)
        assertEquals(pts.first().lat, rp.last().lat, 1e-6)
        assertEquals(pts[3].lon, rp[rp.size - 4].lon, 1e-6)
    }

    @Test
    fun routerMatchesDijkstraOnNodePairs() {
        val pairs = listOf(Pair(Pair(0, 0), Pair(24, 24)), Pair(Pair(3, 20), Pair(22, 2)), Pair(Pair(12, 5), Pair(12, 6)), Pair(Pair(24, 0), Pair(0, 24)), Pair(Pair(7, 9), Pair(8, 9)))
        for ((a, b) in pairs) {
            val na = nodeAt(a.first, a.second)
            val nb = nodeAt(b.first, b.second)
            val plan = router.route(ll(na), ll(nb))
            assertNotNull("route $a -> $b", plan)
            val ref = dijkstra(na, nb)
            assertEquals("time $a -> $b", ref, plan!!.timeS, 1.5)
            // path starts and ends at the requested points
            assertTrue(Geo.distance(plan.points.first(), ll(na)) < 3.0)
            assertTrue(Geo.distance(plan.points.last(), ll(nb)) < 3.0)
            assertTrue(plan.distanceM > 100.0)
        }
    }

    @Test
    fun routerRespectsOneWayStreet() {
        // Column 5 is one-way northwards (increasing row). Going south along it must take a detour.
        val north = router.route(ll(nodeAt(2, 5)), ll(nodeAt(10, 5)))!!
        val south = router.route(ll(nodeAt(10, 5)), ll(nodeAt(2, 5)))!!
        assertTrue("south trip should be longer: ${south.distanceM} vs ${north.distanceM}", south.distanceM > north.distanceM + 200.0)
    }

    @Test
    fun walkerIgnoresOneWayAndCanUseFootways() {
        val a = ll(nodeAt(10, 5)); val b = ll(nodeAt(2, 5))
        val car = router.route(a, b, TravelMode.CAR)!!
        val walk = router.route(a, b, TravelMode.WALK)!!
        assertTrue("walker goes straight against the one-way: ${walk.distanceM} vs ${car.distanceM}", walk.distanceM < car.distanceM - 200.0)
        assertTrue(walk.distanceM in 1700.0..1900.0)
        // walking pace
        assertEquals(walk.distanceM / (5.0 / 3.6), walk.timeS, walk.timeS * 0.02)

        // The footway shortcut (2,2) -> (10,10) is walkers-only.
        val p = ll(nodeAt(2, 2)); val q = ll(nodeAt(10, 10))
        val w = router.route(p, q, TravelMode.WALK)!!
        val c = router.route(p, q, TravelMode.CAR)!!
        assertTrue("walk ${w.distanceM} should beat car ${c.distanceM}", w.distanceM < c.distanceM - 100.0)
        assertTrue(w.distanceM < Geo.distance(p, q) * 1.05)
        // a car must never be routed over the footway: its snap is rejected
        val t = 0.3125 // middle of a city block, far from every grid road
        val mid = LatLon(p.lat + t * (q.lat - p.lat), p.lon + t * (q.lon - p.lon))
        val snapCar = router.snap(mid, TravelMode.CAR, 30.0)
        assertTrue(snapCar == null)
        assertNotNull(router.snap(mid, TravelMode.WALK, 30.0))
    }

    @Test
    fun routerSnapsOffRoadPointsAndHandlesSameEdge() {
        val a = LatLon(LAT0 + 6 * STEP + 0.0001, LON0 + 8.3 * STEP)
        val b = LatLon(LAT0 + 6 * STEP + 0.0001, LON0 + 8.6 * STEP)
        val plan = router.route(a, b)
        assertNotNull(plan)
        assertTrue(plan!!.distanceM in 20.0..120.0)
        assertNull(router.route(LatLon(40.5, 23.5), b))
    }

    @Test
    fun searchIsAccentAndCaseInsensitiveInBothAlphabets() {
        val greek = pack.search("λαρισα")
        assertTrue(greek.any { it.type == Place.CITY })
        val latin = pack.search("LARISSA")
        assertTrue(latin.first().name == "Larissa" || latin.first().name == "Λάρισα")
        val street = pack.search("row street 12")
        assertTrue(street.any { it.name == "Row Street 12" })
        assertTrue(pack.search("Τεστ").any { it.type == Place.VILLAGE })
        assertEquals("larissa", MapPack.normalize("  Λάρισα-Larissa ".split("-")[1]))
        assertEquals("σσοσ", MapPack.normalize("ΣΣΌς"))
    }

    @Test
    fun routeFeedsRouteHorizon() {
        val plan = router.route(ll(nodeAt(0, 24)), ll(nodeAt(24, 0)))!!
        val rh = RouteHorizon.create("t", plan.points, RouteHorizon.Kind.IMPORTED)
        assertNotNull(rh)
        assertTrue(abs(rh!!.lengthM - plan.distanceM) < plan.distanceM * 0.05)
    }
}
