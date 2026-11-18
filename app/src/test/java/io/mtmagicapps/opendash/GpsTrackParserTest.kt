package io.mtmagicapps.opendash

import io.mtmagicapps.opendash.protocol.GpsTrackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTrackParserTest {
    @Test
    fun parsesValidNmeaRmcAndComputesDistance() {
        val nmea = """
            ${'$'}GNRMC,123519,A,4851.396,N,00221.132,E,025.0,084.4,181126,003.1,W*6A
            ${'$'}GNRMC,123529,A,4851.450,N,00221.250,E,030.0,084.4,181126,003.1,W*6A
            ${'$'}GNRMC,123539,V,0000.000,N,00000.000,E,000.0,000.0,181126,003.1,W*6A
        """.trimIndent()

        val points = GpsTrackParser.parse(nmea)
        assertEquals(2, points.size)
        assertEquals(48.8566, points[0].latitude, 0.001)
        assertEquals(2.3522, points[0].longitude, 0.001)
        assertEquals(46.3f, points[0].speedKmh, 0.2f)

        val dist = GpsTrackParser.totalDistanceKm(points)
        assertTrue(dist > 0.1 && dist < 0.3)
    }
}
