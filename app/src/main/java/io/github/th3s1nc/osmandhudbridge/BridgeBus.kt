package io.github.th3s1nc.osmandhudbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Status- und Logsammlung für die Oberfläche (und zum Weitergeben bei der Fehlersuche). */
object BridgeBus {
    private const val TAG = "OsmAndHudBridge"
    private const val MAX_LINES = 300
    private const val LOG_NAME = "osmand-hud-bridge-log.txt"
    private const val MAX_BYTES = 1_000_000 // danach wird die ältere Hälfte verworfen
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    private val fileFmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null

    /** Schaltet das Mitschreiben in eine Textdatei ein (bleibt über App-Neustarts erhalten, höchstens ca. 1 MB). */
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, LOG_NAME)
        file = f
        io.execute {
            try {
                f.appendText("---- Start ${fileFmt.format(Date())} ----\n")
            } catch (_: Exception) {}
        }
    }

    /** Datei zum Teilen; wartet kurz, bis alles geschrieben ist. */
    fun logFile(): File? {
        val f = file ?: return null
        try { io.submit {}.get() } catch (_: Exception) {}
        return f.takeIf { it.exists() }
    }

    /** Verwirft den bisherigen Verlauf (Anzeige und Datei). */
    @Synchronized
    fun clearLog() {
        lines.clear()
        val f = file
        if (f != null) io.execute { try { f.writeText("---- Log gelöscht ${fileFmt.format(Date())} ----\n") } catch (_: Exception) {} }
        changed()
    }

    private fun trim(f: File) {
        val t = f.readText()
        f.writeText(t.takeLast(MAX_BYTES / 2).substringAfter('\n'))
    }

    @Volatile var hud = "HUD: –"
    @Volatile var gps = "GPS: –"
    @Volatile var osm = "OsmAnd: –"
    @Volatile var limit = "Limit: –"
    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        Log.d(TAG, msg)
        val now = Date()
        lines.addLast("${fmt.format(now)} $msg")
        val f = file
        if (f != null) {
            val line = "${fileFmt.format(now)} $msg\n"
            io.execute {
                try {
                    f.appendText(line)
                    if (f.length() > MAX_BYTES) trim(f)
                } catch (_: Exception) {}
            }
        }
        while (lines.size > MAX_LINES) lines.removeFirst()
        changed()
    }

    fun changed() {
        main.post { onChange?.invoke() }
    }

    @Synchronized
    fun logText(): String = lines.joinToString("\n")

    @Synchronized
    fun lastLines(n: Int): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun render(): String {
        val last = lines.toList().takeLast(12).joinToString("\n")
        return "$hud\n$gps\n$osm\n$limit\n\n$last"
    }
}
