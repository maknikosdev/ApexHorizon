package com.apexhorizon.app.data

import com.apexhorizon.app.domain.LatLon
import com.apexhorizon.app.domain.RouteHorizon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the one imported route (resampled) in private storage. */
class RouteStore(private val file: File) {

    fun load(): RouteHorizon? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("pts")
        val pts = List(arr.length()) { i -> arr.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } }
        RouteHorizon.create(o.getString("name"), pts, RouteHorizon.Kind.IMPORTED)
    }.getOrNull()

    fun save(route: RouteHorizon) {
        val arr = JSONArray()
        route.latLonPoints().forEach { arr.put(JSONArray().put(it.lat).put(it.lon)) }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("name", route.name).put("pts", arr).toString())
    }

    fun clear() {
        file.delete()
    }
}
