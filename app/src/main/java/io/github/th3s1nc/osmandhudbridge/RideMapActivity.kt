package io.github.th3s1nc.osmandhudbridge

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.th3s1nc.osmandhudbridge.track.RideStore
import io.github.th3s1nc.osmandhudbridge.track.RouteMapView
import java.util.Locale

/**
 * Vollbild-Karte einer Fahrt: verschieben, zoomen (Plus/Minus, Doppeltipp, zwei Finger), einen Punkt der Route antippen
 * zeigt Tempo und Höhe an dieser Stelle. Die Kartenbilder kommen von OpenStreetMap.
 */
class RideMapActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(RideDetailActivity.EXTRA_ID)
        val points = id?.let { RideStore.points(this, it) } ?: emptyList()
        if (points.size < 2) {
            Toast.makeText(this, "Keine Punkte gespeichert", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val root = FrameLayout(this).apply { fitsSystemWindows = true; setBackgroundColor(0xFF000000.toInt()) }
        val map = RouteMapView(this).apply {
            interactive = true
            cornerDp = 0f
            setRoute(points)
        }
        root.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun button(text: String, size: Float, gravity: Int, topMargin: Int, bottomMargin: Int, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFFFFFFF.toInt())
            this.gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_map_btn)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            root.addView(this, FrameLayout.LayoutParams(dp(44), dp(44), gravity).apply {
                setMargins(dp(12), dp(12) + topMargin, dp(12), dp(12) + bottomMargin)
            })
        }
        button("✕", 18f, Gravity.TOP or Gravity.END, 0, 0) { finish() }
        button("+", 22f, Gravity.BOTTOM or Gravity.END, 0, dp(56)) { map.zoomIn() }
        button("–", 22f, Gravity.BOTTOM or Gravity.END, 0, 0) { map.zoomOut() }
        button("⌖", 20f, Gravity.TOP or Gravity.END, dp(56), 0) { map.fit() }

        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_map_btn)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Tippe auf die Route für Tempo und Höhe"
        }
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        fun show(i: Int) {
            val p = points[i]
            val ele = p.ele?.let { String.format(Locale.GERMANY, " · Höhe %.0f m", it) } ?: ""
            info.text = String.format(Locale.GERMANY, "Tempo %.0f km/h", p.speedKmh) + ele
        }
        map.onPick = { show(it) }
        val start = intent.getIntExtra(EXTRA_MARKER, -1)
        if (start in points.indices) { map.setMarker(start); show(start) }
        setContentView(root)
    }

    companion object {
        const val EXTRA_MARKER = "marker"
    }
}
