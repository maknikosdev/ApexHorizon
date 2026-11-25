package com.apexhorizon.app.data

import com.apexhorizon.app.domain.EventType
import com.apexhorizon.app.domain.RunEvent
import com.apexhorizon.app.domain.RunSummary
import com.apexhorizon.app.domain.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves runs as small JSON files in the app's private storage. A run is split into a summary file
 * (cheap to list) and a track file (loaded only when a run is opened or replayed).
 */
class RunStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun list(): List<RunSummary> =
        (dir.listFiles { f -> f.name.endsWith(SUMMARY_SUFFIX) } ?: emptyArray())
            .mapNotNull { runCatching { parseSummary(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startedAtMs }

    fun save(run: RunSummary) {
        val events = JSONArray()
        run.events.forEach {
            events.put(
                JSONObject()
                    .put("type", it.type.name).put("t", it.tMs).put("d", it.distanceM)
                    .put("v", it.speedMps).put("lat", it.lat).put("lon", it.lon),
            )
        }
        val summary = JSONObject()
            .put("id", run.id).put("title", run.title).put("startedAt", run.startedAtMs)
            .put("duration", run.durationMs).put("distance", run.distanceM)
            .put("avg", run.avgSpeedMps).put("max", run.maxSpeedMps)
            .put("camera", run.usedCamera).put("demo", run.isDemo).put("events", events)
        val track = JSONArray()
        run.track.forEach {
            track.put(JSONArray().put(it.tMs).put(it.lat).put(it.lon).put(it.speedMps).put(it.bearingDeg).put(it.accuracyM))
        }
        writeAtomic(File(dir, run.id + TRACK_SUFFIX), track.toString())
        writeAtomic(File(dir, run.id + SUMMARY_SUFFIX), summary.toString())
    }

    fun loadTrack(id: String): List<TrackPoint> {
        val f = File(dir, id + TRACK_SUFFIX)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val p = arr.getJSONArray(i)
                TrackPoint(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getDouble(3), p.getDouble(4), p.getDouble(5))
            }
        }.getOrDefault(emptyList())
    }

    fun delete(id: String) {
        File(dir, id + SUMMARY_SUFFIX).delete()
        File(dir, id + TRACK_SUFFIX).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun parseSummary(o: JSONObject): RunSummary {
        val ev = o.optJSONArray("events") ?: JSONArray()
        val events = List(ev.length()) { i ->
            val e = ev.getJSONObject(i)
            RunEvent(
                type = runCatching { EventType.valueOf(e.getString("type")) }.getOrDefault(EventType.SHARP_CORNER),
                tMs = e.getLong("t"), distanceM = e.getDouble("d"), speedMps = e.getDouble("v"),
                lat = e.getDouble("lat"), lon = e.getDouble("lon"),
            )
        }
        return RunSummary(
            id = o.getString("id"), title = o.getString("title"), startedAtMs = o.getLong("startedAt"),
            durationMs = o.getLong("duration"), distanceM = o.getDouble("distance"),
            avgSpeedMps = o.getDouble("avg"), maxSpeedMps = o.getDouble("max"),
            events = events, usedCamera = o.optBoolean("camera"), isDemo = o.optBoolean("demo"),
            track = emptyList(),
        )
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val SUMMARY_SUFFIX = ".run.json"
        const val TRACK_SUFFIX = ".track.json"
    }
}
