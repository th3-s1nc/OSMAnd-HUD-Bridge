package io.github.th3s1nc.osmandhudbridge.protocol

/** Eine logische HUD-Nachricht (Protobuf-Body). [ackRequired]: HUD soll sie quittieren (Standardverhalten). */
class HudMessage(val body: ByteArray, val ackRequired: Boolean = true)

/** Quittung des HUD: [transactionId] gehört zur gesendeten Nachricht, [ok] = HUD hat sie vollständig verstanden. */
class HudAck(val transactionId: Int, val ok: Boolean)

/**
 * Funkprotokoll des HUD, beschrieben allein zur Interoperabilität (siehe PROTOCOL.md). Die Werte wurden durch Beobachten
 * des Funkverkehrs ermittelt und gegen Referenz-Mitschnitte geprüft; es ist kein Code und kein Material des Herstellers enthalten.
 *
 * Transport (je BLE-Write max. 20 Byte):
 *   Byte 0: (Transaktions-ID << 4) | Paketindex
 *   Byte 1: (Pakettyp << 5) | Länge der Nutzdaten im Paket
 * Pakettypen: FIRST 0, FIRST_ACK 1, CONT 2, SINGLE_ACK 3, LAST 4, LAST_ACK 5, CTRL 6, SINGLE 7.
 * Mehrteilige Nachricht: erstes Paket [tid<<4][type<<5|16][len lo][len hi] + 16 Byte, danach je 18 Byte,
 * letztes Paket (Rest <= 18 Byte) als LAST/LAST_ACK. Das HUD quittiert mit einem CTRL-Paket [tid<<4][0xC1][status].
 */
object HudProtocol {
    // Pakettypen
    const val PKT_FIRST = 0
    const val PKT_FIRST_ACK = 1
    const val PKT_CONT = 2
    const val PKT_SINGLE_ACK = 3
    const val PKT_LAST = 4
    const val PKT_LAST_ACK = 5
    const val PKT_CTRL = 6
    const val PKT_SINGLE = 7

    const val MAX_SINGLE = 18
    const val FIRST_CHUNK = 16
    const val CHUNK = 18
    const val MAX_FRAMES = 16 // 4 Bit Paketindex

    // Feldnummern der UpdateMessage
    const val F_SPEED = 1
    const val F_COMPASS = 2
    const val F_SPEED_LIMIT = 3
    const val F_PART_DISTANCE = 4
    const val F_POINTER = 5
    const val F_TIME = 8
    const val F_ROUTE_DURATION = 10
    const val F_ROUTE_DISTANCE = 11
    const val F_ARRIVAL_TIME = 12
    const val F_NEXT_STREET = 15
    const val F_CURRENT_STREET = 17
    const val F_LANE_INFO = 18
    const val F_CALL_EVENT = 21
    const val F_SMS_EVENT = 22
    const val F_COMMAND = 100

    // Einheiten
    private const val UNIT_KM = 1
    private const val UNIT_M = 2
    private const val SPEED_UNIT_KMH = 1

    // ------------------------------------------------------------------ Transport

    fun maxMessageBytes(): Int = FIRST_CHUNK + (MAX_FRAMES - 1) * CHUNK

    fun frame(transactionId: Int, msg: HudMessage): List<ByteArray> {
        val tid = (transactionId and 0x0f) shl 4
        val body = msg.body
        val ack = msg.ackRequired
        if (body.size <= MAX_SINGLE) {
            val type = if (ack) PKT_SINGLE_ACK else PKT_SINGLE
            return listOf(byteArrayOf(tid.toByte(), ((type shl 5) or body.size).toByte()) + body)
        }
        require(body.size <= maxMessageBytes()) { "Nachricht zu lang: ${body.size} Byte" }
        val frames = ArrayList<ByteArray>()
        val total = body.size
        val firstType = if (ack) PKT_FIRST_ACK else PKT_FIRST
        frames += byteArrayOf(
            tid.toByte(), ((firstType shl 5) or FIRST_CHUNK).toByte(),
            (total and 0xff).toByte(), ((total shr 8) and 0xff).toByte()
        ) + body.copyOfRange(0, FIRST_CHUNK)
        var idx = 1
        while (true) {
            val offset = idx * CHUNK - 2
            val remaining = total - offset
            if (remaining <= 0) break
            if (remaining <= CHUNK) {
                val type = if (ack) PKT_LAST_ACK else PKT_LAST
                frames += byteArrayOf((tid or idx).toByte(), ((type shl 5) or remaining).toByte()) +
                    body.copyOfRange(offset, total)
                break
            }
            frames += byteArrayOf((tid or idx).toByte(), ((PKT_CONT shl 5) or CHUNK).toByte()) +
                body.copyOfRange(offset, offset + CHUNK)
            idx++
        }
        return frames
    }

    /** Wertet eine Notification der Notify-Characteristic aus. Nur Quittungen (CTRL-Pakete) ergeben ein Ergebnis. */
    fun parseAck(v: ByteArray): HudAck? {
        if (v.size < 3) return null
        val type = (v[1].toInt() and 0xff) shr 5
        if (type != PKT_CTRL) return null
        return HudAck((v[0].toInt() and 0xff) shr 4, v[2].toInt() == 0)
    }

    // ------------------------------------------------------------------ Felder

    fun speedField(kmh: Float): ByteArray {
        val v = if (kmh.isNaN() || kmh < 0f) 0 else kmh.toInt()
        return Protobuf.message(F_SPEED, Protobuf.uint(1, v), Protobuf.uint(2, SPEED_UNIT_KMH))
    }

    /** Tempolimit: Limit (>= 252 km/h = unbekannt), "Tempo ok"-Flag, Kameratyp. */
    fun speedLimitField(limitKmh: Int, ok: Boolean, camera: Int): ByteArray {
        val limit = if (limitKmh in 1..251) limitKmh else 0
        return Protobuf.message(
            F_SPEED_LIMIT,
            Protobuf.uint(1, limit), Protobuf.uint(2, if (ok) 1 else 0), Protobuf.uint(4, camera)
        )
    }

    /**
     * Distanz-Darstellung (Einstellung Meter/Kilometer):
     * unter 1 km in Metern, gerundet auf 100 m (ab 300 m), 50 m (ab 100 m), sonst 10 m; ab 1 km ganze Kilometer (abgeschnitten).
     * null/negativ = leere Nachricht (löscht die Anzeige).
     */
    fun distanceParts(meters: Int?): List<ByteArray> {
        if (meters == null || meters < 0) return emptyList()
        return if (meters < 1000) {
            val v = when {
                meters >= 300 -> meters / 100 * 100
                meters >= 100 -> meters / 50 * 50
                else -> meters / 10 * 10
            }
            listOf(Protobuf.uint(1, v), Protobuf.uint(2, UNIT_M))
        } else {
            listOf(Protobuf.uint(1, meters / 1000), Protobuf.uint(2, UNIT_KM))
        }
    }

    fun partDistanceField(meters: Int?): ByteArray = Protobuf.message(F_PART_DISTANCE, distanceParts(meters))

    fun routeDistanceField(meters: Int?): ByteArray = Protobuf.message(F_ROUTE_DISTANCE, distanceParts(meters))

    /** Pfeil. Die Ausfahrtsnummer wird nur bei Kreisverkehr-Pfeilen gesendet. */
    fun pointerField(command: NavCommand?, exit: Int?): ByteArray {
        if (command == null || command == NavCommand.NONE) return Protobuf.message(F_POINTER)
        val n = if (command.hasExit) (exit ?: 0) else 0
        return Protobuf.message(F_POINTER, Protobuf.uint(1, command.id), Protobuf.uint(2, n))
    }

    fun timeField(hour: Int, minute: Int): ByteArray =
        Protobuf.message(F_TIME, Protobuf.uint(1, hour), Protobuf.uint(2, minute))

    fun arrivalTimeField(hour: Int, minute: Int): ByteArray =
        Protobuf.message(F_ARRIVAL_TIME, Protobuf.uint(1, hour), Protobuf.uint(2, minute))

    fun routeDurationField(totalMinutes: Int?): ByteArray {
        if (totalMinutes == null || totalMinutes < 0) return Protobuf.message(F_ROUTE_DURATION)
        return Protobuf.message(
            F_ROUTE_DURATION, Protobuf.uint(1, totalMinutes / 60), Protobuf.uint(2, totalMinutes % 60)
        )
    }

    /** Kompass: Richtung = (Grad / 2) % 180. */
    fun compassField(degrees: Int): ByteArray =
        Protobuf.message(F_COMPASS, Protobuf.uint(1, ((((degrees % 360) + 360) % 360) / 2) % 180))

    /** Straßenname für das HUD: nur ASCII (Umlaute werden umschrieben), höchstens 40 Zeichen. */
    fun cleanAscii(s: String): String {
        val t = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        val d = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in d) if (c.code in 0x20..0x7e) sb.append(c)
        return sb.toString().trim().take(40)
    }

    /** field = F_NEXT_STREET oder F_CURRENT_STREET. null/leer = Anzeige löschen. */
    fun streetField(field: Int, name: String?): ByteArray {
        val c = name?.let(::cleanAscii)
        return if (c.isNullOrEmpty()) Protobuf.message(field) else Protobuf.message(field, Protobuf.string(1, c))
    }

    /**
     * Anruf-/SMS-Ereignis (Felder 21/22): state 1 = eingehend/empfangen, 2 = aktiv (nur Anruf), Name höchstens 19 Zeichen ASCII,
     * autoHideS = Sekunden bis das HUD die Meldung selbst ausblendet. Format abgeleitet, Anruf am HUD bestätigt, SMS-Meldung wird nicht angezeigt.
     */
    fun eventField(field: Int, state: Int, name: String?, autoHideS: Int): ByteArray {
        val n = name?.let { cleanAscii(it).take(19) }?.takeIf { it.isNotEmpty() }
        return Protobuf.message(
            field,
            Protobuf.uint(1, state),
            if (n != null) Protobuf.message(2, Protobuf.string(1, n)) else Protobuf.EMPTY,
            Protobuf.uint(3, autoHideS)
        )
    }

    /** Beide Ereignisse löschen (leere Nachrichten, wie bei den anderen Feldern). */
    fun clearEventsBody(): ByteArray = Protobuf.concat(listOf(Protobuf.message(F_CALL_EVENT), Protobuf.message(F_SMS_EVENT)))

    fun clearEventBody(field: Int): ByteArray = Protobuf.message(field)

    fun laneInfoField(lanes: List<Lane>): ByteArray =
        Protobuf.message(
            F_LANE_INFO,
            lanes.map { Protobuf.message(1, Protobuf.uint(1, it.directions), Protobuf.uint(2, it.recommendation)) }
        )

    /**
     * "Tempo ok"-Flag fürs HUD: bei zu schnell zeigt es Tempo und Limit fett. Warnung aus oder Limit unbekannt = immer ok,
     * sonst ok bis Limit + Toleranz (km/h).
     */
    fun speedOk(speedKmh: Float, limitKmh: Int, warnEnabled: Boolean = true, toleranceKmh: Int = 0): Boolean =
        !warnEnabled || limitKmh <= 0 || speedKmh <= limitKmh + toleranceKmh

    /** Alle Felder des Navigations-Bildschirms, nach Feldnummer sortiert. */
    fun encodeState(s: HudState, mode: DisplayMode = DisplayMode.NAVIGATOR): LinkedHashMap<Int, ByteArray> {
        val ok = s.limitEstimated || speedOk(s.speedKmh, s.speedLimitKmh, s.warnEnabled, s.warnToleranceKmh)
        val m = LinkedHashMap<Int, ByteArray>()
        m[F_SPEED] = speedField(s.speedKmh)
        m[F_SPEED_LIMIT] = speedLimitField(s.speedLimitKmh, ok, s.camera)
        m[F_PART_DISTANCE] = partDistanceField(s.partDistanceM)
        m[F_POINTER] = pointerField(s.command, s.roundaboutExit)
        m[F_TIME] = timeField(s.hour, s.minute)
        m[F_ROUTE_DURATION] = routeDurationField(s.routeMinutes)
        m[F_ROUTE_DISTANCE] = routeDistanceField(s.routeDistanceM)
        m[F_LANE_INFO] = laneInfoField(s.lanes)
        s.headingDeg?.let { m[F_COMPASS] = compassField(it) }
        val ah = s.arrivalHour
        val am = s.arrivalMinute
        if (ah != null && am != null) m[F_ARRIVAL_TIME] = arrivalTimeField(ah, am)
        // Die Elemente "nächste Straße" und "aktuelle Straße" liegen im HUD übereinander: immer nur eines mit Text,
        // die nächste Straße (Navigation) hat Vorrang
        m[F_NEXT_STREET] = streetField(F_NEXT_STREET, s.nextStreet)
        m[F_CURRENT_STREET] = streetField(F_CURRENT_STREET, if (s.nextStreet.isNullOrBlank()) s.currentStreet else null)
        m.keys.retainAll(mode.fields)
        return m
    }

    /** Felder, die sich gegenüber dem zuletzt gesendeten Stand geändert haben (Feldnummern, aufsteigend). */
    fun changedFields(sent: Map<Int, ByteArray>, want: Map<Int, ByteArray>): List<Int> =
        want.keys.sorted().filter { k -> sent[k]?.contentEquals(want.getValue(k)) != true }

    /** Packt Felder zu Nachrichten bis höchstens [MAX_SINGLE] Byte (wie beobachtet); größere Felder gehen allein. */
    fun bundle(fields: List<ByteArray>): List<HudMessage> {
        val out = ArrayList<HudMessage>()
        var cur = ArrayList<ByteArray>()
        var size = 0
        fun flush() {
            if (cur.isNotEmpty()) out += HudMessage(Protobuf.concat(cur))
            cur = ArrayList()
            size = 0
        }
        for (f in fields) {
            if (size > 0 && size + f.size > MAX_SINGLE) flush()
            cur += f
            size += f.size
            if (size >= MAX_SINGLE) flush()
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------ Kommandos (Feld 100)

    private fun command(sub: ByteArray, ack: Boolean = true) =
        HudMessage(Protobuf.message(F_COMMAND, sub), ack)

    /** Navigation beendet / HUD zurücksetzen (Kommando 20). */
    fun navigationFinished() = command(Protobuf.message(20, Protobuf.uint(1, 1)))

    /** Konfiguration lesen (HUD antwortet mit eigener Nachricht, deshalb ohne Quittung). */
    fun readConfig() = command(Protobuf.message(11, Protobuf.uint(1, 1)), ack = false)

    fun showHide(screen: Int, hide: List<Int>, show: List<Int>): ByteArray =
        Protobuf.message(F_COMMAND, Protobuf.message(2, Protobuf.uint(1, screen), Protobuf.packed(2, hide), Protobuf.packed(3, show)))

    /** Elemente 4 (Restdistanz) und 5 (Restzeit): sichtbar nur, wenn Werte dafür vorliegen. Sonst zeigt das HUD "0 min". */
    fun routeElements(mode: DisplayMode, visible: Boolean) = HudMessage(
        if (visible) showHide(mode.screenId, emptyList(), mode.routeElements)
        else showHide(mode.screenId, mode.routeElements, emptyList())
    )

    /** Moduswechsel: ShowHide-Nachricht, dann Bildschirm aktivieren. */
    fun switchMode(mode: DisplayMode): List<HudMessage> =
        listOf(HudMessage(showHide(mode.screenId, mode.hide, mode.show)), activateScreen(mode.screenId))

    fun activateScreen(screen: Int) = command(Protobuf.message(5, Protobuf.uint(1, screen)))

    /** Automatische Helligkeit einschalten (Wert 2 = ein), beim Verbinden. */
    fun brightnessAutomatic() = command(Protobuf.message(10, Protobuf.message(1, Protobuf.uint(2, 2))))

    /**
     * Manuelle Helligkeit: Stufe 1 = dunkel, 2 = mittel, 3 = hell (am HUD geprüft).
     * Die Zuordnung Regler -> Stufe steht nur in [brightnessLevelForStep].
     */
    fun brightnessManual(level: Int) =
        command(Protobuf.message(10, Protobuf.message(1, Protobuf.uint(1, level), Protobuf.uint(2, 1))))

    /** Regler-Stellung 0 = dunkel, 1 = mittel, 2 = hell -> Helligkeitsstufe des HUD. */
    fun brightnessLevelForStep(step: Int): Int = step.coerceIn(0, 2) + 1

    /** Helligkeit nach Einstellung: step < 0 = automatisch, sonst Reglerstellung 0..2. */
    fun brightness(step: Int) = if (step < 0) brightnessAutomatic() else brightnessManual(brightnessLevelForStep(step))

    /** Justage-Modus: das HUD zeigt sein Justagebild (Bildschirm 1). Beenden: [leaveJustage]. */
    fun enterJustage() = activateScreen(JUSTAGE_SCREEN)

    /** Justage beenden: Konfiguration lesen, Elemente des Modus setzen, Bildschirm des Modus aktivieren. */
    fun leaveJustage(mode: DisplayMode): List<HudMessage> =
        listOf(readConfig(), HudMessage(showHide(mode.screenId, mode.hide, mode.show)), activateScreen(mode.screenId))

    const val JUSTAGE_SCREEN = 1

    /** Ablauf direkt nach dem Verbinden (Navigations-Bildschirm 13). [brightnessStep]: -1 = automatisch, 0..2 = manuell. */
    fun handshake(hour: Int, minute: Int, mode: DisplayMode = DisplayMode.NAVIGATOR, brightnessStep: Int = -1): List<HudMessage> = listOf(
        navigationFinished(),
        readConfig(),
        HudMessage(
            Protobuf.concat(
                listOf(timeField(hour, minute), showHide(mode.screenId, mode.hide, mode.show))
            )
        ),
        activateScreen(mode.screenId),
        brightness(brightnessStep)
    )
}
