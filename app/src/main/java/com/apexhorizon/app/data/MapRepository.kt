package com.apexhorizon.app.data

import com.apexhorizon.app.maps.MapPack
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A region offered by the map server (one entry of manifest.json). */
data class RemoteRegion(
    val id: String,
    val name: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val dataVersion: Int,
)

/** A region present on this device. */
data class InstalledRegion(
    val id: String,
    val file: File,
    val bytes: Long,
    val dataVersion: Int,
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
)

class MapDownloadException(message: String) : IOException(message)

/**
 * Offline map files live in `filesDir/maps`. The only network use in the whole app is here:
 * fetching a small manifest.json and downloading the region files the user picks. Nothing about the
 * user (location, destinations, device) is ever sent; these are plain HTTPS GET requests.
 */
class MapRepository(private val dir: File, private val manifestUrl: String) {

    fun installed(): List<InstalledRegion> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files.mapNotNull { f ->
            val h = MapPack.readHeader(f) ?: return@mapNotNull null
            InstalledRegion(f.name.removeSuffix(EXT), f, f.length(), h.dataVersion, h.minLat, h.minLon, h.maxLat, h.maxLon)
        }.sortedBy { it.id }
    }

    fun delete(id: String) {
        if (!SAFE_ID.matches(id)) return
        File(dir, id + EXT).delete()
    }

    fun fetchManifest(): List<RemoteRegion> {
        requireHttps(manifestUrl)
        val text = openGet(manifestUrl).use { String(it.readBytes(), Charsets.UTF_8) }
        val root = JSONObject(text)
        val arr = root.getJSONArray("regions")
        val out = ArrayList<RemoteRegion>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val file = o.getString("file")
            // Reject anything that could escape the maps folder or point somewhere unexpected.
            if (!SAFE_ID.matches(id) || !SAFE_FILE.matches(file)) continue
            out.add(RemoteRegion(id, o.getString("name"), file, o.getLong("bytes"), o.getString("sha256").lowercase(), o.getInt("dataVersion")))
        }
        return out
    }

    /**
     * Downloads a region to a temporary file, verifies size and SHA-256, then atomically replaces the
     * installed copy. A failed or cancelled download never damages an existing map.
     */
    fun download(region: RemoteRegion, onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean) {
        dir.mkdirs()
        val base = manifestUrl.substringBeforeLast('/') + "/"
        val url = base + region.file
        requireHttps(url)
        val part = File(dir, region.id + ".part")
        val target = File(dir, region.id + EXT)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            openGet(url).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw MapDownloadException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done > region.bytes + 1024) throw MapDownloadException("Download is larger than expected")
                        onProgress(done, region.bytes)
                    }
                    out.fd.sync()
                }
            }
            if (part.length() != region.bytes) throw MapDownloadException("Download incomplete — please try again")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != region.sha256) throw MapDownloadException("Downloaded file failed its integrity check")
            if (MapPack.readHeader(part) == null) throw MapDownloadException("Downloaded file is not a valid map")
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw MapDownloadException("Could not save the map file")
            }
        } finally {
            part.delete()
        }
    }

    private fun requireHttps(url: String) {
        if (!url.startsWith("https://")) throw MapDownloadException("Map server address must use HTTPS")
    }

    private fun openGet(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ApexHorizon-Android")
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw MapDownloadException(if (code == 404) "Map server has no such file (HTTP 404)" else "Map server error (HTTP $code)")
        }
        return c.inputStream
    }

    companion object {
        const val EXT = ".apxm"
        private val SAFE_ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
        private val SAFE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")
    }
}
