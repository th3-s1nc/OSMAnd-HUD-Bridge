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
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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
import io.github.th3s1nc.osmandhudbridge.protocol.ThresholdMode

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
        tvServiceHint = findViewById(R.id.tvServiceHint)
        tvGps = findViewById(R.id.tvGps)
        tvLimit = findViewById(R.id.tvLimit)
        tvOsm = findViewById(R.id.tvOsm)
        tvLog = findViewById(R.id.tvLog)

        setupNavigation()
        setupHome()
        setupDisplay()
        setupSettings()
        setupTools()
        setupInfo()
    }

    override fun onStart() {
        super.onStart()
        BridgeBus.onChange = { refresh() }
        autoConnect()
        refresh()
    }

    override fun onStop() {
        BridgeBus.onChange = null
        super.onStop()
    }

    // ---------------- Aufbau ----------------

    private fun setupNavigation() {
        pages = listOf(
            findViewById(R.id.pageHome), findViewById(R.id.pageDisplay),
            findViewById(R.id.pageSettings), findViewById(R.id.pageTools), findViewById(R.id.pageInfo)
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val idx = when (item.itemId) {
                R.id.nav_display -> 1
                R.id.nav_settings -> 2
                R.id.nav_tools -> 3
                R.id.nav_info -> 4
                else -> 0
            }
            pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
            true
        }
    }

    private fun setupHome() {
        val swMaster = findViewById<MaterialSwitch>(R.id.swMaster)
        swMaster.isChecked = prefs.getBoolean(BridgeService.KEY_ENABLED, true)
        swMaster.setOnCheckedChangeListener { _, checked ->
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
            if (on) {
                BridgeService.userStopped = false
                onStartClicked()
            } else {
                BridgeService.userStopped = true
                stopService(Intent(this, BridgeService::class.java))
            }
            // Bricht der Nutzer die Auswahl ab, springt der Schalter hier wieder zurück
            swService.postDelayed({ refresh() }, 2500)
        }
    }

    private class ModeCard(val mode: DisplayMode, val card: Int, val check: Int)

    private val modeCards by lazy {
        listOf(
            ModeCard(DisplayMode.NAVIGATOR, R.id.cardNavigator, R.id.cardNavigatorCheck),
            ModeCard(DisplayMode.MINIMALIST, R.id.cardMinimalist, R.id.cardMinimalistCheck),
            ModeCard(DisplayMode.EXPLORER, R.id.cardExplorer, R.id.cardExplorerCheck),
            ModeCard(DisplayMode.CITY, R.id.cardCity, R.id.cardCityCheck)
        )
    }

    private fun setupDisplay() {
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
        swWarn.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.KEY_WARN, on).apply()
            sldWarn.isEnabled = on
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

    private fun applyConfigIfRunning() {
        if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_CONFIG)
    }

    private fun setupSettings() {
        val swOsmand = findViewById<MaterialSwitch>(R.id.chkOsmand)
        swOsmand.isChecked = prefs.getBoolean(BridgeService.KEY_OSMAND, true)
        swOsmand.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_OSMAND, checked).apply()
            applyConfigIfRunning()
        }

        for ((id, key) in listOf(
            R.id.chkNoticeCall to BridgeService.KEY_NOTICE_CALL,
            R.id.chkNoticeMsg to BridgeService.KEY_NOTICE_MSG,
            R.id.chkNoticeName to BridgeService.KEY_NOTICE_NAME
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, true)
            sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
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
            Triple(R.id.swPreloadMobile, BridgeService.KEY_PRELOAD_MOBILE, false)
        )) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, def)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                applyConfigIfRunning()
            }
        }

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
            }
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

    private fun refresh() {
        val hud = BridgeBus.hud.removePrefix("HUD: ")
        val running = BridgeService.running
        val enabled = prefs.getBoolean(BridgeService.KEY_ENABLED, true)
        tvHud.text = when {
            !enabled -> "Bridge ausgeschaltet"
            running -> hud.replaceFirstChar { it.uppercase() }
            else -> "Dienst gestoppt"
        }
        dotHud.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    !running -> R.color.status_off
                    hud.contains("bereit") -> R.color.status_ok
                    hud.contains("Fehler", true) || hud.contains("abgelehnt", true) -> R.color.status_bad
                    else -> R.color.status_warn
                }
            )
        )
        tvServiceHint.text = if (!enabled) {
            "Die App nutzt weder Bluetooth noch GPS oder Internet. Das HUD ist frei für die Tilsberk-App."
        } else if (running) {
            "Läuft auch bei ausgeschaltetem Display."
        } else {
            "Schalte \"HUD verbinden\" ein, um das HUD zu verbinden."
        }
        findViewById<TextView>(R.id.tvPreloadStatus).text = if (BridgeService.running) BridgeBus.preload else "Der Dienst läuft nicht (Start: Übersicht)"
        syncPermSwitches()
        val swJust = findViewById<MaterialSwitch>(R.id.swJustage)
        val justOn = prefs.getBoolean(BridgeService.KEY_JUSTAGE, false)
        if (swJust.isChecked != justOn) { justageSyncing = true; swJust.isChecked = justOn; justageSyncing = false }
        val swService = findViewById<MaterialSwitch>(R.id.swService)
        if (swService.isChecked != running) { serviceSyncing = true; swService.isChecked = running; serviceSyncing = false }
        fun plain(t: String) = t.substringAfter(": ", t)
        tvGps.text = plain(BridgeBus.gps)
        tvLimit.text = plain(BridgeBus.limit)
        tvOsm.text = plain(BridgeBus.osm)
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
        findViewById<TextView>(R.id.tvLogHome).text = BridgeBus.lastLines(3).ifBlank { "–" }
        tvLog.text = BridgeBus.lastLines(25)
    }

    // ---------------- Start ----------------

    /** Startet den Dienst von selbst, solange die App offen ist (HudClient verbindet, sobald das HUD an ist). */
    private fun autoConnect() {
        if (!prefs.getBoolean(BridgeService.KEY_ENABLED, true)) return
        if (BridgeService.running || BridgeService.userStopped) return
        if (prefs.getString(BridgeService.KEY_ADDR, null) == null) return
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) return
        BridgeService.send(this)
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
            Toast.makeText(this, "Bluetooth-Berechtigung wird benötigt", Toast.LENGTH_LONG).show()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth ist ausgeschaltet", Toast.LENGTH_LONG).show()
            return
        }
        val bonded = adapter.bondedDevices.orEmpty()
        val huds = bonded.filter { isHud(it) }.ifEmpty { bonded.toList() } // nichts erkannt: alle gekoppelten zur Auswahl
        when {
            huds.isEmpty() -> AlertDialog.Builder(this)
                .setTitle("Kein gekoppeltes Gerät")
                .setMessage("HUD einschalten und in den Android-Bluetooth-Einstellungen koppeln.")
                .setPositiveButton("OK", null)
                .show()
            huds.size == 1 -> begin(huds[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Welches HUD?")
                .setItems(huds.map { "${it.name ?: "?"} (${it.address})" }.toTypedArray()) { _, i ->
                    begin(huds[i])
                }
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun isHud(d: BluetoothDevice): Boolean {
        val n = d.name.orEmpty()
        // Das HUD meldet sich mal als "TILS" (Kurzname in der Werbung), mal als "TILSBERK Head-Up Di..."
        return n.contains("TILS", true) || n.contains("Head-Up", true) || n.contains("DVISION", true)
    }

    @SuppressLint("MissingPermission")
    private fun begin(d: BluetoothDevice) {
        BridgeService.userStopped = false
        prefs.edit()
            .putString(BridgeService.KEY_ADDR, d.address)
            .putString(BridgeService.KEY_NAME, d.name)
            .apply()
        BridgeService.send(this)
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
