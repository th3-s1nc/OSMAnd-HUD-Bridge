package io.github.th3s1nc.osmandhudbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.card.MaterialCardView
import io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode
import io.github.th3s1nc.osmandhudbridge.protocol.HudValue
import io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode
import io.github.th3s1nc.osmandhudbridge.track.Ride
import io.github.th3s1nc.osmandhudbridge.track.RideFormat
import io.github.th3s1nc.osmandhudbridge.track.RideStore
import io.github.th3s1nc.osmandhudbridge.track.SeasonSummary
import io.github.th3s1nc.osmandhudbridge.track.TrackRecorder
import io.github.th3s1nc.osmandhudbridge.track.TrackSession
import io.github.th3s1nc.osmandhudbridge.limit.Tx
import io.github.th3s1nc.osmandhudbridge.limit.StatusText

class MainActivity : AppCompatActivity() {
    private lateinit var tvHud: TextView
    private lateinit var dotHud: TextView
    private lateinit var tvServiceHint: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvLimit: TextView
    private lateinit var tvOsm: TextView
    private lateinit var tvLog: TextView
    private lateinit var pages: List<View>

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pickDeviceAndStart()
        }

    private val prefs by lazy { getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredPage = savedInstanceState?.getInt("page", -1)?.takeIf { it >= 0 }
        BridgeBus.init(applicationContext)
        setContentView(R.layout.activity_main)

        tvHud = findViewById(R.id.tvHud)
        dotHud = findViewById(R.id.dotHud)
        findViewById<View>(R.id.btnOpenOsmand).setOnClickListener { openOsmand() }
        findViewById<View>(R.id.tvNotifHint).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        tvServiceHint = findViewById(R.id.tvServiceHint)
        tvGps = findViewById(R.id.tvGps)
        tvLimit = findViewById(R.id.tvLimit)
        tvOsm = findViewById(R.id.tvOsm)
        tvLog = findViewById(R.id.tvLog)

        setupNavigation()
        setupHome()
        setupDisplay()
        setupInfoButtons()
        setupLanguage()
        setupSettings()
        setupTools()
        setupInfo()
        setupTracking()
    }

    override fun onStart() {
        super.onStart()
        BridgeBus.onChange = { refresh() }
        ImportBus.onChange = { refreshMap() }
        DownloadBus.onChange = { refreshDownload() }
        DownloadService.restore(this)
        refreshMap()
        autoConnect()
        refresh()
        trackHandler.post(trackTick)
        refreshRides()
        offerUnfinishedRide()
    }

    override fun onStop() {
        BridgeBus.onChange = null
        ImportBus.onChange = null
        DownloadBus.onChange = null
        trackHandler.removeCallbacks(trackTick)
        super.onStop()
    }

    // ---------------- Aufbau ----------------

    private var currentPage = 0

    /** Seiten: 0 Übersicht, 1 Anzeige, 2 Tempolimit, 3 Werkzeuge, 4 Info (Knopf oben rechts), 5 Tracking. */
    private fun showPage(idx: Int) {
        currentPage = idx
        pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
        infoBackCallback.isEnabled = idx == 4
    }

    private fun pageForNav(itemId: Int): Int = when (itemId) {
        R.id.nav_display -> 1
        R.id.nav_settings -> 2
        R.id.nav_tools -> 3
        R.id.nav_tracking -> 5
        else -> 0
    }

    private val infoBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showPage(pageForNav(findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId))
        }
    }

    private fun setupNavigation() {
        pages = listOf(
            findViewById(R.id.pageHome), findViewById(R.id.pageDisplay),
            findViewById(R.id.pageSettings), findViewById(R.id.pageTools), findViewById(R.id.pageInfo),
            findViewById(R.id.pageTracking)
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            showPage(pageForNav(item.itemId))
            true
        }
        // Erneutes Antippen des gewählten Tabs holt ihn aus der Info-Seite zurück
        nav.setOnItemReselectedListener { item -> showPage(pageForNav(item.itemId)) }
        findViewById<View>(R.id.btnHeaderInfo).setOnClickListener { showPage(4) }
        onBackPressedDispatcher.addCallback(this, infoBackCallback)
        // Nach dem Umschalten der Sprache (Activity wird neu gebaut) auf der gleichen Seite bleiben
        restoredPage?.let { p ->
            val navId = when (p) { 1 -> R.id.nav_display; 2 -> R.id.nav_settings; 3 -> R.id.nav_tools; 5 -> R.id.nav_tracking; else -> R.id.nav_home }
            if (p != 4) nav.selectedItemId = navId
            showPage(p)
        }
    }

    private var restoredPage: Int? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
    }

    /** Kleines graues i: klappt die Beschreibung zum Schalter auf und zu. */
    /** i-Knopf: zeigt die Erklärung in einem Fenster. Titel ist der Name der Zeile, der Text kommt aus der (versteckten) Textzeile. */
    private fun setupInfoToggle(buttonId: Int, vararg textIds: Int) {
        for (id in textIds) findViewById<View>(id).visibility = View.GONE
        val btn = findViewById<View>(buttonId)
        btn.setOnClickListener {
            val msg = textIds.map { (findViewById<View>(it) as? TextView)?.text?.toString().orEmpty() }
                .filter { it.isNotBlank() }.joinToString("\n\n")
            var title: CharSequence? = null
            val row = btn.parent as? android.view.ViewGroup
            if (row != null) {
                for (i in 0 until row.childCount) {
                    val c = row.getChildAt(i)
                    if (c is TextView && c.text.isNotBlank()) { title = c.text; break }
                }
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private var camScanAt = 0L
    private var camScanBusy = false

    /** Zählt höchstens einmal pro Minute im Hintergrund die Blitzer in allen gespeicherten Kacheln. */
    private fun scanCamerasIfDue() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (camScanBusy || (camScanAt != 0L && now - camScanAt < 60_000L)) return
        camScanBusy = true
        val dir = io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext)
        Thread {
            val text = try {
                val c = io.github.th3s1nc.osmandhudbridge.limit.TileCache(dir).countCameras()
                if (c.tilesWithData == 0) Tx.t("keine Kacheln mit Blitzerdaten", "no tiles with camera data") +
                    (if (c.legacyOnly > 0) Tx.t(" (${c.legacyOnly} ältere Kacheln ohne Blitzerdaten)", " (${c.legacyOnly} older tiles without camera data)") else "")
                else Tx.t("${c.cameras} Blitzer in ${c.tilesWithCameras} von ${c.tilesWithData} Kacheln mit Blitzerdaten", "${c.cameras} cameras in ${c.tilesWithCameras} of ${c.tilesWithData} tiles with camera data") +
                    (if (c.legacyOnly > 0) Tx.t(" (dazu ${c.legacyOnly} ältere Kacheln ohne Blitzerdaten)", " (plus ${c.legacyOnly} older tiles without camera data)") else "")
            } catch (e: Exception) { Tx.t("Zählung fehlgeschlagen", "Count failed") }
            BridgeBus.camerasAll = text
            camScanAt = android.os.SystemClock.elapsedRealtime()
            camScanBusy = false
        }.start()
    }

    /** Sprachwahl (Werkzeuge): Automatisch, Deutsch oder English. */
    private fun setupLanguage() {
        val rg = findViewById<android.widget.RadioGroup>(R.id.rgLang)
        rg.check(when (AppLang.get(this)) { "de" -> R.id.rbLangDe; "en" -> R.id.rbLangEn; else -> R.id.rbLangAuto })
        rg.setOnCheckedChangeListener { _, id ->
            val code = when (id) { R.id.rbLangDe -> "de"; R.id.rbLangEn -> "en"; else -> AppLang.AUTO }
            if (code != AppLang.get(this)) AppLang.set(this, code) // baut das Fenster neu auf
        }
    }

    /** i-Knöpfe auf den Seiten Tempolimit, Werkzeuge und Tracking. */
    private fun setupInfoButtons() {
        setupInfoToggle(R.id.btnInfoWarn, R.id.tvInfoWarn)
        setupInfoToggle(R.id.btnInfoSound, R.id.tvInfoSound)
        setupInfoToggle(R.id.btnInfoCamera, R.id.tvInfoCamera)
        // Karte Blitzer: zugeklappt nur mit Überschrift, Zustand wird gemerkt
        run {
            val box = findViewById<View>(R.id.boxBlitzer)
            val arrow = findViewById<TextView>(R.id.tvBlitzerArrow)
            fun show(open: Boolean) {
                box.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "▲" else "▼"
            }
            show(prefs.getBoolean(BridgeService.KEY_CAM_OPEN, false))
            findViewById<View>(R.id.hdrBlitzer).setOnClickListener {
                val open = box.visibility != View.VISIBLE
                show(open)
                prefs.edit().putBoolean(BridgeService.KEY_CAM_OPEN, open).apply()
            }
        }
        setupInfoToggle(R.id.btnInfoOffline, R.id.tvInfoOffline)
        setupInfoToggle(R.id.btnInfoCamRed, R.id.tvInfoCamRed)
        setupInfoToggle(R.id.btnInfoCamSection, R.id.tvInfoCamSection)
        setupInfoToggle(R.id.btnInfoCamTunnel, R.id.tvInfoCamTunnel)
        setupInfoToggle(R.id.btnInfoCamSound, R.id.tvInfoCamSound)
        setupInfoToggle(R.id.btnInfoHintRail, R.id.tvInfoHintRail)
        setupInfoToggle(R.id.btnInfoHintZebra, R.id.tvInfoHintZebra)
        setupInfoToggle(R.id.btnInfoHintCalming, R.id.tvInfoHintCalming)
        setupInfoToggle(R.id.btnInfoCurve, R.id.tvInfoCurve)
        setupInfoToggle(R.id.btnInfoTags, R.id.tvInfoTags)
        setupInfoToggle(R.id.btnInfoGuess, R.id.tvInfoGuess)
        setupInfoToggle(R.id.btnInfoSigns, R.id.tvInfoSigns)
        setupInfoToggle(R.id.btnInfoNeighbors, R.id.tvInfoNeighbors)
        setupInfoToggle(R.id.btnInfoNotifAccess, R.id.tvInfoNotifAccess)
        setupInfoToggle(R.id.btnInfoBattery, R.id.tvInfoBattery)
        setupInfoToggle(R.id.btnInfoVerbose, R.id.tvInfoVerbose)
        setupInfoToggle(R.id.btnInfoRecWhat, R.id.tvInfoRecWhat2)
    }

    private fun setupHome() {
        setupInfoToggle(R.id.btnInfoService, R.id.tvInfoService)
        setupInfoToggle(R.id.btnInfoMaster, R.id.tvInfoMaster)
        setupInfoToggle(R.id.btnInfoFreeRide, R.id.tvInfoFreeRide)
        // Freies Fahren = OSMAnd getrennt (gespeichert wie bisher als KEY_OSMAND, umgekehrt)
        val swFree = findViewById<MaterialSwitch>(R.id.swFreeRide)
        swFree.isChecked = !prefs.getBoolean(BridgeService.KEY_OSMAND, true)
        swFree.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_OSMAND, !checked).apply()
            applyConfigIfRunning()
        }
        val swMaster = findViewById<MaterialSwitch>(R.id.swMaster)
        swMaster.isChecked = prefs.getBoolean(BridgeService.KEY_ENABLED, true)
        // Freies Fahren macht nur bei eingeschalteter App Sinn: sonst ausgegraut
        swFree.isEnabled = swMaster.isChecked
        swMaster.setOnCheckedChangeListener { _, checked ->
            swFree.isEnabled = checked
            prefs.edit().putBoolean(BridgeService.KEY_ENABLED, checked).apply()
            if (checked) {
                BridgeService.userStopped = false
                autoConnect()
            } else {
                // Aus: Dienst beenden, Bluetooth, GPS und Netz werden nicht mehr genutzt, das HUD bleibt frei
                BridgeService.userStopped = true
                stopService(Intent(this, BridgeService::class.java))
            }
            refresh()
        }
        val swService = findViewById<MaterialSwitch>(R.id.swService)
        swService.setOnCheckedChangeListener { _, on ->
            if (serviceSyncing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(BridgeService.KEY_HUD_ON, on).apply()
            if (on) {
                BridgeService.userStopped = false
                onStartClicked()
            } else {
                // Nur die Bluetooth-Verbindung endet; Laden der Straßendaten läuft im Dienst weiter (er beendet sich selbst, wenn nichts zu tun ist)
                applyConfigIfRunning()
            }
            swService.postDelayed({ refresh() }, 1500)
        }
    }

    private class ModeCard(val mode: DisplayMode, val card: Int, val check: Int)

    private val modeCards by lazy {
        listOf(
            ModeCard(DisplayMode.NAVIGATOR, R.id.cardNavigator, R.id.cardNavigatorCheck),
            ModeCard(DisplayMode.MINIMALIST, R.id.cardMinimalist, R.id.cardMinimalistCheck),
            ModeCard(DisplayMode.EXPLORER, R.id.cardExplorer, R.id.cardExplorerCheck),
            ModeCard(DisplayMode.CITY, R.id.cardCity, R.id.cardCityCheck),
            ModeCard(DisplayMode.GUIDE, R.id.cardGuide, R.id.cardGuideCheck),
            ModeCard(DisplayMode.TRACKING, R.id.cardTracking, R.id.cardTrackingCheck)
        )
    }

    /** Vorschauen von Guide und Cruiser zeigen die gewählten Zeilen (leere Zeilen bleiben leer). */
    private fun updateSlotPreviews() {
        fun fill(mode: DisplayMode, vals: List<Int>, labs: List<Int>) {
            val list = FieldPickerDialog.current(prefs, mode)
            for (i in vals.indices) {
                val v = list.getOrNull(i) ?: HudValue.EMPTY
                findViewById<TextView>(vals[i]).text = v.sample
                findViewById<TextView>(labs[i]).text = v.label
            }
        }
        fill(DisplayMode.GUIDE, listOf(R.id.pgVal1, R.id.pgVal2), listOf(R.id.pgLab1, R.id.pgLab2))
        fill(DisplayMode.TRACKING, listOf(R.id.pcVal1, R.id.pcVal2, R.id.pcVal3, R.id.pcVal4), listOf(R.id.pcLab1, R.id.pcLab2, R.id.pcLab3, R.id.pcLab4))
    }

    private fun setupDisplay() {
        updateSlotPreviews()
        setupInfoToggle(R.id.btnInfoKeep, R.id.tvInfoKeep)
        setupInfoToggle(R.id.btnInfoMusic, R.id.tvInfoMusic)
        setupInfoToggle(R.id.btnInfoBatt, R.id.tvInfoBatt)
        setupInfoToggle(R.id.btnInfoNoticeMsg, R.id.tvInfoNoticeMsg)
        setupInfoToggle(R.id.btnInfoNoticeCall, R.id.tvInfoNoticeCall)
        setupInfoToggle(R.id.btnInfoBright, R.id.tvInfoBright)
        setupInfoToggle(R.id.btnInfoJust, R.id.tvInfoJust)
        for ((gear, mode) in listOf(R.id.btnGuideGear to DisplayMode.GUIDE, R.id.btnCruiserGear to DisplayMode.TRACKING)) {
            findViewById<View>(gear).setOnClickListener {
                FieldPickerDialog.show(this, prefs, mode) {
                    updateSlotPreviews()
                    applyConfigIfRunning()
                }
            }
        }
        fun highlight(selected: DisplayMode) {
            for (mc in modeCards) {
                val card = findViewById<MaterialCardView>(mc.card)
                val on = mc.mode == selected
                card.strokeColor = ContextCompat.getColor(this, R.color.accent)
                card.strokeWidth = if (on) (2 * resources.displayMetrics.density).toInt() else 0
                findViewById<TextView>(mc.check).text = if (on) "AKTIV" else ""
            }
        }
        highlight(DisplayMode.fromName(prefs.getString(BridgeService.KEY_MODE, null)))
        for (mc in modeCards) {
            findViewById<MaterialCardView>(mc.card).setOnClickListener {
                prefs.edit().putString(BridgeService.KEY_MODE, mc.mode.name).apply()
                highlight(mc.mode)
                applyConfigIfRunning()
            }
        }

        // Pfeil anzeigen ab (Regler: Kurz, Normal, Lang, Immer)
        val steps = listOf(ThresholdMode.SHORT, ThresholdMode.NORMAL, ThresholdMode.LONG, ThresholdMode.ALWAYS)
        val names = listOf(Tx.t("Kurz", "Short"), "Normal", Tx.t("Lang", "Long"), Tx.t("Immer", "Always"))
        val sldThr = findViewById<Slider>(R.id.sldThr)
        val tvThrName = findViewById<TextView>(R.id.tvThrName)
        val boxThr = findViewById<View>(R.id.boxThrRows)
        val tvAlways = findViewById<TextView>(R.id.tvThrAlways)
        val thrRows = listOf(R.id.tvThr1, R.id.tvThr2, R.id.tvThr3).map { findViewById<TextView>(it) }
        fun thrUi(i: Int) {
            val m = steps[i.coerceIn(0, 3)]
            tvThrName.text = names[i.coerceIn(0, 3)]
            val t = m.meters
            boxThr.visibility = if (t == null) View.GONE else View.VISIBLE
            tvAlways.visibility = if (t == null) View.VISIBLE else View.GONE
            if (t != null) for (k in 0..2) thrRows[k].text = "${t[k]} m"
        }
        val saved = ThresholdMode.values().firstOrNull { it.name == prefs.getString(BridgeService.KEY_THRESHOLD, null) }
        val startIdx = saved?.let { steps.indexOf(it) }?.takeIf { it >= 0 } ?: 1 // früher gespeichertes "Ab 1 km" -> Normal
        sldThr.value = startIdx.toFloat()
        thrUi(startIdx)
        sldThr.addOnChangeListener { _, value, fromUser ->
            val i = value.toInt()
            thrUi(i)
            if (fromUser) {
                prefs.edit().putString(BridgeService.KEY_THRESHOLD, steps[i].name).apply()
                applyConfigIfRunning()
            }
        }

        // Tempowarnung
        val swWarn = findViewById<MaterialSwitch>(R.id.swWarn)
        val sldWarn = findViewById<Slider>(R.id.sldWarnTol)
        val txtWarn = findViewById<TextView>(R.id.txtWarnTol)
        fun warnText(v: Int) { txtWarn.text = Tx.t("Überschreitung: $v km/h", "Over the limit: $v km/h") }
        val tol = prefs.getInt(BridgeService.KEY_WARN_TOL, BridgeService.DEFAULT_WARN_TOL).coerceIn(0, 30)
        sldWarn.value = tol.toFloat()
        warnText(tol)
        swWarn.isChecked = prefs.getBoolean(BridgeService.KEY_WARN, true)
        sldWarn.isEnabled = swWarn.isChecked
        val swWarnSound = findViewById<MaterialSwitch>(R.id.chkWarnSound)
        val swCamera = findViewById<MaterialSwitch>(R.id.swCamera)
        val sldCam = findViewById<Slider>(R.id.sldCamLead)
        val txtCam = findViewById<TextView>(R.id.txtCamLead)
        val txtCamHint = findViewById<TextView>(R.id.txtCamHint)
        fun camText(level: Int) {
            val name = when (level) { 0 -> Tx.t("Kurz (ca. 6 s)", "Short (approx. 6 s)"); 2 -> Tx.t("Lang (ca. 15 s)", "Long (approx. 15 s)"); else -> Tx.t("Normal (ca. 10 s)", "Normal (approx. 10 s)") }
            txtCam.text = Tx.t("Warnabstand: ", "Warning distance: ") + name
            txtCamHint.text = io.github.th3s1nc.osmandhudbridge.limit.CameraWarn.leadHint(level)
        }
        val swCamSound = findViewById<MaterialSwitch>(R.id.swCamSound)
        fun soundEnabledUi() {
            swWarnSound.isEnabled = swWarn.isChecked
            swCamSound.isEnabled = swCamera.isChecked
        }
        fun cameraUi() {
            sldCam.isEnabled = swCamera.isChecked
            val a = if (swCamera.isChecked) 1f else 0.5f
            txtCam.alpha = a
            txtCamHint.alpha = a
        }
        swWarnSound.isChecked = prefs.getBoolean(BridgeService.KEY_WARN_SOUND, false)
        swCamera.isChecked = prefs.getBoolean(BridgeService.KEY_CAMERA, false)
        // Ton bei Blitzern ist neu: ohne eigenen Wert gilt der bisherige Ton-Schalter (der galt für beides)
        swCamSound.isChecked = prefs.getBoolean(BridgeService.KEY_CAMERA_SOUND, prefs.getBoolean(BridgeService.KEY_WARN_SOUND, false))
        val camLevel = prefs.getInt(BridgeService.KEY_CAMERA_LEAD, 1).coerceIn(0, 2)
        sldCam.value = camLevel.toFloat()
        camText(camLevel)
        cameraUi()
        soundEnabledUi()
        swWarnSound.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_WARN_SOUND, on).apply()
            applyConfigIfRunning()
        }
        for ((id, key) in listOf(R.id.swHintRail to BridgeService.KEY_HINT_RAIL, R.id.swHintZebra to BridgeService.KEY_HINT_ZEBRA, R.id.swHintCalming to BridgeService.KEY_HINT_CALMING)) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, false)
            sw.setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                applyConfigIfRunning()
                if (on && io.github.th3s1nc.osmandhudbridge.limit.PbfImport.countImported(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext)) == 0) {
                    Toast.makeText(this, Tx.t("Hinweis: Ohne importierte Straßendaten gibt es keinen Ton. Importiere zuerst eine Datei.", "Note: without imported road data there is no tone. Import a file first."), Toast.LENGTH_LONG).show()
                }
            }
        }
        val swCurve = findViewById<MaterialSwitch>(R.id.swCurve)
        val sldCurve = findViewById<Slider>(R.id.sldCurve)
        val txtCurve = findViewById<TextView>(R.id.txtCurveLevel)
        val txtCurveHint = findViewById<TextView>(R.id.txtCurveHint)
        fun curveUi() {
            txtCurve.text = Tx.t("Empfindlichkeit: ", "Sensitivity: ") + when (sldCurve.value.toInt()) { 0 -> Tx.t("Wenig", "Low"); 2 -> Tx.t("Viel", "High"); else -> "Normal" }
            sldCurve.isEnabled = swCurve.isChecked
            val al = if (swCurve.isChecked) 1f else 0.5f
            txtCurve.alpha = al; txtCurveHint.alpha = al
        }
        swCurve.isChecked = prefs.getBoolean(BridgeService.KEY_CURVE, false)
        sldCurve.value = prefs.getInt(BridgeService.KEY_CURVE_LEVEL, 1).coerceIn(0, 2).toFloat()
        curveUi()
        swCurve.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_CURVE, on).apply()
            curveUi()
            applyConfigIfRunning()
        }
        sldCurve.addOnChangeListener { _, value, fromUser ->
            curveUi()
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_CURVE_LEVEL, value.toInt()).apply()
                applyConfigIfRunning()
            }
        }
        swCamSound.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_CAMERA_SOUND, on).apply()
            applyConfigIfRunning()
        }
        swWarn.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_WARN, on).apply()
            sldWarn.isEnabled = on
            soundEnabledUi()
            applyConfigIfRunning()
        }
        sldWarn.addOnChangeListener { _, value, fromUser ->
            warnText(value.toInt())
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_WARN_TOL, value.toInt()).apply()
                applyConfigIfRunning()
            }
        }
        // Blitzer-Warnung: Einschalten fragt erst nach (in Deutschland nicht erlaubt), Ausschalten gleich
        var camRevert = false
        swCamera.setOnCheckedChangeListener { _, on ->
            if (camRevert) return@setOnCheckedChangeListener
            if (on) {
                camRevert = true
                swCamera.isChecked = false // bis zur Antwort aus
                camRevert = false
                AlertDialog.Builder(this)
                    .setTitle(Tx.t("Blitzer-Warnung", "Speed camera warning"))
                    .setMessage(Tx.t("Achtung: In Deutschland und einigen anderen Ländern dürfen Fahrer kein Gerät nutzen, das vor Blitzern warnt. Das kann ein Bußgeld kosten. Du bist selbst dafür verantwortlich, wo du die Funktion nutzt. Die Daten kommen aus OpenStreetMap und sind lückenhaft: mobile Blitzer und Abschnittskontrollen fehlen.", "Warning: in Germany and some other countries drivers must not use a device that warns about speed cameras. This can cost a fine. You are responsible for where you use this function. The data comes from OpenStreetMap and has gaps: mobile cameras and section controls are missing."))
                    .setNegativeButton(Tx.t("Abbrechen", "Cancel"), null)
                    .setPositiveButton(Tx.t("Trotzdem aktivieren", "Enable anyway")) { _, _ ->
                        camRevert = true
                        swCamera.isChecked = true
                        camRevert = false
                        prefs.edit().putBoolean(BridgeService.KEY_CAMERA, true).apply()
                        cameraUi(); soundEnabledUi()
                        applyConfigIfRunning()
                    }
                    .show()
            } else {
                prefs.edit().putBoolean(BridgeService.KEY_CAMERA, false).apply()
                cameraUi(); soundEnabledUi()
                applyConfigIfRunning()
            }
        }
        sldCam.addOnChangeListener { _, value, fromUser ->
            camText(value.toInt())
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_CAMERA_LEAD, value.toInt()).apply()
                applyConfigIfRunning()
            }
        }

        // Helligkeit
        val swBright = findViewById<MaterialSwitch>(R.id.swBrightAuto)
        val sldBright = findViewById<Slider>(R.id.sldBright)
        val boxBright = findViewById<View>(R.id.boxBright)
        sldBright.value = prefs.getInt(BridgeService.KEY_BRIGHT_STEP, 1).coerceIn(0, 2).toFloat()
        swBright.isChecked = prefs.getBoolean(BridgeService.KEY_BRIGHT_AUTO, true)
        fun brightUi(auto: Boolean) {
            sldBright.isEnabled = !auto
            boxBright.alpha = if (auto) 0.4f else 1f
        }
        brightUi(swBright.isChecked)
        swBright.setOnCheckedChangeListener { _, auto ->
            prefs.edit().putBoolean(BridgeService.KEY_BRIGHT_AUTO, auto).apply()
            brightUi(auto)
            applyConfigIfRunning()
        }
        sldBright.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.edit().putInt(BridgeService.KEY_BRIGHT_STEP, value.toInt()).apply()
            applyConfigIfRunning()
        }

        // Justage (wird nie gespeichert über einen Neustart hinweg; der Dienst setzt ihn zurück)
        val swJust = findViewById<MaterialSwitch>(R.id.swJustage)
        swJust.isChecked = false
        prefs.edit().putBoolean(BridgeService.KEY_JUSTAGE, false).apply()
        swJust.setOnCheckedChangeListener { _, on ->
            if (justageSyncing) return@setOnCheckedChangeListener
            if (on && !BridgeService.running) {
                Toast.makeText(this, Tx.t("Bitte zuerst das HUD verbinden (Übersicht, Start).", "Please connect the HUD first (Overview, Start)."), Toast.LENGTH_SHORT).show()
                justageSyncing = true; swJust.isChecked = false; justageSyncing = false
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(BridgeService.KEY_JUSTAGE, on).apply()
            applyConfigIfRunning()
        }
    }

    private var justageSyncing = false
    private var serviceSyncing = false

    /** Startet OSMAnd (Play-Store-, F-Droid- oder Entwickler-Version, was installiert ist). */
    private fun openOsmand() {
        for (pkg in listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")) {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, Tx.t("OSMAnd ist nicht installiert", "OSMAnd is not installed"), Toast.LENGTH_SHORT).show()
    }

    private val pickCamList = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importCameraList(uris)
    }

    /** Blitzer-Dateien (GPX) lesen, zusammenlegen und als eigene Liste speichern. Die alte Liste wird ersetzt. */
    private fun importCameraList(uris: List<Uri>) {
        val app = applicationContext
        val box = findViewById<View>(R.id.boxCamProgress)
        val txt = findViewById<TextView>(R.id.tvCamProgress)
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.barCam)
        val pick = findViewById<MaterialButton>(R.id.btnCamPick)
        box.visibility = View.VISIBLE; pick.isEnabled = false
        txt.text = Tx.t("Datei 1 von ${uris.size}", "File 1 of ${uris.size}"); bar.setProgressCompat(0, false)
        Thread {
            var files = 0
            val all = ArrayList<io.github.th3s1nc.osmandhudbridge.limit.CameraImport.Parsed>()
            for ((idx, u) in uris.withIndex()) {
                runOnUiThread {
                    txt.text = Tx.t("Datei ${idx + 1} von ${uris.size}", "File ${idx + 1} of ${uris.size}")
                    bar.setProgressCompat(idx * 1000 / uris.size, true)
                }
                try {
                    val text = contentResolver.openInputStream(u)?.use { s ->
                        val b = s.readBytes()
                        if (b.size > 30_000_000) null else String(b, Charsets.UTF_8)
                    } ?: continue
                    val list = io.github.th3s1nc.osmandhudbridge.limit.CameraImport.parseGpx(text)
                    if (list.isNotEmpty()) { all += list; files++ }
                } catch (_: Exception) { }
            }
            runOnUiThread { txt.text = Tx.t("Zusammenführen …", "Merging …"); bar.setProgressCompat(1000, true) }
            val merged = io.github.th3s1nc.osmandhudbridge.limit.CameraImport.merge(all)
            val msg = if (merged.isEmpty()) Tx.t("Keine Blitzer in den Dateien gefunden (erwartet: GPX mit Wegpunkten).", "No cameras found in the files (expected: GPX with waypoints).") else {
                try {
                    io.github.th3s1nc.osmandhudbridge.limit.CameraStore.save(app.filesDir, merged)
                    prefs.edit().putInt(BridgeService.KEY_CAM_LIST_COUNT, merged.size).putInt(BridgeService.KEY_CAM_LIST_FILES, files).apply()
                    Tx.t("${merged.size} Blitzer importiert", "${merged.size} cameras imported")
                } catch (e: Exception) { Tx.t("Speichern nicht möglich (${e.message})", "Cannot save (${e.message})") }
            }
            runOnUiThread {
                box.visibility = View.GONE; pick.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshCamList()
                applyConfigIfRunning()
            }
        }.start()
    }

    /** Karte "Blitzer-Liste": Status und Schalter nach dem Import oder Entfernen neu zeigen. */
    private fun refreshCamList() {
        val n = prefs.getInt(BridgeService.KEY_CAM_LIST_COUNT, 0)
        val files = prefs.getInt(BridgeService.KEY_CAM_LIST_FILES, 0)
        val has = n > 0 && io.github.th3s1nc.osmandhudbridge.limit.CameraStore.file(filesDir).isFile
        findViewById<TextView>(R.id.tvCamListStat).text = if (has) String.format(Tx.loc(), Tx.t("%,d Blitzer importiert", "%,d cameras imported"), n) else Tx.t("Noch keine Liste importiert", "No list imported yet")
        findViewById<TextView>(R.id.tvCamListSub).text = Tx.t("aus $files Dateien, doppelte (unter 30 m) zusammengelegt", "from $files files, duplicates (under 30 m) merged")
        findViewById<View>(R.id.boxCamList).visibility = if (has) View.VISIBLE else View.GONE
    }

    private val regionLoader by lazy { RegionLoader(this) }

    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            ImportService.start(this, uri)
        }
    }

    /** Region-Download: Balken, Pausieren/Fortsetzen/Verwerfen, geladene Region. Leicht genug für den Takt des Downloads. */
    private fun refreshDownload() {
        val st = DownloadBus.state
        val box = findViewById<View>(R.id.boxDlProgress)
        box.visibility = if (st == DlState.IDLE) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.btnRegionLoad).isEnabled = !ImportBus.running && st == DlState.IDLE
        val region = prefs.getString(BridgeService.KEY_REGION_ID, null)
        findViewById<View>(R.id.boxRegion).visibility = if (region == null || st != DlState.IDLE || ImportBus.running) View.GONE else View.VISIBLE
        if (region != null) {
            findViewById<TextView>(R.id.tvRegionInfo).text =
                (prefs.getString(BridgeService.KEY_REGION_NAME, null) ?: "–") + ", " + (prefs.getString(BridgeService.KEY_REGION_DATE, null) ?: "–")
        }
        if (st == DlState.IDLE) return
        val frac = io.github.th3s1nc.osmandhudbridge.limit.RegionDownload.fraction(DownloadBus.done, DownloadBus.total)
        findViewById<TextView>(R.id.tvDlName).text = DownloadBus.name
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.barDl)
            .setProgressCompat((frac * 1000).toInt().coerceIn(0, 1000), false)
        val sizes = io.github.th3s1nc.osmandhudbridge.limit.RegionDownload.sizeText(DownloadBus.done) + Tx.t(" von ", " of ") +
            io.github.th3s1nc.osmandhudbridge.limit.RegionDownload.sizeText(DownloadBus.total)
        findViewById<TextView>(R.id.tvDlText).text =
            (if (st == DlState.PAUSED) Tx.t("Pausiert bei ", "Paused at ") else "") + "${(frac * 100).toInt()} %  ·  " + sizes
        findViewById<TextView>(R.id.tvDlNote).text = DownloadBus.note
        findViewById<MaterialButton>(R.id.btnDlPause).text = if (st == DlState.RUNNING) Tx.t("Pausieren", "Pause") else Tx.t("Fortsetzen", "Resume")
        findViewById<View>(R.id.btnDlDiscard).visibility = if (st == DlState.PAUSED) View.VISIBLE else View.GONE
    }

    /** Karte "Straßendaten importieren": Fortschritt während des Imports, sonst Ergebnis und Zahl der importierten Kacheln. */
    private fun refreshMap() {
        val running = ImportBus.running
        refreshDownload()
        findViewById<View>(R.id.boxMapProgress).visibility = if (running) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnMapPick).isEnabled = !running
        val stat = findViewById<TextView>(R.id.tvMapStat)
        val sub = findViewById<TextView>(R.id.tvMapSub)
        val clear = findViewById<MaterialButton>(R.id.btnMapClear)
        if (running) {
            findViewById<TextView>(R.id.tvMapFile).text = ImportBus.fileName
            findViewById<TextView>(R.id.tvMapStep).text = Tx.t("Schritt ${ImportBus.step} von ${ImportBus.steps}: ${ImportBus.stepText}", "Step ${ImportBus.step} of ${ImportBus.steps}: ${ImportBus.stepText}")
            findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.barMap)
                .setProgressCompat((ImportBus.fraction * 1000).toInt().coerceIn(0, 1000), false)
            findViewById<TextView>(R.id.tvMapPercent).text = "${(ImportBus.fraction * 100).toInt().coerceIn(0, 100)} %"
            findViewById<TextView>(R.id.tvMapEta).text = ImportBus.eta
            findViewById<TextView>(R.id.tvMapTiles).text =
                if (ImportBus.tiles > 0) String.format(Tx.loc(), Tx.t("%,d Kacheln fertig", "%,d tiles done"), ImportBus.tiles) else ""
            stat.visibility = View.GONE; sub.visibility = View.GONE; clear.visibility = View.GONE
            findViewById<View>(R.id.btnInfoMap).visibility = View.GONE
            findViewById<View>(R.id.tvMapStale).visibility = View.GONE
            return
        }
        stat.visibility = View.VISIBLE
        findViewById<View>(R.id.btnInfoMap).visibility =
            if (io.github.th3s1nc.osmandhudbridge.limit.ImportLog.parse(prefs.getString(BridgeService.KEY_IMPORT_LOG, null)).isEmpty()) View.GONE else View.VISIBLE
        val app = applicationContext
        Thread {
            val n = try { io.github.th3s1nc.osmandhudbridge.limit.PbfImport.countImported(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(app)) } catch (_: Exception) { 0 }
            val all = try { io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(app).listFiles { f -> f.name.endsWith(".json") } } catch (_: Exception) { null }
            val tilesAll = all?.size ?: 0
            val bytesAll = all?.sumOf { it.length() } ?: 0L
            runOnUiThread {
                if (ImportBus.running) return@runOnUiThread
                val log = io.github.th3s1nc.osmandhudbridge.limit.ImportLog.parse(prefs.getString(BridgeService.KEY_IMPORT_LOG, null))
                val st = findViewById<TextView>(R.id.tvStoreStat)
                val nl = Tx.loc()
                st.text = String.format(nl, Tx.t("Kacheln: %,d (%,d importiert, %,d online geladen)\nBelegt: %,.0f MB", "Tiles: %,d (%,d imported, %,d loaded online)\nUsed: %,.0f MB"), tilesAll, n, (tilesAll - n).coerceAtLeast(0), bytesAll / 1_048_576.0) +
                    (if (log.isEmpty()) "" else Tx.t("\n\nAus den Importen:\n", "\n\nFrom the imports:\n") + io.github.th3s1nc.osmandhudbridge.limit.ImportLog.total(log).describe())
                val stale = findViewById<TextView>(R.id.tvMapStale)
                val hint = if (n == 0) null else io.github.th3s1nc.osmandhudbridge.limit.ImportLog.staleHint(log, java.time.LocalDate.now())
                stale.text = hint ?: ""
                stale.visibility = if (hint == null) View.GONE else View.VISIBLE
                val info = prefs.getString(BridgeService.KEY_IMPORT_INFO, null)
                stat.text = ImportBus.message ?: if (n == 0) Tx.t("Noch keine Straßendaten importiert", "No road data imported yet") else (info?.let { StatusText.stored(it) } ?: String.format(Tx.loc(), Tx.t("%,d Kacheln importiert", "%,d tiles imported"), n))
                val subText = if (n == 0) "" else String.format(Tx.loc(), Tx.t("%,d importierte Kacheln gespeichert", "%,d imported tiles saved"), n) +
                    (prefs.getString(BridgeService.KEY_IMPORT_SUB, null)?.let { "\n" + StatusText.stored(it) } ?: "")
                sub.text = subText
                sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
                clear.visibility = if (n > 0) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun applyConfigIfRunning() {
        if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_CONFIG)
    }

    private val monthNames: List<String> get() = if (Tx.en)
        listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    else listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    private fun dateText(d: java.time.LocalDate) =
        if (Tx.en) "${monthNames[d.monthValue - 1]} ${d.dayOfMonth}" else "${d.dayOfMonth}. ${monthNames[d.monthValue - 1]}"

    /** Karte "Saison" (Werkzeuge): Schalter, Beginn- und Ende-Monat. */
    private fun setupSeason() {
        val sw = findViewById<MaterialSwitch>(R.id.swSeason)
        val box = findViewById<View>(R.id.seasonBox)
        val btnFrom = findViewById<MaterialButton>(R.id.btnSeasonFrom)
        val btnTo = findViewById<MaterialButton>(R.id.btnSeasonTo)
        fun ui() {
            box.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
            btnFrom.text = Tx.t("Beginn: ", "Start: ") + monthNames[prefs.getInt(BridgeService.KEY_SEASON_FROM, 3).coerceIn(1, 12) - 1]
            btnTo.text = Tx.t("Ende: ", "End: ") + monthNames[prefs.getInt(BridgeService.KEY_SEASON_TO, 10).coerceIn(1, 12) - 1]
        }
        fun changed() {
            if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_CONFIG)
            refresh()
        }
        sw.isChecked = prefs.getBoolean(BridgeService.KEY_SEASON_ON, false)
        ui()
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_SEASON_ON, on).apply()
            ui()
            changed()
            if (!on) autoConnect() // Saison-Schalter aus: die App darf wieder starten
        }
        fun pickMonth(title: String, key: String, def: Int) {
            val cur = prefs.getInt(key, def).coerceIn(1, 12) - 1
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(monthNames.toTypedArray(), cur) { dlg, which ->
                    prefs.edit().putInt(key, which + 1).apply()
                    dlg.dismiss()
                    ui()
                    changed()
                }
                .setNegativeButton(Tx.t("Abbrechen", "Cancel"), null)
                .show()
        }
        btnFrom.setOnClickListener { pickMonth(Tx.t("Saisonbeginn (erster Tag des Monats)", "Season start (first day of the month)"), BridgeService.KEY_SEASON_FROM, 3) }
        btnTo.setOnClickListener { pickMonth(Tx.t("Saisonende (letzter Tag des Monats)", "Season end (last day of the month)"), BridgeService.KEY_SEASON_TO, 10) }
    }

    private fun setupSettings() {
        for ((id, key) in listOf(
            R.id.chkNoticeCall to BridgeService.KEY_NOTICE_CALL,
            R.id.chkNoticeMsg to BridgeService.KEY_NOTICE_MSG
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, false)
            sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        val swStraight = findViewById<MaterialSwitch>(R.id.chkKeepStraight)
        swStraight.isChecked = prefs.getBoolean(BridgeService.KEY_KEEP_STRAIGHT, false)
        swStraight.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_KEEP_STRAIGHT, checked).apply()
            applyConfigIfRunning()
        }
        val swMusic = findViewById<MaterialSwitch>(R.id.chkNoticeMusic)
        swMusic.isChecked = prefs.getBoolean(BridgeService.KEY_NOTICE_MUSIC, false)
        swMusic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_NOTICE_MUSIC, checked).apply()
            applyConfigIfRunning()
        }

        // Handy-Akku-Warnung (Guide, Cruiser, Explorer)
        val swBatt = findViewById<MaterialSwitch>(R.id.chkBattWarn)
        val sldBatt = findViewById<Slider>(R.id.sldBattPct)
        val txtBatt = findViewById<TextView>(R.id.txtBattPct)
        val pct = prefs.getInt(BridgeService.KEY_BATT_PCT, io.github.th3s1nc.osmandhudbridge.limit.BatteryWarn.DEFAULT_PERCENT).coerceIn(5, 50) / 5 * 5
        sldBatt.value = pct.toFloat()
        txtBatt.text = Tx.t("Warnen ab: $pct %", "Warn at: $pct %")
        swBatt.isChecked = prefs.getBoolean(BridgeService.KEY_BATT_WARN, false)
        sldBatt.isEnabled = swBatt.isChecked
        txtBatt.alpha = if (swBatt.isChecked) 1f else 0.5f
        swBatt.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_BATT_WARN, on).apply()
            sldBatt.isEnabled = on
            txtBatt.alpha = if (on) 1f else 0.5f
            applyConfigIfRunning()
        }
        sldBatt.addOnChangeListener { _, value, fromUser ->
            txtBatt.text = Tx.t("Warnen ab: ${value.toInt()} %", "Warn at: ${value.toInt()} %")
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_BATT_PCT, value.toInt()).apply()
                applyConfigIfRunning()
            }
        }

        // Tempolimit: einzelne Schalter (Ortsschilder und Nachbarabschnitte wirken nur beim Schätzen)
        val swGuess = findViewById<MaterialSwitch>(R.id.chkGuess)
        val swSigns = findViewById<MaterialSwitch>(R.id.chkSigns)
        val swNeighbors = findViewById<MaterialSwitch>(R.id.chkNeighbors)
        fun subState(on: Boolean) {
            swSigns.isEnabled = on
            swNeighbors.isEnabled = on
            swSigns.alpha = if (on) 1f else 0.4f
            swNeighbors.alpha = if (on) 1f else 0.4f
        }
        swGuess.isChecked = prefs.getBoolean(BridgeService.KEY_GUESS_LIMIT, false)
        subState(swGuess.isChecked)
        swGuess.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_GUESS_LIMIT, checked).apply()
            subState(checked)
            applyConfigIfRunning()
        }
        for ((id, key, def) in listOf(
            Triple(R.id.chkTags, BridgeService.KEY_LIM_TAGS, true),
            Triple(R.id.chkSigns, BridgeService.KEY_LIM_SIGNS, true),
            Triple(R.id.chkNeighbors, BridgeService.KEY_LIM_NEIGHBORS, true),
            Triple(R.id.swVerbose, BridgeService.KEY_VERBOSE, false)
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, def)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (key == BridgeService.KEY_VERBOSE) BridgeBus.verbose = checked
                applyConfigIfRunning()
            }
        }
        setupSeason()

        val cacheValues = listOf(256, 512, 1024, 2048)
        val sldCache = findViewById<Slider>(R.id.sldCache)
        val tvCacheName = findViewById<TextView>(R.id.tvCacheName)
        fun cacheUi(i: Int) {
            val mb = cacheValues[i]
            tvCacheName.text = if (mb < 1024) (if (mb == 256) Tx.t("0,25 GB", "0.25 GB") else Tx.t("0,5 GB", "0.5 GB")) else "${mb / 1024} GB"
        }
        val savedCache = prefs.getInt(BridgeService.KEY_CACHE_MB, BridgeService.DEFAULT_CACHE_MB)
        val cacheIdx = cacheValues.indexOfFirst { it >= savedCache }.takeIf { it >= 0 } ?: (cacheValues.size - 1)
        if (cacheValues[cacheIdx] != savedCache) prefs.edit().putInt(BridgeService.KEY_CACHE_MB, cacheValues[cacheIdx]).apply()
        sldCache.value = cacheIdx.toFloat()
        cacheUi(cacheIdx)
        sldCache.addOnChangeListener { _, value, fromUser ->
            val i = value.toInt().coerceIn(0, cacheValues.size - 1)
            cacheUi(i)
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_CACHE_MB, cacheValues[i]).apply()
                applyConfigIfRunning()
            }
        }

        // Blitzer-Liste (eigene GPX-Dateien)
        findViewById<MaterialButton>(R.id.btnCamPick).setOnClickListener {
            try { pickCamList.launch(arrayOf("*/*")) } catch (_: Exception) { Toast.makeText(this, Tx.t("Keine Dateiauswahl verfügbar", "No file picker available"), Toast.LENGTH_LONG).show() }
        }
        findViewById<MaterialButton>(R.id.btnCamClear).setOnClickListener {
            io.github.th3s1nc.osmandhudbridge.limit.CameraStore.clear(filesDir)
            prefs.edit().remove(BridgeService.KEY_CAM_LIST_COUNT).remove(BridgeService.KEY_CAM_LIST_FILES).apply()
            refreshCamList()
            applyConfigIfRunning()
            Toast.makeText(this, Tx.t("Blitzer-Liste entfernt", "Camera list removed"), Toast.LENGTH_SHORT).show()
        }
        for ((id, key) in listOf(R.id.swCamRed to BridgeService.KEY_CAM_RED, R.id.swCamSection to BridgeService.KEY_CAM_SECTION, R.id.swCamTunnel to BridgeService.KEY_CAM_TUNNEL)) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, true)
            sw.setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                applyConfigIfRunning()
            }
        }
        refreshCamList()

        // Straßendaten importieren (.osm.pbf) und Schalter "Offline-Daten"
        findViewById<MaterialSwitch>(R.id.swDlMobile).apply {
            isChecked = prefs.getBoolean(BridgeService.KEY_DL_MOBILE, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(BridgeService.KEY_DL_MOBILE, on).apply()
                // War der Download nur pausiert, weil das WLAN weg war: jetzt von selbst weitermachen
                if (on && DownloadBus.state == DlState.PAUSED && DownloadBus.pausedForMobile && !prefs.getBoolean(BridgeService.KEY_OFFLINE, false)) {
                    DownloadService.start(this@MainActivity, null, null, null, false)
                }
            }
        }
        findViewById<View>(R.id.btnInfoDlMobile).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(Tx.t("Mobile Daten", "Mobile data"))
                .setMessage(Tx.t("Aus: Die Region wird nur im WLAN geladen. Fällt das WLAN weg, pausiert der Download.\n\nAn: Ohne WLAN lädt die App über Mobilfunk weiter, ohne dass du etwas tun musst, und verbindet sich bei Funklöchern von selbst neu. Sobald wieder WLAN da ist, wechselt sie zurück ins WLAN.\n\nDas verbraucht Datenvolumen deines Mobilfunkvertrags. Eine Region hat meist einige hundert Megabyte; die genaue Größe siehst du vor dem Start. Den Schalter kannst du jederzeit umlegen, auch während des Downloads.", "Off: the region is only loaded over Wi-Fi. If Wi-Fi is lost, the download pauses.\n\nOn: without Wi-Fi the app continues over mobile data without you doing anything and reconnects by itself in dead spots. As soon as Wi-Fi is back, it switches back to Wi-Fi.\n\nThis uses data from your mobile plan. A region is usually a few hundred megabytes; you see the exact size before the start. You can flip the switch at any time, also during the download."))
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.btnRegionLoad).setOnClickListener { regionLoader.open() }
        findViewById<MaterialButton>(R.id.btnRegionUpdate).setOnClickListener { regionLoader.update() }
        findViewById<MaterialButton>(R.id.btnDlPause).setOnClickListener {
            if (DownloadBus.state == DlState.RUNNING) DownloadBus.pauseRequested = true else regionLoader.resume()
        }
        findViewById<MaterialButton>(R.id.btnDlDiscard).setOnClickListener { regionLoader.discard() }
        findViewById<MaterialButton>(R.id.btnMapPick).setOnClickListener {
            try { pickMap.launch(arrayOf("*/*")) } catch (_: Exception) { Toast.makeText(this, Tx.t("Keine Dateiauswahl verfügbar", "No file picker available"), Toast.LENGTH_LONG).show() }
        }
        findViewById<View>(R.id.btnInfoMap).setOnClickListener {
            val log = io.github.th3s1nc.osmandhudbridge.limit.ImportLog.parse(prefs.getString(BridgeService.KEY_IMPORT_LOG, null))
            AlertDialog.Builder(this)
                .setTitle(Tx.t("Was wurde importiert?", "What was imported?"))
                .setMessage(io.github.th3s1nc.osmandhudbridge.limit.ImportLog.describe(log))
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.btnMapCancel).setOnClickListener { ImportBus.cancel = true; Toast.makeText(this, Tx.t("Wird abgebrochen …", "Cancelling …"), Toast.LENGTH_SHORT).show() }
        findViewById<MaterialButton>(R.id.btnMapClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(Tx.t("Importierte Daten entfernen?", "Remove imported data?"))
                .setMessage(Tx.t("Alle aus einer Datei importierten Kacheln werden gelöscht. Online geladene Kacheln bleiben.", "All tiles imported from a file will be deleted. Tiles loaded online stay."))
                .setPositiveButton(Tx.t("Entfernen", "Remove")) { _, _ ->
                    val app = applicationContext
                    Thread {
                        val n = io.github.th3s1nc.osmandhudbridge.limit.PbfImport.removeImported(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(app))
                        prefs.edit().remove(BridgeService.KEY_IMPORT_INFO).remove(BridgeService.KEY_IMPORT_SUB).remove(BridgeService.KEY_IMPORT_LOG).apply()
                        ImportBus.message = null
                        runOnUiThread { Toast.makeText(this, Tx.t("$n Kacheln entfernt", "$n tiles removed"), Toast.LENGTH_SHORT).show(); refreshMap(); applyConfigIfRunning() }
                    }.start()
                }
                .setNegativeButton(Tx.t("Abbrechen", "Cancel"), null)
                .show()
        }
        fun applyKeepScreen(on: Boolean) {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyKeepScreen(prefs.getBoolean(BridgeService.KEY_KEEP_SCREEN, false))
        findViewById<MaterialSwitch>(R.id.swKeepScreen).apply {
            isChecked = prefs.getBoolean(BridgeService.KEY_KEEP_SCREEN, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(BridgeService.KEY_KEEP_SCREEN, on).apply()
                applyKeepScreen(on)
            }
        }
        findViewById<View>(R.id.btnInfoKeepScreen).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Stay awake")
                .setMessage(Tx.t("Der Bildschirm bleibt an, solange diese App vorne offen ist. So siehst du bei einer Testfahrt dauernd Schild, Straße und Tempo.\n\nWenn du eine andere App öffnest (z. B. OSMAnd), gilt wieder die normale Zeit. Die Warnungen laufen im Hintergrund trotzdem weiter.\n\nKostet viel Akku und belastet den Bildschirm: am besten nur mit Ladekabel.", "The screen stays on as long as this app is open in the foreground. On a test drive you can keep seeing sign, street and speed.\n\nIf you open another app (e.g. OSMAnd), the normal screen timeout applies again. The warnings keep running in the background anyway.\n\nUses a lot of battery and strains the screen: best only with a charging cable."))
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<MaterialSwitch>(R.id.swCarMode).apply {
            isChecked = prefs.getBoolean(BridgeService.KEY_CAR_MODE, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(BridgeService.KEY_CAR_MODE, on).apply()
                applyConfigIfRunning()
                if (on) Toast.makeText(this@MainActivity, Tx.t("Auto-Modus an: GPS jede Sekunde, alle Töne aktiv. Braucht mehr Akku.", "Car mode on: GPS every second, all tones active. Uses more battery."), Toast.LENGTH_LONG).show()
            }
        }
        findViewById<MaterialSwitch>(R.id.swOffline).apply {
            isChecked = prefs.getBoolean(BridgeService.KEY_OFFLINE, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(BridgeService.KEY_OFFLINE, on).apply()
                applyConfigIfRunning()
                if (on && prefs.getString(BridgeService.KEY_IMPORT_INFO, null) == null) {
                    Toast.makeText(this@MainActivity, Tx.t("Hinweis: Es sind nur die schon gespeicherten Kacheln da. Importiere zuerst Straßendaten.", "Note: only the tiles already saved are there. Import road data first."), Toast.LENGTH_LONG).show()
                }
            }
        }
        refreshMap()

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggleSpeed)
        toggle.check(
            if (prefs.getString(BridgeService.KEY_SPEED_SRC, "gps") == "osmand") R.id.btnSpeedOsmand else R.id.btnSpeedGps
        )
        toggle.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.edit().putString(
                BridgeService.KEY_SPEED_SRC, if (id == R.id.btnSpeedOsmand) "osmand" else "gps"
            ).apply()
            applyConfigIfRunning()
        }
    }

    private fun setupInfo() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        findViewById<android.widget.TextView>(R.id.tvVersion).text = Tx.t("Version ${version ?: "?"} · inoffiziell, privat und ohne Gewähr", "Version ${version ?: "?"} · unofficial, private, no warranty")
        findViewById<android.widget.TextView>(R.id.tvAbout).text = getString(R.string.about_intro)
        findViewById<android.widget.TextView>(R.id.tvLiability).text = getString(R.string.about_liability)
        findViewById<android.widget.TextView>(R.id.tvPrivacy).text = getString(R.string.about_privacy)
        findViewById<android.widget.TextView>(R.id.tvLicenses).text = getString(R.string.about_licenses)
    }

    private fun setupTools() {
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { command(BridgeService.ACTION_CLEAR) }
        findViewById<MaterialButton>(R.id.btnElementTest).setOnClickListener { ElementTestDialog(this).show() }
        findViewById<MaterialSwitch>(R.id.swNotifAccess).setOnCheckedChangeListener { _, _ ->
            if (permSyncing) return@setOnCheckedChangeListener
            // Android erlaubt das Setzen/Entziehen nur dem Nutzer: Systemseite öffnen, Schalter zeigt weiter den echten Zustand
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            syncPermSwitches()
        }
        findViewById<MaterialSwitch>(R.id.swBattery).setOnCheckedChangeListener { _, on ->
            if (permSyncing) return@setOnCheckedChangeListener
            if (on) batteryExemption() else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            syncPermSwitches()
        }
        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener { shareLog() }
        findViewById<MaterialButton>(R.id.btnLogClear).setOnClickListener {
            BridgeBus.clearLog()
            Toast.makeText(this, Tx.t("Log gelöscht", "Log cleared"), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Anzeige ----------------

    private var cacheStatsAt = 0L
    private var cacheStatsBusy = false
    private var cacheStatsText = Tx.t("Belegt: wird berechnet …", "Used: calculating …")

    /** Belegten Kartenspeicher höchstens alle 15 s im Hintergrund zählen (bei vielen Dateien dauert das etwas). */
    private fun updateCacheUsed() {
        findViewById<TextView>(R.id.tvCacheUsed).text = cacheStatsText
        val now = android.os.SystemClock.elapsedRealtime()
        if (cacheStatsBusy || (cacheStatsAt != 0L && now - cacheStatsAt < 15_000L)) return
        cacheStatsBusy = true
        cacheStatsAt = now
        val dir = io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext)
        Thread {
            val (n, bytes) = try { io.github.th3s1nc.osmandhudbridge.limit.TileCache(dir).stats() } catch (_: Exception) { 0 to 0L }
            val mb = bytes / (1024.0 * 1024.0)
            val txt = Tx.t("Belegt: ", "Used: ") + (if (mb >= 1024) String.format(Tx.loc(), "%.1f GB", mb / 1024) else String.format(Tx.loc(), "%.0f MB", mb)) +
                Tx.t(", $n Kacheln", ", $n tiles")
            runOnUiThread {
                cacheStatsText = txt; cacheStatsBusy = false
                findViewById<TextView>(R.id.tvCacheUsed)?.text = txt // sofort anzeigen, nicht erst beim nächsten Auffrischen
            }
        }.start()
    }

    /** Kurzer Status für die Live-Karte, Einzelheiten stehen im Protokoll. */
    private fun osmStatus(raw: String): String = when {
        raw.contains("aus") && raw.startsWith("OSMAnd: aus") -> Tx.t("Aus", "Off")
        raw.contains("nicht freigegeben") -> Tx.t("Nicht freigegeben (siehe Protokoll)", "Not enabled (see log)")
        raw.contains("Anmeldung fehlgeschlagen") -> Tx.t("Anmeldung fehlgeschlagen", "Registration failed")
        raw.contains("verbunden (") || raw.contains("liefert nichts") || raw.contains("Ansagen nicht verfügbar") -> Tx.t("Verbunden", "Connected")
        raw.contains("verbinde") -> Tx.t("Verbinde …", "Connecting …")
        raw.contains("verloren") || raw.contains("Binding beendet") || raw.contains("nicht erreichbar") || raw.contains("keine Antwort") -> Tx.t("Nicht verbunden", "Not connected")
        else -> "–"
    }

    /** Hinweis auf der Übersicht, wenn die Benachrichtigungen von OSMAnd nicht ankommen (Tempo, Restzeit, Ankunft fehlen dann). */
    private fun updateNotifHint() {
        val tv = findViewById<TextView>(R.id.tvNotifHint)
        val needed = BridgeService.running && prefs.getBoolean(BridgeService.KEY_ENABLED, true) &&
            !io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationListener.connected
        if (!needed) { tv.visibility = View.GONE; return }
        val granted = try {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) { false }
        tv.text = if (granted) Tx.t("Benachrichtigungen kommen nicht an: Tempo, Restzeit und Ankunft aus OSMAnd fehlen. Hier tippen, dann den Zugriff für diese App aus- und wieder einschalten.", "Notifications do not arrive: speed, remaining time and arrival from OSMAnd are missing. Tap here, then switch access for this app off and on again.")
        else Tx.t("Benachrichtigungszugriff fehlt: Tempo, Restzeit und Ankunft aus OSMAnd fehlen. Hier tippen und den Zugriff erlauben.", "Notification access is missing: speed, remaining time and arrival from OSMAnd are missing. Tap here and allow access.")
        tv.visibility = View.VISIBLE
    }

    /** Nur die Live-Werte (Tempo, Limit, Straße, Log): läuft jede Sekunde, solange die App offen ist. */
    private fun refreshLive() {
        fun plain(t: String) = StatusText.en(t).let { it.substringAfter(": ", it) }
        tvGps.text = plain(BridgeBus.gps)
        tvLimit.text = plain(BridgeBus.limit)
        tvOsm.text = osmStatus(BridgeBus.osm)
        val limRaw = BridgeBus.limit
        val limKmh = Regex("(\\d+) km/h").find(limRaw)?.groupValues?.get(1)
        val tvSignValue = findViewById<TextView>(R.id.tvSignValue)
        tvSignValue.text = limKmh ?: "–"
        findViewById<TextView>(R.id.tvSignSource).text = when {
            limKmh == null -> if (limRaw.contains("unbekannt")) Tx.t("Unbekannt", "Unknown") else plain(limRaw)
            limRaw.contains("geschätzt") -> Tx.t("Geschätzt, nicht aus den Kartendaten", "Estimated, not from the map data")
            else -> Tx.t("Aus den Kartendaten (OSM)", "From the map data (OSM)")
        }
        val spd = Regex("Tempo: (\\d+) km/h").find(BridgeBus.gps)?.groupValues?.get(1)
        findViewById<TextView>(R.id.tvSignStreet).text = Tx.t("Straße: ", "Street: ") + (BridgeBus.street?.takeIf { it.isNotBlank() } ?: "–")
        findViewById<TextView>(R.id.tvSignSpeed).text = Tx.t("Tempo: ", "Speed: ") + (spd?.let { "$it km/h" } ?: "–")
        findViewById<TextView>(R.id.tvLogHome).text = StatusText.en(BridgeBus.lastLinesNewestFirst(3)).ifBlank { "–" }
        tvLog.text = StatusText.en(BridgeBus.lastLinesNewestFirst(25))
    }

    private fun refresh() {
        updateNotifHint()
        val hud = BridgeBus.hud.removePrefix("HUD: ")
        val running = BridgeService.running
        val enabled = prefs.getBoolean(BridgeService.KEY_ENABLED, true)
        val hudOn = BridgeService.hudWanted(prefs)
        val season = BridgeService.seasonState(prefs)
        val inSeason = season == io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON
        findViewById<TextView>(R.id.tvSeasonStatus).text = if (!prefs.getBoolean(BridgeService.KEY_SEASON_ON, false)) "" else {
            val plan = BridgeService.seasonPlan(prefs)
            val today = java.time.LocalDate.now()
            when (season) {
                io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON -> Tx.t("Gerade ist Saison, die App läuft normal.", "It is season now, the app runs normally.")
                else -> Tx.t("Die App ruht, Saisonbeginn am ", "The app rests, season starts on ") + (plan.nextStart(today)?.let { dateText(it) } ?: "–") + "."
            }
        }
        scanCamerasIfDue()
        findViewById<TextView>(R.id.tvCameraStat).text = Tx.t("Blitzer (nur feste, aus OpenStreetMap): ", "Speed cameras (fixed only, from OpenStreetMap): ") +
            (if (BridgeService.running) BridgeBus.cameras else Tx.t("– (Dienst läuft nicht)", "– (service not running)")) +
            Tx.t("\nAlle gespeicherten Kacheln: ", "\nAll saved tiles: ") + BridgeBus.camerasAll
        // Kurzmeldung oben (englisch) und eine kurze Zeile darunter
        val failed = hud.contains("Fehler", true) || hud.contains("abgelehnt", true)
        tvHud.text = when {
            !enabled -> "Paused"
            !inSeason -> "Off-season"
            !hudOn -> "No HUD"
            running && failed -> "Error"
            running && hud.contains("bereit") -> "Connected"
            running -> "Connecting …"
            else -> "No HUD"
        }
        dotHud.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    !running || !hudOn || !enabled || !inSeason -> R.color.status_off
                    hud.contains("bereit") -> R.color.status_ok
                    hud.contains("Fehler", true) || hud.contains("abgelehnt", true) -> R.color.status_bad
                    else -> R.color.status_warn
                }
            )
        )
        tvServiceHint.text = when {
            !enabled -> Tx.t("HUD frei für die Tilsberk-App", "HUD free for the Tilsberk app")
            !inSeason -> {
                val start = BridgeService.seasonPlan(prefs).nextStart(java.time.LocalDate.now())
                Tx.t("Start am ", "Starts on ") + (start?.let { dateText(it) } ?: "–")
            }
            !hudOn -> ""
            running && failed -> StatusText.en(hud).replaceFirstChar { it.uppercase() }
            running && hud.contains("bereit") -> Tx.t("Läuft auch bei Display aus", "Also runs with the screen off")
            running -> ""
            else -> Tx.t("HUD verbinden einschalten", "Switch on Connect HUD")
        }
        tvServiceHint.visibility = if (tvServiceHint.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateCacheUsed()
        syncPermSwitches()
        val swJust = findViewById<MaterialSwitch>(R.id.swJustage)
        val justOn = prefs.getBoolean(BridgeService.KEY_JUSTAGE, false)
        if (swJust.isChecked != justOn) { justageSyncing = true; swJust.isChecked = justOn; justageSyncing = false }
        val swService = findViewById<MaterialSwitch>(R.id.swService)
        if (swService.isChecked != hudOn) { serviceSyncing = true; swService.isChecked = hudOn; serviceSyncing = false }
        refreshLive()
    }

    // ---------------- Start ----------------

    /** Startet den Dienst von selbst, solange die App offen ist (HudClient verbindet, sobald das HUD an ist). */
    private fun autoConnect() {
        if (!prefs.getBoolean(BridgeService.KEY_ENABLED, true)) return
        if (BridgeService.running || BridgeService.userStopped) return
        if (BridgeService.seasonState(prefs) != io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON) return
        val hudOn = BridgeService.hudWanted(prefs) && prefs.getString(BridgeService.KEY_ADDR, null) != null
        val needed = buildList {
            if (hudOn && Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) return
        if (hudOn) pickDeviceAndStart() else BridgeService.send(this)
    }

    private fun onStartClicked() {
        // Ein ausdrücklicher Start schaltet die Bridge wieder ein
        if (!prefs.getBoolean(BridgeService.KEY_ENABLED, true)) findViewById<MaterialSwitch>(R.id.swMaster).isChecked = true
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) pickDeviceAndStart() else permLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun pickDeviceAndStart() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, Tx.t("Bluetooth-Berechtigung wird benötigt", "Bluetooth permission is required"), Toast.LENGTH_LONG).show()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, Tx.t("Bluetooth ist ausgeschaltet", "Bluetooth is switched off"), Toast.LENGTH_LONG).show()
            return
        }
        val bonded = adapter.bondedDevices.orEmpty().toList()
        val huds = bonded.filter { isHud(it) }
        when {
            huds.isEmpty() -> {
                // ein früher gemerktes Gerät, das kein HUD ist (und nicht von Hand gewählt wurde), wird vergessen
                if (!prefs.getBoolean("addr_manual", false)) {
                    prefs.edit().remove(BridgeService.KEY_ADDR).remove(BridgeService.KEY_NAME).apply()
                }
                val b = AlertDialog.Builder(this)
                    .setTitle(Tx.t("Kein HUD gefunden", "No HUD found"))
                    .setMessage(Tx.t("Schalte das HUD ein und koppel es in den Android-Bluetooth-Einstellungen. Es heißt meist \"TILS\" oder \"TILSBERK Head-Up Display\".", "Switch the HUD on and pair it in the Android Bluetooth settings. It is usually called \"TILS\" or \"TILSBERK Head-Up Display\"."))
                    .setPositiveButton("OK", null)
                if (bonded.isNotEmpty()) b.setNeutralButton(Tx.t("Alle gekoppelten Geräte zeigen", "Show all paired devices")) { _, _ -> pickDevice(bonded, true) }
                b.show()
            }
            huds.size == 1 -> begin(huds[0])
            else -> pickDevice(huds)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice(list: List<BluetoothDevice>, manual: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle(Tx.t("Welches HUD?", "Which HUD?"))
            .setItems(list.map { "${it.name ?: "?"} (${it.address})" }.toTypedArray()) { _, i -> begin(list[i], manual) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun isHud(d: BluetoothDevice): Boolean {
        val n = d.name.orEmpty()
        // Das HUD meldet sich mal als "TILS" (Kurzname in der Werbung), mal als "TILSBERK Head-Up Di..."
        return n.contains("TILS", true) || n.contains("Head-Up", true) || n.contains("DVISION", true)
    }

    @SuppressLint("MissingPermission")
    private fun begin(d: BluetoothDevice, manual: Boolean = false) {
        BridgeService.userStopped = false
        prefs.edit()
            .putBoolean("addr_manual", manual)
            .putBoolean(BridgeService.KEY_HUD_ON, true)
            .putString(BridgeService.KEY_ADDR, d.address)
            .putString(BridgeService.KEY_NAME, d.name)
            .apply()
        BridgeService.send(this)
    }

    // ---------------- Tracking ----------------

    private val trackHandler = Handler(Looper.getMainLooper())
    private val trackTick = object : Runnable {
        override fun run() {
            updateRecUi()
            refreshLive()
            trackHandler.postDelayed(this, 1000)
        }
    }
    private var saveDialogs = 0 // offene Speichern-Dialoge

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Kennzahlen als Text; Zeilen für Höhe, Limit und Schräglage nur, wenn es Werte dazu gibt. */
    private fun statsText(st: io.github.th3s1nc.osmandhudbridge.track.RideStats): String {
        val sb = StringBuilder()
        sb.append(Tx.t("Strecke: ", "Distance: ")).append(RideFormat.distance(st.distanceM))
        sb.append(Tx.t("\nIn Bewegung: ", "\nMoving: ")).append(RideFormat.duration(st.movingMs))
        sb.append(Tx.t("\nGesamtzeit: ", "\nTotal time: ")).append(RideFormat.duration(st.totalMs))
        sb.append(Tx.t("\nØ Tempo: ", "\nAvg speed: ")).append(st.avgKmh).append(" km/h")
        sb.append(Tx.t("\nHöchstes Tempo: ", "\nTop speed: ")).append(st.maxKmh).append(" km/h")
        if (st.hasEle) sb.append(Tx.t("\nAufstieg: ", "\nAscent: ")).append(st.ascentM).append(Tx.t(" m, Abstieg: ", " m, descent: ")).append(st.descentM).append(" m")
        if (st.hasLimit) sb.append(Tx.t("\nTempolimit überschritten: ", "\nSpeed limit exceeded: ")).append(st.overCount).append(Tx.t(" Mal", " times"))
        if (st.hasLean) sb.append(Tx.t("\nMax. Schräglage: ", "\nMax lean angle: ")).append(st.maxLeanDeg).append(Tx.t("° (Schätzung)", "° (estimate)"))
        return sb.toString()
    }

    private fun setupTracking() {
        findViewById<View>(R.id.btnRec).setOnClickListener { onRecClicked() }
        findViewById<View>(R.id.tvRecBanner).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_tracking
        }
        for ((id, key) in listOf(
            R.id.swRecAlt to BridgeService.KEY_REC_ALT,
            R.id.swRecLimit to BridgeService.KEY_REC_LIMIT,
            R.id.swRecLean to BridgeService.KEY_REC_LEAN
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, false)
            sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        refreshRides()
        updateRecUi()
    }

    private fun updateRecUi() {
        val s = TrackRecorder.session
        val chip = findViewById<TextView>(R.id.tvRecStatus)
        chip.text = when {
            s == null -> Tx.t("● BEREIT", "● READY")
            s.paused -> Tx.t("● PAUSE", "● PAUSED")
            else -> Tx.t("● LÄUFT", "● RECORDING")
        }
        chip.setTextColor(if (s == null) 0xFFA8A8B0.toInt() else 0xFFFF7A00.toInt())
        chip.setBackgroundResource(if (s == null) R.drawable.bg_chip_idle else R.drawable.bg_chip_live)
        val recCard = findViewById<MaterialCardView>(R.id.cardRec)
        recCard.strokeColor = ContextCompat.getColor(this, R.color.accent)
        recCard.strokeWidth = if (s == null) 0 else (2 * resources.displayMetrics.density).toInt()
        val live = findViewById<TextView>(R.id.tvRecLive)
        if (s == null) {
            live.text = RideFormat.duration(0L) + " · " + RideFormat.distance(0)
        } else {
            val st = s.stats()
            val kmh = s.points.lastOrNull()?.speedKmh?.toInt() ?: 0
            live.text = RideFormat.duration(st.movingMs) + Tx.t(" in Bewegung · ", " moving · ") + RideFormat.distance(st.distanceM) + " · " + kmh + " km/h"
        }
        findViewById<MaterialButton>(R.id.btnRec).text = if (s == null) Tx.t("Aufzeichnung starten", "Start recording") else Tx.t("Beenden", "Stop")
        for (id in listOf(R.id.swRecAlt, R.id.swRecLimit, R.id.swRecLean)) findViewById<View>(id).isEnabled = s == null
        findViewById<View>(R.id.tvRecBanner).visibility = if (s != null) View.VISIBLE else View.GONE
        updateRecLive(s)
    }

    /** Kleine Live-Werte unter den Schaltern, solange aufgenommen wird und der Schalter an ist. */
    private fun updateRecLive(s: TrackSession?) {
        fun show(sw: Int, tv: Int, text: () -> String) {
            val t = findViewById<TextView>(tv)
            val on = s != null && findViewById<MaterialSwitch>(sw).isChecked
            t.visibility = if (on) View.VISIBLE else View.GONE
            if (on) t.text = text()
        }
        val st = s?.stats()
        val last = s?.points?.lastOrNull()
        show(R.id.swRecAlt, R.id.tvRecAltLive) {
            (last?.ele?.let { Tx.t("Jetzt ", "Now ") + Math.round(it) + " m" } ?: Tx.t("Noch keine Höhe", "No altitude yet")) +
                Tx.t(" · Aufstieg ", " · ascent ") + (st?.ascentM ?: 0) + Tx.t(" m · Abstieg ", " m · descent ") + (st?.descentM ?: 0) + " m"
        }
        show(R.id.swRecLimit, R.id.tvRecLimitLive) {
            (if ((last?.limitKmh ?: 0) > 0) "Limit " + last?.limitKmh + " km/h" else Tx.t("Limit unbekannt", "Limit unknown")) +
                Tx.t(" · bisher ", " · so far ") + (st?.overCount ?: 0) + Tx.t(" Mal überschritten", " times exceeded")
        }
        show(R.id.swRecLean, R.id.tvRecLeanLive) {
            val l = last?.leanDeg
            (if (l == null) Tx.t("Unter 15 km/h keine Schätzung", "No estimate below 15 km/h")
            else Tx.t("Jetzt ", "Now ") + Math.abs(Math.round(l)) + "°" + (if (Math.round(l) > 0) Tx.t(" rechts", " right") else if (Math.round(l) < 0) Tx.t(" links", " left") else "")) +
                Tx.t(" · max. ", " · max ") + (st?.maxLeanDeg ?: 0) + "°"
        }
    }

    private fun onRecClicked() {
        if (!TrackRecorder.active) {
            if (offerUnfinishedRide()) return
            if (!BridgeService.running) {
                Toast.makeText(this, Tx.t("Erst auf der Übersicht die App einschalten", "First switch the app on in the Overview"), Toast.LENGTH_LONG).show()
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, Tx.t("Ohne Standort-Erlaubnis geht keine Aufzeichnung", "Recording needs location permission"), Toast.LENGTH_LONG).show()
                return
            }
            TrackRecorder.start(this)
            BridgeService.send(this, BridgeService.ACTION_CONFIG)
            updateRecUi()
        } else {
            val points = TrackRecorder.finish()
            updateRecUi()
            showSaveDialog(points, Tx.t("Fahrt beenden", "End ride"))
        }
    }

    /** Gibt true zurück, wenn eine unterbrochene Fahrt gefunden wurde und der Dialog dazu erscheint. */
    private fun offerUnfinishedRide(): Boolean {
        if (TrackRecorder.active || saveDialogs > 0) return saveDialogs > 0
        val points = RideStore.unfinished(this)
        if (points.size < 2) {
            if (points.isNotEmpty()) RideStore.discardUnfinished(this)
            return false
        }
        showSaveDialog(points, Tx.t("Unterbrochene Fahrt gefunden", "Interrupted ride found"))
        return true
    }

    private fun showSaveDialog(points: List<io.github.th3s1nc.osmandhudbridge.track.TrackPoint>, title: String) {
        val st = TrackSession().also { it.restore(points) }.stats()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = statsText(st)
        })
        val input = EditText(this).apply {
            setSingleLine()
            setText(RideStore.defaultName(st.startMs))
            hint = Tx.t("Name der Fahrt", "Name of the ride")
        }
        box.addView(input)
        saveDialogs++
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(Tx.t("Speichern", "Save")) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { RideStore.defaultName(st.startMs) }
                val ride = RideStore.save(this, name, points)
                if (ride == null) {
                    Toast.makeText(this, Tx.t("Zu kurz zum Speichern", "Too short to save"), Toast.LENGTH_LONG).show()
                    showSaveDialog(points, title)
                } else {
                    Toast.makeText(
                        this,
                        if (ride.publicUri != null) Tx.t("Gespeichert, Kopie in Download/GPX-Tracking", "Saved, copy in Download/GPX-Tracking") else Tx.t("Gespeichert (nur in der App)", "Saved (in the app only)"),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshRides()
                }
            }
            .setNegativeButton(Tx.t("Verwerfen", "Discard")) { _, _ -> confirmDiscard(points, title) }
            .setNeutralButton(Tx.t("Weiter aufzeichnen", "Keep recording")) { _, _ ->
                if (BridgeService.running) {
                    TrackRecorder.resume(this, points)
                    BridgeService.send(this, BridgeService.ACTION_CONFIG)
                    updateRecUi()
                } else {
                    Toast.makeText(this, Tx.t("Erst auf der Übersicht die App einschalten", "First switch the app on in the Overview"), Toast.LENGTH_LONG).show()
                    showSaveDialog(points, title)
                }
            }
            .setOnDismissListener { saveDialogs-- }
            .show()
    }

    private fun confirmDiscard(points: List<io.github.th3s1nc.osmandhudbridge.track.TrackPoint>, title: String) {
        saveDialogs++
        AlertDialog.Builder(this)
            .setTitle(Tx.t("Fahrt wirklich verwerfen?", "Really discard the ride?"))
            .setMessage(Tx.t("Die Aufzeichnung ist danach unwiderruflich weg.", "The recording will be gone for good."))
            .setCancelable(false)
            .setPositiveButton(Tx.t("Ja, verwerfen", "Yes, discard")) { _, _ -> RideStore.discardUnfinished(this) }
            .setNegativeButton(Tx.t("Nein", "No")) { _, _ -> showSaveDialog(points, title) }
            .setOnDismissListener { saveDialogs-- }
            .show()
    }

    private var seasonYear: Int? = null

    /** Saison-Karte oben in "Meine Fahrten": Kennzahlen, Überschreitungen und Strecke je Monat, mit Pfeilen durch die Jahre. */
    private fun refreshSeason(list: List<Ride>) {
        val seasonOn = prefs.getBoolean(BridgeService.KEY_SEASON_ON, false)
        val from = prefs.getInt(BridgeService.KEY_SEASON_FROM, 3)
        val to = prefs.getInt(BridgeService.KEY_SEASON_TO, 10)
        val stats = list.map { it.stats }
        val cur = SeasonSummary.currentYear(java.time.LocalDate.now(), seasonOn, from, to)
        val years = SeasonSummary.years(stats, cur, seasonOn, from, to)
        val year = (seasonYear ?: cur).let { if (it in years) it else cur }
        seasonYear = year
        val idx = years.indexOf(year)
        val sum = SeasonSummary.summarize(stats, SeasonSummary.period(year, seasonOn, from, to))
        findViewById<TextView>(R.id.tvSeasonTitle).text = (if (seasonOn) "Saison " else "Jahr ") + sum.period.label
        val prev = findViewById<TextView>(R.id.btnSeasonPrev)
        val next = findViewById<TextView>(R.id.btnSeasonNext)
        prev.alpha = if (idx > 0) 1f else 0.25f
        next.alpha = if (idx < years.size - 1) 1f else 0.25f
        prev.setOnClickListener { if (idx > 0) { seasonYear = years[idx - 1]; refreshSeason(list) } }
        next.setOnClickListener { if (idx < years.size - 1) { seasonYear = years[idx + 1]; refreshSeason(list) } }
        findViewById<TextView>(R.id.tvSeasonRides).text = sum.rides.toString()
        findViewById<TextView>(R.id.tvSeasonDist).text = String.format(Tx.loc(), "%,.0f km", sum.distanceM / 1000.0)
        findViewById<TextView>(R.id.tvSeasonTime).text = RideFormat.duration(sum.movingMs)
        findViewById<TextView>(R.id.tvSeasonMax).text = if (sum.rides == 0) "–" else "${sum.maxKmh} km/h"
        findViewById<TextView>(R.id.tvSeasonOver).text = when {
            sum.rides == 0 -> Tx.t("Keine Fahrten in dieser Saison.", "No rides this season.")
            sum.ridesWithLimit == 0 -> Tx.t("Überschreitungen: keine Tempolimit-Daten aufgezeichnet.", "Overspeeding: no speed limit data recorded.")
            else -> Tx.t("Zu schnell: ${sum.overCount} Mal in ${sum.ridesWithOver} Fahrten", "Too fast: ${sum.overCount} times in ${sum.ridesWithOver} rides")
        }
        val bars = findViewById<LinearLayout>(R.id.seasonBars)
        bars.removeAllViews()
        val maxM = (sum.months.maxOfOrNull { it.distanceM } ?: 0L).coerceAtLeast(1L)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val best = sum.months.maxByOrNull { it.distanceM }?.takeIf { it.distanceM > 0 }
        for (mo in sum.months) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
            val h = if (mo.distanceM == 0L) dp(2) else (dp(70) * mo.distanceM / maxM).toInt().coerceAtLeast(dp(3))
            val holder = LinearLayout(this).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL }
            holder.addView(View(this).apply {
                setBackgroundColor(if (mo === best) accent else (accent and 0x00FFFFFF) or 0x66000000)
            }, LinearLayout.LayoutParams(dp(14), h))
            col.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            col.addView(TextView(this).apply {
                text = java.time.format.TextStyle.SHORT.let { mo.ym.month.getDisplayName(it, java.util.Locale.GERMANY) }.take(3).removeSuffix(".")
                textSize = 9f
                alpha = 0.7f
                gravity = android.view.Gravity.CENTER
            })
            bars.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refreshRides() {
        val list = RideStore.list(this)
        refreshSeason(list)
        val box = findViewById<LinearLayout>(R.id.listRides)
        box.removeAllViews()
        findViewById<View>(R.id.tvRidesEmpty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val bg = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        for (r in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setBackgroundResource(bg)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, RideDetailActivity::class.java).putExtra(RideDetailActivity.EXTRA_ID, r.id)) }
            }
            row.addView(TextView(this).apply {
                text = r.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = RideStore.dateText(r.stats.startMs) + " · " + RideFormat.distance(r.stats.distanceM) + " · " + RideFormat.duration(r.stats.movingMs)
                textSize = 13f
                alpha = 0.7f
            })
            box.addView(row)
        }
    }

    // ---------------- Werkzeuge ----------------

    private fun command(action: String) {
        if (!BridgeService.running) {
            Toast.makeText(this, Tx.t("Erst den Dienst starten", "Start the service first"), Toast.LENGTH_SHORT).show()
            return
        }
        BridgeService.send(this, action)
    }

    private var permSyncing = false

    private fun notifAccessGranted(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun batteryIgnored(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    /** Setzt die beiden Schalter auf den echten Zustand der Berechtigungen. */
    private fun syncPermSwitches() {
        val sn = findViewById<MaterialSwitch>(R.id.swNotifAccess)
        val sb = findViewById<MaterialSwitch>(R.id.swBattery)
        permSyncing = true
        try {
            val n = try { notifAccessGranted() } catch (_: Exception) { false }
            if (sn.isChecked != n) sn.isChecked = n
            val b = try { batteryIgnored() } catch (_: Exception) { false }
            if (sb.isChecked != b) sb.isChecked = b
        } finally { permSyncing = false }
    }

    private fun batteryExemption() {
        if (batteryIgnored()) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun shareLog() {
        val f = BridgeBus.logFile()
        if (f == null) {
            Toast.makeText(this, Tx.t("Noch kein Log vorhanden", "No log yet"), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, Tx.t("Log teilen", "Share log")))
    }
}
