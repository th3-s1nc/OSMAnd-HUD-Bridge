package io.github.th3s1nc.osmandhudbridge.track

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Diagramm einer Messreihe über die Fahrt (links Start, rechts Ziel). Fläche (Höhe, Tempo) oder Striche (Schräglage,
 * Beschleunigung, mit Nulllinie). Tippen oder Wischen setzt eine blaue Markierung mit Wert; [onSelect] meldet den Punkt,
 * damit alle Diagramme und die Karte mitlaufen.
 */
class RideChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Kind { AREA, BARS }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    var kind = Kind.AREA
    var values = FloatArray(0)
        private set
    /** Zweite Reihe als dünne orange Linie (Tempolimit im Tempo-Diagramm), NaN = Lücke. */
    var overlay: FloatArray? = null
        private set
    var lo = 0f
        private set
    var hi = 1f
        private set
    var sideLabels = false
    var onSelect: ((Int) -> Unit)? = null
    var format: (Float) -> String = { Math.round(it).toString() }
    var axisFormat: (Float) -> String = { Math.round(it).toString() }

    private var marker = -1
    private var cacheW = -1
    private var colMin = FloatArray(0)
    private var colMax = FloatArray(0)
    private var colAvg = FloatArray(0)
    private var ovAvg = FloatArray(0)

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * d; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A8A8F"); style = Paint.Style.FILL }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9A9AA0"); strokeWidth = 1.2f * d }
    private val ovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A00"); strokeWidth = 1.5f * d; style = Paint.Style.STROKE }
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FA3E0"); strokeWidth = 1.2f * d }
    private val blueText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FA3E0"); textSize = 12f * d; isFakeBoldText = true }
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A8A8B0"); textSize = 9f * d; textAlign = Paint.Align.RIGHT }
    private val greyBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A8A8B0"); textSize = 11f * d; isFakeBoldText = true }

    private val left get() = 38f * d
    private val top get() = 20f * d
    private val right get() = width - 4f * d
    private val bottom get() = height - 3f * d

    fun setData(v: FloatArray, lo: Float, hi: Float, overlay: FloatArray? = null) {
        values = v
        this.lo = lo
        this.hi = if (hi > lo) hi else lo + 1f
        this.overlay = overlay
        cacheW = -1
        marker = -1
        invalidate()
    }

    fun setMarker(index: Int) {
        marker = index
        invalidate()
    }

    private fun y(v: Float): Float = bottom - (v - lo) / (hi - lo) * (bottom - top)

    private fun x(i: Int): Float = if (values.size < 2) left else left + (right - left) * i / (values.size - 1)

    /** Je Pixelspalte Kleinstwert, Größtwert und Mittel der zugehörigen Punkte (bei vielen Punkten viel schneller zu zeichnen). */
    private fun buildCache() {
        val w = Math.max(1, (right - left).toInt())
        cacheW = w
        colMin = FloatArray(w) { Float.NaN }
        colMax = FloatArray(w) { Float.NaN }
        colAvg = FloatArray(w) { Float.NaN }
        ovAvg = FloatArray(w) { Float.NaN }
        val n = values.size
        if (n == 0) return
        for (c in 0 until w) {
            val i0 = (c.toLong() * n / w).toInt()
            val i1 = Math.max(i0 + 1, ((c + 1).toLong() * n / w).toInt()).coerceAtMost(n)
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0f; var cnt = 0
            var os = 0f; var oc = 0
            for (i in i0 until i1) {
                val v = values[i]
                if (!v.isNaN()) { if (v < mn) mn = v; if (v > mx) mx = v; sum += v; cnt++ }
                overlay?.let { val o = it[i]; if (!o.isNaN()) { os += o; oc++ } }
            }
            if (cnt > 0) { colMin[c] = mn; colMax[c] = mx; colAvg[c] = sum / cnt }
            if (oc > 0) ovAvg[c] = os / oc
        }
    }

    override fun onDraw(c: Canvas) {
        if (cacheW != Math.max(1, (right - left).toInt())) buildCache()
        val zeroY = y(0f.coerceIn(lo, hi))
        // Zeichnung
        if (kind == Kind.AREA) {
            val path = Path()
            var started = false
            var lastX = left
            for (i in 0 until cacheW) {
                val v = colAvg[i]
                if (v.isNaN()) continue
                val px = left + i
                if (!started) { path.moveTo(px, bottom); started = true }
                path.lineTo(px, y(v))
                lastX = px
            }
            if (started) { path.lineTo(lastX, bottom); path.close(); c.drawPath(path, fill) }
        } else {
            for (i in 0 until cacheW) {
                if (colMax[i].isNaN()) continue
                val px = left + i
                c.drawLine(px, zeroY, px, y(colMax[i]), bar)
                c.drawLine(px, zeroY, px, y(colMin[i]), bar)
            }
        }
        if (overlay != null) {
            val p = Path()
            var pen = false
            for (i in 0 until cacheW) {
                val v = ovAvg[i]
                if (v.isNaN()) { pen = false; continue }
                if (!pen) { p.moveTo(left + i, y(v)); pen = true } else p.lineTo(left + i, y(v))
            }
            c.drawPath(p, ovPaint)
        }
        // Achsen
        c.drawLine(left, top, left, bottom, axis)
        c.drawLine(left, bottom, right, bottom, axis)
        if (kind == Kind.BARS) c.drawLine(left, zeroY, right, zeroY, axis)
        c.drawText(axisFormat(hi), left - 4f * d, top + 8f * d, grey)
        c.drawText(axisFormat(lo), left - 4f * d, bottom, grey)
        if (kind == Kind.BARS && lo < 0 && hi > 0) c.drawText("0", left - 4f * d, zeroY + 3f * d, grey)
        if (sideLabels) {
            c.drawText("L", 4f * d, top + 12f * d, greyBold)
            c.drawText("R", 4f * d, bottom - 2f * d, greyBold)
        }
        // Markierung
        if (marker in values.indices) {
            val mx = x(marker)
            c.drawLine(mx, 3f * d, mx, bottom, blue)
            c.drawLine(left, 3f * d, right, 3f * d, blue.apply { alpha = 120 })
            blue.alpha = 255
            val v = values[marker]
            val text = if (v.isNaN()) "–" else format(v)
            val tw = blueText.measureText(text)
            val tx = if (mx + tw + 8f * d > width) mx - tw - 4f * d else mx + 4f * d
            c.drawText(text, tx, 15f * d, blueText)
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (values.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; select(e.x) }
            MotionEvent.ACTION_MOVE -> {
                // waagerechtes Wischen gehört dem Diagramm, senkrechtes dem Scrollen der Seite
                if (Math.abs(e.x - downX) > slop && Math.abs(e.x - downX) > Math.abs(e.y - downY)) parent?.requestDisallowInterceptTouchEvent(true)
                select(e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun select(px: Float) {
        val frac = ((px - left) / (right - left)).coerceIn(0f, 1f)
        val i = Math.round(frac * (values.size - 1))
        marker = i
        invalidate()
        onSelect?.invoke(i)
    }
}
