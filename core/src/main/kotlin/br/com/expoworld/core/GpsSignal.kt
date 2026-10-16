package br.com.expoworld.core

import kotlin.math.*

enum class GpsQuality { GOOD, FAIR, POOR, LOST }
data class GpsSignal(val quality: GpsQuality, val accuracyMeters: Double? = null) {
    val label get() = when (quality) {
        GpsQuality.GOOD -> "GPS bom"
        GpsQuality.FAIR -> "GPS razoável"
        GpsQuality.POOR -> "GPS impreciso"
        GpsQuality.LOST -> "Sem sinal GPS recente"
    }
    companion object {
        fun from(fix: Fix?, now: Double): GpsSignal {
            if (fix == null || now - fix.monotonicSeconds !in 0.0..ExplorationConfig.MAX_FIX_AGE_SECONDS ||
                !fix.accuracy.isFinite() || fix.accuracy <= 0) return GpsSignal(GpsQuality.LOST)
            return GpsSignal(when {
                fix.accuracy <= 10 -> GpsQuality.GOOD
                fix.accuracy <= ExplorationConfig.MAX_ACCURACY_METERS -> GpsQuality.FAIR
                else -> GpsQuality.POOR
            }, fix.accuracy)
        }
    }
}

object AccuracyGeometry {
    /** Geodesic circle in meters; never uses degree offsets as distances. */
    fun ring(fix: Fix): List<Pair<Double, Double>> {
        if (!fix.accuracy.isFinite() || fix.accuracy !in 0.0..10_000.0 ||
            !fix.latitude.isFinite() || abs(fix.latitude) > Geo.MAX_LAT ||
            !fix.longitude.isFinite() || abs(fix.longitude) > 180) return emptyList()
        val latitude = Math.toRadians(fix.latitude)
        val longitude = Math.toRadians(fix.longitude)
        val angular = fix.accuracy / Geo.R
        return (0..48).map { step ->
            val bearing = 2 * PI * (step % 48) / 48
            val lat = asin(sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing))
            val lon = longitude + atan2(sin(bearing) * sin(angular) * cos(latitude), cos(angular) - sin(latitude) * sin(lat))
            Math.toDegrees(lon) to Math.toDegrees(lat)
        }.takeIf { points -> points.all { abs(it.first) <= 180 && abs(it.second) <= Geo.MAX_LAT } } ?: emptyList()
    }
    fun geoJson(fix: Fix?): String {
        val points = fix?.let { ring(it) }.orEmpty()
        if (points.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
        val coordinates = points.joinToString(",") { "[${it.first},${it.second}]" }
        return """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$coordinates]]}}"""
    }
}
