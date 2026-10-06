package io.github.th3s1nc.osmandhudbridge.limit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import io.github.th3s1nc.osmandhudbridge.BridgeService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lädt die Karte rund um den zuletzt gemerkten Standort im Voraus, auch wenn der Dienst nicht läuft
 * ("Im Hintergrund vorladen"). Android startet den Auftrag selbst (nur WLAN, Akku nicht schwach).
 * Läuft auch bei "Bridge aktiv" aus (das HUD wird dabei nicht berührt). Läuft der Dienst, übernimmt dieser das Vorladen und der Auftrag tut nichts.
 */
class PreloadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        if (BridgeService.running) return Result.success() // der Dienst lädt selbst (Touren und Kreis)
        BridgeBus.init(applicationContext)
        BridgeBus.log("Hintergrund-Laden gestartet")
        loaded = 0
        val r = work()
        BridgeBus.log("Hintergrund-Laden beendet: $loaded Kacheln neu geladen" + (if (r is Result.Retry) ", wird später fortgesetzt" else ""))
        return r
    }

    private var loaded = 0

    private fun work(): Result {
        val ctx = applicationContext
        val sp = ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
        if (!networkAllowed(sp.getBoolean(BridgeService.KEY_PRELOAD_MOBILE, false))) return Result.retry()

        val cache = TileCache(TileStore.dir(ctx))
        cache.maxBytes = sp.getInt(BridgeService.KEY_CACHE_MB, BridgeService.DEFAULT_CACHE_MB).coerceAtMost(BridgeService.MAX_CACHE_MB).toLong() * 1024 * 1024
        val started = System.currentTimeMillis()
        val dl = Downloader { loaded++ }

        // 1. importierte Touren (GPX), egal ob der Hintergrund-Schalter an ist
        val tours = TourStore(File(TileStore.dir(ctx).parentFile, "tours"))
        while (true) {
            val key = tours.nextMissing(cache, System.currentTimeMillis()) ?: break
            val r = dl.fetch(key, cache) ?: return Result.retry()
            if (r == Downloader.STOP || isStopped || BridgeService.running || System.currentTimeMillis() - started > BUDGET_MS) return Result.retry()
        }

        // 2. Kreis um den letzten Standort: mit Hintergrund-Schalter in der Saison, im Vorlauf vor der Saison immer, sonst nicht
        val km = sp.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM)
        val season = BridgeService.seasonState(sp)
        val circleAllowed = when (season) {
            SeasonPlan.State.IN_SEASON -> sp.getBoolean(BridgeService.KEY_PRELOAD_BG, false)
            SeasonPlan.State.LEAD -> true
            SeasonPlan.State.OFF -> false
        }
        if (!circleAllowed || km <= 0) return Result.success()
        val pos = sp.getString(BridgeService.KEY_LAST_POS, null)?.split(",")
        val lat = pos?.getOrNull(0)?.toDoubleOrNull()
        val lon = pos?.getOrNull(1)?.toDoubleOrNull()
        if (pos == null || pos.size != 2 || lat == null || lon == null) {
            BridgeBus.updatePreload("Wartet auf den Standort (Dienst einmal starten)")
            return Result.success()
        }
        val plan = PreloadPlanner.tilesInRadius(lat, lon, km)
        val now = System.currentTimeMillis()
        val missing = plan.filter { !cache.isFresh(it, now, TileCache.PRELOAD_SKIP_MS) }
        var done = plan.size - missing.size
        if (missing.isEmpty()) {
            BridgeBus.updatePreload("Fertig: ${plan.size} Kacheln im Umkreis von $km km gespeichert")
            return Result.success()
        }
        for (key in missing) {
            if (isStopped || BridgeService.running) return Result.retry()
            if (System.currentTimeMillis() - started > BUDGET_MS) return Result.retry()
            val r = dl.fetch(key, cache)
            if (r == null) {
                BridgeBus.updatePreload("Hintergrund: Server nicht erreichbar ($done von ${plan.size} Kacheln)")
                return Result.retry()
            }
            if (r == Downloader.STOP) return Result.retry()
            done++
            BridgeBus.updatePreload("Hintergrund: $done von ${plan.size} Kacheln")
        }
        BridgeBus.updatePreload("Fertig: ${plan.size} Kacheln im Umkreis von $km km gespeichert")
        return Result.success()
    }

    /** Lädt eine Kachel (bei Fehler der nächste Server), mit Pause danach. Gibt null zurück, wenn dreimal in Folge alles fehlschlug. */
    private class Downloader(private val onLoaded: () -> Unit) {
        private var failuresInRow = 0

        fun fetch(key: TileKey, cache: TileCache): Int? {
            val q = TileDownloader.query(key)
            val text: String? = try { TileDownloader.downloadSequential(q) } catch (_: Exception) { null }
            if (text == null) {
                failuresInRow++
                return if (failuresInRow >= 3) null else OK
            }
            failuresInRow = 0
            cache.write(key, text)
            onLoaded()
            try { Thread.sleep(GAP_MS) } catch (_: InterruptedException) { return STOP }
            return OK
        }

        companion object { const val OK = 0; const val STOP = 1 }
    }

    /** WLAN bzw. Netz ohne Volumenbegrenzung; Mobilfunk nur wenn erlaubt, im Roaming nie. */
    private fun networkAllowed(mobileAllowed: Boolean): Boolean = try {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) &&
            (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || mobileAllowed)
    } catch (_: Exception) { false }

    companion object {
        private const val NAME = "preload_bg"
        private const val NAME_NOW = "preload_bg_now"
        private const val GAP_MS = 2_500L
        private const val BUDGET_MS = 8 * 60_000L

        /** Nach einem GPX-Import: Touren sofort laden lassen (sobald WLAN da ist), unabhängig vom Hintergrund-Schalter. */
        fun runTours(ctx: Context) {
            try {
                val sp = ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
                val mobile = sp.getBoolean(BridgeService.KEY_PRELOAD_MOBILE, false)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build()
                WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                    "tour_now", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PreloadWorker>().setConstraints(constraints).build()
                )
            } catch (_: Exception) { }
        }

        /** Je nach Einstellung den Hintergrund-Auftrag einplanen oder entfernen. */
        fun apply(ctx: Context) {
            try {
                val sp = ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
                val wm = WorkManager.getInstance(ctx.applicationContext)
                val on = (sp.getBoolean(BridgeService.KEY_PRELOAD_BG, false) || sp.getBoolean(BridgeService.KEY_SEASON_ON, false)) &&
                    sp.getInt(BridgeService.KEY_PRELOAD_KM, BridgeService.DEFAULT_PRELOAD_KM) > 0
                if (!on) {
                    wm.cancelUniqueWork(NAME)
                    wm.cancelUniqueWork(NAME_NOW)
                    return
                }
                val mobile = sp.getBoolean(BridgeService.KEY_PRELOAD_MOBILE, false)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                wm.enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<PreloadWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
                )
                wm.enqueueUniqueWork(
                    NAME_NOW, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<PreloadWorker>().setConstraints(constraints).build()
                )
            } catch (_: Exception) { }
        }
    }
}
