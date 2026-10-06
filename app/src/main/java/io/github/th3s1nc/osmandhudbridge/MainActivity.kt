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
import io.github.th3s1nc.osmandhudbridge.track.TrackRecorder
import io.github.th3s1nc.osmandhudbridge.track.TrackSession

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
        setupSettings()
        setupTools()
        setupInfo()
        setupTracking()
    }

    override fun onStart() {
        super.onStart()
        BridgeBus.onChange = { refresh() }
        autoConnect()
        refresh()
        trackHandler.post(trackTick)
        refreshRides()
        offerUnfinishedRide()
    }

    override fun onStop() {
        BridgeBus.onChange = null
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
    }

    /** Kleines graues i: klappt die Beschreibung zum Schalter auf und zu. */
    private fun setupInfoToggle(buttonId: Int, vararg textIds: Int) {
        findViewById<View>(buttonId).setOnClickListener {
            val show = findViewById<View>(textIds[0]).visibility != View.VISIBLE
            for (id in textIds) findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /** i-Knöpfe auf den Seiten Tempolimit, Werkzeuge und Tracking. */
    private fun setupInfoButtons() {
        setupInfoToggle(R.id.btnInfoWarn, R.id.tvInfoWarn)
        setupInfoToggle(R.id.btnInfoSound, R.id.tvInfoSound)
        setupInfoToggle(R.id.btnInfoLimitOsm, R.id.tvInfoLimitOsm)
        setupInfoToggle(R.id.btnInfoTags, R.id.tvInfoTags)
        setupInfoToggle(R.id.btnInfoGuess, R.id.tvInfoGuess)
        setupInfoToggle(R.id.btnInfoSigns, R.id.tvInfoSigns)
        setupInfoToggle(R.id.btnInfoNeighbors, R.id.tvInfoNeighbors)
        setupInfoToggle(R.id.btnInfoPreMobile, R.id.tvInfoPreMobile)
        setupInfoToggle(R.id.btnInfoPreBg, R.id.tvInfoPreBg)
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
        val names = listOf("Kurz", "Normal", "Lang", "Immer")
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
        fun warnText(v: Int) { txtWarn.text = "Überschreitung: $v km/h" }
        val tol = prefs.getInt(BridgeService.KEY_WARN_TOL, BridgeService.DEFAULT_WARN_TOL).coerceIn(0, 30)
        sldWarn.value = tol.toFloat()
        warnText(tol)
        swWarn.isChecked = prefs.getBoolean(BridgeService.KEY_WARN, true)
        sldWarn.isEnabled = swWarn.isChecked
        val swWarnSound = findViewById<MaterialSwitch>(R.id.chkWarnSound)
        swWarnSound.isChecked = prefs.getBoolean(BridgeService.KEY_WARN_SOUND, false)
        swWarnSound.isEnabled = swWarn.isChecked
        swWarnSound.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_WARN_SOUND, on).apply()
            applyConfigIfRunning()
        }
        swWarn.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_WARN, on).apply()
            sldWarn.isEnabled = on
            swWarnSound.isEnabled = on
            applyConfigIfRunning()
        }
        sldWarn.addOnChangeListener { _, value, fromUser ->
            warnText(value.toInt())
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_WARN_TOL, value.toInt()).apply()
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
                Toast.makeText(this, "Bitte zuerst das HUD verbinden (Übersicht, Start).", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "OSMAnd ist nicht installiert", Toast.LENGTH_SHORT).show()
    }

    private fun applyConfigIfRunning() {
        if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_CONFIG)
    }

    private val pickGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importGpx(uri)
    }

    /** GPX-Datei lesen, Kacheln entlang der Strecke merken und das Laden anstoßen. */
    private fun importGpx(uri: Uri) {
        val app = applicationContext
        Thread {
            val msg = try {
                val text = contentResolver.openInputStream(uri)?.use { s ->
                    val b = s.readBytes()
                    if (b.size > 30_000_000) null else String(b, Charsets.UTF_8)
                }
                val route = text?.let { io.github.th3s1nc.osmandhudbridge.limit.GpxParser.parse(it) }
                if (route == null) {
                    "Keine Strecke in der Datei gefunden (erwartet: GPX mit Track oder Route)."
                } else {
                    val tiles = io.github.th3s1nc.osmandhudbridge.limit.PreloadPlanner.route(route.points, if (route.sparse) 3000.0 else 1500.0)
                    val store = io.github.th3s1nc.osmandhudbridge.limit.TourStore(
                        java.io.File(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(app).parentFile, "tours")
                    )
                    store.add(route.name, tiles)
                    BridgeBus.log("Tour importiert: ${route.name}, ${route.points.size} Punkte, ${tiles.size} Kacheln")
                    io.github.th3s1nc.osmandhudbridge.limit.PreloadWorker.runTours(app)
                    "Tour „${route.name}“: ${tiles.size} Kacheln" + (if (route.sparse) ". Hinweis: wenige Punkte in der Datei, der Rand wurde auf 3 km verbreitert." else "")
                }
            } catch (e: Exception) {
                "Datei konnte nicht gelesen werden (${e.message ?: e.javaClass.simpleName})."
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                toursStatsAt = 0L
            }
        }.start()
    }

    private var toursStatsAt = 0L
    private var toursBusy = false
    private var toursText = "Keine Tour in Arbeit"
    private var toursStatus: List<io.github.th3s1nc.osmandhudbridge.limit.TourStore.Status> = emptyList()

    /** Stand der Touren höchstens alle 4 s im Hintergrund zählen. */
    private fun updateTours() {
        findViewById<TextView>(R.id.tvTours).text = toursText
        val now = android.os.SystemClock.elapsedRealtime()
        if (toursBusy || (toursStatsAt != 0L && now - toursStatsAt < 4_000L)) return
        toursBusy = true
        toursStatsAt = now
        val dir = io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext)
        Thread {
            val st = try {
                val cache = io.github.th3s1nc.osmandhudbridge.limit.TileCache(dir)
                io.github.th3s1nc.osmandhudbridge.limit.TourStore(java.io.File(dir.parentFile, "tours")).status(cache, System.currentTimeMillis())
            } catch (_: Exception) { emptyList() }
            val txt = if (st.isEmpty()) "Keine Tour in Arbeit"
                else st.joinToString("\n") { "${it.name}: ${it.total - it.missing} von ${it.total} Kacheln" }
            runOnUiThread {
                val changed = txt != toursText
                toursText = txt
                toursStatus = st
                toursBusy = false
                if (changed) refresh() // Live-Zeilen sofort nachziehen (updateTours ist auf 4 s gedrosselt, das ergibt keine Schleife)
            }
        }.start()
    }

    private val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    private fun dateText(d: java.time.LocalDate) = "${d.dayOfMonth}. ${monthNames[d.monthValue - 1]}"

    /** Karte "Saison" (Werkzeuge): Schalter, Beginn- und Ende-Monat, Vorlauf. */
    private fun setupSeason() {
        val sw = findViewById<MaterialSwitch>(R.id.swSeason)
        val box = findViewById<View>(R.id.seasonBox)
        val btnFrom = findViewById<MaterialButton>(R.id.btnSeasonFrom)
        val btnTo = findViewById<MaterialButton>(R.id.btnSeasonTo)
        val sld = findViewById<Slider>(R.id.sldSeasonLead)
        val tvLead = findViewById<TextView>(R.id.tvSeasonLead)
        fun leadText(w: Int) = when (w) { 0 -> "nicht vorher"; 1 -> "1 Woche vorher"; else -> "$w Wochen vorher" }
        fun ui() {
            box.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
            btnFrom.text = "Beginn: " + monthNames[prefs.getInt(BridgeService.KEY_SEASON_FROM, 3).coerceIn(1, 12) - 1]
            btnTo.text = "Ende: " + monthNames[prefs.getInt(BridgeService.KEY_SEASON_TO, 10).coerceIn(1, 12) - 1]
            tvLead.text = leadText(sld.value.toInt())
        }
        fun changed() {
            io.github.th3s1nc.osmandhudbridge.limit.PreloadWorker.apply(this)
            if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_CONFIG)
            refresh()
        }
        sw.isChecked = prefs.getBoolean(BridgeService.KEY_SEASON_ON, false)
        sld.value = prefs.getInt(BridgeService.KEY_SEASON_LEAD, 3).coerceIn(0, 6).toFloat()
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
                .setNegativeButton("Abbrechen", null)
                .show()
        }
        btnFrom.setOnClickListener { pickMonth("Saisonbeginn (erster Tag des Monats)", BridgeService.KEY_SEASON_FROM, 3) }
        btnTo.setOnClickListener { pickMonth("Saisonende (letzter Tag des Monats)", BridgeService.KEY_SEASON_TO, 10) }
        sld.addOnChangeListener { _, value, fromUser ->
            tvLead.text = leadText(value.toInt())
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_SEASON_LEAD, value.toInt()).apply()
                changed()
            }
        }
    }

    /** Live-Karte: eine Zeile pro offenem Punkt (Touren, dann Umkreis). Fertiges verschwindet. */
    private fun updateLiveLoad(preloadText: String): Boolean {
        val box = findViewById<android.widget.LinearLayout>(R.id.liveLoadRows)
        val rows = ArrayList<Pair<String, String>>()
        for (t in toursStatus) rows += "Straßendaten Tour \u201e${t.name}\u201c" to "${t.total - t.missing} von ${t.total} Kacheln"
        val circleDone = preloadText.startsWith("Fertig") || preloadText == "Aus" || preloadText == "–" || preloadText == "-" ||
            preloadText.startsWith("Gerade aus") || preloadText.startsWith("Hintergrund: startet")
        if (!circleDone) {
            var v = preloadText.removePrefix("Hintergrund: ").removePrefix("Vorgeladen: ")
            if (toursStatus.isNotEmpty()) v += " (wartet auf Tour)"
            rows += "Straßendaten Umkreis" to v
        }
        val sig = rows.joinToString("|") { it.first + "=" + it.second }
        if (box.tag == sig) return rows.isNotEmpty()
        box.tag = sig
        box.removeAllViews()
        val gap = (12 * resources.displayMetrics.density).toInt()
        for ((label, value) in rows) {
            box.addView(TextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                text = label
                setPadding(0, gap, 0, 0)
            })
            box.addView(TextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setTextColor(0xFFFF7A00.toInt())
                text = value
            })
        }
        return rows.isNotEmpty()
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

        val swLimit = findViewById<MaterialSwitch>(R.id.chkLimit)
        swLimit.isChecked = prefs.getBoolean(BridgeService.KEY_OSM_LIMIT, true)
        swLimit.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_OSM_LIMIT, checked).apply()
            applyConfigIfRunning()
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
            Triple(R.id.swPreloadMobile, BridgeService.KEY_PRELOAD_MOBILE, false),
            Triple(R.id.swPreloadBg, BridgeService.KEY_PRELOAD_BG, false),
            Triple(R.id.swVerbose, BridgeService.KEY_VERBOSE, false)
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, def)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (key == BridgeService.KEY_VERBOSE) BridgeBus.verbose = checked
                applyConfigIfRunning()
                if (key == BridgeService.KEY_PRELOAD_BG || key == BridgeService.KEY_PRELOAD_MOBILE) {
                    io.github.th3s1nc.osmandhudbridge.limit.PreloadWorker.apply(this)
                }
            }
        }
        io.github.th3s1nc.osmandhudbridge.limit.PreloadWorker.apply(this)

        val preloadValues = listOf(0, 10, 25, 50)
        val sldPre = findViewById<Slider>(R.id.sldPreload)
        val tvPreName = findViewById<TextView>(R.id.tvPreloadName)
        fun preUi(i: Int) { tvPreName.text = if (preloadValues[i] == 0) "Aus" else "${preloadValues[i]} km" }
        val preIdx = preloadValues.indexOf(prefs.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM))
            .takeIf { it >= 0 } ?: 2
        sldPre.value = preIdx.toFloat()
        preUi(preIdx)
        sldPre.addOnChangeListener { _, value, fromUser ->
            val i = value.toInt().coerceIn(0, 3)
            preUi(i)
            if (fromUser) {
                prefs.edit().putInt(BridgeService.KEY_PRELOAD_KM, preloadValues[i]).apply()
                applyConfigIfRunning()
                io.github.th3s1nc.osmandhudbridge.limit.PreloadWorker.apply(this)
            }
        }

        setupSeason()

        val cacheValues = listOf(256, 512, 1024, 2048)
        val sldCache = findViewById<Slider>(R.id.sldCache)
        val tvCacheName = findViewById<TextView>(R.id.tvCacheName)
        fun cacheUi(i: Int) {
            val mb = cacheValues[i]
            tvCacheName.text = if (mb < 1024) (if (mb == 256) "0,25 GB" else "0,5 GB") else "${mb / 1024} GB"
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

        findViewById<MaterialButton>(R.id.btnTourPick).setOnClickListener {
            try { pickGpx.launch(arrayOf("*/*")) } catch (_: Exception) { Toast.makeText(this, "Keine Dateiauswahl verfügbar", Toast.LENGTH_LONG).show() }
        }
        findViewById<MaterialButton>(R.id.btnTourClear).setOnClickListener {
            io.github.th3s1nc.osmandhudbridge.limit.TourStore(
                java.io.File(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext).parentFile, "tours")
            ).clear()
            toursText = "Keine Tour in Arbeit"
            toursStatsAt = 0L
            Toast.makeText(this, "Touren in Arbeit entfernt (gespeicherte Kacheln bleiben)", Toast.LENGTH_SHORT).show()
        }

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
        findViewById<android.widget.TextView>(R.id.tvVersion).text = "Version ${version ?: "?"} · inoffiziell, privat und ohne Gewähr"
        findViewById<android.widget.TextView>(R.id.tvAbout).text = getString(R.string.about_intro)
        findViewById<android.widget.TextView>(R.id.tvLiability).text = getString(R.string.about_liability)
        findViewById<android.widget.TextView>(R.id.tvPrivacy).text = getString(R.string.about_privacy)
        findViewById<android.widget.TextView>(R.id.tvLicenses).text = getString(R.string.about_licenses)
    }

    private fun setupTools() {
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { command(BridgeService.ACTION_CLEAR) }
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
            Toast.makeText(this, "Log gelöscht", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Anzeige ----------------

    private var cacheStatsAt = 0L
    private var cacheStatsBusy = false
    private var cacheStatsText = "Belegt: –"

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
            val txt = "Belegt: " + (if (mb >= 1024) String.format(java.util.Locale.GERMANY, "%.1f GB", mb / 1024) else String.format(java.util.Locale.GERMANY, "%.0f MB", mb)) +
                ", $n Kacheln"
            runOnUiThread { cacheStatsText = txt; cacheStatsBusy = false }
        }.start()
    }

    /** Kurzer Status für die Live-Karte, Einzelheiten stehen im Protokoll. */
    private fun osmStatus(raw: String): String = when {
        raw.contains("aus") && raw.startsWith("OSMAnd: aus") -> "Aus"
        raw.contains("nicht freigegeben") -> "Nicht freigegeben (siehe Protokoll)"
        raw.contains("Anmeldung fehlgeschlagen") -> "Anmeldung fehlgeschlagen"
        raw.contains("verbunden (") || raw.contains("liefert nichts") || raw.contains("Ansagen nicht verfügbar") -> "Verbunden"
        raw.contains("verbinde") -> "Verbinde …"
        raw.contains("verloren") || raw.contains("Binding beendet") || raw.contains("nicht erreichbar") || raw.contains("keine Antwort") -> "Nicht verbunden"
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
        tv.text = if (granted) "Benachrichtigungen kommen nicht an: Tempo, Restzeit und Ankunft aus OSMAnd fehlen. Hier tippen, dann den Zugriff für diese App aus- und wieder einschalten."
        else "Benachrichtigungszugriff fehlt: Tempo, Restzeit und Ankunft aus OSMAnd fehlen. Hier tippen und den Zugriff erlauben."
        tv.visibility = View.VISIBLE
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
                io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON -> "Gerade ist Saison, die App läuft normal."
                io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.LEAD -> "Vor der Saison: Straßendaten werden geladen, Saisonbeginn am " + (plan.nextStart(today)?.let { dateText(it) } ?: "–") + "."
                else -> "Die App ruht. Laden ab " + (plan.loadStart(today)?.let { dateText(it) } ?: "–") + ", Saisonbeginn am " + (plan.nextStart(today)?.let { dateText(it) } ?: "–") + "."
            }
        }
        val preloadText = when {
            BridgeService.running -> BridgeBus.preload
            prefs.getBoolean(BridgeService.KEY_PRELOAD_BG, false) && prefs.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM) > 0 ->
                if (BridgeBus.preload != "–" && BridgeBus.preload != "-") BridgeBus.preload
                else "Hintergrund: startet, sobald WLAN da ist (Android bestimmt den Zeitpunkt)"
            else -> "Gerade aus (Vorladen läuft, sobald die App aktiv ist)"
        }
        findViewById<TextView>(R.id.tvPreloadStatus).text = preloadText
        val loading = updateLiveLoad(preloadText)
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
            !enabled -> "HUD frei für die Tilsberk-App"
            !inSeason -> {
                val start = BridgeService.seasonPlan(prefs).nextStart(java.time.LocalDate.now())
                "Start am " + (start?.let { dateText(it) } ?: "–") +
                    if (season == io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.LEAD) " · Straßendaten laden" else ""
            }
            !hudOn -> if (running && loading) "Straßendaten laden" else ""
            running && failed -> hud.replaceFirstChar { it.uppercase() }
            running && hud.contains("bereit") -> "Läuft auch bei Display aus"
            running -> ""
            else -> "HUD verbinden einschalten"
        }
        tvServiceHint.visibility = if (tvServiceHint.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateCacheUsed()
        updateTours()
        syncPermSwitches()
        val swJust = findViewById<MaterialSwitch>(R.id.swJustage)
        val justOn = prefs.getBoolean(BridgeService.KEY_JUSTAGE, false)
        if (swJust.isChecked != justOn) { justageSyncing = true; swJust.isChecked = justOn; justageSyncing = false }
        val swService = findViewById<MaterialSwitch>(R.id.swService)
        if (swService.isChecked != hudOn) { serviceSyncing = true; swService.isChecked = hudOn; serviceSyncing = false }
        fun plain(t: String) = t.substringAfter(": ", t)
        tvGps.text = plain(BridgeBus.gps)
        tvLimit.text = plain(BridgeBus.limit)
        tvOsm.text = osmStatus(BridgeBus.osm)
        val limRaw = BridgeBus.limit
        val limKmh = Regex("(\\d+) km/h").find(limRaw)?.groupValues?.get(1)
        val tvSignValue = findViewById<TextView>(R.id.tvSignValue)
        tvSignValue.text = limKmh ?: "–"
        findViewById<TextView>(R.id.tvSignSource).text = when {
            limKmh == null -> if (limRaw.contains("unbekannt")) "Unbekannt" else plain(limRaw)
            limRaw.contains("geschätzt") -> "Geschätzt, nicht aus den Kartendaten"
            else -> "Aus den Kartendaten (OSM)"
        }
        val spd = Regex("Tempo: (\\d+) km/h").find(BridgeBus.gps)?.groupValues?.get(1)
        findViewById<TextView>(R.id.tvSignSpeed).text = "Tempo: " + (spd?.let { "$it km/h" } ?: "–")
        findViewById<TextView>(R.id.tvLogHome).text = BridgeBus.lastLinesNewestFirst(3).ifBlank { "–" }
        tvLog.text = BridgeBus.lastLinesNewestFirst(25)
    }

    // ---------------- Start ----------------

    /** Startet den Dienst von selbst, solange die App offen ist (HudClient verbindet, sobald das HUD an ist). */
    private fun autoConnect() {
        if (!prefs.getBoolean(BridgeService.KEY_ENABLED, true)) return
        if (BridgeService.running || BridgeService.userStopped) return
        if (BridgeService.seasonState(prefs) != io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State.IN_SEASON) return
        val hudOn = BridgeService.hudWanted(prefs) && prefs.getString(BridgeService.KEY_ADDR, null) != null
        val loadOn = prefs.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM) > 0 || toursExist()
        if (!hudOn && !loadOn) return
        val needed = buildList {
            if (hudOn && Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) return
        if (hudOn) pickDeviceAndStart() else BridgeService.send(this)
    }

    private fun toursExist(): Boolean = try {
        java.io.File(io.github.th3s1nc.osmandhudbridge.limit.TileStore.dir(applicationContext).parentFile, "tours")
            .listFiles { f -> f.name.endsWith(".tour") }?.isNotEmpty() == true
    } catch (_: Exception) { false }

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
            Toast.makeText(this, "Bluetooth-Berechtigung wird benötigt", Toast.LENGTH_LONG).show()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth ist ausgeschaltet", Toast.LENGTH_LONG).show()
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
                    .setTitle("Kein HUD gefunden")
                    .setMessage("Schalte das HUD ein und koppel es in den Android-Bluetooth-Einstellungen. Es heißt meist \"TILS\" oder \"TILSBERK Head-Up Display\".")
                    .setPositiveButton("OK", null)
                if (bonded.isNotEmpty()) b.setNeutralButton("Alle gekoppelten Geräte zeigen") { _, _ -> pickDevice(bonded, true) }
                b.show()
                // ohne HUD trotzdem Straßendaten laden, wenn es etwas zu laden gibt
                if (prefs.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM) > 0 || toursExist()) BridgeService.send(this)
            }
            huds.size == 1 -> begin(huds[0])
            else -> pickDevice(huds)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice(list: List<BluetoothDevice>, manual: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle("Welches HUD?")
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
            trackHandler.postDelayed(this, 1000)
        }
    }
    private var saveDialogs = 0 // offene Speichern-Dialoge

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Kennzahlen als Text; Zeilen für Höhe, Limit und Schräglage nur, wenn es Werte dazu gibt. */
    private fun statsText(st: io.github.th3s1nc.osmandhudbridge.track.RideStats): String {
        val sb = StringBuilder()
        sb.append("Strecke: ").append(RideFormat.distance(st.distanceM))
        sb.append("\nIn Bewegung: ").append(RideFormat.duration(st.movingMs))
        sb.append("\nGesamtzeit: ").append(RideFormat.duration(st.totalMs))
        sb.append("\nØ Tempo: ").append(st.avgKmh).append(" km/h")
        sb.append("\nHöchstes Tempo: ").append(st.maxKmh).append(" km/h")
        if (st.hasEle) sb.append("\nAufstieg: ").append(st.ascentM).append(" m, Abstieg: ").append(st.descentM).append(" m")
        if (st.hasLimit) sb.append("\nTempolimit überschritten: ").append(st.overCount).append(" Mal")
        if (st.hasLean) sb.append("\nMax. Schräglage: ").append(st.maxLeanDeg).append("° (Schätzung)")
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
            s == null -> "● BEREIT"
            s.paused -> "● PAUSE"
            else -> "● LÄUFT"
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
            live.text = RideFormat.duration(st.movingMs) + " in Bewegung · " + RideFormat.distance(st.distanceM) + " · " + kmh + " km/h"
        }
        findViewById<MaterialButton>(R.id.btnRec).text = if (s == null) "Aufzeichnung starten" else "Beenden"
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
            (last?.ele?.let { "Jetzt " + Math.round(it) + " m" } ?: "Noch keine Höhe") +
                " · Aufstieg " + (st?.ascentM ?: 0) + " m · Abstieg " + (st?.descentM ?: 0) + " m"
        }
        show(R.id.swRecLimit, R.id.tvRecLimitLive) {
            (if ((last?.limitKmh ?: 0) > 0) "Limit " + last?.limitKmh + " km/h" else "Limit unbekannt") +
                " · bisher " + (st?.overCount ?: 0) + " Mal überschritten"
        }
        show(R.id.swRecLean, R.id.tvRecLeanLive) {
            val l = last?.leanDeg
            (if (l == null) "Unter 15 km/h keine Schätzung"
            else "Jetzt " + Math.abs(Math.round(l)) + "°" + (if (Math.round(l) > 0) " rechts" else if (Math.round(l) < 0) " links" else "")) +
                " · max. " + (st?.maxLeanDeg ?: 0) + "°"
        }
    }

    private fun onRecClicked() {
        if (!TrackRecorder.active) {
            if (offerUnfinishedRide()) return
            if (!BridgeService.running) {
                Toast.makeText(this, "Erst auf der Übersicht die App einschalten", Toast.LENGTH_LONG).show()
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Ohne Standort-Erlaubnis geht keine Aufzeichnung", Toast.LENGTH_LONG).show()
                return
            }
            TrackRecorder.start(this)
            BridgeService.send(this, BridgeService.ACTION_CONFIG)
            updateRecUi()
        } else {
            val points = TrackRecorder.finish()
            updateRecUi()
            showSaveDialog(points, "Fahrt beenden")
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
        showSaveDialog(points, "Unterbrochene Fahrt gefunden")
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
            hint = "Name der Fahrt"
        }
        box.addView(input)
        saveDialogs++
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { RideStore.defaultName(st.startMs) }
                val ride = RideStore.save(this, name, points)
                if (ride == null) {
                    Toast.makeText(this, "Zu kurz zum Speichern", Toast.LENGTH_LONG).show()
                    showSaveDialog(points, title)
                } else {
                    Toast.makeText(
                        this,
                        if (ride.publicUri != null) "Gespeichert, Kopie in Download/GPX-Tracking" else "Gespeichert (nur in der App)",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshRides()
                }
            }
            .setNegativeButton("Verwerfen") { _, _ -> confirmDiscard(points, title) }
            .setNeutralButton("Weiter aufzeichnen") { _, _ ->
                if (BridgeService.running) {
                    TrackRecorder.resume(this, points)
                    BridgeService.send(this, BridgeService.ACTION_CONFIG)
                    updateRecUi()
                } else {
                    Toast.makeText(this, "Erst auf der Übersicht die App einschalten", Toast.LENGTH_LONG).show()
                    showSaveDialog(points, title)
                }
            }
            .setOnDismissListener { saveDialogs-- }
            .show()
    }

    private fun confirmDiscard(points: List<io.github.th3s1nc.osmandhudbridge.track.TrackPoint>, title: String) {
        saveDialogs++
        AlertDialog.Builder(this)
            .setTitle("Fahrt wirklich verwerfen?")
            .setMessage("Die Aufzeichnung ist danach unwiderruflich weg.")
            .setCancelable(false)
            .setPositiveButton("Ja, verwerfen") { _, _ -> RideStore.discardUnfinished(this) }
            .setNegativeButton("Nein") { _, _ -> showSaveDialog(points, title) }
            .setOnDismissListener { saveDialogs-- }
            .show()
    }

    private fun refreshRides() {
        val list = RideStore.list(this)
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
            Toast.makeText(this, "Erst den Dienst starten", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Noch kein Log vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Log teilen"))
    }
}
