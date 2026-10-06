package io.github.th3s1nc.osmandhudbridge.track

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Kleine Karte der Fahrt: OpenStreetMap-Kacheln als Hintergrund (falls erreichbar), darauf die Route in Blau,
 * Start grün, Ziel rot und, beim Wischen über ein Diagramm, die Stelle der Markierung.
 * Ohne Kacheln (kein Netz) bleibt ein heller Grund und die Route ist trotzdem zu sehen.
 */
class RouteMapView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val tilePx = 256.0 * d * 0.8

    private var pts: List<TrackPoint> = emptyList()
    private var marker = -1
    private var z = 2
    private var originX = 0.0 // Weltpixel der linken oberen Ecke der Ansicht
    private var originY = 0.0
    private val tiles = HashMap<Long, Bitmap>()
    private var generation = 0

    private val bg = Paint().apply { color = Color.parseColor("#E9E6DF") }
    private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F7FD0"); style = Paint.Style.STROKE; strokeWidth = 3.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); textSize = 9f * d }
    private val creditBg = Paint().apply { color = Color.parseColor("#CCFFFFFF") }
    private val clip = Path()

    fun setRoute(points: List<TrackPoint>) {
        pts = points
        layoutMap()
        invalidate()
    }

    fun setMarker(index: Int) {
        marker = index
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutMap()
    }

    /** Zoom und Ausschnitt wählen und die nötigen Kacheln anfordern. */
    private fun layoutMap() {
        if (width == 0 || height == 0 || pts.size < 2) return
        generation++
        tiles.clear()
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat; if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon; if (p.lon > maxLon) maxLon = p.lon
        }
        if (maxLat - minLat < 0.002) { minLat -= 0.001; maxLat += 0.001 }
        if (maxLon - minLon < 0.002) { minLon -= 0.001; maxLon += 0.001 }
        z = MapMath.chooseZoom(minLat, maxLat, minLon, maxLon, width.toDouble(), height.toDouble(), tilePx)
        val cx = (MapMath.worldX(minLon, z, tilePx) + MapMath.worldX(maxLon, z, tilePx)) / 2
        val cy = (MapMath.worldY(minLat, z, tilePx) + MapMath.worldY(maxLat, z, tilePx)) / 2
        originX = cx - width / 2.0
        originY = cy - height / 2.0
        val x0 = Math.floor(originX / tilePx).toInt()
        val x1 = Math.floor((originX + width) / tilePx).toInt()
        val y0 = Math.floor(originY / tilePx).toInt()
        val y1 = Math.floor((originY + height) / tilePx).toInt()
        val gen = generation
        val n = 1 shl z
        for (ty in y0..y1) for (tx in x0..x1) {
            if (ty < 0 || ty >= n) continue
            val wx = ((tx % n) + n) % n
            TileCache.get(context, z, wx, ty) { bmp ->
                if (bmp != null && gen == generation) {
                    tiles[(tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)] = bmp
                    invalidate()
                }
            }
        }
    }

    private fun sx(lon: Double) = (MapMath.worldX(lon, z, tilePx) - originX).toFloat()
    private fun sy(lat: Double) = (MapMath.worldY(lat, z, tilePx) - originY).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 12f * d, 12f * d, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawRect(0f, 0f, w, h, bg)
        for ((k, bmp) in tiles) {
            val tx = (k shr 32).toInt()
            val ty = k.toInt()
            val l = (tx * tilePx - originX).toFloat()
            val t = (ty * tilePx - originY).toFloat()
            c.drawBitmap(bmp, null, RectF(l, t, l + tilePx.toFloat(), t + tilePx.toFloat()), tilePaint)
        }
        if (pts.size >= 2) {
            val step = Math.max(1, pts.size / 1500)
            val path = Path()
            var i = 0
            var first = true
            while (i < pts.size) {
                val p = pts[i]
                if (first) { path.moveTo(sx(p.lon), sy(p.lat)); first = false } else path.lineTo(sx(p.lon), sy(p.lat))
                i += step
            }
            val last = pts.last()
            path.lineTo(sx(last.lon), sy(last.lat))
            c.drawPath(path, casing)
            c.drawPath(path, route)
            fun mark(p: TrackPoint, color: Int, r: Float) {
                dot.color = color
                c.drawCircle(sx(p.lon), sy(p.lat), r * d, dot)
                c.drawCircle(sx(p.lon), sy(p.lat), r * d, ring)
            }
            mark(pts.first(), Color.parseColor("#3FB950"), 6f)
            mark(last, Color.parseColor("#E5484D"), 6f)
            if (marker in pts.indices) mark(pts[marker], Color.parseColor("#FF7A00"), 7f)
        }
        val text = "© OpenStreetMap-Mitwirkende"
        val tw = credit.measureText(text)
        c.drawRect(w - tw - 10f * d, h - 15f * d, w, h, creditBg)
        c.drawText(text, w - tw - 5f * d, h - 4f * d, credit)
        c.restore()
    }
}
