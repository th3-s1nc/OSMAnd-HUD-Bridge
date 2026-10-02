package io.github.th3s1nc.osmandhudbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.th3s1nc.osmandhudbridge.ble.HudClient
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndClient
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndTurns
import kotlin.math.roundToInt

/**
 * Foreground-Service: hält HUD-Verbindung, GPS-Tempo und OsmAnd-Anbindung am Leben,
 * auch bei ausgeschaltetem Display.
 */
class BridgeService : Service(), HudClient.Listener {

    private lateinit var handler: Handler
    private lateinit var client: HudClient
    private lateinit var controller: HudController
    private var osmand: OsmAndClient? = null
    private var locMgr: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var lastFixAt = 0L
    private var lastNavMeters = Int.MIN_VALUE
    private var lastNavTurn = Int.MIN_VALUE
    private lateinit var limitProvider: io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitProvider
    private var speedFromOsmand = false
    private var useOsmand = true
    private var lastAidlNavAt = 0L
    private var notifNavActive = false
    private var lastGpsKmh = 0f
    private var lastOsmKmh = 0
    private var idleStopEnabled = true
    private var idleSince = 0L
    private var lastOsmAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BridgeBus.init(applicationContext)
        if (intent?.action == ACTION_STOP) {
            // "Beenden" per Benachrichtigung: Auto-Verbinden bleibt aus, bis der Nutzer selbst wieder startet
            userStopped = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)) {
            // Schalter "Bridge aktiv" ist aus: nichts starten (auch nicht nach einem Neustart durch das System).
            // Nach startForegroundService ist startForeground Pflicht, sonst stürzt die App ab.
            if (intent != null) enterForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            startAll()
            if (!started) return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CLEAR -> controller.clearNavigation()
            ACTION_CONFIG -> applyConfig()
        }
        return START_STICKY
    }

    // ---------------- Start / Stop ----------------

    private fun enterForeground(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            ensureChannel()
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            BridgeBus.log("Dienst-Start abgelehnt: ${e.message}")
            BridgeBus.hud = "HUD: Dienst konnte nicht starten (Berechtigungen?)"
            false
        }
    }

    private fun startAll() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val addr = prefs.getString(KEY_ADDR, null)
        if (addr == null) {
            BridgeBus.log("Kein HUD gewählt")
            BridgeBus.hud = "HUD: kein Gerät gewählt"
            stopSelf()
            return
        }
        handler = Handler(Looper.getMainLooper())
        client = HudClient(applicationContext, this)
        controller = HudController(client)
        client.mode = io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.fromName(prefs.getString(KEY_MODE, null))
        controller.setModeSilently(client.mode)
        limitProvider = io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitProvider({ controller.setLimit(it) }, { controller.setCurrentStreet(it) })
        started = true
        running = true

        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmAndHudBridge:service")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        BridgeBus.log("Dienst gestartet, HUD ${prefs.getString(KEY_NAME, addr)}")
        io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationBus.listener = { info ->
            handler.post {
                if (!useOsmand) return@post // Schalter "OsmAnd-Navigation übernehmen" aus: gar nichts aus OsmAnd
                info.speedKmh?.let { onOsmandSpeed(it) }
                // Ersatz: liefert die OsmAnd-Schnittstelle nichts (z. B. Bridge in OsmAnd nicht freigegeben),
                // kommen Pfeil und Entfernung aus der Benachrichtigung
                val d = info.turnDistanceM
                val t = info.turn
                if (d != null && t != null && SystemClock.elapsedRealtime() - lastAidlNavAt > AIDL_SILENT_MS) {
                    if (!notifNavActive) {
                        notifNavActive = true
                        BridgeBus.log("Pfeil aus OsmAnd-Benachrichtigung (Schnittstelle liefert nichts)")
                    }
                    controller.setNav(d, t, info.roundaboutExit, extrapolate = false)
                }
                controller.setExitHint(info.roundaboutExit)
                controller.setRoute(info.routeDistanceM, info.routeMinutes, info.arrivalHour, info.arrivalMinute, info.nextStreet)
            }
        }
        io.github.th3s1nc.osmandhudbridge.nav.NoticeBus.listener = { n -> handler.post { onNotice(n) } }
        client.start(addr)
        startGps()
        applyConfig()
        handler.postDelayed(ticker, 1000)
    }

    override fun onDestroy() {
        if (started) {
            handler.removeCallbacksAndMessages(null)
            try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
            io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationBus.listener = null
            io.github.th3s1nc.osmandhudbridge.nav.NoticeBus.listener = null
            limitProvider.shutdown()
            osmand?.stop()
            osmand = null
            try { locMgr?.removeUpdates(locListener) } catch (_: Exception) {}
            client.stop()
            try { wakeLock?.release() } catch (_: Exception) {}
            started = false
        }
        running = false
        BridgeBus.hud = "HUD: Dienst beendet"
        BridgeBus.gps = "GPS: –"
        BridgeBus.osm = "OsmAnd: –"
        BridgeBus.log("Dienst beendet")
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (checkIdle()) return
            controller.tick()
            if (speedFromOsmand && !osmSpeedFresh()) controller.setSpeed(lastGpsKmh)
            if (locMgr != null && lastFixAt != 0L &&
                SystemClock.elapsedRealtime() - lastFixAt > 5_000
            ) {
                BridgeBus.gps = "GPS: kein aktueller Fix"
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Beendet den Dienst, wenn das HUD lange nicht verbunden war. Gibt true zurück, wenn beendet wurde. */
    private fun checkIdle(): Boolean {
        if (client.isReady) { idleSince = 0L; return false }
        val now = SystemClock.elapsedRealtime()
        if (idleSince == 0L) { idleSince = now; return false }
        if (!idleStopEnabled || now - idleSince < IDLE_STOP_MS) return false
        BridgeBus.log("HUD seit ${IDLE_STOP_MS / 60_000} Minuten nicht verbunden -> Dienst beendet (in den Einstellungen abschaltbar)")
        stopSelf()
        return true
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> client.onBluetoothChanged(true)
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    client.onBluetoothChanged(false)
            }
        }
    }

    // ---------------- HudClient.Listener ----------------

    override fun onStateChanged(state: HudClient.State, detail: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    override fun onReady() {
        controller.onReady()
    }

    override fun onLinkLost() {
        BridgeBus.log("Verbindung zum HUD verloren, versuche automatisch neu zu verbinden")
    }

    // ---------------- GPS ----------------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            limitProvider.onLocation(location)
            if (location.hasBearing() && location.hasSpeed() && location.speed > 1.5f) {
                controller.setHeading(location.bearing.roundToInt())
            }
            if (!location.hasSpeed()) return
            val kmh = location.speed * 3.6f
            lastGpsKmh = if (kmh < 2f) 0f else kmh
            if (!speedFromOsmand || !osmSpeedFresh()) {
                controller.setSpeed(lastGpsKmh)
                BridgeBus.gps = "Tempo: ${kmh.roundToInt()} km/h (GPS)"
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            BridgeBus.gps = "GPS: Ortung ist ausgeschaltet"
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            BridgeBus.gps = "GPS: keine Berechtigung (Tempo-Anzeige aus)"
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper()
            )
            locMgr = lm
            BridgeBus.gps = "GPS: warte auf Fix"
        } catch (e: Exception) {
            BridgeBus.gps = "GPS-Fehler: ${e.message}"
        }
    }

    // ---------------- OsmAnd ----------------

    private fun onNotice(n: io.github.th3s1nc.osmandhudbridge.nav.Notice) {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val call = n.kind == io.github.th3s1nc.osmandhudbridge.nav.NoticeKind.CALL
        val enabled = sp.getBoolean(if (call) KEY_NOTICE_CALL else KEY_NOTICE_MSG, true)
        if (!enabled && !n.clear) { BridgeBus.log("Meldung ignoriert: in den Einstellungen ausgeschaltet"); return }
        val name = if (sp.getBoolean(KEY_NOTICE_NAME, true)) n.name else null
        controller.showNotice(call, name, n.clear)
    }

    private fun osmSpeedFresh() = SystemClock.elapsedRealtime() - lastOsmAt < 5_000

    private fun onOsmandSpeed(kmh: Int) {
        lastOsmKmh = kmh
        lastOsmAt = SystemClock.elapsedRealtime()
        if (speedFromOsmand) {
            controller.setSpeed(kmh.toFloat())
            BridgeBus.gps = "Tempo: $kmh km/h (OsmAnd)"
        }
    }

    private fun applyConfig() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        speedFromOsmand = sp.getString(KEY_SPEED_SRC, "gps") == "osmand"
        limitProvider.enabled = sp.getBoolean(KEY_OSM_LIMIT, true)
        idleStopEnabled = sp.getBoolean(KEY_IDLE_STOP, true)
        controller.setMode(io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.fromName(sp.getString(KEY_MODE, null)))
        val thr = sp.getString(KEY_THRESHOLD, null)
        controller.threshold = io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode.values()
            .firstOrNull { it.name == thr } ?: io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode.ONE_KM
        val use = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_OSMAND, true)
        useOsmand = use
        if (use && osmand == null) {
            osmand = OsmAndClient(
                this, handler,
                onNav = { meters, turn ->
                    lastAidlNavAt = SystemClock.elapsedRealtime()
                    notifNavActive = false
                    if (meters != lastNavMeters || turn != lastNavTurn) {
                        lastNavMeters = meters; lastNavTurn = turn
                        BridgeBus.log("OsmAnd: $meters m, Typ $turn -> ${OsmAndTurns.toHudCommand(turn) ?: "kein Pfeil"}")
                    }
                    controller.setNav(meters.coerceAtLeast(0), OsmAndTurns.toHudCommand(turn), extrapolate = true)
                },
                onStatus = {
                    BridgeBus.osm = it
                    BridgeBus.log(it)
                },
                onArrived = {
                    BridgeBus.log("Ziel erreicht -> Zielflagge")
                    controller.showGoal()
                },
                onVia = {
                    BridgeBus.log("Zwischenziel erreicht -> VIA-Symbol")
                    controller.showVia()
                }
            ).also { it.start() }
        } else if (!use && osmand != null) {
            osmand?.stop()
            osmand = null
            controller.clearNavigation()
            BridgeBus.osm = "OsmAnd: aus"
        } else if (!use) {
            BridgeBus.osm = "OsmAnd: aus"
        }
    }

    // ---------------- Benachrichtigung ----------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HUD-Verbindung", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OsmAnd HUD Bridge")
            .setContentText(BridgeBus.hud)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Beenden", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.th3s1nc.osmandhudbridge.START"
        const val ACTION_STOP = "io.github.th3s1nc.osmandhudbridge.STOP"
        const val ACTION_CLEAR = "io.github.th3s1nc.osmandhudbridge.CLEAR"
        const val ACTION_CONFIG = "io.github.th3s1nc.osmandhudbridge.CONFIG"
        const val PREFS = "bridge"
        const val KEY_ADDR = "hud_address"
        const val KEY_NAME = "hud_name"
        const val KEY_OSMAND = "use_osmand"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_SPEED_SRC = "speed_source" // "gps" oder "osmand"
        const val KEY_OSM_LIMIT = "osm_limit"
        const val KEY_MODE = "display_mode"
        const val KEY_NOTICE_CALL = "notice_call"
        const val KEY_NOTICE_MSG = "notice_msg"
        const val KEY_NOTICE_NAME = "notice_name"
        const val KEY_AUTO = "auto_connect"
        const val KEY_ENABLED = "bridge_enabled"
        const val KEY_IDLE_STOP = "idle_stop"
        private const val IDLE_STOP_MS = 10 * 60 * 1000L
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val AIDL_SILENT_MS = 6_000L

        @Volatile
        var running = false

        /** Nutzer hat den Dienst beendet: Auto-Verbinden pausiert, bis er selbst wieder startet (gilt bis Prozessende). */
        @Volatile
        var userStopped = false

        /** Startet den Dienst bzw. schickt ihm einen Befehl. Nur aus dem Vordergrund aufrufen. */
        fun send(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, BridgeService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
