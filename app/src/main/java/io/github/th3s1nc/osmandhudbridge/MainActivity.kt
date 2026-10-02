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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
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
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            BridgeService.userStopped = true
            stopService(Intent(this, BridgeService::class.java))
            refresh()
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
    }

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

        val swAuto = findViewById<MaterialSwitch>(R.id.chkAuto)
        swAuto.isChecked = prefs.getBoolean(BridgeService.KEY_AUTO, true)
        swAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_AUTO, checked).apply()
            if (checked) autoConnect()
        }

        val swIdle = findViewById<MaterialSwitch>(R.id.chkIdle)
        swIdle.isChecked = prefs.getBoolean(BridgeService.KEY_IDLE_STOP, true)
        swIdle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeService.KEY_IDLE_STOP, checked).apply()
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

        val chips = findViewById<ChipGroup>(R.id.chipsThreshold)
        val hint = findViewById<TextView>(R.id.tvThresholdHint)
        val modes = mapOf(
            R.id.chipAlways to ThresholdMode.ALWAYS,
            R.id.chipShort to ThresholdMode.SHORT,
            R.id.chipNormal to ThresholdMode.NORMAL,
            R.id.chipLong to ThresholdMode.LONG,
            R.id.chipOneKm to ThresholdMode.ONE_KM
        )
        fun describe(m: ThresholdMode) = when (m) {
            ThresholdMode.ALWAYS -> "Der Pfeil steht immer da."
            ThresholdMode.SHORT -> "Ab 300 m (Limit bis 50), 600 m (bis 100) bzw. 1000 m."
            ThresholdMode.NORMAL -> "Ab 500 m (Limit bis 50), 1000 m (bis 100) bzw. 2000 m."
            ThresholdMode.LONG -> "Ab 800 m (Limit bis 50), 2000 m (bis 100) bzw. 4000 m."
            ThresholdMode.ONE_KM -> "Immer erst ab 1 km vor dem Manöver."
        }
        val current = ThresholdMode.values().firstOrNull { it.name == prefs.getString(BridgeService.KEY_THRESHOLD, null) }
            ?: ThresholdMode.ONE_KM
        chips.check(modes.entries.first { it.value == current }.key)
        hint.text = describe(current)
        chips.setOnCheckedStateChangeListener { _, ids ->
            val m = ids.firstOrNull()?.let { modes[it] } ?: return@setOnCheckedStateChangeListener
            prefs.edit().putString(BridgeService.KEY_THRESHOLD, m.name).apply()
            hint.text = describe(m)
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
        findViewById<MaterialButton>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { batteryExemption() }
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
            "Tippe auf Start, um das HUD zu verbinden."
        }
        tvGps.text = BridgeBus.gps
        tvLimit.text = BridgeBus.limit
        tvOsm.text = BridgeBus.osm
        tvLog.text = BridgeBus.lastLines(25)
    }

    // ---------------- Start ----------------

    /** Startet den Dienst von selbst, solange die App offen ist (HudClient verbindet, sobald das HUD an ist). */
    private fun autoConnect() {
        if (!prefs.getBoolean(BridgeService.KEY_ENABLED, true)) return
        if (!prefs.getBoolean(BridgeService.KEY_AUTO, true)) return
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

    private fun batteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Bereits von der Akku-Optimierung ausgenommen", Toast.LENGTH_LONG).show()
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
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
