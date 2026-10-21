package io.github.th3s1nc.osmandhudbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.th3s1nc.osmandhudbridge.limit.RegionDownload
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class DlState { IDLE, RUNNING, PAUSED }

/** Stand des Region-Downloads für die Oberfläche. */
object DownloadBus {
    @Volatile var state = DlState.IDLE
    @Volatile var name = ""
    @Volatile var done = 0L
    @Volatile var total = 0L
    /** Kurzer Zusatz unter dem Balken (zum Beispiel "WLAN" oder warum pausiert wurde). */
    @Volatile var note = ""
    @Volatile var pauseRequested = false
    @Volatile var mobileOk = false
    /** Pausiert, weil das WLAN weg war und "Mobile Daten" aus ist (dann setzt das Einschalten des Schalters fort). */
    @Volatile var pausedForMobile = false
    @Volatile var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { onChange?.invoke() } }
}

/**
 * Lädt eine Region (.osm.pbf) von Geofabrik, mit Pausieren und Fortsetzen (Teildatei bleibt liegen, der Server liefert den Rest).
 * Danach startet der Import; die Datei wird nach dem Import gelöscht. Lädt nur auf Tipp des Nutzers, nie von selbst.
 */
class DownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) { DownloadBus.pauseRequested = true; return START_NOT_STICKY }
        val sp = getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
        val url = intent?.getStringExtra(EXTRA_URL) ?: sp.getString(BridgeService.KEY_DL_URL, null)
        val id = intent?.getStringExtra(EXTRA_ID) ?: sp.getString(BridgeService.KEY_DL_ID, null)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: sp.getString(BridgeService.KEY_DL_NAME, null) ?: "Region"
        if (url == null || DownloadBus.state == DlState.RUNNING) {
            // Nach startForegroundService ist startForeground Pflicht, sonst stürzt die App ab
            try { startFg(buildNotification(name, 0, 0)) } catch (_: Exception) { }
            if (DownloadBus.state != DlState.RUNNING) stopSelf()
            return START_NOT_STICKY
        }
        sp.edit().putString(BridgeService.KEY_DL_URL, url).putString(BridgeService.KEY_DL_ID, id).putString(BridgeService.KEY_DL_NAME, name).apply()
        DownloadBus.apply {
            state = DlState.RUNNING; this.name = name; pauseRequested = false; note = ""
            mobileOk = intent?.getBooleanExtra(EXTRA_MOBILE_OK, false) ?: false
        }
        DownloadBus.changed()
        try { startFg(buildNotification(name, DownloadBus.done, DownloadBus.total)) } catch (e: Exception) {
            DownloadBus.state = DlState.PAUSED; DownloadBus.note = "Start nicht möglich (${e.message})"
            DownloadBus.changed()
            stopSelf()
            return START_NOT_STICKY
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hudbridge:download").also { it.acquire(3 * 60 * 60 * 1000L) }
        Thread { download(url, id, name) }.start()
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun isMetered(): Boolean = try {
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    private enum class Outcome { DONE, STOPPED, BROKEN, SWITCH }

    @Volatile private var progressAt = 0L

    /** Mobilfunk erlaubt: durch den Schalter "Mobile Daten" oder durch "Trotzdem laden" bei diesem Start. */
    private fun mobileAllowed(): Boolean =
        DownloadBus.mobileOk || getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).getBoolean(BridgeService.KEY_DL_MOBILE, false)

    private fun download(url: String, id: String?, name: String) {
        try {
            progressAt = System.currentTimeMillis()
            while (true) {
                when (attempt(url, id, name)) {
                    Outcome.DONE, Outcome.STOPPED -> return // fertig oder schon pausiert/abgebrochen gemeldet
                    Outcome.SWITCH -> continue // WLAN ist wieder da: sofort dort weiterladen
                    Outcome.BROKEN -> {
                        // Verbindung weg (zum Beispiel WLAN verlassen). Mit erlaubten mobilen Daten selbst neu verbinden, sonst pausieren.
                        if (!mobileAllowed() || System.currentTimeMillis() - progressAt > 5 * 60_000L) {
                            pause("Pausiert: Verbindung unterbrochen", DownloadBus.done, DownloadBus.total)
                            return
                        }
                        DownloadBus.note = "Verbindung weg, neuer Versuch …"
                        DownloadBus.changed()
                        var waited = 0
                        while (waited < 3000) {
                            if (DownloadBus.pauseRequested) { pause("Pausiert", DownloadBus.done, DownloadBus.total); return }
                            try { Thread.sleep(250) } catch (_: InterruptedException) { }
                            waited += 250
                        }
                    }
                }
            }
        } finally {
            try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        }
    }

    /** Ein Verbindungsversuch: lädt ab der Teildatei weiter, bis fertig, pausiert, unterbrochen oder ins WLAN gewechselt werden soll. */
    private fun attempt(url: String, id: String?, name: String): Outcome {
        val sp = getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
        val part = partFile(this)
        var offset = RegionDownload.resumeOffset(part.length(), sp.getLong(BridgeService.KEY_DL_TOTAL, 0L))
        if (offset == 0L && part.exists()) part.delete()
        var conn: HttpURLConnection? = null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OSMAndHudBridge (Android, private use; manual region download)")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            conn = c
            val startedMetered = isMetered()
            if (startedMetered && !mobileAllowed()) { pause("Pausiert: kein WLAN, Mobile Daten sind aus", offset, DownloadBus.total, true); return Outcome.STOPPED }
            val code = c.responseCode
            if (code != 200 && code != 206) { fail("Server antwortet mit Fehler $code"); return Outcome.STOPPED }
            if (code == 200 && offset > 0) offset = 0 // Server kann nicht fortsetzen: von vorn
            val len = c.contentLengthLong
            val total = if (len > 0) offset + len else 0L
            if (!RegionDownload.enoughSpace(filesDir.usableSpace, total, offset)) {
                fail("Zu wenig freier Speicher (gebraucht werden etwa ${RegionDownload.sizeText(RegionDownload.neededBytes(total))})")
                return Outcome.STOPPED
            }
            sp.edit().putLong(BridgeService.KEY_DL_TOTAL, total).apply()
            DownloadBus.total = total
            DownloadBus.done = offset
            DownloadBus.note = if (startedMetered) "Mobilfunk" else "WLAN"
            DownloadBus.pausedForMobile = false
            DownloadBus.changed()
            var done = offset
            var lastUi = 0L
            var lastNet = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            FileOutputStream(part, offset > 0).use { out ->
                c.inputStream.use { input ->
                    while (true) {
                        if (DownloadBus.pauseRequested) { pause("Pausiert", done, total); return Outcome.STOPPED }
                        val now = System.currentTimeMillis()
                        if (now - lastNet > 3000) {
                            lastNet = now
                            val metered = isMetered()
                            if (metered && !mobileAllowed()) { pause("Pausiert: WLAN weg, Mobile Daten sind aus", done, total, true); return Outcome.STOPPED }
                            if (startedMetered && !metered) return Outcome.SWITCH // WLAN ist wieder da
                            DownloadBus.note = if (metered) "Mobilfunk" else "WLAN"
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progressAt = now
                        DownloadBus.done = done
                        if (now - lastUi > 500) {
                            lastUi = now
                            DownloadBus.changed()
                            try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(name, done, total)) } catch (_: Exception) { }
                        }
                    }
                }
            }
            if (total > 0 && done < total) return Outcome.BROKEN
            finishDownload(part, id, name, url)
            return Outcome.DONE
        } catch (e: java.io.IOException) {
            return Outcome.BROKEN
        } catch (e: Throwable) {
            fail("Fehler: ${e.message ?: e.javaClass.simpleName}")
            return Outcome.STOPPED
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun pause(why: String, done: Long, total: Long, forMobile: Boolean = false) {
        DownloadBus.pausedForMobile = forMobile
        DownloadBus.state = DlState.PAUSED
        DownloadBus.pauseRequested = false
        DownloadBus.done = done
        DownloadBus.note = why
        DownloadBus.changed()
        endForeground("${DownloadBus.name}: $why (${(RegionDownload.fraction(done, total) * 100).toInt()} %)")
    }

    private fun fail(msg: String) {
        // Teildatei und Merker bleiben, damit man es später wieder versuchen kann
        DownloadBus.state = DlState.PAUSED
        DownloadBus.pauseRequested = false
        DownloadBus.note = msg
        DownloadBus.changed()
        endForeground("${DownloadBus.name}: $msg")
    }

    private fun finishDownload(part: File, id: String?, name: String, url: String) {
        val ready = File(filesDir, READY_NAME)
        ready.delete()
        if (!part.renameTo(ready)) { fail("Datei konnte nicht umbenannt werden"); return }
        val sp = getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
        sp.edit().remove(BridgeService.KEY_DL_URL).remove(BridgeService.KEY_DL_ID).remove(BridgeService.KEY_DL_NAME).remove(BridgeService.KEY_DL_TOTAL).apply()
        DownloadBus.state = DlState.IDLE
        DownloadBus.done = 0; DownloadBus.total = 0; DownloadBus.note = ""
        DownloadBus.changed()
        main.post {
            ImportService.start(this, Uri.fromFile(ready), id, name, url, true)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            stopSelf()
        }
    }

    private fun endForeground(text: String) {
        main.post {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val n = NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Karte laden")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build()
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
            } catch (_: Exception) { }
            stopSelf()
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(name: String, done: Long, total: Long): Notification {
        val pct = (RegionDownload.fraction(done, total) * 100).toInt()
        val pause = PendingIntent.getService(
            this, 3, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("$name wird geladen")
            .setContentText(if (total > 0) "$pct % · ${RegionDownload.sizeText(done)} von ${RegionDownload.sizeText(total)}" else RegionDownload.sizeText(done))
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Pausieren", pause)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Karten laden", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "region_download"
        private const val NOTIF_ID = 41
        const val ACTION_PAUSE = "io.github.th3s1nc.osmandhudbridge.DOWNLOAD_PAUSE"
        private const val EXTRA_URL = "url"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MOBILE_OK = "mobile_ok"
        const val READY_NAME = "region.osm.pbf"

        fun partFile(ctx: Context) = File(ctx.filesDir, "region_download.part")

        /** Lädt eine Region; [url] = null setzt eine pausierte Ladung fort. */
        fun start(ctx: Context, url: String?, id: String?, name: String?, mobileOk: Boolean) {
            val i = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_MOBILE_OK, mobileOk)
            if (url != null) i.putExtra(EXTRA_URL, url).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Verwirft eine pausierte Ladung: Teildatei weg, Merker weg. */
        fun discard(ctx: Context) {
            partFile(ctx).delete()
            ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit()
                .remove(BridgeService.KEY_DL_URL).remove(BridgeService.KEY_DL_ID).remove(BridgeService.KEY_DL_NAME).remove(BridgeService.KEY_DL_TOTAL).apply()
            DownloadBus.state = DlState.IDLE
            DownloadBus.done = 0; DownloadBus.total = 0; DownloadBus.note = ""
            DownloadBus.changed()
        }

        /** Nach einem App-Neustart: eine liegengebliebene Teildatei als "pausiert" anzeigen. */
        fun restore(ctx: Context) {
            if (DownloadBus.state != DlState.IDLE) return
            val sp = ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            if (sp.getString(BridgeService.KEY_DL_URL, null) == null) return
            val part = partFile(ctx)
            if (!part.exists()) {
                sp.edit().remove(BridgeService.KEY_DL_URL).remove(BridgeService.KEY_DL_ID).remove(BridgeService.KEY_DL_NAME).remove(BridgeService.KEY_DL_TOTAL).apply()
                return
            }
            DownloadBus.name = sp.getString(BridgeService.KEY_DL_NAME, null) ?: "Region"
            DownloadBus.done = part.length()
            DownloadBus.total = sp.getLong(BridgeService.KEY_DL_TOTAL, 0L)
            DownloadBus.note = "Pausiert"
            DownloadBus.state = DlState.PAUSED
        }
    }
}
