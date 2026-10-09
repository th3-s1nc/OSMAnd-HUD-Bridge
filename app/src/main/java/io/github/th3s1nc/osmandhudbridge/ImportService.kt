package io.github.th3s1nc.osmandhudbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.th3s1nc.osmandhudbridge.limit.ImportCancelled
import io.github.th3s1nc.osmandhudbridge.limit.ImportListener
import io.github.th3s1nc.osmandhudbridge.limit.PbfImport
import io.github.th3s1nc.osmandhudbridge.limit.TileStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stand des Imports der Straßendaten für die Oberfläche. */
object ImportBus {
    @Volatile var running = false
    @Volatile var fileName = ""
    @Volatile var step = 0
    @Volatile var steps = 3
    @Volatile var fraction = 0.0
    @Volatile var stepText = ""
    @Volatile var tiles = 0
    @Volatile var eta = ""
    @Volatile var cancel = false
    /** Letzte Meldung (Fertig, Abgebrochen, Fehler); null = keine. */
    @Volatile var message: String? = null
    @Volatile var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { onChange?.invoke() } }
}

/**
 * Liest eine OSM-Datei (.osm.pbf) und macht daraus Kacheln für das Tempolimit (siehe [PbfImport]). Läuft als Dienst mit
 * Benachrichtigung und Fortschritt, damit Android die Arbeit nicht abbricht, wenn der Bildschirm ausgeht.
 */
class ImportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { ImportBus.cancel = true; return START_NOT_STICKY }
        val uri = intent?.data
        if (uri == null || ImportBus.running) {
            // Nach startForegroundService ist startForeground Pflicht, sonst stürzt die App ab
            try { startFg(buildNotification("Straßendaten werden gelesen", (ImportBus.fraction * 100).toInt(), true)) } catch (_: Exception) { }
            if (!ImportBus.running) stopSelf()
            return START_NOT_STICKY
        }
        val name = nameOf(uri)
        ImportBus.apply { running = true; cancel = false; fileName = name; step = 1; fraction = 0.0; stepText = "Straßen suchen"; tiles = 0; eta = ""; message = null }
        ImportBus.changed()
        try { startFg(buildNotification("$name: wird gelesen", 0, true)) } catch (e: Exception) {
            finish("Start nicht möglich (${e.message})")
            return START_NOT_STICKY
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hudbridge:import").also { it.acquire(3 * 60 * 60 * 1000L) }
        Thread { work(uri, name) }.start()
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun work(uri: Uri, name: String) {
        val size = sizeOf(uri)
        val started = System.currentTimeMillis()
        var lastNotif = 0L
        val listener = object : ImportListener {
            override fun progress(step: Int, steps: Int, fraction: Double, text: String, tilesDone: Int) {
                ImportBus.step = step; ImportBus.steps = steps; ImportBus.fraction = fraction
                ImportBus.stepText = text
                if (tilesDone > 0) ImportBus.tiles = tilesDone
                val el = (System.currentTimeMillis() - started) / 1000.0
                ImportBus.eta = if (fraction > 0.03 && el > 15) etaText(el * (1 - fraction) / fraction) else ""
                ImportBus.changed()
                val now = System.currentTimeMillis()
                if (now - lastNotif > 1000) {
                    lastNotif = now
                    val n = buildNotification("Schritt $step von $steps: $text", (fraction * 100).toInt(), true)
                    try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) } catch (_: Exception) { }
                }
            }
            override fun cancelled() = ImportBus.cancel
        }
        val outDir = TileStore.dir(this)
        val msg: String = try {
            val r = PbfImport.run({ contentResolver.openInputStream(uri) ?: throw java.io.IOException("Datei nicht lesbar") }, size, outDir, File(cacheDir, "import_tmp"), listener)
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
            val mb = r.bytes / 1_048_576.0
            val info = String.format(Locale.GERMANY, "Fertig: %,d Kacheln, %,.0f MB (%d s)", r.tiles, mb, r.seconds)
            val sp = getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            sp.edit()
                .putString(BridgeService.KEY_IMPORT_LOG, io.github.th3s1nc.osmandhudbridge.limit.ImportLog.add(
                    sp.getString(BridgeService.KEY_IMPORT_LOG, null), io.github.th3s1nc.osmandhudbridge.limit.ImportLog.Entry(name, date, r.stats)))
                .putString(BridgeService.KEY_IMPORT_INFO, info)
                .putString(BridgeService.KEY_IMPORT_SUB, "Eingelesen am $date aus $name")
                .apply()
            val total = outDir.listFiles { f -> f.name.endsWith(".json") }?.sumOf { it.length() } ?: 0L
            val limit = getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).getInt(BridgeService.KEY_CACHE_MB, BridgeService.DEFAULT_CACHE_MB).toLong() * 1_048_576L
            BridgeBus.log("Import der Straßendaten: $name, ${r.tiles} Kacheln, ${r.ways} Straßen, ${r.points} Punkte, ${r.seconds} s")
            if (total > limit) info + ". Achtung: mehr Daten als das Speicherlimit (Kartenspeicher), ältere Kacheln würden gelöscht. Erhöhe das Limit." else info
        } catch (_: ImportCancelled) {
            "Abgebrochen. Was bis dahin fertig war, bleibt gespeichert."
        } catch (e: Throwable) {
            BridgeBus.log("Import der Straßendaten fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            if (e is OutOfMemoryError) "Zu wenig Arbeitsspeicher für diese Datei. Nimm eine kleinere Region (zum Beispiel einen Regierungsbezirk)."
            else "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
        main.post { finish(msg) }
    }

    private fun finish(msg: String) {
        ImportBus.message = msg
        ImportBus.running = false
        ImportBus.changed()
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        val nm = getSystemService(NotificationManager::class.java)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val done = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Straßendaten importieren")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            nm.notify(NOTIF_ID + 1, done)
        } catch (_: Exception) { }
        // Der HUD-Dienst soll die neuen Kacheln sehen (Speicher der geladenen Kacheln)
        if (BridgeService.running) BridgeService.send(this, BridgeService.ACTION_RELOAD_TILES)
        stopSelf()
    }

    private fun etaText(sec: Double): String = when {
        sec < 60 -> "noch weniger als 1 Minute"
        sec < 3600 -> "noch ca. ${(sec / 60).toInt() + 1} Minuten"
        else -> String.format(Locale.GERMANY, "noch ca. %.1f Stunden", sec / 3600)
    }

    private fun nameOf(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: (uri.lastPathSegment ?: "Datei")
    } catch (_: Exception) { uri.lastPathSegment ?: "Datei" }

    private fun sizeOf(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) { 0L }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(text: String, percent: Int, withCancel: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Straßendaten importieren")
            .setContentText(if (ImportBus.eta.isNotEmpty()) "$text · $percent % · ${ImportBus.eta}" else "$text · $percent %")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (withCancel) {
            val cancel = PendingIntent.getService(
                this, 2, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "Abbrechen", cancel)
        }
        return b.build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Straßendaten importieren", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "import"
        private const val NOTIF_ID = 31
        const val ACTION_CANCEL = "io.github.th3s1nc.osmandhudbridge.IMPORT_CANCEL"

        fun start(ctx: Context, uri: Uri) {
            val i = Intent(ctx, ImportService::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
