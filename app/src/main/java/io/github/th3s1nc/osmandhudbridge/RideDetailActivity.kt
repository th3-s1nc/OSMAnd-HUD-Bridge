package io.github.th3s1nc.osmandhudbridge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import io.github.th3s1nc.osmandhudbridge.track.Ride
import io.github.th3s1nc.osmandhudbridge.track.RideAnalysis
import io.github.th3s1nc.osmandhudbridge.track.RideChartView
import io.github.th3s1nc.osmandhudbridge.track.RideFormat
import io.github.th3s1nc.osmandhudbridge.track.RideSeries
import io.github.th3s1nc.osmandhudbridge.track.RideStore
import io.github.th3s1nc.osmandhudbridge.track.RouteMapView
import io.github.th3s1nc.osmandhudbridge.track.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.th3s1nc.osmandhudbridge.limit.Tx

/** Seite einer gespeicherten Fahrt: Karte, Kacheln mit Kennzahlen und Diagramme mit Markierung. */
class RideDetailActivity : AppCompatActivity() {
    private lateinit var ride: Ride
    private var points: List<TrackPoint> = emptyList()
    private val charts = ArrayList<RideChartView>()
    private lateinit var map: RouteMapView
    private var markerIdx = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = intent.getStringExtra(EXTRA_ID)?.let { RideStore.find(this, it) }
        if (r == null) {
            Toast.makeText(this, Tx.t("Fahrt nicht gefunden", "Ride not found"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ride = r
        setContentView(R.layout.activity_ride)
        points = RideStore.points(this, ride.id)
        map = findViewById(R.id.map)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.btnRename).setOnClickListener { rename() }
        findViewById<View>(R.id.btnShare).setOnClickListener { chooseShare() }
        findViewById<View>(R.id.btnOpenMap).setOnClickListener { openInMapApp() }
        findViewById<View>(R.id.btnFullMap).setOnClickListener {
            startActivity(Intent(this, RideMapActivity::class.java).putExtra(EXTRA_ID, ride.id).putExtra(RideMapActivity.EXTRA_MARKER, markerIdx))
        }
        showHeader()

        if (points.size >= 2) {
            map.setRoute(points)
            buildTiles()
            buildCharts()
        } else {
            findViewById<View>(R.id.mapBox).visibility = View.GONE
            findViewById<TextView>(R.id.tvHint).text = Tx.t("Zu dieser Fahrt sind keine Punkte gespeichert.", "No points are saved for this ride.")
        }
    }

    private fun showHeader() {
        findViewById<TextView>(R.id.tvTitle).text = ride.name
        findViewById<TextView>(R.id.tvDate).text = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY).format(Date(ride.stats.startMs))
        findViewById<TextView>(R.id.tvChipDist).text = "↔  " + RideFormat.distance(ride.stats.distanceM)
        findViewById<TextView>(R.id.tvChipTime).text = "◷  " + RideFormat.duration(ride.stats.movingMs)
    }

    // ---------------------------------------------------------------- Kacheln

    private fun tile(value: String, label: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        box.addView(TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xFFA8A8B0.toInt())
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun fmt1(v: Float) = String.format(Locale.GERMANY, "%.1f", v)

    private fun buildTiles() {
        val st = ride.stats
        val s = RideAnalysis.series(points)
        val list = ArrayList<Pair<String, String>>()
        list += "${st.avgKmh} km/h" to Tx.t("Durchschnitt", "Average")
        list += "${st.maxKmh} km/h" to Tx.t("Maximum", "Maximum")
        if (st.hasEle) list += "↑ ${st.ascentM} m" to Tx.t("Aufstieg", "Ascent")
        list += "${fmt1(s.maxAccel)} m/s²" to Tx.t("Beschleunigen", "Acceleration")
        list += "${fmt1(s.maxBrake)} m/s²" to Tx.t("Bremsen", "Braking")
        if (st.hasEle) list += "↓ ${st.descentM} m" to Tx.t("Abstieg", "Descent")
        if (st.hasLean) list += "${st.maxLeanDeg}°" to Tx.t("Max. Schräglage", "Max lean angle")
        if (st.hasLimit) list += Tx.t("${st.overCount} Mal", "${st.overCount} times") to Tx.t("Limit überschritten", "Limit exceeded")
        list += RideFormat.duration(st.totalMs) to Tx.t("Gesamtzeit", "Total time")
        val box = findViewById<LinearLayout>(R.id.tiles)
        for (i in list.indices step 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                val item = list.getOrNull(i + j)
                row.addView(if (item != null) tile(item.first, item.second) else View(this), lp)
            }
            box.addView(row)
        }
    }

    // ---------------------------------------------------------------- Diagramme

    private fun niceCeil(v: Float, step: Float): Float = Math.ceil((v / step).toDouble()).toFloat() * step

    private fun addChart(title: String, build: (RideChartView) -> Unit) {
        val box = findViewById<LinearLayout>(R.id.charts)
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(6))
        })
        val c = RideChartView(this)
        build(c)
        c.onSelect = { i -> select(i) }
        box.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        charts += c
    }

    private fun select(i: Int) {
        for (c in charts) c.setMarker(i)
        map.setMarker(i)
        markerIdx = i
    }

    private fun buildCharts() {
        val s: RideSeries = RideAnalysis.series(points)
        if (RideAnalysis.hasData(s.ele)) addChart(Tx.t("Höhenprofil", "Elevation profile")) { c ->
            val mn = s.ele.filter { !it.isNaN() }.minOrNull() ?: 0f
            val mx = s.ele.filter { !it.isNaN() }.maxOrNull() ?: 1f
            val lo = Math.floor((mn / 100f).toDouble()).toFloat() * 100f
            c.kind = RideChartView.Kind.AREA
            c.format = { v -> Math.round(v).toString() + " m" }
            c.setData(s.ele, lo, niceCeil(mx, 100f))
        }
        if (RideAnalysis.hasData(s.lean)) addChart(Tx.t("Schräglage", "Lean angle")) { c ->
            val m = s.lean.filter { !it.isNaN() }.maxOfOrNull { Math.abs(it) } ?: 10f
            val r = Math.max(10f, niceCeil(m, 10f))
            c.kind = RideChartView.Kind.BARS
            c.sideLabels = true
            // links ist negativ, im Diagramm oben (L); rechts positiv, unten (R): Werte spiegeln
            c.axisFormat = { v -> Math.abs(Math.round(v)).toString() }
            c.setData(FloatArray(s.lean.size) { -s.lean[it] }, -r, r)
            c.format = { v -> Math.abs(Math.round(v)).toString() + "° " + (if (v > 0) Tx.t("links", "left") else if (v < 0) Tx.t("rechts", "right") else "") }
        }
        addChart(Tx.t("Beschleunigung", "Acceleration")) { c ->
            val m = Math.max(2f, niceCeil(Math.max(s.maxAccel, -s.maxBrake), 1f))
            c.kind = RideChartView.Kind.BARS
            c.format = { v -> fmt1(v) + " m/s²" }
            c.setData(s.accel, -m, m)
        }
        addChart(Tx.t("Geschwindigkeit", "Speed")) { c ->
            val mx = s.speedKmh.maxOrNull() ?: 1f
            c.kind = RideChartView.Kind.AREA
            c.format = { v -> Math.round(v).toString() + " km/h" }
            c.setData(s.speedKmh, 0f, Math.max(20f, niceCeil(mx, 20f)), if (RideAnalysis.hasData(s.limit)) s.limit else null)
        }
        if (RideAnalysis.hasData(s.limit)) {
            findViewById<TextView>(R.id.tvHint).append(Tx.t("\nDie orange Linie im Tempo-Diagramm ist das Tempolimit.", "\nThe orange line in the speed chart is the speed limit."))
        }
    }

    // ---------------------------------------------------------------- Aktionen

    private fun gpxUri() = FileProvider.getUriForFile(this, "$packageName.logs", RideStore.gpxFile(this, ride.id))

    private fun openInMapApp() {
        val f = RideStore.gpxFile(this, ride.id)
        if (!f.exists()) {
            Toast.makeText(this, Tx.t("GPX-Datei nicht mehr vorhanden", "GPX file no longer available"), Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(gpxUri(), "application/gpx+xml")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageManager.queryIntentActivities(view, 0).isEmpty()) {
            Toast.makeText(this, Tx.t("Keine Karten-App gefunden, nimm Teilen", "No map app found, use Share"), Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent.createChooser(view, Tx.t("Fahrt anzeigen mit", "Show ride with")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, Tx.t("Keine Karten-App gefunden, nimm Teilen", "No map app found, use Share"), Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseShare() {
        AlertDialog.Builder(this)
            .setTitle(Tx.t("Was teilen?", "Share what?"))
            .setItems(arrayOf(Tx.t("GPX (für Karten-Apps)", "GPX (for map apps)"), Tx.t("CSV (für Excel)", "CSV (for Excel)"))) { _, i -> share(i == 1) }
            .show()
    }

    private fun share(csv: Boolean) {
        val f = if (csv) RideStore.csvFile(this, ride.id) else RideStore.gpxFile(this, ride.id)
        if (!f.exists()) {
            Toast.makeText(this, Tx.t("Datei nicht mehr vorhanden", "File no longer available"), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (csv) "text/csv" else "application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, Tx.t("Fahrt teilen", "Share ride")))
    }

    private fun rename() {
        val input = EditText(this).apply {
            setSingleLine()
            setText(ride.name)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this)
            .setTitle(Tx.t("Name der Fahrt", "Name of the ride"))
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    RideStore.rename(this, ride.id, n)
                    RideStore.find(this, ride.id)?.let { ride = it }
                    showHeader()
                }
            }
            .setNegativeButton(Tx.t("Abbrechen", "Cancel"), null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(Tx.t("Fahrt löschen?", "Delete ride?"))
            .setMessage(Tx.t("\"${ride.name}\" wird samt GPX- und CSV-Datei gelöscht, auch aus Download/GPX-Tracking.", "\"${ride.name}\" will be deleted together with its GPX and CSV files, also from Download/GPX-Tracking."))
            .setPositiveButton(Tx.t("Ja, löschen", "Yes, delete")) { _, _ ->
                RideStore.delete(this, ride)
                finish()
            }
            .setNegativeButton(Tx.t("Nein", "No"), null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "ride_id"
    }
}
