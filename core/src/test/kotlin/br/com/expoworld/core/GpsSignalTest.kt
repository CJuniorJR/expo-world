package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class GpsSignalTest {
    private val fix = Fix(-23.5, -46.6, 10.0, 100.0)
    @Test fun qualityRespectsAccuracyAndExpiresWithoutAnotherFix() {
        assertEquals(GpsQuality.GOOD, GpsSignal.from(fix, 101.0).quality)
        assertEquals(GpsQuality.FAIR, GpsSignal.from(fix.copy(accuracy = 20.0), 101.0).quality)
        assertEquals(GpsQuality.POOR, GpsSignal.from(fix.copy(accuracy = 21.0), 101.0).quality)
        assertEquals(GpsQuality.LOST, GpsSignal.from(fix, 111.0).quality)
        assertEquals(GpsQuality.LOST, GpsSignal.from(fix, 99.0).quality)
        assertEquals(GpsQuality.LOST, GpsSignal.from(null, 101.0).quality)
        assertEquals(GpsQuality.LOST, GpsSignal.from(fix.copy(accuracy = Double.NaN), 101.0).quality)
    }
    @Test fun uncertaintyCircleUsesMetersAtDifferentLatitudesAndCloses() {
        for (latitude in listOf(0.0, -23.5, 70.0)) {
            val center = fix.copy(latitude = latitude, accuracy = 37.0)
            val ring = AccuracyGeometry.ring(center)
            assertEquals(49, ring.size)
            assertEquals(ring.first(), ring.last())
            ring.forEach { (lon, lat) -> assertEquals(37.0, Geo.distance(center, center.copy(latitude = lat, longitude = lon)), .001) }
        }
        assertTrue(AccuracyGeometry.ring(fix.copy(accuracy = Double.POSITIVE_INFINITY)).isEmpty())
        assertFalse(AccuracyGeometry.geoJson(null).contains("Polygon"))
        assertTrue(AccuracyGeometry.ring(fix.copy(longitude = 179.99999, accuracy = 100.0)).isEmpty())
    }
}
