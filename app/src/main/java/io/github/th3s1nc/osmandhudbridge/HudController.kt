package io.github.th3s1nc.osmandhudbridge

import android.os.SystemClock
import io.github.th3s1nc.osmandhudbridge.ble.HudClient
import io.github.th3s1nc.osmandhudbridge.protocol.CameraType
import io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode
import io.github.th3s1nc.osmandhudbridge.protocol.HudProtocol
import io.github.th3s1nc.osmandhudbridge.protocol.HudMessage
import io.github.th3s1nc.osmandhudbridge.protocol.HudState
import io.github.th3s1nc.osmandhudbridge.protocol.Lane
import io.github.th3s1nc.osmandhudbridge.protocol.LaneDirection
import io.github.th3s1nc.osmandhudbridge.protocol.LaneRecommendation
import io.github.th3s1nc.osmandhudbridge.protocol.NavCommand
import io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode
import java.util.Calendar

/**
 * Hält den gewünschten Anzeigezustand und sendet nur geänderte Felder ans HUD.
 * Nach jeder (Wieder-)Verbindung wird alles neu gesendet (inklusive "leer"-Felder).
 * Nur auf dem Main-Thread benutzen.
 */
class HudController(private val client: HudClient) {
    private var want = HudState()
    private var sent: Map<Int, ByteArray> = emptyMap()
    private var navDeadline = 0L
    private var lastSendAt = 0L
    private var routeShown: Boolean? = null

    // Hochrechnung der Manöver-Distanz zwischen zwei OSMAnd-Meldungen (OSMAnd meldet nur alle ca. 3 s)
    private var refMeters: Int? = null
    private var refAt = 0L
    private var refCommand: NavCommand? = null
    private var rate = 0f // Meter pro Sekunde, aus den letzten beiden Meldungen
    var threshold: ThresholdMode = ThresholdMode.NORMAL

    /** Warnton bei Tempoüberschreitung (zusätzlich zur Anzeige am HUD). */
    var acousticWarn = false
    var onOverspeed: (() -> Unit)? = null
    private val alarm = io.github.th3s1nc.osmandhudbridge.limit.OverspeedAlarm()

    /** Zwischen den Abbiegungen dauerhaft den Geradeaus-Pfeil zeigen (ohne Distanz), solange OSMAnd frische Daten liefert. */
    var keepStraight = false
        set(v) {
            if (field == v) return
            field = v
            flush()
        }

    var mode: DisplayMode = DisplayMode.NAVIGATOR
        private set

    /** Wechselt den Anzeigemodus (ShowHide + Aktivieren, danach alle Felder neu). */
    fun setMode(m: DisplayMode) {
        if (m == mode) return
        mode = m
        client.mode = m
        BridgeBus.log("Anzeigemodus: ${m.title}")
        if (!client.isReady || justage) return // im Justage-Modus gilt der neue Modus erst nach dem Beenden
        if (client.send(HudProtocol.switchMode(m))) {
            sent = emptyMap()
            routeShown = null
                flush()
        } else {
            // Warteschlange voll: sauber neu aufbauen lassen
            BridgeBus.log("Moduswechsel zurückgestellt")
        }
    }

    private var justage = false
    private var brightnessStep = Int.MIN_VALUE

    /** Tempowarnung: an/aus und Toleranz in km/h (HUD zeigt Tempo und Limit fett ab Limit + Toleranz). */
    fun setWarn(enabled: Boolean, toleranceKmh: Int) {
        val t = toleranceKmh.coerceIn(0, 30)
        if (want.warnEnabled == enabled && want.warnToleranceKmh == t) return
        want = want.copy(warnEnabled = enabled, warnToleranceKmh = t)
        flush()
    }

    /** Helligkeit: -1 = automatisch, 0..2 = dunkel/mittel/hell. Wird beim Verbinden ohnehin gesetzt. */
    fun setBrightness(step: Int) {
        if (step == brightnessStep) return
        brightnessStep = step
        client.brightnessStep = step
        BridgeBus.log("Helligkeit: " + if (step < 0) "automatisch" else listOf("dunkel", "mittel", "hell")[step.coerceIn(0, 2)])
        if (client.isReady) client.send(listOf(HudProtocol.brightness(step)))
    }

    /** Justage-Modus ein/aus. Gibt false zurück, wenn das HUD nicht verbunden ist (dann bleibt er aus). */
    fun setJustage(on: Boolean): Boolean {
        if (on == justage) return true
        if (!client.isReady) return !on
        if (on) {
            if (!client.send(listOf(HudProtocol.enterJustage()))) return false
            justage = true
            BridgeBus.log("Justage-Modus ein")
        } else {
            if (!client.send(HudProtocol.leaveJustage(mode))) return false
            justage = false
            BridgeBus.log("Justage-Modus aus")
            sent = emptyMap()
            routeShown = null
            flush()
        }
        return true
    }

    /** Startmodus vor dem Verbinden setzen (ohne Senden). */
    fun setModeSilently(m: DisplayMode) {
        mode = m
        client.mode = m
    }

    /** Fahrtrichtung in Grad für den Kompass (Explorer). */
    fun setHeading(deg: Int?) {
        val d = deg?.let { (it % 360 + 360) % 360 }
        // nur senden, wenn sich die Richtung merklich ändert (HUD löst in 2-Grad-Schritten auf)
        val old = want.headingDeg
        if (d != null && old != null) {
            val diff = Math.abs(d - old).let { if (it > 180) 360 - it else it }
            if (diff < 4) return
        }
        want = want.copy(headingDeg = d)
        flush()
    }

    /** Aktuelle Straße (aus OSM). */
    fun setCurrentStreet(name: String?) {
        if (name == want.currentStreet) return
        want = want.copy(currentStreet = name)
        flush()
    }
    /** Kreisverkehr-Ausfahrt aus der OSMAnd-Benachrichtigung (die AIDL-Schnittstelle liefert sie nicht). */
    private var exitHint: Int? = null

    fun setExitHint(exit: Int?) {
        if (exit == exitHint) return
        exitHint = exit
        flush()
    }

    fun setSpeed(kmh: Float) {
        want = want.copy(speedKmh = kmh)
        flush()
    }

    /** Gefahrene Strecke (m), gefahrene Zeit (min) und Höhe (m) für Guide und Cruiser. */
    fun setTrip(distanceM: Int, minutes: Int, elevationM: Int? = null) {
        val w = want
        if (w.tripDistanceM == distanceM && w.tripMinutes == minutes && w.elevationM == elevationM) return
        want = w.copy(tripDistanceM = distanceM, tripMinutes = minutes, elevationM = elevationM)
        if (mode.usesTextSlots) flush()
    }

    /** Kein GPS-Empfang (länger keine Position): das HUD zeigt sein Warnsymbol (nur Guide und Cruiser). */
    fun setGpsLost(lost: Boolean) {
        if (want.gpsLost == lost) return
        want = want.copy(gpsLost = lost)
        if (mode.usesTextSlots) flush()
    }

    /** Gewählte Zeilen von Guide/Cruiser haben sich geändert: Textfelder neu senden, dann alle Felder. */
    fun slotsChanged() {
        if (!client.isReady || justage || !mode.usesTextSlots) return
        if (client.send(listOf(HudProtocol.textSlots()))) {
            sent = emptyMap()
            flush()
        } else BridgeBus.log("Zeilenwahl zurückgestellt")
    }

    /** Aktuelles Tempolimit für die Aufzeichnung: 0, wenn unbekannt oder nur geschätzt. */
    fun recordLimit(): Int = if (want.limitEstimated) 0 else want.speedLimitKmh.coerceAtLeast(0)

    /** 0 = unbekannt. */
    fun setLimit(kmh: Int, estimated: Boolean = false) {
        want = want.copy(speedLimitKmh = kmh, limitEstimated = estimated && kmh > 0)
        flush()
    }

    /** ttlMs: nach dieser Zeit ohne neues Update wird Pfeil/Distanz geleert (null = nie). */
    fun setNav(
        meters: Int?, command: NavCommand?, exit: Int? = null, ttlMs: Long? = NAV_TTL_MS,
        extrapolate: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        // Zielflagge läuft noch: späte OSMAnd-Meldungen dürfen sie nicht überschreiben
        if (want.command == NavCommand.GOAL && navDeadline != 0L && now < navDeadline) return
        val prev = refMeters
        if (refAt != 0L && now - refAt > 10_000L && meters != null)
            BridgeBus.log("OSMAnd war ${(now - refAt) / 1000} s still")
        if (extrapolate && meters != null && command != null) {
            if (prev != null && command == refCommand) {
                val dt = (now - refAt) / 1000f
                if (dt in 0.3f..8f && meters <= prev) {
                    val r = ((prev - meters) / dt).coerceIn(0f, 60f)
                    rate = if (rate == 0f) r else 0.5f * rate + 0.5f * r
                }
            } else {
                rate = 0f
            }
            refMeters = meters; refAt = now; refCommand = command
        } else {
            refMeters = null; rate = 0f; refCommand = null
        }
        // Frische Meldung: auch hier die Zeit abziehen, die sie schon unterwegs war (sonst springt die Anzeige zurück)
        val shown = if (extrapolate && meters != null && command != null)
            io.github.th3s1nc.osmandhudbridge.protocol.NavExtrapolation.distance(meters, 0f, want.speedKmh, rate)
        else meters
        want = want.copy(partDistanceM = shown, command = command, roundaboutExit = exit)
        navDeadline = if (ttlMs == null || (meters == null && command == null)) 0L
        else SystemClock.elapsedRealtime() + ttlMs
        flush()
    }

    private var callClearAt = 0L
    private var msgClearAt = 0L
    private var musicPhase = 0 // 0 aus, 2 Interpret läuft, 3 Pause, 4 Titel läuft
    private var musicNextAt = 0L
    private var musicArtist = ""
    private var musicTitle = ""
    private var callActive = false

    /**
     * Anruf (call=true) bzw. Nachricht anzeigen oder wegnehmen. Das HUD zeigt Nachrichten-Ereignisse nicht an,
     * deshalb läuft beides über die Anruf-Anzeige (Feld 21): Anruf bis er endet, Nachricht kurz.
     * Nur in Modi, die das HUD dafür darstellt (Explorer, City).
     */
    fun showNotice(call: Boolean, name: String?, clear: Boolean) {
        if (!client.isReady) {
            if (!clear) BridgeBus.log("Meldung verworfen: HUD nicht verbunden")
            return
        }
        val field = HudProtocol.F_CALL_EVENT
        if (clear) {
            if (call) { callActive = false; callClearAt = 0L } else { msgClearAt = 0L; if (callActive) return }
            client.send(listOf(HudMessage(HudProtocol.clearEventBody(field))))
            return
        }
        if (!mode.supportsEvents) {
            BridgeBus.log("Meldung nicht angezeigt: Modus ${mode.title} hat keinen Platz dafür (nur Explorer/City)")
            return
        }
        if (!call && callActive) return // laufender Anruf hat Vorrang
        musicPhase = 0 // Meldung oder Anruf ersetzt den Titel
        val hideS = if (call) CALL_MAX_S else MSG_SHOW_S
        client.send(listOf(HudMessage(HudProtocol.eventField(field, 1, name, hideS))))
        val until = SystemClock.elapsedRealtime() + hideS * 1000L
        if (call) { callActive = true; callClearAt = until } else msgClearAt = until
        BridgeBus.log(if (call) "HUD: Anruf angezeigt" else "HUD: Nachricht angezeigt")
    }

    /**
     * Neuer Musiktitel: im Meldungsfeld des HUD, dort wo auch Anrufe und Nachrichten erscheinen (nur Explorer und City,
     * nicht während eines Anrufs). Das HUD scrollt nicht und verträgt höchstens 19 Zeichen (mehr ließ es einfrieren).
     * Deshalb nacheinander: Interpret 4 s, 1 s leer, Titel 4 s. [tick] steuert den Ablauf.
     */
    fun showMusic(artist: String?, title: String) {
        if (!client.isReady) return
        if (!mode.supportsEvents) {
            BridgeBus.log("Titel nicht angezeigt: Modus ${mode.title} hat keinen Platz dafür (nur Explorer/City)")
            return
        }
        if (callActive) return
        musicArtist = HudProtocol.cleanAscii(artist ?: "").take(MUSIC_MAX_CHARS)
        musicTitle = HudProtocol.cleanAscii(title).take(MUSIC_MAX_CHARS)
        if (musicArtist.isEmpty() && musicTitle.isEmpty()) return // nur Zeichen, die das HUD nicht darstellt
        BridgeBus.log("HUD: Titel angezeigt (Interpret: $musicArtist, Titel: $musicTitle)")
        if (musicArtist.isNotEmpty()) {
            sendMusicText(musicArtist)
            musicPhase = 2
        } else {
            sendMusicText(musicTitle)
            musicPhase = 4
        }
        musicNextAt = SystemClock.elapsedRealtime() + MUSIC_PART_S * 1000L
    }

    private fun sendMusicText(text: String) {
        client.send(listOf(HudMessage(HudProtocol.eventField(HudProtocol.F_CALL_EVENT, 1, text, MUSIC_HUD_HIDE_S, MUSIC_MAX_CHARS))))
    }

    /** Nächster Schritt der Musik-Anzeige (wird von [tick] aufgerufen). */
    private fun musicStep(now: Long) {
        if (musicPhase == 0 || now < musicNextAt) return
        if (callActive || !client.isReady) { musicPhase = 0; return }
        when (musicPhase) {
            2 -> { // Interpret weg, kurze Pause
                client.send(listOf(HudMessage(HudProtocol.clearEventBody(HudProtocol.F_CALL_EVENT))))
                musicPhase = if (musicTitle.isNotEmpty()) 3 else 0
                musicNextAt = now + MUSIC_GAP_MS
            }
            3 -> { // Titel
                sendMusicText(musicTitle)
                musicPhase = 4
                musicNextAt = now + MUSIC_PART_S * 1000L
            }
            else -> { // fertig, Feld leeren
                client.send(listOf(HudMessage(HudProtocol.clearEventBody(HudProtocol.F_CALL_EVENT))))
                musicPhase = 0
            }
        }
    }

    /** Titel wegnehmen (Schalter aus). */
    fun clearMusic() {
        if (musicPhase != 0) {
            musicPhase = 0
            if (client.isReady && !callActive) client.send(listOf(HudMessage(HudProtocol.clearEventBody(HudProtocol.F_CALL_EVENT))))
        }
    }

    fun setRoute(
        distanceM: Int?, minutes: Int?, arrivalHour: Int? = null, arrivalMinute: Int? = null, nextStreet: String? = null
    ) {
        want = want.copy(
            routeDistanceM = distanceM, routeMinutes = minutes,
            arrivalHour = arrivalHour, arrivalMinute = arrivalMinute, nextStreet = nextStreet
        )
        flush()
    }

    /** Ziel erreicht (Ansage "reached_destination" von OSMAnd): Zielflagge für einige Sekunden. */
    fun showGoal() {
        refMeters = null; rate = 0f; refCommand = null
        want = want.copy(partDistanceM = null, command = NavCommand.GOAL, roundaboutExit = null)
        navDeadline = SystemClock.elapsedRealtime() + GOAL_SHOW_MS
        flush()
    }

    /** Zwischenziel erreicht: VIA-Symbol für einige Sekunden. */
    fun showVia() {
        refMeters = null; rate = 0f; refCommand = null
        want = want.copy(partDistanceM = null, command = NavCommand.VIA, roundaboutExit = null)
        navDeadline = SystemClock.elapsedRealtime() + VIA_SHOW_MS
        flush()
    }

    fun clearNavigation() {
        navDeadline = 0L
        refMeters = null; rate = 0f
        want = want.copy(
            partDistanceM = null, command = null, roundaboutExit = null,
            routeDistanceM = null, routeMinutes = null, lanes = emptyList(),
            arrivalHour = null, arrivalMinute = null, nextStreet = null
        )
        flush()
    }

    fun onReady() {
        justage = false // der Verbindungsaufbau aktiviert den normalen Bildschirm wieder
        sent = emptyMap()
        routeShown = null
        flush()
    }

    /** Sekundentakt vom Service. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val ref = refMeters
        if (ref != null && want.command != null) {
            val d = io.github.th3s1nc.osmandhudbridge.protocol.NavExtrapolation.distance((ref), (now - refAt) / 1000f, want.speedKmh, rate)
            if (d != want.partDistanceM) want = want.copy(partDistanceM = d)
        }
        if (navDeadline != 0L && now > navDeadline) {
            BridgeBus.log("Navigationsdaten veraltet, HUD-Anzeige geleert")
            navDeadline = 0L
            refMeters = null; rate = 0f
            want = want.copy(partDistanceM = null, command = null, roundaboutExit = null)
        }
        if (callClearAt != 0L && now > callClearAt) showNotice(true, null, true)
        if (msgClearAt != 0L && now > msgClearAt) showNotice(false, null, true)
        musicStep(now)
        // Warnton: nur mit HUD (dann kommt das Tempo jede Sekunde), nur bei echtem Limit aus den Kartendaten
        if (acousticWarn && want.warnEnabled && !want.limitEstimated && client.isReady) {
            if (alarm.update(want.speedKmh, want.speedLimitKmh, want.warnToleranceKmh, now)) {
                BridgeBus.log("Tempo ${want.speedKmh.toInt()} bei Limit ${want.speedLimitKmh}: Warnton")
                onOverspeed?.invoke()
            }
        } else alarm.pause()
        if (client.isReady && now - lastSendAt > KEEPALIVE_MS) {
            // Lebenszeichen: Uhrzeit erneut senden. Das HUD quittiert, ein totes Gerät fällt so auf.
            sent = sent - HudProtocol.F_TIME
        }
        flush()
    }

    /** Anzeige-Zustand mit Schwellwert-Regel (Pfeil/Distanz/Spuren erst nahe am Manöver). */
    private fun effective(): HudState {
        val w = want
        val cal = Calendar.getInstance()
        val exit = w.roundaboutExit ?: if (w.command?.hasExit == true) exitHint else null
        val d = w.partDistanceM
        val s = w.copy(hour = cal.get(Calendar.HOUR_OF_DAY), minute = cal.get(Calendar.MINUTE), roundaboutExit = exit)
        return if (d != null && threshold.isOver(w.speedLimitKmh, d)) {
            // Ziel- und Zwischenziel-Symbol haben keine Distanz und kommen hier nie an; nur echte Manöver werden ersetzt
            if (keepStraight && w.command != null) s.copy(partDistanceM = null, command = NavCommand.STRAIGHT, roundaboutExit = null, lanes = emptyList())
            else s.copy(partDistanceM = null, command = null, roundaboutExit = null, lanes = emptyList())
        } else s
    }

    private fun flush(force: Boolean = false) {
        if (!client.isReady || justage) return
        val eff = effective()
        val wantFields = HudProtocol.encodeState(eff, mode)
        if (force) sent = emptyMap()
        // Restzeit/-distanz ausblenden, solange es keine Werte gibt (sonst steht dauerhaft "0 min" da)
        val wantRoute = eff.routeMinutes != null || eff.routeDistanceM != null || eff.arrivalHour != null
        if (mode.routeElements.isNotEmpty() && routeShown != wantRoute) {
            if (client.send(listOf(HudProtocol.routeElements(mode, wantRoute)))) routeShown = wantRoute
        }
        val changed = HudProtocol.changedFields(sent, wantFields)
        if (changed.isEmpty()) return
        val msgs = HudProtocol.bundle(changed.map { wantFields.getValue(it) })
        if (client.send(msgs)) {
            sent = sent + changed.associateWith { wantFields.getValue(it) }
            lastSendAt = SystemClock.elapsedRealtime()
        }
    }

    private companion object {
        const val NAV_TTL_MS = 60_000L
        const val MAX_EXTRAPOLATE_S = 4f
        const val KEEPALIVE_MS = 10_000L
        const val CALL_MAX_S = 120
        const val MSG_SHOW_S = 5
        const val MUSIC_PART_S = 4
        const val MUSIC_HUD_HIDE_S = 9 // nur Sicherheit, die App nimmt den Text selbst weg
        const val MUSIC_GAP_MS = 1_000L
        const val MUSIC_MAX_CHARS = 19
        const val GOAL_SHOW_MS = 20_000L
        const val VIA_SHOW_MS = 8_000L
    }
}
