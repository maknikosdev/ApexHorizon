package com.apexhorizon.app.maps

import com.apexhorizon.app.domain.LatLon
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class MapPackException(message: String) : Exception(message)

data class Place(val name: String, val lat: Double, val lon: Double, val type: Int) {
    val latLon get() = LatLon(lat, lon)

    companion object {
        const val CITY = 0
        const val TOWN = 1
        const val VILLAGE = 2
        const val AREA = 3
        const val STREET = 4
    }
}

/**
 * Read-only view of an offline map pack (.apxm) produced by tools/mapbuilder/build_pack.py.
 * Big arrays stay memory-mapped, so a regional pack costs little heap.
 */
class MapPack private constructor(
    private val channel: FileChannel,
    private val raf: RandomAccessFile,
    val nodeCount: Int,
    val edgeCount: Int,
    private val geomCount: Int,
    val nameCount: Int,
    private val dispBytes: Int,
    private val keyBytes: Int,
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
    val dataVersion: Int,
    private val nodeLatB: IntBuffer, private val nodeLonB: IntBuffer, private val edgeStartB: IntBuffer,
    private val edgeToB: IntBuffer, private val edgeLenB: IntBuffer, private val edgeInfoB: IntBuffer,
    private val edgeGeomOffB: IntBuffer, private val edgeTwinB: IntBuffer,
    private val geomLatB: IntBuffer, private val geomLonB: IntBuffer,
    private val nameTypeB: IntBuffer, private val nameLatB: IntBuffer, private val nameLonB: IntBuffer,
    private val dispOffB: IntBuffer, private val keyOffB: IntBuffer,
    private val dispBlob: ByteBuffer, private val keyBlob: ByteBuffer,
) : AutoCloseable {

    /** Source node of each edge (derived from the CSR offsets once). */
    val edgeFrom = IntArray(edgeCount).also { arr ->
        for (n in 0 until nodeCount) {
            for (e in edgeStartB.get(n) until edgeStartB.get(n + 1)) arr[e] = n
        }
    }

    fun nodeLat(n: Int) = nodeLatB.get(n) / E7
    fun nodeLon(n: Int) = nodeLonB.get(n) / E7
    fun edgeStart(n: Int) = edgeStartB.get(n)
    fun edgeTo(e: Int) = edgeToB.get(e)
    fun edgeLenM(e: Int) = edgeLenB.get(e) / 10.0
    fun edgeSpeedKmh(e: Int) = edgeInfoB.get(e) and 0xFF
    fun edgeClass(e: Int) = (edgeInfoB.get(e) shr 8) and 0x7
    fun edgeIsMotorway(e: Int) = edgeClass(e) == 0
    fun edgeIsReverse(e: Int) = ((edgeInfoB.get(e) shr 11) and 1) == 1
    private fun edgeGeomN(e: Int) = (edgeInfoB.get(e) ushr 12) and 0x3FFFF

    /** True when cars may not use this edge (pedestrian-only way, closed road, or the back direction of a one-way road). */
    fun edgeNoCar(e: Int) = ((edgeInfoB.get(e) shr 30) and 1) == 1
    fun edgeTwin(e: Int) = edgeTwinB.get(e)

    /** Points of an edge from its source node to its target node (both included). */
    fun edgePoints(e: Int): List<LatLon> {
        val out = ArrayList<LatLon>()
        out.add(LatLon(nodeLat(edgeFrom[e]), nodeLon(edgeFrom[e])))
        val n = edgeGeomN(e)
        val off = edgeGeomOffB.get(e)
        if (!edgeIsReverse(e)) {
            for (i in 0 until n) out.add(LatLon(geomLatB.get(off + i) / E7, geomLonB.get(off + i) / E7))
        } else {
            for (i in n - 1 downTo 0) out.add(LatLon(geomLatB.get(off + i) / E7, geomLonB.get(off + i) / E7))
        }
        val t = edgeTo(e)
        out.add(LatLon(nodeLat(t), nodeLon(t)))
        return out
    }

    /** Number of shape points between the end nodes (used by the renderer to simplify at low zoom). */
    fun edgeShapeCount(e: Int) = edgeGeomN(e)

    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon

    // ---- spatial grid over "canonical" edges (reverse flag 0), built lazily ---------------------

    private val cellDeg = 0.02
    private var cols = 0
    private var rows = 0
    private var cellStart: IntArray? = null
    private var cellEdges: IntArray? = null

    @Synchronized
    private fun ensureGrid() {
        if (cellStart != null) return
        cols = ceil((maxLon - minLon) / cellDeg).toInt() + 1
        rows = ceil((maxLat - minLat) / cellDeg).toInt() + 1
        val counts = IntArray(cols * rows + 1)
        fun forEachCell(e: Int, f: (Int) -> Unit) {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (p in edgePoints(e)) {
                a = min(a, p.lat); c = max(c, p.lat); b = min(b, p.lon); d = max(d, p.lon)
            }
            val r0 = clampRow(a); val r1 = clampRow(c); val c0 = clampCol(b); val c1 = clampCol(d)
            for (r in r0..r1) for (cc in c0..c1) f(r * cols + cc)
        }
        for (e in 0 until edgeCount) if (!edgeIsReverse(e)) forEachCell(e) { counts[it + 1]++ }
        for (i in 1 until counts.size) counts[i] += counts[i - 1]
        val fill = counts.copyOf()
        val list = IntArray(counts[counts.size - 1])
        for (e in 0 until edgeCount) if (!edgeIsReverse(e)) forEachCell(e) { list[fill[it]++] = e }
        cellEdges = list
        cellStart = counts
    }

    private fun clampRow(lat: Double) = max(0, min(rows - 1, floor((lat - minLat) / cellDeg).toInt()))
    private fun clampCol(lon: Double) = max(0, min(cols - 1, floor((lon - minLon) / cellDeg).toInt()))

    /** Calls [visit] for each canonical edge whose cells overlap the box (may include a few extra edges). */
    fun forEachEdgeInBox(la0: Double, lo0: Double, la1: Double, lo1: Double, visit: (Int) -> Unit) {
        ensureGrid()
        val starts = cellStart!!
        val list = cellEdges!!
        if (la1 < minLat || la0 > maxLat || lo1 < minLon || lo0 > maxLon) return
        val seen = HashSet<Int>()
        for (r in clampRow(la0)..clampRow(la1)) {
            for (c in clampCol(lo0)..clampCol(lo1)) {
                val cell = r * cols + c
                for (i in starts[cell] until starts[cell + 1]) {
                    val e = list[i]
                    if (seen.add(e)) visit(e)
                }
            }
        }
    }

    // ---- names / search ---------------------------------------------------------------------------

    private var keys: Array<String>? = null
    private var placeIndices: IntArray? = null

    /** Loads the search index into memory. Call from a background thread; it is cached afterwards. */
    @Synchronized
    fun ensureSearchIndex() {
        if (keys != null) return
        val k = Array(nameCount) { i -> text(keyBlob, keyOffB.get(i), keyOffB.get(i + 1)) }
        placeIndices = (0 until nameCount).filter { nameTypeB.get(it) <= Place.AREA }.toIntArray()
        keys = k
    }

    private fun text(blob: ByteBuffer, from: Int, to: Int): String {
        val bytes = ByteArray(to - from)
        for (i in bytes.indices) bytes[i] = blob.get(from + i)
        return String(bytes, Charsets.UTF_8)
    }

    fun placeAt(i: Int) = Place(
        text(dispBlob, dispOffB.get(i), dispOffB.get(i + 1)),
        nameLatB.get(i) / E7, nameLonB.get(i) / E7, nameTypeB.get(i),
    )

    /** Towns/villages inside a box, for map labels. Requires [ensureSearchIndex]. */
    fun placesInBox(la0: Double, lo0: Double, la1: Double, lo1: Double, maxType: Int, limit: Int): List<Place> {
        val idx = placeIndices ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in idx) {
            if (nameTypeB.get(i) > maxType) continue
            val la = nameLatB.get(i) / E7
            val lo = nameLonB.get(i) / E7
            if (la in la0..la1 && lo in lo0..lo1) {
                out.add(placeAt(i))
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Offline place/street search. Accent- and case-insensitive; Greek and Latin names both work. */
    fun search(query: String, limit: Int = 8, near: LatLon? = null): List<Place> {
        ensureSearchIndex()
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val k = keys!!
        class Hit(val idx: Int, val score: Int)
        val hits = ArrayList<Hit>()
        for (i in k.indices) {
            val key = k[i]
            val score = when {
                key == q -> 0
                key.startsWith(q) -> 1
                key.contains(" $q") -> 2
                key.contains(q) -> 3
                else -> continue
            }
            hits.add(Hit(i, score))
        }
        val cosLat = near?.let { cos(Math.toRadians(it.lat)) } ?: 1.0
        fun dist(i: Int): Double {
            if (near == null) return 0.0
            val dy = nameLatB.get(i) / E7 - near.lat
            val dx = (nameLonB.get(i) / E7 - near.lon) * cosLat
            return dx * dx + dy * dy
        }
        hits.sortWith(
            compareBy<Hit> { it.score }
                .thenBy { nameTypeB.get(it.idx) }
                .thenBy { dist(it.idx) }
                .thenBy { k[it.idx].length },
        )
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        for (h in hits) {
            val p = placeAt(h.idx)
            if (seen.add(p.name + "|" + p.type + "|" + (p.lat * 50).toInt() + "|" + (p.lon * 50).toInt())) out.add(p)
            if (out.size >= limit) break
        }
        return out
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    companion object {
        private const val E7 = 1e7
        private const val MAGIC = 0x4D585041
        private const val SUPPORTED_VERSION = 1

        /** Must match norm() in build_pack.py. */
        fun normalize(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (ch in d) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(ch)
            }
            val lower = sb.toString().lowercase(Locale.ROOT).replace('ς', 'σ')
            val cleaned = StringBuilder(lower.length)
            for (ch in lower) cleaned.append(if (ch.isLetterOrDigit()) ch else ' ')
            return cleaned.toString().trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** Reads only the header (cheap); used to list installed packs. */
        fun readHeader(file: File): Header? = runCatching {
            RandomAccessFile(file, "r").use { f ->
                val b = ByteArray(64)
                f.readFully(b)
                val h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                if (h.get(0) != MAGIC || h.get(1) != SUPPORTED_VERSION) return null
                Header(h.get(12), h.get(8) / E7, h.get(9) / E7, h.get(10) / E7, h.get(11) / E7)
            }
        }.getOrNull()

        data class Header(val dataVersion: Int, val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

        fun open(file: File): MapPack {
            val raf = RandomAccessFile(file, "r")
            try {
                val ch = raf.channel
                val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
                val h = IntArray(16) { map.getInt(it * 4) }
                if (h[0] != MAGIC) throw MapPackException("Not an Apex Horizon map file")
                if (h[1] != SUPPORTED_VERSION) throw MapPackException("Unsupported map format version ${h[1]}")
                val n = h[2]; val e = h[3]; val g = h[4]; val m = h[5]; val d = h[6]; val kb = h[7]
                var pos = 64
                fun ints(count: Int): IntBuffer {
                    val dup = map.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    dup.position(pos)
                    dup.limit(pos + count * 4)
                    pos += count * 4
                    return dup.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                }
                val nodeLat = ints(n); val nodeLon = ints(n); val edgeStart = ints(n + 1)
                val edgeTo = ints(e); val edgeLen = ints(e); val edgeInfo = ints(e)
                val edgeGeomOff = ints(e); val edgeTwin = ints(e)
                val geomLat = ints(g); val geomLon = ints(g)
                val nType = ints(m); val nLat = ints(m); val nLon = ints(m)
                val dOff = ints(m + 1); val kOff = ints(m + 1)
                fun bytes(count: Int): ByteBuffer {
                    val dup = map.duplicate()
                    dup.position(pos)
                    dup.limit(pos + count)
                    return dup.slice()
                }
                val dispBlob = bytes(d)
                pos += d + ((4 - d % 4) % 4)
                val keyBlob = bytes(kb)
                if (pos + kb > map.capacity()) throw MapPackException("Map file is truncated")
                return MapPack(
                    ch, raf, n, e, g, m, d, kb,
                    h[8] / E7, h[9] / E7, h[10] / E7, h[11] / E7, h[12],
                    nodeLat, nodeLon, edgeStart, edgeTo, edgeLen, edgeInfo, edgeGeomOff, edgeTwin,
                    geomLat, geomLon, nType, nLat, nLon, dOff, kOff, dispBlob, keyBlob,
                )
            } catch (ex: Exception) {
                runCatching { raf.close() }
                if (ex is MapPackException) throw ex
                throw MapPackException("Could not read map file: ${ex.message}")
            }
        }
    }
}
