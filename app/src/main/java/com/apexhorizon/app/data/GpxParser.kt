package com.apexhorizon.app.data

import android.util.Xml
import com.apexhorizon.app.domain.LatLon
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

class GpxException(message: String) : Exception(message)

data class ParsedGpx(val name: String?, val points: List<LatLon>)

/** Minimal GPX reader: collects track points (trkpt) or route points (rtept) in file order. */
object GpxParser {
    private const val MAX_POINTS = 200_000

    fun parse(input: InputStream): ParsedGpx {
        val parser = Xml.newPullParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            val track = ArrayList<LatLon>()
            val route = ArrayList<LatLon>()
            var name: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                val list = if (parser.name == "trkpt") track else route
                                if (list.size < MAX_POINTS) list.add(LatLon(lat, lon))
                            }
                        }
                        "name" -> if (name == null) name = runCatching { parser.nextText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                }
                event = parser.next()
            }
            val points = if (track.size >= 2) track else route
            if (points.size < 2) throw GpxException("No track or route points found in this file.")
            return ParsedGpx(name, points)
        } catch (e: GpxException) {
            throw e
        } catch (e: Exception) {
            throw GpxException("This file is not a valid GPX file.")
        }
    }
}
