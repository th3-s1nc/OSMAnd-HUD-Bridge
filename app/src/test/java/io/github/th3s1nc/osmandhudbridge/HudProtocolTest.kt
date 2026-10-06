package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.protocol.CameraType
import io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode
import io.github.th3s1nc.osmandhudbridge.protocol.HudProtocol
import io.github.th3s1nc.osmandhudbridge.protocol.HudMessage
import io.github.th3s1nc.osmandhudbridge.protocol.HudState
import io.github.th3s1nc.osmandhudbridge.protocol.HudValue
import io.github.th3s1nc.osmandhudbridge.protocol.SlotConfig
import io.github.th3s1nc.osmandhudbridge.protocol.Lane
import io.github.th3s1nc.osmandhudbridge.protocol.LaneDirection
import io.github.th3s1nc.osmandhudbridge.protocol.LaneRecommendation
import io.github.th3s1nc.osmandhudbridge.protocol.NavCommand
import io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vergleicht den Encoder mit Referenzbytes aus eigenen Funkmitschnitten (Fahrt, Koppeln, Moduswechsel). */
class HudProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun frames(tid: Int, m: HudMessage) = HudProtocol.frame(tid, m).map(::hex)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }

    // ---------- Referenzbytes aus Mitschnitten ----------

    @Test fun multiFrameMessageFromCapture() {
        // Tempolimit 50 + Uhrzeit 7:07 + Bildschirm 13 einrichten: 37 Byte in 3 Paketen
        val body = cat(
            HudProtocol.speedLimitField(50, true, CameraType.NONE),
            HudProtocol.timeField(7, 7),
            HudProtocol.showHide(13, listOf(21), listOf(8, 9, 10, 53, 16, 3, 51, 5, 4, 6, 17, 13, 52))
        )
        assertEquals(
            listOf("103025001a0408321001420408071007a2061612", "115214080d1201151a0d08090a35100333050406", "12a3110d34"),
            frames(1, HudMessage(body))
        )
    }

    @Test fun roundaboutWithLaneInfoFromCapture() {
        // 400 m, Kreisverkehr Ausfahrt 2, Spur geradeaus empfohlen
        val body = cat(
            HudProtocol.partDistanceField(400),
            HudProtocol.pointerField(NavCommand.ROUNDABOUT, 2),
            HudProtocol.laneInfoField(listOf(Lane(LaneDirection.STRAIGHT, LaneRecommendation.RECOMMENDED)))
        )
        assertEquals(listOf("c0301600220508900310022a0408101002920106", "c1a60a0408011003"), frames(12, HudMessage(body)))
    }

    @Test fun laneInfoTwoLanesFromCapture() {
        // 18:{1:{1=64 2=1} 1:{1=5 2=3}}
        val f = HudProtocol.laneInfoField(
            listOf(
                Lane(LaneDirection.LEFT, LaneRecommendation.NOT_RECOMMENDED),
                Lane(LaneDirection.STRAIGHT or LaneDirection.RIGHT, LaneRecommendation.RECOMMENDED)
            )
        )
        assertEquals("92010c" + "0a0408401001" + "0a0408051003", hex(f))
    }

    @Test fun singleMessagesFromCapture() {
        fun one(tid: Int, f: ByteArray) = hex(HudProtocol.frame(tid, HudMessage(f))[0])
        assertEquals("50660a0408131001", one(5, HudProtocol.speedField(19.9f))) // abgeschnitten, nicht gerundet
        assertEquals("1066220408641002", one(1, HudProtocol.partDistanceField(100)))
        assertEquals("20642a020804", one(2, HudProtocol.pointerField(NavCommand.TURN_RIGHT, null)))
        assertEquals("30665a0408061001", one(3, HudProtocol.routeDistanceField(6000)))
        assertEquals("a067220508ac021002", one(10, HudProtocol.partDistanceField(300)))
        assertEquals("0a021001", hex(HudProtocol.speedField(0f))) // proto3: 0 wird weggelassen
        assertEquals("1a04081e1001", hex(HudProtocol.speedLimitField(30, true, 0)))
        assertEquals("1a02081e", hex(HudProtocol.speedLimitField(30, false, 0)))
        assertEquals("1a021001", hex(HudProtocol.speedLimitField(0, true, 0)))
    }

    @Test fun clearMessagesFromCapture() {
        assertEquals("2200", hex(HudProtocol.partDistanceField(null)))
        assertEquals("2a00", hex(HudProtocol.pointerField(null, null)))
        assertEquals("5200", hex(HudProtocol.routeDurationField(null)))
        assertEquals("5a00", hex(HudProtocol.routeDistanceField(null)))
        assertEquals("920100", hex(HudProtocol.laneInfoField(emptyList())))
    }

    @Test fun handshakeMatchesCapture() {
        val h = HudProtocol.handshake(11, 59)
        assertEquals(5, h.size)
        assertEquals("0068a20605a201020801", frames(0, h[0])[0])
        assertEquals("10e7a206045a020801", frames(1, h[1])[0]) // ohne Quittung
        assertEquals("3067a206042a02080d", frames(3, h[3])[0])
        assertEquals("4069a2060652040a021002", frames(4, h[4])[0])
        // Uhrzeit + Bildschirm: 31 Byte in 2 Paketen (16 + 15), zweites Paket wie im Referenz-Mitschnitt
        assertEquals(
            listOf("20301f004204080b103ba206161214080d120115", "21af1a0d08090a35100333050406110d34"),
            frames(2, h[2])
        )
    }

    @Test fun acksFromCapture() {
        val a = HudProtocol.parseAck(byteArrayOf(0x30, 0xc1.toByte(), 0x00))
        assertNotNull(a)
        assertEquals(3, a!!.transactionId)
        assertTrue(a.ok)
        val bad = HudProtocol.parseAck(byteArrayOf(0x50, 0xc1.toByte(), 0x03))
        assertEquals(5, bad!!.transactionId)
        assertTrue(!bad.ok)
        // Antwort auf readConfig ist keine Quittung
        assertNull(HudProtocol.parseAck("10f20a1008cd1d10e70f18022007281e30023801".chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
    }

    // ---------- Distanzen ----------

    @Test fun distanceRounding() {
        fun d(m: Int?) = hex(HudProtocol.partDistanceField(m))
        assertEquals("2200", d(null))
        assertEquals("2200", d(-1))
        assertEquals("22021002", d(0))
        assertEquals("22021002", d(5))
        assertEquals("2204085a1002", d(95))
        assertEquals("220408641002", d(120))
        assertEquals("220508fa011002", d(250))
        assertEquals("22050884071002", d(999))
        assertEquals("220408011001", d(1000))
        assertEquals("220408021001", d(2999))
    }

    @Test fun routeDurationSplitsHours() {
        assertEquals("52021007", hex(HudProtocol.routeDurationField(7)))
        assertEquals("52040801101e", hex(HudProtocol.routeDurationField(90)))
        assertEquals("520208" + "01", hex(HudProtocol.routeDurationField(60)))
    }

    // ---------- Framing: Rundreise für alle Längen ----------

    private fun reassemble(frames: List<ByteArray>): ByteArray {
        val first = frames[0]
        val type = (first[1].toInt() and 0xff) shr 5
        if (type == HudProtocol.PKT_SINGLE || type == HudProtocol.PKT_SINGLE_ACK) {
            return first.copyOfRange(2, 2 + (first[1].toInt() and 0x1f))
        }
        val total = (first[2].toInt() and 0xff) or ((first[3].toInt() and 0xff) shl 8)
        var out = first.copyOfRange(4, 4 + 16)
        for ((i, f) in frames.withIndex().drop(1)) {
            assertEquals(i, f[0].toInt() and 0x0f)
            val t = (f[1].toInt() and 0xff) shr 5
            val n = f[1].toInt() and 0x1f
            assertEquals(n, f.size - 2)
            val last = i == frames.lastIndex
            assertEquals(if (last) HudProtocol.PKT_LAST_ACK else HudProtocol.PKT_CONT, t)
            out += f.copyOfRange(2, 2 + n)
        }
        assertEquals(total, out.size)
        return out
    }

    @Test fun framingRoundTripAllLengths() {
        for (len in 1..HudProtocol.maxMessageBytes()) {
            val body = ByteArray(len) { (it * 7 + 3).toByte() }
            val frames = HudProtocol.frame(9, HudMessage(body))
            assertTrue("zu langes Paket bei $len", frames.all { it.size <= 20 })
            assertTrue("Header bei $len", frames.all { (it[0].toInt() and 0xf0) shr 4 == 9 })
            assertEquals("Länge $len", hex(body), hex(reassemble(frames)))
        }
    }

    // ---------- Zustand -> Felder ----------

    @Test fun speedOkFlagFollowsLimit() {
        fun lim(speed: Float, limit: Int) = hex(HudProtocol.encodeState(HudState(speedKmh = speed, speedLimitKmh = limit))[3]!!)
        assertEquals("1a04081e1001", lim(30f, 30))
        assertEquals("1a02081e", lim(30.4f, 30)) // keine Toleranz
        assertEquals("1a021001", lim(120f, 0)) // kein Limit bekannt
    }

    @Test fun onlyChangedFieldsAreSent() {
        val a = HudProtocol.encodeState(HudState(speedKmh = 50f, partDistanceM = 300, command = NavCommand.TURN_RIGHT))
        val b = HudProtocol.encodeState(HudState(speedKmh = 51f, partDistanceM = 300, command = NavCommand.TURN_RIGHT))
        assertEquals(listOf(1), HudProtocol.changedFields(a, b))
        // Distanz 304 m rundet auf 300 m -> nichts zu senden
        val c = HudProtocol.encodeState(HudState(speedKmh = 50f, partDistanceM = 304, command = NavCommand.TURN_RIGHT))
        assertEquals(emptyList<Int>(), HudProtocol.changedFields(a, c))
        // Nach Reconnect: alles senden
        assertEquals(a.keys.sorted(), HudProtocol.changedFields(emptyMap(), a))
    }

    @Test fun bundlesFieldsLikeReference() {
        val f = listOf(
            HudProtocol.partDistanceField(100), // 6 Byte
            HudProtocol.pointerField(NavCommand.TURN_LEFT, null), // 6 Byte
            HudProtocol.routeDistanceField(6000) // 6 Byte
        )
        val m = HudProtocol.bundle(f)
        assertEquals(1, m.size)
        assertEquals(16, m[0].body.size)
        val m2 = HudProtocol.bundle(f + HudProtocol.speedField(10f))
        assertEquals(2, m2.size)
        assertTrue(m2.all { it.ackRequired })
    }

    @Test fun thresholdsDependOnSpeedLimit() {
        val n = ThresholdMode.NORMAL
        assertTrue(!n.isOver(50, 499))
        assertTrue(n.isOver(50, 500))
        assertTrue(!n.isOver(80, 999))
        assertTrue(n.isOver(80, 1000))
        assertTrue(!n.isOver(0, 1999)) // Limit unbekannt: größte Schwelle
        assertTrue(n.isOver(130, 2000))
        assertTrue(!ThresholdMode.ALWAYS.isOver(30, 99999))
    }

    @Test fun roundaboutExitOnlyForRoundaboutArrows() {
        assertEquals("2a0408101002", hex(HudProtocol.pointerField(NavCommand.ROUNDABOUT, 2)))
        assertEquals("2a020804", hex(HudProtocol.pointerField(NavCommand.TURN_RIGHT, 2)))
    }

    // ---------- Anzeigemodi (Referenz-Mitschnitt) ----------

    @Test fun modeSwitchMatchesCapture() {
        fun sw(m: DisplayMode) = HudProtocol.switchMode(m).map { hex(it.body) }
        assertEquals(
            listOf("a206161214080d1201151a0d08090a35100333050406110d34", "a206042a02080d"),
            sw(DisplayMode.NAVIGATOR)
        )
        assertEquals(
            listOf("a206121210080c1201151a09351003330504061134", "a206042a02080c"),
            sw(DisplayMode.MINIMALIST)
        )
        assertEquals(
            listOf("a206191217080b1201051a100208090a352310033315040611203034", "a206042a02080b"),
            sw(DisplayMode.EXPLORER)
        )
        // City: im Referenz-Mitschnitt ist Element 12 ausgeblendet, wir zeigen es (aktuelle Strasse)
        val captured = HudProtocol.showHide(10, listOf(21, 12), listOf(8, 9, 10, 53, 16, 3, 51, 5, 4, 6, 7, 17, 32, 48, 52))
        assertEquals("a206191217080a1202150c1a0f08090a351003330504060711203034".substring(0, 2), hex(captured).substring(0, 2))
        assertEquals("a206191217080a1202150c1a0f08090a351003330504060711203034".drop(2), hex(captured).drop(2))
        assertEquals("a206042a02080a", sw(DisplayMode.CITY)[1])
        assertTrue(DisplayMode.CITY.show.contains(12) && !DisplayMode.CITY.hide.contains(12))
    }

    @Test fun handshakeUsesSelectedMode() {
        val h = HudProtocol.handshake(11, 59, DisplayMode.EXPLORER)
        assertEquals(hex(HudProtocol.activateScreen(11).body), hex(h[3].body))
        assertEquals("a206042a02080b", hex(h[3].body))
    }

    @Test fun compassAndStreetFields() {
        assertEquals("1203088101", hex(HudProtocol.compassField(258))) // wie im Referenz-Mitschnitt: 2:{1:129}
        assertEquals("12020841", hex(HudProtocol.compassField(130)))
        assertEquals("1202085a", hex(HudProtocol.compassField(180)))
        assertEquals("1200", hex(HudProtocol.compassField(0)))
        assertEquals("Hauptstrasse", HudProtocol.cleanAscii("Hauptstra\u00dfe"))
        assertEquals("Muenchner Str", HudProtocol.cleanAscii("M\u00fcnchner Str"))
        assertEquals(40, HudProtocol.cleanAscii("x".repeat(60)).length)
        assertEquals("7a00", hex(HudProtocol.streetField(15, null)))
        assertEquals("7a050a03414243", hex(HudProtocol.streetField(15, "ABC")))
        assertEquals("8a01050a03414243", hex(HudProtocol.streetField(17, "ABC")))
    }

    @Test fun modesFilterFields() {
        val s = HudState(
            speedKmh = 50f, headingDeg = 90, arrivalHour = 15, arrivalMinute = 35,
            nextStreet = "Hauptstr", currentStreet = "Dorfstr", routeMinutes = 5
        )
        fun keys(m: DisplayMode) = HudProtocol.encodeState(s, m).keys.sorted()
        assertEquals(listOf(1, 3, 4, 5, 8, 10, 11, 18), keys(DisplayMode.NAVIGATOR))
        assertEquals(listOf(3, 4, 5, 8, 10, 11), keys(DisplayMode.MINIMALIST))
        assertEquals(listOf(1, 2, 3, 4, 5, 8, 11, 12), keys(DisplayMode.EXPLORER))
        assertEquals(listOf(1, 3, 4, 5, 8, 10, 11, 15, 17), keys(DisplayMode.CITY))
    }

    @Test fun eventFields() {
        assertEquals("aa010b" + "0801" + "1205" + "0a034d756d" + "1808", hex(HudProtocol.eventField(21, 1, "Mum", 8)))
        assertEquals("b201020801", hex(HudProtocol.eventField(22, 1, null, 0)))
        assertEquals("b2010408011808", hex(HudProtocol.eventField(22, 1, null, 8)))
        assertEquals("aa0100b20100", hex(HudProtocol.clearEventsBody()))
        assertEquals(19, HudProtocol.cleanAscii("x".repeat(30)).take(19).length)
    }


    @Test fun streetElementsNeverOverlap() {
        val both = HudProtocol.encodeState(HudState(nextStreet = "Hauptstr", currentStreet = "Dorfstr"), DisplayMode.CITY)
        assertEquals(hex(HudProtocol.streetField(17, null)), hex(both[17]!!)) // aktuelle Strasse leer
        assertEquals(hex(HudProtocol.streetField(15, "Hauptstr")), hex(both[15]!!))
        val onlyCurrent = HudProtocol.encodeState(HudState(currentStreet = "Dorfstr"), DisplayMode.CITY)
        assertEquals(hex(HudProtocol.streetField(17, "Dorfstr")), hex(onlyCurrent[17]!!))
        assertEquals(hex(HudProtocol.streetField(15, null)), hex(onlyCurrent[15]!!))
    }

    @Test fun brightnessAndJustageCommands() {
        assertEquals("a206085206 0a0408031001".replace(" ", ""), hex(HudProtocol.brightnessManual(3).body))
        assertEquals("a2060652040a0210 02".replace(" ", ""), hex(HudProtocol.brightnessAutomatic().body))
        assertEquals("a20604 2a020801".replace(" ", ""), hex(HudProtocol.enterJustage().body))
        // Regler: 0 dunkel, 1 mittel, 2 hell
        assertEquals(listOf(1, 2, 3), listOf(0, 1, 2).map { HudProtocol.brightnessLevelForStep(it) })
        assertEquals(hex(HudProtocol.brightnessAutomatic().body), hex(HudProtocol.brightness(-1).body))
        assertEquals(hex(HudProtocol.brightnessManual(3).body), hex(HudProtocol.brightness(2).body))
        // Handshake endet mit der gewählten Helligkeit
        assertEquals(hex(HudProtocol.brightnessManual(2).body), hex(HudProtocol.handshake(1, 2, DisplayMode.NAVIGATOR, 1).last().body))
        // Justage verlassen: Konfiguration lesen, Elemente, Bildschirm aktivieren
        val leave = HudProtocol.leaveJustage(DisplayMode.CITY)
        assertEquals(3, leave.size)
        assertEquals(hex(HudProtocol.activateScreen(DisplayMode.CITY.screenId).body), hex(leave.last().body))
    }

    @Test fun speedWarningTolerance() {
        assertTrue(HudProtocol.speedOk(60f, 50, true, 10))   // genau Limit + Toleranz
        assertFalse(HudProtocol.speedOk(61f, 50, true, 10))
        assertTrue(HudProtocol.speedOk(120f, 50, false, 10)) // Warnung aus: immer ok
        assertTrue(HudProtocol.speedOk(120f, 0, true, 0))    // Limit unbekannt: ok
        val st = HudState(speedKmh = 58f, speedLimitKmh = 50, warnEnabled = true, warnToleranceKmh = 10)
        assertEquals(hex(HudProtocol.speedLimitField(50, true, 0)), hex(HudProtocol.encodeState(st, DisplayMode.NAVIGATOR)[3]!!))
        // geschätztes Limit: nie warnen
        val est = HudState(speedKmh = 130f, speedLimitKmh = 100, limitEstimated = true)
        assertEquals(hex(HudProtocol.speedLimitField(100, true, 0)), hex(HudProtocol.encodeState(est, DisplayMode.NAVIGATOR)[3]!!))
        val over = st.copy(speedKmh = 65f)
        assertEquals(hex(HudProtocol.speedLimitField(50, false, 0)), hex(HudProtocol.encodeState(over, DisplayMode.NAVIGATOR)[3]!!))
    }

    @Test fun trackingTestMode() {
        // Strecke wie die Restdistanz, aber Feld 13; Zeit Feld 14 (Stunde, Minute); Höhe Feld 26 in 5-m-Schritten, Einheit Meter
        assertEquals("6a0408011001", hex(HudProtocol.tripDistanceField(1234)))
        assertEquals("720408021005", hex(HudProtocol.tripDurationField(125)))
        // Limit: Feld 29 (Tempo-Aufbau: Wert, Einheit km/h), ohne Limit leer
        assertEquals("ea01" + "04" + "08321001", hex(HudProtocol.limitSlotField(50)))
        assertEquals("ea0100", hex(HudProtocol.limitSlotField(0)))
        // Nur der Tracking-Modus sendet die neuen Felder
        val s = HudState(speedKmh = 50f, speedLimitKmh = 50, tripDistanceM = 2500, tripMinutes = 75)
        // Standardzeilen: Tempo (1), Limit (29), Fahrzeit (14), Strecke (13); dazu Uhrzeit (8) und GPS-Status (30)
        assertEquals(listOf(1, 8, 13, 14, 29, 30), HudProtocol.encodeState(s, DisplayMode.TRACKING).keys.sorted())
        for (m in listOf(DisplayMode.NAVIGATOR, DisplayMode.MINIMALIST, DisplayMode.EXPLORER, DisplayMode.CITY))
            assertFalse(HudProtocol.encodeState(s, m).keys.any { it == 13 || it == 14 || it == 29 || it == 26 || it == 30 })
        // Moduswechsel: erst Textfelder (Kommando 100, Unterkommando 12), dann ShowHide (Bildschirm 21), dann Aktivieren
        val sw = HudProtocol.switchMode(DisplayMode.TRACKING)
        assertEquals(3, sw.size)
        assertTrue(hex(sw[0].body).startsWith("a206"))
        val txt = String(sw[0].body, Charsets.ISO_8859_1)
        for (w in listOf("GESCHWINDIGKEIT", "VERBLEIBEND", "LIMIT", "FAHRZEIT", "STRECKE")) assertTrue(w, txt.contains(w))
        assertTrue(sw[0].body.size <= HudProtocol.maxMessageBytes())
        assertEquals(hex(HudProtocol.showHide(21, emptyList(), DisplayMode.TRACKING.show)), hex(sw[1].body))
        assertEquals(hex(HudProtocol.activateScreen(21).body), hex(sw[2].body))
        // andere Modi: unverändert zwei Nachrichten
        assertEquals(2, HudProtocol.switchMode(DisplayMode.CITY).size)
        // Handshake im Tracking-Modus enthält die Textfelder vor ShowHide
        assertEquals(HudProtocol.handshake(1, 2, DisplayMode.CITY).size + 1, HudProtocol.handshake(1, 2, DisplayMode.TRACKING).size)
    }

    @Test fun guideAndSlotChoice() {
        // Guide: feste Felder Pfeil (5), Entfernung (4), Uhrzeit (8) plus Standardzeilen Reststrecke (11) und Restzeit (10), GPS-Status (30)
        val s = HudState(speedKmh = 50f, routeDistanceM = 267000, routeMinutes = 225)
        assertEquals(listOf(4, 5, 8, 10, 11, 30), HudProtocol.encodeState(s, DisplayMode.GUIDE).keys.sorted())
        assertEquals(20, DisplayMode.GUIDE.screenId)
        assertEquals("Guide", DisplayMode.GUIDE.title)
        assertEquals("Cruiser", DisplayMode.TRACKING.title)
        // gewählte Zeilen bestimmen die Felder: Tempo, Höhe, leer, Ankunft
        val pick = listOf(HudValue.SPEED, HudValue.ELEVATION, HudValue.EMPTY, HudValue.ARRIVAL)
        val m = HudProtocol.encodeState(HudState(elevationM = 412, arrivalHour = 17, arrivalMinute = 30), DisplayMode.TRACKING, pick)
        assertEquals(listOf(1, 8, 12, 26, 30), m.keys.sorted())
        assertEquals("d201" + "05" + "089c03" + "1002", hex(m.getValue(26)))
        // Höhe: Feld 26, Meter; negativ -> 0; ohne Wert leer
        assertEquals("d20100", hex(HudProtocol.elevationField(null)))
        assertEquals("d201021002", hex(HudProtocol.elevationField(-5)))
        // GPS-Status: Feld 30, ein Byte-Flag
        assertEquals("f20102" + "0801", hex(HudProtocol.gpsStateField(true)))
        assertEquals("f20100", hex(HudProtocol.gpsStateField(false)))
        // Auswahl lesen und schreiben
        assertEquals(SlotConfig.CRUISER_DEFAULT, SlotConfig.parse(DisplayMode.TRACKING, null))
        assertEquals(listOf(HudValue.SPEED, HudValue.EMPTY, HudValue.ELEVATION, HudValue.TRIP_TIME),
            SlotConfig.parse(DisplayMode.TRACKING, "speed,empty,ele,ttime"))
        assertEquals(SlotConfig.GUIDE_DEFAULT, SlotConfig.parse(DisplayMode.GUIDE, "kaputt"))
        assertEquals("speed,empty", SlotConfig.encode(listOf(HudValue.SPEED, HudValue.EMPTY)))
        // Textfelder: leere Zeile = Element 0, Beschriftungen kommen mit; Nachricht passt in ein Paket
        try {
            assertTrue(SlotConfig.set(DisplayMode.TRACKING, pick))
            val txt = String(HudProtocol.textSlots().body, Charsets.ISO_8859_1)
            for (w in listOf("GESCHWINDIGKEIT", "HOEHE", "ANKUNFT", "VERBLEIBEND")) assertTrue(w, txt.contains(w))
            assertTrue(HudProtocol.textSlots().body.size <= HudProtocol.maxMessageBytes())
        } finally {
            SlotConfig.set(DisplayMode.TRACKING, SlotConfig.CRUISER_DEFAULT)
        }
        // Modus-Setup und Handshake enthalten die Textfelder auch für Guide
        assertEquals(3, HudProtocol.switchMode(DisplayMode.GUIDE).size)
        assertEquals(HudProtocol.handshake(1, 2, DisplayMode.CITY).size + 1, HudProtocol.handshake(1, 2, DisplayMode.GUIDE).size)
    }
}
