package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.BatteryWarn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryWarnTest {
    @Test fun warntAbGrenzeUndHaeltBisPolster() {
        val w = BatteryWarn()
        assertFalse(w.update(true, 30, false, 20))
        assertTrue(w.update(true, 20, false, 20))
        assertTrue(w.update(true, 24, false, 20)) // noch im Polster
        assertFalse(w.update(true, 25, false, 20))
    }

    @Test fun ausBeiLadenAusgeschaltetOderUnbekannt() {
        val w = BatteryWarn()
        assertTrue(w.update(true, 10, false, 20))
        assertFalse(w.update(true, 10, true, 20))
        assertTrue(w.update(true, 10, false, 20))
        assertFalse(w.update(false, 10, false, 20))
        assertFalse(w.update(true, -1, false, 20))
    }

    @Test fun feldAufbau() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val p = io.github.th3s1nc.osmandhudbridge.protocol.HudProtocol
        assertEquals("a201020801", hex(p.batteryWarnField(true)))
        assertEquals("a20100", hex(p.batteryWarnField(false)))
    }
}
