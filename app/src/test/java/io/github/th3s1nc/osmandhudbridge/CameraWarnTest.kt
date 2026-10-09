package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.Camera
import io.github.th3s1nc.osmandhudbridge.limit.CameraWarn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWarnTest {
    private val lat0 = 50.0
    private val lon0 = 8.0
    private fun northM(m: Double) = lat0 + m / 111_320.0
    private fun cam(id: Long, northM: Double, eastM: Double = 0.0) =
        Camera(id, northM(northM), lon0 + eastM / (111_320.0 * Math.cos(Math.toRadians(lat0))), emptyMap())

    @Test
    fun warnabstandHaengtVomTempoAb() {
        assertTrue(Math.abs(100.0 - CameraWarn.leadDistance(8f, 0)) <= 0.01)    // sehr langsam: Untergrenze
        assertTrue(Math.abs(278.0 - CameraWarn.leadDistance(27.8f, 1)) <= 0.5)  // 100 km/h, Normal: ca. 280 m
        assertTrue(Math.abs(417.0 - CameraWarn.leadDistance(27.8f, 2)) <= 0.5)  // 100 km/h, Lang
        assertTrue(Math.abs(600.0 - CameraWarn.leadDistance(50f, 2)) <= 0.01)   // sehr schnell: Obergrenze
    }

    @Test
    fun warntNurVorDemBlitzerUndNurEinmal() {
        val w = CameraWarn()
        val cams = listOf(cam(1, 250.0))
        val far = w.update(cams, lat0, lon0, 0.0, 27.8f, 1)           // 250 m vor dem Blitzer: im Bereich (278 m)
        assertNotNull(far.distanceM); assertEquals(true, far.sound)
        val closer = w.update(cams, northM(100.0), lon0, 0.0, 27.8f, 1)
        assertTrue(Math.abs(closer.distanceM!! - 150) <= 2); assertEquals(false, closer.sound)
    }

    @Test
    fun zuWeitWeg() {
        val w = CameraWarn()
        assertNull(w.update(listOf(cam(1, 400.0)), lat0, lon0, 0.0, 27.8f, 1).distanceM)
    }

    @Test
    fun hinterDirOderNebenDerStrasseKeineWarnung() {
        val w = CameraWarn()
        assertNull(w.update(listOf(cam(1, -100.0)), lat0, lon0, 0.0, 27.8f, 1).distanceM)        // hinter dir
        assertNull(w.update(listOf(cam(2, 150.0, 80.0)), lat0, lon0, 0.0, 27.8f, 1).distanceM)   // 80 m neben der Linie
        assertNull(w.update(listOf(cam(3, 150.0)), lat0, lon0, 180.0, 27.8f, 1).distanceM)       // du fährst von ihm weg
    }

    @Test
    fun langsamOderOhneRichtungKeineWarnung() {
        val w = CameraWarn()
        assertNull(w.update(listOf(cam(1, 50.0)), lat0, lon0, 0.0, 1f, 1).distanceM)
        assertNull(w.update(listOf(cam(1, 50.0)), lat0, lon0, null, 20f, 1).distanceM)
    }

    @Test
    fun nachWeitemAbstandWiederScharf() {
        val w = CameraWarn()
        val cams = listOf(cam(1, 200.0))
        assertEquals(true, w.update(cams, lat0, lon0, 0.0, 27.8f, 1).sound)
        w.update(cams, northM(2500.0), lon0, 0.0, 27.8f, 1)           // weit weg: vergessen
        assertEquals(true, w.update(cams, lat0, lon0, 0.0, 27.8f, 1).sound)
    }

    @Test
    fun gefahrenBildschirmNichtBeiNaherAbbiegung() {
        assertEquals(true, CameraWarn.blockedByTurn(200, true))    // Abbiegung in 200 m: nicht umschalten
        assertEquals(false, CameraWarn.blockedByTurn(300, true))
        assertEquals(false, CameraWarn.blockedByTurn(1500, true))
        assertEquals(false, CameraWarn.blockedByTurn(100, false))  // keine Navigation: umschalten
        assertEquals(false, CameraWarn.blockedByTurn(null, true))
    }

    @Test
    fun hinweistextZeigtDieEchtenMeter() {
        assertEquals(140, CameraWarn.leadMeters(50, 1))
        assertEquals(280, CameraWarn.leadMeters(100, 1))
        assertEquals(100, CameraWarn.leadMeters(50, 0)) // Mindestabstand
        assertEquals(540, CameraWarn.leadMeters(130, 2))
        assertTrue(CameraWarn.leadHint(1).contains("140 m vor dem Blitzer bei 50 km/h"))
        assertTrue(CameraWarn.leadHint(2).contains("420 m bei 100 km/h"))
    }
}
