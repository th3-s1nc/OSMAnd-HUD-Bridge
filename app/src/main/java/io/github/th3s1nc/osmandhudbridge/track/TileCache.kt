package io.github.th3s1nc.osmandhudbridge.track

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Holt Kartenkacheln von OpenStreetMap für die kleine Karte der Fahrt-Seite und merkt sie sich auf dem Handy.
 * Höflich zur Kartenquelle: höchstens zwei Abfragen gleichzeitig, jede Kachel nur einmal (danach aus dem Speicher),
 * ehrlicher App-Name als Absender, bei Fehlern kein erneutes Hämmern. Ohne Netz bleibt die Karte leer, die Route wird trotzdem gezeichnet.
 */
object TileCache {
    private const val USER_AGENT = "OSMAndHudBridge/0.11 (+https://github.com/th3-s1nc/OSMAnd-HUD-Bridge)"
    private const val MAX_DISK_BYTES = 40L * 1024 * 1024

    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val mem = LruCache<String, Bitmap>(80)
    private val failed = HashSet<String>()
    private var trimmed = false

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    /** Ruft [cb] auf dem Main-Thread mit der Kachel auf (oder null, wenn sie nicht zu bekommen ist). */
    fun get(ctx: Context, z: Int, x: Int, y: Int, cb: (Bitmap?) -> Unit) {
        val k = key(z, x, y)
        mem.get(k)?.let { cb(it); return }
        if (synchronized(failed) { k in failed }) { cb(null); return }
        val app = ctx.applicationContext
        exec.execute {
            val bmp = fromDisk(app, k) ?: download(app, z, x, y, k)
            if (bmp != null) mem.put(k, bmp) else synchronized(failed) { failed += k }
            main.post { cb(bmp) }
        }
    }

    private fun file(ctx: Context, k: String) = File(File(ctx.cacheDir, "maptiles"), "$k.png")

    private fun fromDisk(ctx: Context, k: String): Bitmap? {
        val f = file(ctx, k)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null }
    }

    private fun download(ctx: Context, z: Int, x: Int, y: Int, k: String): Bitmap? {
        if (z !in 0..19 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        var c: HttpURLConnection? = null
        try {
            c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 6000
            c.readTimeout = 8000
            if (c.responseCode != 200) {
                BridgeBus.log("Kartenkachel $k: HTTP ${c.responseCode}")
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val f = file(ctx, k)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
            trimOnce(ctx)
            return bmp
        } catch (e: Exception) {
            BridgeBus.log("Kartenkachel $k nicht geladen: ${e.message}")
            return null
        } finally {
            c?.disconnect()
        }
    }

    /** Einmal je Programmstart: ist der Kartenspeicher über der Grenze, die ältesten Kacheln löschen. */
    private fun trimOnce(ctx: Context) {
        if (trimmed) return
        trimmed = true
        try {
            val all = File(ctx.cacheDir, "maptiles").walkTopDown().filter { it.isFile }.toList()
            var total = all.sumOf { it.length() }
            if (total <= MAX_DISK_BYTES) return
            for (f in all.sortedBy { it.lastModified() }) {
                total -= f.length()
                f.delete()
                if (total <= MAX_DISK_BYTES * 0.7) break
            }
        } catch (_: Exception) {}
    }
}
