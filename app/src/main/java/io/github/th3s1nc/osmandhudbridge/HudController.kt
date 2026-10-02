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

    // Hochrechnung der Manöver-Distanz zwischen zwei OsmAnd-Meldungen (OsmAnd meldet nur alle ca. 3 s)
    private var refMeters: Int? = null
    private var refAt = 0L
    private var refCommand: NavCommand? = null
    private var rate = 0f // Meter pro Sekunde, aus den letzten beiden Meldungen
    var threshold: ThresholdMode = ThresholdMode.ONE_KM

    var mode: DisplayMode = DisplayMode.NAVIGATOR
        private set

    /** Wechselt den Anzeigemodus (ShowHide + Aktivieren, danach alle Felder neu). */
    fun setMode(m: DisplayMode) {
        if (m == mode) return
        mode = m
        client.mode = m
        BridgeBus.log("Anzeigemodus: ${m.title}")
        if (!client.isReady) return
        if (client.send(HudProtocol.switchMode(m))) {
            sent = emptyMap()
            routeShown = null
                flush()
        } else {
            // Warteschlange voll: sauber neu aufbauen lassen
            BridgeBus.log("Moduswechsel zurückgestellt")
        }
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
    /** Kreisverkehr-Ausfahrt aus der OsmAnd-Benachrichtigung (die AIDL-Schnittstelle liefert sie nicht). */
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

    /** 0 = unbekannt. */
    fun setLimit(kmh: Int) {
        want = want.copy(speedLimitKmh = kmh)
        flush()
    }

    /** ttlMs: nach dieser Zeit ohne neues Update wird Pfeil/Distanz geleert (null = nie). */
    fun setNav(
        meters: Int?, command: NavCommand?, exit: Int? = null, ttlMs: Long? = NAV_TTL_MS,
        extrapolate: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        val prev = refMeters
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
        want = want.copy(partDistanceM = meters, command = command, roundaboutExit = exit)
        navDeadline = if (ttlMs == null || (meters == null && command == null)) 0L
        else SystemClock.elapsedRealtime() + ttlMs
        flush()
    }

    private var callClearAt = 0L
    private var msgClearAt = 0L
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
        val hideS = if (call) CALL_MAX_S else MSG_SHOW_S
        client.send(listOf(HudMessage(HudProtocol.eventField(field, 1, name, hideS))))
        val until = SystemClock.elapsedRealtime() + hideS * 1000L
        if (call) { callActive = true; callClearAt = until } else msgClearAt = until
        BridgeBus.log(if (call) "HUD: Anruf angezeigt" else "HUD: Nachricht angezeigt")
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

    /** Ziel erreicht (Ansage "reached_destination" von OsmAnd): Zielflagge für einige Sekunden. */
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
        sent = emptyMap()
        routeShown = null
        flush()
    }

    /** Sekundentakt vom Service. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val ref = refMeters
        if (ref != null && rate > 0f && want.command != null) {
            val elapsed = minOf((now - refAt) / 1000f, MAX_EXTRAPOLATE_S)
            want = want.copy(partDistanceM = maxOf(0, ref - (rate * elapsed).toInt()))
        }
        if (navDeadline != 0L && now > navDeadline) {
            BridgeBus.log("Navigationsdaten veraltet, HUD-Anzeige geleert")
            navDeadline = 0L
            refMeters = null; rate = 0f
            want = want.copy(partDistanceM = null, command = null, roundaboutExit = null)
        }
        if (callClearAt != 0L && now > callClearAt) showNotice(true, null, true)
        if (msgClearAt != 0L && now > msgClearAt) showNotice(false, null, true)
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
            s.copy(partDistanceM = null, command = null, roundaboutExit = null, lanes = emptyList())
        } else s
    }

    private fun flush(force: Boolean = false) {
        if (!client.isReady) return
        val eff = effective()
        val wantFields = HudProtocol.encodeState(eff, mode)
        if (force) sent = emptyMap()
        // Restzeit/-distanz ausblenden, solange es keine Werte gibt (sonst steht dauerhaft "0 min" da)
        val wantRoute = eff.routeMinutes != null || eff.routeDistanceM != null || eff.arrivalHour != null
        if (routeShown != wantRoute) {
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
        const val NAV_TTL_MS = 10_000L
        const val MAX_EXTRAPOLATE_S = 4f
        const val KEEPALIVE_MS = 10_000L
        const val CALL_MAX_S = 120
        const val MSG_SHOW_S = 5
        const val GOAL_SHOW_MS = 20_000L
        const val VIA_SHOW_MS = 8_000L
    }
}
