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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.th3s1nc.osmandhudbridge.track.SensorTracker
import io.github.th3s1nc.osmandhudbridge.track.TrackPoint
import io.github.th3s1nc.osmandhudbridge.track.TrackRecorder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.th3s1nc.osmandhudbridge.ble.HudClient
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndClient
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndTurns
import kotlin.math.roundToInt

/**
 * Foreground-Service: hält HUD-Verbindung, GPS-Tempo und OSMAnd-Anbindung am Leben,
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
    private var osmRetryCount = 0
    private var osmRetrySince = 0L
    private var lastFixAt = 0L
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastNavMeters = Int.MIN_VALUE
    private var lastNavTurn = Int.MIN_VALUE
    private lateinit var limitProvider: io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitProvider
    private var speedFromOsmand = false
    private var useOsmand = true
    private var beeper: SpeedBeeper? = null
    private var lastAidlNavAt = 0L
    private var notifNavActive = false
    private var lastGpsKmh = 0f
    private val trip = io.github.th3s1nc.osmandhudbridge.protocol.TripTracker()
    private var lastOsmKmh = 0
    private var idleSince = 0L
    private var lastOsmAt = 0L
    private var hudActive = false
    private var stopping = false
    private val gpsPolicy = io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy()
    private var gpsMode: io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode? = null
    private var lastLocTime = 0L
    private var notifRecording = false
    private val sensors by lazy { SensorTracker(this) }
    private var music: io.github.th3s1nc.osmandhudbridge.nav.MusicWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BridgeBus.init(applicationContext)
        if (intent?.action == ACTION_STOP) {
            // "Beenden" per Benachrichtigung: Auto-Verbinden bleibt aus, bis der Nutzer selbst wieder startet
            userStopped = true
            stopSelf()
            return START_NOT_STICKY
        }
        val spStart = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!spStart.getBoolean(KEY_ENABLED, true) || seasonState(spStart) != io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON) {
            // Schalter "App aktiv" ist aus oder es ist nicht Saison: nichts starten (auch nicht nach einem Neustart durch das System).
            // Nach startForegroundService ist startForeground Pflicht, sonst stürzt die App ab.
            if (intent != null) enterForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        var firstStart = false
        if (!started) {
            startAll()
            if (!started) return START_NOT_STICKY
            firstStart = true
        }
        when (intent?.action) {
            ACTION_CLEAR -> controller.clearNavigation()
            ACTION_ELEMENT_TEST -> {
                val code = intent.getIntExtra(EXTRA_CODE, TEST_SHOW_MODE)
                when {
                    code == TEST_END -> controller.endElementTest()
                    code == TEST_SHOW_MODE && intent.getStringExtra(EXTRA_MODE) == TEST_DANGER ->
                        if (!controller.testShowScreen(TEST_DANGER_SCREEN)) BridgeBus.log("Element-Test: HUD nicht bereit")
                    code == TEST_SHOW_MODE -> {
                        val m = io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.fromName(intent.getStringExtra(EXTRA_MODE))
                        if (!controller.testShowMode(m)) BridgeBus.log("Element-Test: HUD nicht bereit")
                    }
                    else -> if (!controller.testBlink(code)) BridgeBus.log("Element-Test: HUD nicht bereit")
                }
            }
            ACTION_RELOAD_TILES -> limitProvider.reloadTiles()
            ACTION_CONFIG, ACTION_START -> if (!firstStart) applyConfig()
        }
        return START_STICKY
    }

    // ---------------- Start / Stop ----------------

    private fun enterForeground(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // "connectedDevice" verlangt ab Android 12 die Bluetooth-Berechtigung; ohne HUD kann sie fehlen
        val hasBt = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        var type = if (hasBt || !hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
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

    /** Ton bei Blitzern (eigener Schalter, unabhängig vom Ton bei Überschreitung). */
    @Volatile private var cameraSound = false

    private fun playBeep(hint: Boolean = false) {
        val b = beeper ?: SpeedBeeper(applicationContext, handler).also { beeper = it }
        b.beep(hint)
    }

    private fun startAll() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_JUSTAGE, false).apply() // Justage bleibt nie über einen Neustart hinweg an
        handler = Handler(Looper.getMainLooper())
        client = HudClient(applicationContext, this)
        controller = HudController(client)
        controller.onOverspeed = { playBeep() }
        client.mode = io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.fromName(prefs.getString(KEY_MODE, null))
        controller.setModeSilently(client.mode)
        limitProvider = io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitProvider(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext), { kmh, est -> controller.setLimit(kmh, est) }, { controller.setCurrentStreet(it) }, { dist, sound, lim ->
            controller.setCamera(dist, sound, lim)
            if (sound && cameraSound) playBeep()
        }, { _ -> playBeep(true) })
        started = true
        running = true

        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        readBattery(ContextCompat.registerReceiver(
            this, battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        ))
        evalBattery()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OSMAndHudBridge:service")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        BridgeBus.log("Dienst gestartet" + (if (hudWanted(prefs)) ", HUD ${prefs.getString(KEY_NAME, null) ?: "?"}" else ", ohne HUD"))
        checkNotificationAccess()
        io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationBus.listener = { info ->
            handler.post {
                if (!useOsmand) return@post // Schalter "OSMAnd-Navigation übernehmen" aus: gar nichts aus OSMAnd
                info.speedKmh?.let { onOsmandSpeed(it) }
                // Ersatz: liefert die OSMAnd-Schnittstelle nichts (z. B. Bridge in OSMAnd nicht freigegeben),
                // kommen Pfeil und Entfernung aus der Benachrichtigung
                val d = info.turnDistanceM
                val t = info.turn
                if (d != null && t != null && SystemClock.elapsedRealtime() - lastAidlNavAt > AIDL_SILENT_MS) {
                    if (!notifNavActive) {
                        notifNavActive = true
                        BridgeBus.log("Pfeil aus OSMAnd-Benachrichtigung (Schnittstelle liefert nichts)")
                    }
                    controller.setNav(d, t, info.roundaboutExit, extrapolate = false)
                }
                controller.setExitHint(info.roundaboutExit)
                controller.setRoute(info.routeDistanceM, info.routeMinutes, info.arrivalHour, info.arrivalMinute, info.nextStreet)
            }
        }
        io.github.th3s1nc.osmandhudbridge.nav.NoticeBus.listener = { n -> handler.post { onNotice(n) } }
        applyConfig() // startet auch die HUD-Verbindung, falls gewünscht
        if (stopping) return
        applyGpsMode(gpsPolicy.mode(SystemClock.elapsedRealtime(), client.isReady))
        handler.postDelayed(ticker, 1000)
    }

    override fun onDestroy() {
        if (started) {
            handler.removeCallbacksAndMessages(null)
            try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(battReceiver) } catch (_: Exception) {}
            io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationBus.listener = null
            io.github.th3s1nc.osmandhudbridge.nav.NoticeBus.listener = null
            limitProvider.shutdown()
            osmand?.stop()
            osmand = null
            music?.stop()
            music = null
            try { locMgr?.removeUpdates(locListener) } catch (_: Exception) {}
            sensors.stop()
            if (TrackRecorder.active) TrackRecorder.finish() // die Sicherung bleibt, die App bietet das Speichern an
            client.stop()
            try { wakeLock?.release() } catch (_: Exception) {}
            started = false
            BridgeBus.log("Dienst beendet")
        }
        running = false
        BridgeBus.hud = "HUD: –"
        BridgeBus.gps = "GPS: –"
        BridgeBus.osm = "OSMAnd: –"
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (checkIdle() || checkSeason()) return
            controller.tick()
            applyGpsMode(gpsPolicy.mode(SystemClock.elapsedRealtime(), client.isReady || TrackRecorder.active))
            if (notifRecording != TrackRecorder.active) {
                notifRecording = TrackRecorder.active
                if (notifRecording) {
                    val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
                    sensors.start(sp.getBoolean(KEY_REC_ALT, false), sp.getBoolean(KEY_REC_LEAN, false))
                } else sensors.stop()
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
            }
            if (speedFromOsmand && !osmSpeedFresh()) controller.setSpeed(lastGpsKmh)
            if (locMgr != null && lastFixAt != 0L && gpsMode == io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.FAST &&
                SystemClock.elapsedRealtime() - lastFixAt > 5_000
            ) {
                BridgeBus.gps = "GPS: kein aktueller Fix"
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Beendet den Dienst, wenn das HUD lange nicht verbunden war. Gibt true zurück, wenn beendet wurde. */
    private fun checkIdle(): Boolean {
        // Nur wenn ein HUD gewünscht ist und nicht gefunden wird, endet der Dienst nach einer Weile
        if (client.isReady || TrackRecorder.active || !hudActive) { idleSince = 0L; return false }
        val now = SystemClock.elapsedRealtime()
        if (idleSince == 0L) { idleSince = now; return false }
        if (now - idleSince < IDLE_STOP_MS) return false
        BridgeBus.log("HUD seit ${IDLE_STOP_MS / 60_000} Minuten nicht verbunden -> Dienst beendet (beim nächsten Öffnen der App startet er wieder)")
        stopSelf()
        return true
    }

    private var lastSeasonCheck = 0L

    /** Einmal pro Minute: Endet die Saison, während die App läuft, beenden. true = beendet. */
    private fun checkSeason(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastSeasonCheck != 0L && now - lastSeasonCheck < 60_000L) return false
        lastSeasonCheck = now
        if (seasonState(getSharedPreferences(PREFS, MODE_PRIVATE)) == io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON) return false
        BridgeBus.log("Die Saison ist zu Ende, die App ruht bis zum nächsten Saisonbeginn")
        stopping = true
        stopSelf()
        return true
    }

    // ---- Handy-Akku-Warnung ----
    private val battWarn = io.github.th3s1nc.osmandhudbridge.limit.BatteryWarn()
    private var battPercent = -1
    private var battCharging = false

    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            readBattery(intent)
            evalBattery()
        }
    }

    private fun readBattery(i: Intent?) {
        if (i == null) return
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        battPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        battCharging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun evalBattery() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val low = battWarn.update(
            sp.getBoolean(KEY_BATT_WARN, false), battPercent, battCharging,
            sp.getInt(KEY_BATT_PCT, io.github.th3s1nc.osmandhudbridge.limit.BatteryWarn.DEFAULT_PERCENT)
        )
        controller.setPhoneBatteryLow(low)
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_JUSTAGE, false).apply()
    }

    override fun onLinkLost() {
        BridgeBus.log("Verbindung zum HUD verloren, versuche automatisch neu zu verbinden")
    }

    // ---------------- GPS ----------------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > 200f) return // grobe Ortung (Mobilfunk/WLAN) taugt nicht für das Tempolimit
            if (location.time == lastLocTime) return // dieselbe Ortung kam schon (GPS und Mitlauschen)
            lastLocTime = location.time
            lastFixAt = SystemClock.elapsedRealtime()
            gpsPolicy.onFix(lastFixAt, if (location.hasSpeed()) location.speed else null)
            limitProvider.onLocation(location)
            if (gpsMode != io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.FAST) {
                BridgeBus.gps = "GPS: Sparmodus (ohne HUD)"
                return
            }
            trip.onFix(
                SystemClock.elapsedRealtime(), location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude else null,
                if (location.hasSpeed()) location.speed * 3.6f else 0f,
                if (location.hasAccuracy()) location.accuracy else null
            )
            if (TrackRecorder.active && (!location.hasAccuracy() || location.accuracy <= 50f)) {
                val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
                val mps = if (location.hasSpeed()) location.speed else 0f
                if (location.hasAltitude()) sensors.alt.onGps(location.altitude)
                TrackRecorder.onFix(
                    TrackPoint(
                        location.time, location.latitude, location.longitude,
                        if (sp.getBoolean(KEY_REC_ALT, false)) sensors.alt.altitude() else null,
                        mps * 3.6f,
                        if (sp.getBoolean(KEY_REC_LIMIT, false)) controller.recordLimit() else 0,
                        if (sp.getBoolean(KEY_REC_LEAN, false)) sensors.leanEst.lean(mps) else null
                    )
                )
            }
            controller.setTrip(trip.distanceM, trip.minutes(SystemClock.elapsedRealtime()), trip.elevationM)
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

    /** Stellt das GPS auf die gewünschte Abfrage-Häufigkeit um (nur wenn sich der Modus ändert). */
    private fun applyGpsMode(m: io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode) {
        if (m == gpsMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            BridgeBus.gps = "GPS: keine Berechtigung (Tempo-Anzeige aus)"
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            try { lm.removeUpdates(locListener) } catch (_: Exception) {}
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, m.intervalMs, 0f, locListener, Looper.getMainLooper())
            // Sparmodus: zusätzlich Ortungen anderer Apps (zum Beispiel OSMAnd) kostenlos mitnehmen
            if (m != io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.FAST && lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, locListener, Looper.getMainLooper())
            }
            locMgr = lm
            val old = gpsMode
            gpsMode = m
            BridgeBus.gps = if (m == io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.FAST) "GPS: warte auf Fix" else "GPS: Sparmodus (ohne HUD)"
            if (old != null) BridgeBus.log("GPS: " + when (m) {
                io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.FAST -> "jede Sekunde (HUD verbunden)"
                io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.MOVING -> "Sparmodus, alle 30 s (unterwegs ohne HUD)"
                io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode.IDLE -> "Sparmodus, alle 5 min (Stand ohne HUD)"
            })
        } catch (e: Exception) {
            BridgeBus.gps = "GPS-Fehler: ${e.message}"
        }
    }

    /** HUD-Verbindung nach dem Schalter "HUD verbinden" starten oder beenden; Dienst ohne Aufgabe beenden. */
    private fun applyHud() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val addr = sp.getString(KEY_ADDR, null)
        val want = hudWanted(sp) && addr != null
        if (want && !hudActive) {
            hudActive = true
            client.start(addr!!)
        } else if (!want && hudActive) {
            hudActive = false
            client.stop()
            BridgeBus.hud = "HUD: aus"
        } else if (!want && !hudActive && BridgeBus.hud.startsWith("HUD: –")) {
            BridgeBus.hud = "HUD: aus"
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
        // Ohne HUD läuft der Dienst weiter (GPS, Tempolimit, Töne); nur "App aktiv" aus lässt die App ruhen
    }

    // ---------------- OSMAnd ----------------

    private fun onNotice(n: io.github.th3s1nc.osmandhudbridge.nav.Notice) {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val call = n.kind == io.github.th3s1nc.osmandhudbridge.nav.NoticeKind.CALL
        val enabled = sp.getBoolean(if (call) KEY_NOTICE_CALL else KEY_NOTICE_MSG, true)
        if (!enabled && !n.clear) { BridgeBus.log("Meldung ignoriert: in den Einstellungen ausgeschaltet"); return }
        controller.showNotice(call, n.name, n.clear)
    }

    /**
     * Nach einem App-Update bindet Android den Benachrichtigungszugriff oft nicht neu, obwohl der Schalter "an" zeigt.
     * Darum beim Start neues Verbinden anfordern und nach 10 s prüfen; sonst eine klare Warnung ins Protokoll.
     */
    private fun checkNotificationAccess() {
        val cn = android.content.ComponentName(this, io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationListener::class.java)
        val granted = try {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) { false }
        if (granted && !io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationListener.connected) {
            try { android.service.notification.NotificationListenerService.requestRebind(cn) } catch (_: Exception) {}
        }
        // Was OSMAnd schon in der Leiste hatte, bevor die Bridge lief, jetzt einmal nachlesen
        handler.postDelayed({ io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationListener.instance?.snapshot() }, 1_000)
        handler.postDelayed({
            if (io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationListener.connected) return@postDelayed
            BridgeBus.log(
                if (granted) "Warnung: Benachrichtigungszugriff ist erlaubt, aber nicht verbunden. Tempo, Restzeit und Ankunft aus OSMAnd fehlen. " +
                    "In den Android-Einstellungen den Zugriff für diese App einmal aus- und wieder einschalten"
                else "Hinweis: Benachrichtigungszugriff ist nicht erlaubt. Tempo, Restzeit und Ankunft aus OSMAnd fehlen"
            )
        }, 10_000)
    }

    private fun osmSpeedFresh() = SystemClock.elapsedRealtime() - lastOsmAt < 5_000

    private fun onOsmandSpeed(kmh: Int) {
        lastOsmKmh = kmh
        lastOsmAt = SystemClock.elapsedRealtime()
        if (speedFromOsmand) {
            controller.setSpeed(kmh.toFloat())
            BridgeBus.gps = "Tempo: $kmh km/h (OSMAnd)"
        }
    }

    private fun applyConfig() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        applyHud()
        if (stopping) return
        speedFromOsmand = sp.getString(KEY_SPEED_SRC, "gps") == "osmand"
        controller.keepStraight = sp.getBoolean(KEY_KEEP_STRAIGHT, false)
        limitProvider.guessMissing = sp.getBoolean(KEY_GUESS_LIMIT, false)
        limitProvider.useExtraTags = sp.getBoolean(KEY_LIM_TAGS, true)
        limitProvider.useNeighbors = sp.getBoolean(KEY_LIM_NEIGHBORS, true)
        limitProvider.useSigns = sp.getBoolean(KEY_LIM_SIGNS, true)
        limitProvider.offlineOnly = sp.getBoolean(KEY_OFFLINE, false)
        limitProvider.cacheMaxMb = sp.getInt(KEY_CACHE_MB, DEFAULT_CACHE_MB).coerceAtMost(MAX_CACHE_MB)
        limitProvider.enabled = true // der frühere Hauptschalter ist entfallen
        limitProvider.importedCameras = io.github.th3s1nc.osmandhudbridge.limit.CameraStore.load(filesDir)
        limitProvider.warnRedLight = sp.getBoolean(KEY_CAM_RED, true)
        limitProvider.warnSection = sp.getBoolean(KEY_CAM_SECTION, true)
        limitProvider.warnTunnel = sp.getBoolean(KEY_CAM_TUNNEL, true)
        limitProvider.cameraLevel = if (sp.getBoolean(KEY_CAMERA, false)) sp.getInt(KEY_CAMERA_LEAD, 1).coerceIn(0, 2) else -1
        val newMode = io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.fromName(sp.getString(KEY_MODE, null))
        // Guide/Cruiser: Strecke und Zeit beginnen bei null, sobald der Modus gewählt wird
        if (newMode.usesTextSlots && controller.mode != newMode) trip.reset()
        // gewählte Zeilen von Guide und Cruiser; bei Änderung Textfelder neu senden
        var slotsChanged = false
        for (m in listOf(io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.GUIDE, io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.TRACKING)) {
            val key = if (m == io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.GUIDE) KEY_SLOTS_GUIDE else KEY_SLOTS_CRUISER
            if (io.github.th3s1nc.osmandhudbridge.protocol.SlotConfig.set(m, io.github.th3s1nc.osmandhudbridge.protocol.SlotConfig.parse(m, sp.getString(key, null)))) slotsChanged = true
        }
        controller.setMode(newMode)
        if (slotsChanged) controller.slotsChanged()
        controller.setWarn(sp.getBoolean(KEY_WARN, true), sp.getInt(KEY_WARN_TOL, DEFAULT_WARN_TOL))
        controller.acousticWarn = sp.getBoolean(KEY_WARN_SOUND, false)
        cameraSound = sp.getBoolean(KEY_CAMERA_SOUND, sp.getBoolean(KEY_WARN_SOUND, false))
        limitProvider.curveLevel = if (sp.getBoolean(KEY_CURVE, false)) sp.getInt(KEY_CURVE_LEVEL, 1).coerceIn(0, 2) else -1
        limitProvider.pointMask =
            (if (sp.getBoolean(KEY_HINT_RAIL, false)) io.github.th3s1nc.osmandhudbridge.limit.PointKind.LEVEL_CROSSING else 0) or
            (if (sp.getBoolean(KEY_HINT_ZEBRA, false)) io.github.th3s1nc.osmandhudbridge.limit.PointKind.ZEBRA else 0) or
            (if (sp.getBoolean(KEY_HINT_CALMING, false)) io.github.th3s1nc.osmandhudbridge.limit.PointKind.CALMING else 0)
        evalBattery()
        controller.setBrightness(if (sp.getBoolean(KEY_BRIGHT_AUTO, true)) -1 else sp.getInt(KEY_BRIGHT_STEP, 1).coerceIn(0, 2))
        if (!controller.setJustage(sp.getBoolean(KEY_JUSTAGE, false))) {
            sp.edit().putBoolean(KEY_JUSTAGE, false).apply() // HUD nicht verbunden: Schalter zurücksetzen
            BridgeBus.log("Justage nur bei verbundenem HUD möglich")
        }
        val thr = sp.getString(KEY_THRESHOLD, null)
        controller.threshold = io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode.values()
            .firstOrNull { it.name == thr } ?: io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode.NORMAL
        val wantMusic = sp.getBoolean(KEY_NOTICE_MUSIC, false)
        if (wantMusic && music == null) {
            music = io.github.th3s1nc.osmandhudbridge.nav.MusicWatcher(this, handler) { artist, title ->
                controller.showMusic(artist?.takeIf { it.isNotBlank() }, title)
            }.also { if (!it.start()) music = null }
        } else if (!wantMusic && music != null) {
            music?.stop()
            music = null
            controller.clearMusic()
        }
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
                        BridgeBus.log("OSMAnd: $meters m, Typ $turn -> ${OsmAndTurns.toHudCommand(turn) ?: "kein Pfeil"}")
                    }
                    controller.setNav(meters.coerceAtLeast(0), OsmAndTurns.toHudCommand(turn), extrapolate = true)
                },
                onStatus = {
                    if (!it.startsWith("OSMAnd-Ansage")) BridgeBus.osm = it // Ansagen nur ins Protokoll, nicht in die Statusanzeige
                    // Verbindungsversuche (alle 10 s): erst eine Zeile, am Ende eine Zusammenfassung mit Zahl und Dauer
                    val unreachable = it.startsWith("OSMAnd nicht erreichbar")
                    val connecting = it.startsWith("OSMAnd: verbinde")
                    if (!BridgeBus.verbose && (unreachable || (connecting && osmRetryCount > 0))) {
                        if (unreachable) {
                            if (osmRetryCount == 0) {
                                osmRetrySince = System.currentTimeMillis()
                                BridgeBus.log("OSMAnd nicht erreichbar, die App versucht es alle 10 s weiter")
                            }
                            osmRetryCount++
                        }
                    } else {
                        if (osmRetryCount > 0) {
                            val mins = (System.currentTimeMillis() - osmRetrySince) / 60_000
                            BridgeBus.log("OSMAnd: $osmRetryCount Verbindungsversuche in ${if (mins < 1) "unter 1" else mins.toString()} min")
                            osmRetryCount = 0
                        }
                        BridgeBus.log(it)
                    }
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
            BridgeBus.osm = "OSMAnd: aus"
        } else if (!use) {
            BridgeBus.osm = "OSMAnd: aus"
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
            .setContentTitle("OSMAnd HUD Bridge")
            .setContentText(
                if (TrackRecorder.active) "Aufzeichnung läuft" + (if (hudActive) " · " + BridgeBus.hud else "")
                else if (hudActive) BridgeBus.hud else "Ohne HUD: wartet auf die Verbindung, Standort im Sparmodus"
            )
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
        const val ACTION_ELEMENT_TEST = "io.github.th3s1nc.osmandhudbridge.ELEMENT_TEST"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE = "code"
        const val TEST_SHOW_MODE = -1
        const val TEST_END = -2
        const val TEST_DANGER = "DANGER" // Gefahren-Bildschirm des HUD, kein Anzeigemodus
        const val TEST_DANGER_SCREEN = 22
        const val ACTION_RELOAD_TILES = "io.github.th3s1nc.osmandhudbridge.RELOAD_TILES" // nach einem Karten-Import: geladene Kacheln neu lesen
        const val ACTION_CONFIG = "io.github.th3s1nc.osmandhudbridge.CONFIG"
        const val PREFS = "bridge"
        const val KEY_ADDR = "hud_address"
        const val KEY_NAME = "hud_name"
        const val KEY_OSMAND = "use_osmand"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_SPEED_SRC = "speed_source" // "gps" oder "osmand"
        const val KEY_OSM_LIMIT = "osm_limit"
        const val KEY_IMPORT_INFO = "import_info" // Ergebniszeile des letzten Imports der Straßendaten
        const val KEY_IMPORT_SUB = "import_sub"
        const val KEY_IMPORT_LOG = "import_log" // Zahlen je eingelesener Datei (für die Zusammenfassung)
        const val KEY_OFFLINE = "offline_data" // nur gespeicherte und importierte Straßendaten, nie laden
        const val KEY_MODE = "display_mode"
        const val KEY_SLOTS_GUIDE = "slots_guide"
        const val KEY_SLOTS_CRUISER = "slots_cruiser"
        const val KEY_NOTICE_CALL = "notice_call"
        const val KEY_NOTICE_MSG = "notice_msg"
        const val KEY_KEEP_STRAIGHT = "keep_straight" // Geradeaus-Pfeil dauerhaft
        const val KEY_BATT_WARN = "batt_warn" // Handy-Akku-Warnung am HUD (Guide, Cruiser, Explorer)
        const val KEY_BATT_PCT = "batt_warn_pct"
        const val KEY_NOTICE_MUSIC = "notice_music" // Spotify: neuer Titel am HUD
        const val KEY_ENABLED = "bridge_enabled"
        const val KEY_GUESS_LIMIT = "guess_limit"
        const val KEY_LIM_TAGS = "limit_extra_tags"
        const val KEY_LIM_NEIGHBORS = "limit_neighbors"
        const val KEY_LIM_SIGNS = "limit_signs"
        const val KEY_CACHE_MB = "cache_max_mb" // Obergrenze des Kartenspeichers
        const val DEFAULT_CACHE_MB = 1024
        const val MAX_CACHE_MB = 2048
        const val KEY_VERBOSE = "log_verbose"
        const val KEY_SEASON_ON = "season_on" // nur in der Saison aktiv
        const val KEY_SEASON_FROM = "season_from" // Monat 1..12
        const val KEY_SEASON_TO = "season_to"
        const val KEY_HUD_ON = "hud_on" // Schalter "HUD verbinden" (nur die Bluetooth-Verbindung)
        const val KEY_WARN_SOUND = "warn_sound" // Warnton bei Überschreitung
        const val KEY_CURVE = "hint_curve" // Ton vor sehr scharfen Kurven
        const val KEY_CURVE_LEVEL = "hint_curve_level" // 0 = wenig, 1 = normal, 2 = viel
        const val KEY_HINT_RAIL = "hint_rail" // Ton vor Bahnübergängen (nur mit importierten Straßendaten)
        const val KEY_HINT_ZEBRA = "hint_zebra" // Ton vor Zebrastreifen
        const val KEY_HINT_CALMING = "hint_calming" // Ton vor Verkehrsberuhigung
        const val KEY_CAMERA_SOUND = "camera_sound" // Ton bei Blitzern (ohne eigenen Wert: wie KEY_WARN_SOUND)
        const val KEY_CAMERA = "camera_warn" // Blitzer-Warnung (aus OSM-Daten), standardmäßig aus
        const val KEY_CAMERA_LEAD = "camera_lead" // 0 Kurz, 1 Normal, 2 Lang
        const val KEY_CAM_RED = "cam_red" // eigene Liste: Ampelblitzer warnen
        const val KEY_CAM_SECTION = "cam_section" // Abschnittskontrolle warnen
        const val KEY_CAM_TUNNEL = "cam_tunnel" // Tunnel warnen
        const val KEY_CAM_LIST_COUNT = "cam_list_count" // Zahl importierter Blitzer (nur für die Anzeige)
        const val KEY_CAM_LIST_FILES = "cam_list_files" // Zahl der importierten Dateien (nur für die Anzeige)
        const val KEY_WARN = "warn_enabled"
        const val KEY_WARN_TOL = "warn_tolerance" // km/h, 0..30
        const val DEFAULT_WARN_TOL = 10
        const val KEY_BRIGHT_AUTO = "brightness_auto"
        const val KEY_BRIGHT_STEP = "brightness_step" // 0 dunkel, 1 mittel, 2 hell
        const val KEY_JUSTAGE = "justage"
        const val KEY_REC_ALT = "rec_alt" // Aufzeichnung: Höhe (Barometer)
        const val KEY_REC_LIMIT = "rec_limit" // Aufzeichnung: Tempolimit und Überschreitungen
        const val KEY_REC_LEAN = "rec_lean" // Aufzeichnung: Schräglage
        private const val IDLE_STOP_MS = 10 * 60 * 1000L
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val AIDL_SILENT_MS = 6_000L

        @Volatile
        var running = false

        fun seasonPlan(sp: SharedPreferences) = io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan(
            sp.getInt(KEY_SEASON_FROM, 3), sp.getInt(KEY_SEASON_TO, 10)
        )

        /** Saison-Zustand heute. Ist der Saison-Schalter aus, ist immer Saison. */
        fun seasonState(sp: SharedPreferences, today: java.time.LocalDate = java.time.LocalDate.now()): io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State =
            if (!sp.getBoolean(KEY_SEASON_ON, false)) io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON
            else seasonPlan(sp).state(today)

        /** Schalter "HUD verbinden" (an, sobald ein HUD gewählt wurde; ohne gewähltes Gerät immer aus). */
        fun hudWanted(sp: SharedPreferences): Boolean =
            sp.getString(KEY_ADDR, null) != null && sp.getBoolean(KEY_HUD_ON, true)

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
