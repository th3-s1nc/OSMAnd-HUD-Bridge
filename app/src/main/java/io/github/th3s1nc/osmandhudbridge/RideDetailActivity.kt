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

/** Seite einer gespeicherten Fahrt: Karte, Kacheln mit Kennzahlen und Diagramme mit Markierung. */
class RideDetailActivity : AppCompatActivity() {
    private lateinit var ride: Ride
    private var points: List<TrackPoint> = emptyList()
    private val charts = ArrayList<RideChartView>()
    private lateinit var map: RouteMapView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = intent.getStringExtra(EXTRA_ID)?.let { RideStore.find(this, it) }
        if (r == null) {
            Toast.makeText(this, "Fahrt nicht gefunden", Toast.LENGTH_SHORT).show()
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
        showHeader()

        if (points.size >= 2) {
            map.setRoute(points)
            buildTiles()
            buildCharts()
        } else {
            map.visibility = View.GONE
            findViewById<TextView>(R.id.tvHint).text = "Zu dieser Fahrt sind keine Punkte gespeichert."
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
        list += "${st.avgKmh} km/h" to "Durchschnitt"
        list += "${st.maxKmh} km/h" to "Maximum"
        if (st.hasEle) list += "↑ ${st.ascentM} m" to "Aufstieg"
        list += "${fmt1(s.maxAccel)} m/s²" to "Beschleunigen"
        list += "${fmt1(s.maxBrake)} m/s²" to "Bremsen"
        if (st.hasEle) list += "↓ ${st.descentM} m" to "Abstieg"
        if (st.hasLean) list += "${st.maxLeanDeg}°" to "Max. Schräglage"
        if (st.hasLimit) list += "${st.overCount} Mal" to "Limit überschritten"
        list += RideFormat.duration(st.totalMs) to "Gesamtzeit"
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
    }

    private fun buildCharts() {
        val s: RideSeries = RideAnalysis.series(points)
        if (RideAnalysis.hasData(s.ele)) addChart("Höhenprofil") { c ->
            val mn = s.ele.filter { !it.isNaN() }.minOrNull() ?: 0f
            val mx = s.ele.filter { !it.isNaN() }.maxOrNull() ?: 1f
            val lo = Math.floor((mn / 100f).toDouble()).toFloat() * 100f
            c.kind = RideChartView.Kind.AREA
            c.format = { v -> Math.round(v).toString() + " m" }
            c.setData(s.ele, lo, niceCeil(mx, 100f))
        }
        if (RideAnalysis.hasData(s.lean)) addChart("Schräglage") { c ->
            val m = s.lean.filter { !it.isNaN() }.maxOfOrNull { Math.abs(it) } ?: 10f
            val r = Math.max(10f, niceCeil(m, 10f))
            c.kind = RideChartView.Kind.BARS
            c.sideLabels = true
            // links ist negativ, im Diagramm oben (L); rechts positiv, unten (R): Werte spiegeln
            c.axisFormat = { v -> Math.abs(Math.round(v)).toString() }
            c.setData(FloatArray(s.lean.size) { -s.lean[it] }, -r, r)
            c.format = { v -> Math.abs(Math.round(v)).toString() + "° " + (if (v > 0) "links" else if (v < 0) "rechts" else "") }
        }
        addChart("Beschleunigung") { c ->
            val m = Math.max(2f, niceCeil(Math.max(s.maxAccel, -s.maxBrake), 1f))
            c.kind = RideChartView.Kind.BARS
            c.format = { v -> fmt1(v) + " m/s²" }
            c.setData(s.accel, -m, m)
        }
        addChart("Geschwindigkeit") { c ->
            val mx = s.speedKmh.maxOrNull() ?: 1f
            c.kind = RideChartView.Kind.AREA
            c.format = { v -> Math.round(v).toString() + " km/h" }
            c.setData(s.speedKmh, 0f, Math.max(20f, niceCeil(mx, 20f)), if (RideAnalysis.hasData(s.limit)) s.limit else null)
        }
        if (RideAnalysis.hasData(s.limit)) {
            findViewById<TextView>(R.id.tvHint).append("\nDie orange Linie im Tempo-Diagramm ist das Tempolimit.")
        }
    }

    // ---------------------------------------------------------------- Aktionen

    private fun gpxUri() = FileProvider.getUriForFile(this, "$packageName.logs", RideStore.gpxFile(this, ride.id))

    private fun openInMapApp() {
        val f = RideStore.gpxFile(this, ride.id)
        if (!f.exists()) {
            Toast.makeText(this, "GPX-Datei nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(gpxUri(), "application/gpx+xml")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageManager.queryIntentActivities(view, 0).isEmpty()) {
            Toast.makeText(this, "Keine Karten-App gefunden, nimm Teilen", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent.createChooser(view, "Fahrt anzeigen mit"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Keine Karten-App gefunden, nimm Teilen", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseShare() {
        AlertDialog.Builder(this)
            .setTitle("Was teilen?")
            .setItems(arrayOf("GPX (für Karten-Apps)", "CSV (für Excel)")) { _, i -> share(i == 1) }
            .show()
    }

    private fun share(csv: Boolean) {
        val f = if (csv) RideStore.csvFile(this, ride.id) else RideStore.gpxFile(this, ride.id)
        if (!f.exists()) {
            Toast.makeText(this, "Datei nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (csv) "text/csv" else "application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Fahrt teilen"))
    }

    private fun rename() {
        val input = EditText(this).apply {
            setSingleLine()
            setText(ride.name)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this)
            .setTitle("Name der Fahrt")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    RideStore.rename(this, ride.id, n)
                    RideStore.find(this, ride.id)?.let { ride = it }
                    showHeader()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Fahrt löschen?")
            .setMessage("\"${ride.name}\" wird samt GPX- und CSV-Datei gelöscht, auch aus Download/GPX-Tracking.")
            .setPositiveButton("Ja, löschen") { _, _ ->
                RideStore.delete(this, ride)
                finish()
            }
            .setNegativeButton("Nein", null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "ride_id"
    }
}
