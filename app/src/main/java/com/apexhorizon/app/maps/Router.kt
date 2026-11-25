package com.apexhorizon.app.maps

import com.apexhorizon.app.domain.Geo
import com.apexhorizon.app.domain.LatLon
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TravelMode { CAR, WALK }

data class RoutePlan(
    val points: List<LatLon>,
    val distanceM: Double,
    val timeS: Double,
)

/** A point snapped onto the road network. */
class Snap(
    val edge: Int,
    val seg: Int,
    val point: LatLon,
    val distM: Double,
    val pts: List<LatLon>,
    /** Metres along the edge from its source node to [point]. */
    val alongM: Double,
    val edgeLenM: Double,
)

/** Time-based A* on the pack's road graph. Turn restrictions are not modelled. */
class Router(private val pack: MapPack) {

    private fun revOk(e: Int, mode: TravelMode) = pack.edgeTwin(e) >= 0 && allowed(pack.edgeTwin(e), mode)

    private fun allowed(e: Int, mode: TravelMode) =
        if (mode == TravelMode.CAR) !pack.edgeNoCar(e) else !pack.edgeIsMotorway(e)


    fun snap(p: LatLon, mode: TravelMode = TravelMode.CAR, maxDistM: Double = 400.0): Snap? {
        val deg = maxDistM / 111_320.0 + 0.005
        var best: Snap? = null
        var bestD = Double.MAX_VALUE
        val cosLat = Math.cos(Math.toRadians(p.lat))
        pack.forEachEdgeInBox(p.lat - deg, p.lon - deg / cosLat, p.lat + deg, p.lon + deg / cosLat) { e ->
            // The canonical edge may be car-closed in one direction only; it is usable if either direction is.
            if (!allowed(e, mode) && (pack.edgeTwin(e) < 0 || !allowed(pack.edgeTwin(e), mode))) return@forEachEdgeInBox
            val pts = pack.edgePoints(e)
            var along = 0.0
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val ax = 0.0; val ay = 0.0
                val bx = (b.lon - a.lon) * cosLat * 111_320.0
                val by = (b.lat - a.lat) * 111_320.0
                val px = (p.lon - a.lon) * cosLat * 111_320.0
                val py = (p.lat - a.lat) * 111_320.0
                val len2 = bx * bx + by * by
                val t = if (len2 < 1e-9) 0.0 else (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0)
                val dx = px - t * bx
                val dy = py - t * by
                val d = sqrt(dx * dx + dy * dy)
                val segLen = sqrt(len2)
                if (d < bestD) {
                    bestD = d
                    val q = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
                    best = Snap(e, i, q, d, pts, along + t * segLen, 0.0)
                }
                along += segLen
            }
        }
        val s = best ?: return null
        if (s.distM > maxDistM) return null
        var total = 0.0
        for (i in 0 until s.pts.size - 1) total += Geo.distance(s.pts[i], s.pts[i + 1])
        return Snap(s.edge, s.seg, s.point, s.distM, s.pts, s.alongM, total)
    }

    private fun speedMs(e: Int, mode: TravelMode) = if (mode == TravelMode.WALK) 5.0 / 3.6 else max(5, pack.edgeSpeedKmh(e)) / 3.6

    fun route(from: LatLon, to: LatLon, mode: TravelMode = TravelMode.CAR): RoutePlan? {
        val a = snap(from, mode) ?: return null
        val b = snap(to, mode) ?: return null

        // Both points on the same road piece: go straight along it when possible.
        if (a.edge == b.edge) {
            val sp = speedMs(a.edge, mode)
            if (b.alongM >= a.alongM && allowed(a.edge, mode)) return direct(a, b, forward = true, sp)
            if (b.alongM < a.alongM && revOk(a.edge, mode)) return direct(a, b, forward = false, sp)
        }

        val n = pack.nodeCount
        val g = FloatArray(n) { Float.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val settled = BooleanArray(n)
        val pq = PriorityQueue<Long>()
        val vMax = (if (mode == TravelMode.WALK) 5.0 else 130.0) / 3.6
        val cosLat = Math.cos(Math.toRadians(to.lat))
        fun h(node: Int): Float {
            val dx = (pack.nodeLon(node) - to.lon) * cosLat * 111_320.0
            val dy = (pack.nodeLat(node) - to.lat) * 111_320.0
            return (sqrt(dx * dx + dy * dy) / vMax).toFloat()
        }
        fun push(node: Int) {
            val f = g[node] + h(node)
            pq.add((java.lang.Float.floatToIntBits(f).toLong() shl 32) or node.toLong())
        }

        val aU = pack.edgeFrom[a.edge]
        val aV = pack.edgeTo(a.edge)
        val aSp = speedMs(a.edge, mode)
        val seeds = HashMap<Int, Float>()
        if (allowed(a.edge, mode)) seeds[aV] = ((a.edgeLenM - a.alongM) / aSp).toFloat()
        if (revOk(a.edge, mode)) {
            val c = (a.alongM / aSp).toFloat()
            if (c < (seeds[aU] ?: Float.MAX_VALUE)) seeds[aU] = c
        }
        for ((node, c) in seeds) {
            g[node] = c
            push(node)
        }

        val bU = pack.edgeFrom[b.edge]
        val bV = pack.edgeTo(b.edge)
        val bSp = speedMs(b.edge, mode)
        val targets = HashMap<Int, Float>()
        if (allowed(b.edge, mode)) targets[bU] = (b.alongM / bSp).toFloat()
        if (revOk(b.edge, mode)) {
            val c = ((b.edgeLenM - b.alongM) / bSp).toFloat()
            if (c < (targets[bV] ?: Float.MAX_VALUE)) targets[bV] = c
        }

        if (seeds.isEmpty() || targets.isEmpty()) return null

        var best = Float.MAX_VALUE
        var bestNode = -1
        while (pq.isNotEmpty()) {
            val key = pq.poll()
            val f = java.lang.Float.intBitsToFloat((key ushr 32).toInt())
            val node = (key and 0xFFFFFFFFL).toInt()
            if (f >= best) break
            if (settled[node]) continue
            settled[node] = true
            targets[node]?.let { extra ->
                if (g[node] + extra < best) {
                    best = g[node] + extra
                    bestNode = node
                }
            }
            for (e in pack.edgeStart(node) until pack.edgeStart(node + 1)) {
                val to2 = pack.edgeTo(e)
                if (settled[to2] || !allowed(e, mode)) continue
                val c = g[node] + (pack.edgeLenM(e) / speedMs(e, mode)).toFloat()
                if (c < g[to2]) {
                    g[to2] = c
                    prev[to2] = e
                    push(to2)
                }
            }
        }
        if (bestNode < 0) return null

        // Rebuild the node/edge path.
        val edges = ArrayList<Int>()
        var cur = bestNode
        while (prev[cur] >= 0 && !(seeds.containsKey(cur) && g[cur] == seeds[cur])) {
            val e = prev[cur]
            edges.add(e)
            cur = pack.edgeFrom[e]
        }
        edges.reverse()
        val startNode = cur

        val pts = ArrayList<LatLon>()
        pts.add(a.point)
        if (startNode == aV) {
            for (i in a.seg + 1 until a.pts.size) pts.add(a.pts[i])
        } else {
            for (i in a.seg downTo 0) pts.add(a.pts[i])
        }
        for (e in edges) {
            val ep = pack.edgePoints(e)
            for (i in 1 until ep.size) pts.add(ep[i])
        }
        if (bestNode == bU) {
            for (i in 1..b.seg) pts.add(b.pts[i])
        } else {
            for (i in b.pts.size - 2 downTo b.seg + 1) pts.add(b.pts[i])
        }
        pts.add(b.point)

        var dist = 0.0
        for (i in 0 until pts.size - 1) dist += Geo.distance(pts[i], pts[i + 1])
        return RoutePlan(pts, dist, best.toDouble())
    }

    private fun direct(a: Snap, b: Snap, forward: Boolean, speed: Double): RoutePlan {
        val pts = ArrayList<LatLon>()
        pts.add(a.point)
        if (forward) {
            for (i in a.seg + 1..b.seg) pts.add(a.pts[i])
        } else {
            for (i in a.seg downTo b.seg + 1) pts.add(a.pts[i])
        }
        pts.add(b.point)
        var d = 0.0
        for (i in 0 until pts.size - 1) d += Geo.distance(pts[i], pts[i + 1])
        return RoutePlan(pts, d, d / speed)
    }
}
