package io.github.th3s1nc.osmandhudbridge.track

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eine gespeicherte Fahrt (Kennzahlen). Die GPX-Datei liegt privat in der App und, wenn möglich, in Download/GPX-Tracking. */
class Ride(
    val id: String,
    val name: String,
    val stats: RideStats,
    /** Adresse der Kopie in Download/GPX-Tracking (null = nur in der App). */
    val publicUri: String?,
    /** Adresse der CSV in Download/GPX-Tracking (null = nur in der App). */
    val csvUri: String? = null
)

/**
 * Speicherort der Fahrten: Kennzahlen als kleine Datei je Fahrt, GPX privat in der App (zum Teilen) und eine Kopie
 * in Download/GPX-Tracking über MediaStore (ab Android 10 ohne Berechtigung). Läuft eine Aufnahme, werden die Punkte
 * zusätzlich laufend in current.csv gesichert, damit eine unterbrochene Fahrt nicht verloren geht.
 */
object RideStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "rides").also { it.mkdirs() }

    fun currentFile(ctx: Context) = File(dir(ctx), "current.csv")

    fun gpxFile(ctx: Context, id: String) = File(dir(ctx), "$id.gpx")

    fun csvFile(ctx: Context, id: String) = File(dir(ctx), "$id.csv")

    fun defaultName(startMs: Long): String =
        "Fahrt " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(startMs))

    fun dateText(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(ms))

    // ---------------------------------------------------------------- laufende Aufnahme sichern

    fun line(p: TrackPoint): String =
        "${p.timeMs},${p.lat},${p.lon},${p.ele ?: ""},${p.speedKmh},${p.limitKmh},${p.leanDeg ?: ""}\n"

    /** Punkte einer unterbrochenen Fahrt (leer, wenn es keine gibt). */
    fun unfinished(ctx: Context): List<TrackPoint> = parse(currentFile(ctx))

    /** Rohpunkte einer gespeicherten Fahrt (für die Fahrt-Seite). */
    fun points(ctx: Context, id: String): List<TrackPoint> = parse(File(dir(ctx), "$id.pts"))

    private fun parse(f: File): List<TrackPoint> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<TrackPoint>()
        try {
            f.forEachLine { l ->
                val c = l.split(",")
                if (c.size >= 5) {
                    val t = c[0].toLongOrNull()
                    val la = c[1].toDoubleOrNull()
                    val lo = c[2].toDoubleOrNull()
                    val sp = c[4].toFloatOrNull()
                    if (t != null && la != null && lo != null && sp != null) {
                        out += TrackPoint(
                            t, la, lo, c[3].toDoubleOrNull(), sp,
                            c.getOrNull(5)?.toIntOrNull() ?: 0, c.getOrNull(6)?.toFloatOrNull()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            BridgeBus.log("Fahrtpunkte nicht lesbar (${f.name}): ${e.message}")
        }
        return out
    }

    fun discardUnfinished(ctx: Context) {
        currentFile(ctx).delete()
    }

    // ---------------------------------------------------------------- speichern, auflisten, löschen

    /** Speichert die Fahrt. Gibt null zurück, wenn sie zu kurz ist (weniger als 2 Punkte) oder das Schreiben scheitert. */
    fun save(ctx: Context, name: String, points: List<TrackPoint>, clearUnfinished: Boolean = true): Ride? {
        if (points.size < 2) return null
        val s = TrackSession().also { it.restore(points) }
        val st = s.stats()
        val id = st.startMs.toString()
        val gpx = GpxWriter.write(name, points)
        try {
            gpxFile(ctx, id).writeText(gpx)
        } catch (e: Exception) {
            BridgeBus.log("Fahrt speichern gescheitert: ${e.message}")
            return null
        }
        val base = "${RideFormat.fileSafe(name)}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(st.startMs))}"
        val csv = CsvWriter.write(points)
        try { File(dir(ctx), "$id.pts").writeText(points.joinToString("") { line(it) }) } catch (e: Exception) { BridgeBus.log("Punkte speichern gescheitert: ${e.message}") }
        try { csvFile(ctx, id).writeText(csv) } catch (e: Exception) { BridgeBus.log("CSV speichern gescheitert: ${e.message}") }
        val pub = publish(ctx, "$base.gpx", "application/gpx+xml", gpx)
        val cpub = publish(ctx, "$base.csv", "text/csv", csv)
        val ride = Ride(id, name, st, pub, cpub)
        try {
            File(dir(ctx), "$id.json").writeText(
                JSONObject()
                    .put("id", id).put("name", name)
                    .put("start", st.startMs).put("end", st.endMs).put("moving", st.movingMs)
                    .put("dist", st.distanceM).put("max", st.maxKmh).put("points", st.points)
                    .put("uri", pub ?: JSONObject.NULL).put("curi", cpub ?: JSONObject.NULL)
                    .put("asc", st.ascentM).put("desc", st.descentM).put("over", st.overCount).put("lean", st.maxLeanDeg)
                    .put("hasEle", st.hasEle).put("hasLimit", st.hasLimit).put("hasLean", st.hasLean)
                    .toString()
            )
        } catch (e: Exception) {
            BridgeBus.log("Fahrt-Kennzahlen speichern gescheitert: ${e.message}")
        }
        if (clearUnfinished) discardUnfinished(ctx)
        BridgeBus.log("Fahrt gespeichert: $name, ${RideFormat.distance(st.distanceM)}, ${st.points} Punkte" + if (pub != null) ", Kopie in Download/GPX-Tracking" else "")
        return ride
    }

    /** Legt eine Kopie in Download/GPX-Tracking an. Gibt die Adresse zurück oder null. */
    private fun publish(ctx: Context, fileName: String, mime: String, text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GPX-Tracking")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return null
            uri.toString()
        } catch (e: Exception) {
            BridgeBus.log("Kopie in Downloads gescheitert: ${e.message}")
            null
        }
    }

    /** Eine gespeicherte Fahrt nach ihrer Nummer (null, wenn es sie nicht gibt). */
    fun find(ctx: Context, id: String): Ride? = list(ctx).firstOrNull { it.id == id }

    /** Benennt die Fahrt um (nur in der App; Dateinamen in Downloads bleiben, wie sie sind). */
    fun rename(ctx: Context, id: String, name: String) {
        val f = File(dir(ctx), "$id.json")
        try {
            f.writeText(JSONObject(f.readText()).put("name", name).toString())
        } catch (e: Exception) {
            BridgeBus.log("Umbenennen gescheitert: ${e.message}")
        }
    }

    /** Alle gespeicherten Fahrten, neueste zuerst. */
    fun list(ctx: Context): List<Ride> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<Ride>()
        for (f in files) {
            try {
                val j = JSONObject(f.readText())
                out += Ride(
                    j.getString("id"), j.getString("name"),
                    RideStats(
                        j.getLong("start"), j.getLong("end"), j.getLong("moving"), j.getInt("dist"), j.getInt("max"), j.getInt("points"),
                        j.optInt("asc"), j.optInt("desc"), j.optInt("over"), j.optInt("lean"),
                        j.optBoolean("hasEle"), j.optBoolean("hasLimit"), j.optBoolean("hasLean")
                    ),
                    if (j.isNull("uri")) null else j.getString("uri"),
                    if (j.isNull("curi")) null else j.optString("curi", "").ifEmpty { null }
                )
            } catch (e: Exception) {
                BridgeBus.log("Fahrt nicht lesbar (${f.name}): ${e.message}")
            }
        }
        return out.sortedByDescending { it.stats.startMs }
    }

    /** Löscht die Fahrt samt GPX- und CSV-Datei, auch die Kopien in Download/GPX-Tracking. */
    fun delete(ctx: Context, ride: Ride) {
        File(dir(ctx), "${ride.id}.json").delete()
        gpxFile(ctx, ride.id).delete()
        csvFile(ctx, ride.id).delete()
        File(dir(ctx), "${ride.id}.pts").delete()
        for (u in listOfNotNull(ride.publicUri, ride.csvUri)) {
            try { ctx.contentResolver.delete(Uri.parse(u), null, null) } catch (e: Exception) {
                BridgeBus.log("Kopie in Downloads nicht gelöscht: ${e.message}")
            }
        }
    }
}
