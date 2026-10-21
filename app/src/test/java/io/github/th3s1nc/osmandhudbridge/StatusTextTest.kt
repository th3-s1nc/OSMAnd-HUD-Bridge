package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.StatusText
import io.github.th3s1nc.osmandhudbridge.limit.Tx
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test
    fun deutsch_bleibtUnveraendert() {
        Tx.en = false
        assertEquals("Limit: Karte wird geladen", StatusText.en("Limit: Karte wird geladen"))
        assertEquals("Fertig: 16.546 Kacheln, 10 MB (5 s)", StatusText.stored("Fertig: 16.546 Kacheln, 10 MB (5 s)"))
    }

    @Test
    fun englisch_uebersetztStatusUndImportzeilen() {
        Tx.en = true
        try {
            assertEquals("Limit: map is loading", StatusText.en("Limit: Karte wird geladen"))
            assertEquals("Limit: 50 km/h (estimated)", StatusText.en("Limit: 50 km/h (geschätzt)"))
            assertEquals("Done: 16,546 tiles, 1,234 MB (5 s)", StatusText.stored("Fertig: 16.546 Kacheln, 1.234 MB (5 s)"))
            assertEquals("Read on 01.10.2026 from a.pbf", StatusText.stored("Eingelesen am 01.10.2026 aus a.pbf"))
        } finally { Tx.en = false }
    }

    @Test
    fun deutsch_uebersetztGespeichertEnglisch_zurueck() {
        Tx.en = false
        assertEquals("Fertig: 16.546 Kacheln, 10 MB (5 s)", StatusText.stored("Done: 16,546 tiles, 10 MB (5 s)"))
    }
}
