package io.mtmagicapps.opendash.protocol

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GpsTrackParser {

    fun parse(raw: String): List<GpsTrackPoint> {
        if (raw.isBlank()) return emptyList()
        val points = mutableListOf<GpsTrackPoint>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("\$GPRMC") || trimmed.startsWith("\$GNRMC")) {
                parseRmc(trimmed)?.let { points.add(it) }
            }
        }
        return points
    }

    private fun parseRmc(sentence: String): GpsTrackPoint? {
        val parts = sentence.split(',')
        if (parts.size < 8) return null
        if (parts[2] != "A") return null // 'A' = active fix
        val lat = nmeaToDecimal(parts[3], parts[4]) ?: return null
        val lon = nmeaToDecimal(parts[5], parts[6]) ?: return null
        val knots = parts[7].toFloatOrNull() ?: 0f
        return GpsTrackPoint(
            latitude = lat,
            longitude = lon,
            speedKmh = knots * 1.852f
        )
    }

    private fun nmeaToDecimal(coord: String, hemi: String): Double? {
        if (coord.isBlank()) return null
        val dot = coord.indexOf('.')
        if (dot < 2) return null
        val degLen = dot - 2
        val deg = coord.substring(0, degLen).toDoubleOrNull() ?: return null
        val min = coord.substring(degLen).toDoubleOrNull() ?: return null
        var dec = deg + (min / 60.0)
        if (hemi == "S" || hemi == "W") dec = -dec
        return dec
    }

    fun totalDistanceKm(points: List<GpsTrackPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineKm(points[i - 1], points[i])
        }
        return total
    }

    private fun haversineKm(a: GpsTrackPoint, b: GpsTrackPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val h = s1 * s1 + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * s2 * s2
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
