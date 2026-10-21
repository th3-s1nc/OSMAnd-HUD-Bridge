package io.github.th3s1nc.osmandhudbridge

import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.th3s1nc.osmandhudbridge.limit.Region
import io.github.th3s1nc.osmandhudbridge.limit.RegionDownload
import io.github.th3s1nc.osmandhudbridge.limit.RegionIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Region laden": Auswahl aus dem Verzeichnis von Geofabrik, Rückfrage mit Größe (und bei Mobilfunk mit Warnung), dann Start des
 * [DownloadService]. Lädt nur auf Tipp des Nutzers. Das Verzeichnis wird bis zu 7 Tage zwischengespeichert.
 */
class RegionLoader(private val act: AppCompatActivity) {
    private val prefs get() = act.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)

    private fun offline() = prefs.getBoolean(BridgeService.KEY_OFFLINE, false)

    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()

    private fun isMetered(): Boolean = try {
        (act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** Knopf "Region laden". */
    fun open() {
        if (offline()) { toast("Offline-Daten ist an: es wird nichts aus dem Internet geladen."); return }
        if (DownloadBus.state != DlState.IDLE) { toast("Es läuft oder pausiert schon ein Download."); return }
        withIndex { ix -> picker(ix, ix.startId()) }
    }

    /** Knopf "Aktualisieren": die zuletzt geladene Region noch einmal laden. */
    fun update() {
        if (offline()) { toast("Offline-Daten ist an: es wird nichts aus dem Internet geladen."); return }
        if (DownloadBus.state != DlState.IDLE) { toast("Es läuft oder pausiert schon ein Download."); return }
        val url = prefs.getString(BridgeService.KEY_REGION_URL, null) ?: return
        val id = prefs.getString(BridgeService.KEY_REGION_ID, null) ?: return
        val name = prefs.getString(BridgeService.KEY_REGION_NAME, null) ?: RegionDownload.nameFromUrl(url)
        confirm(Region(id, name, null, url), false)
    }

    /** Knopf "Fortsetzen" bei einem pausierten Download. */
    fun resume() {
        if (offline()) { toast("Offline-Daten ist an: es wird nichts aus dem Internet geladen."); return }
        if (isMetered() && !prefs.getBoolean(BridgeService.KEY_DL_MOBILE, false)) {
            AlertDialog.Builder(act)
                .setTitle("Mobile Daten verwenden?")
                .setMessage("Du bist nicht im WLAN. Der Rest der Datei wird über Mobilfunk geladen. Tipp: Mit dem Schalter \"Mobile Daten\" geht das künftig ohne Rückfrage.")
                .setPositiveButton("Trotzdem laden") { _, _ -> DownloadService.start(act, null, null, null, true) }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else DownloadService.start(act, null, null, null, false)
    }

    /** Knopf "Verwerfen" bei einem pausierten Download. */
    fun discard() {
        AlertDialog.Builder(act)
            .setTitle("Verwerfen?")
            .setMessage("Die halb geladene Datei wird gelöscht.")
            .setPositiveButton("Verwerfen") { _, _ -> DownloadService.discard(act) }
            .setNegativeButton("Zurück", null)
            .show()
    }

    // ---------------- Verzeichnis ----------------

    private fun withIndex(done: (RegionIndex) -> Unit) {
        val cache = File(act.cacheDir, "region_index.json")
        Toast.makeText(act, "Verzeichnis wird geladen …", Toast.LENGTH_SHORT).show()
        Thread {
            val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 7L * 24 * 3600 * 1000
            val text: String? = try {
                if (fresh) cache.readText() else fetchIndex(cache)
            } catch (_: Exception) {
                try { if (cache.isFile) cache.readText() else null } catch (_: Exception) { null }
            }
            val ix = try { text?.let { RegionIndex.parse(it) } } catch (_: Exception) { null }
            act.runOnUiThread {
                if (ix == null || ix.size == 0) toast("Das Verzeichnis der Regionen konnte nicht geladen werden. Internet prüfen und noch einmal versuchen.")
                else done(ix)
            }
        }.start()
    }

    private fun fetchIndex(cache: File): String {
        val c = URL(INDEX_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) throw java.io.IOException("Fehler ${c.responseCode}")
            val t = c.inputStream.bufferedReader().use { it.readText() }
            RegionIndex.parse(t) // nur speichern, was sich lesen lässt
            cache.writeText(t)
            return t
        } finally {
            c.disconnect()
        }
    }

    // ---------------- Auswahl ----------------

    private fun picker(ix: RegionIndex, nodeId: String?) {
        val node = nodeId?.let { ix.get(it) }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val parentId = node?.parent
        if (node != null && parentId != null && ix.get(parentId) != null) {
            labels.add("‹ Zurück"); actions.add { picker(ix, parentId) }
        }
        if (node?.pbfUrl != null) {
            val big = ix.children(node.id).isNotEmpty()
            labels.add("Diese Region laden: ${node.name}"); actions.add { confirm(node, big) }
        }
        for (k in ix.children(nodeId)) {
            val sub = ix.children(k.id).isNotEmpty()
            labels.add(if (sub) "${k.name}  ›" else k.name)
            actions.add { if (sub) picker(ix, k.id) else confirm(k, false) }
        }
        if (labels.isEmpty()) { toast("In dieser Auswahl gibt es nichts zu laden."); return }
        val title = if (nodeId == null) "Region wählen" else ix.path(nodeId).joinToString(" › ") { it.name }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ---------------- Rückfrage und Start ----------------

    private fun confirm(r: Region, big: Boolean) {
        val url = r.pbfUrl ?: return
        Toast.makeText(act, "Größe wird geprüft …", Toast.LENGTH_SHORT).show()
        Thread {
            val size = headSize(url)
            act.runOnUiThread { showConfirm(r, url, size, big) }
        }.start()
    }

    private fun headSize(url: String): Long = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode == 200) c.contentLengthLong else 0L
        } finally { c.disconnect() }
    } catch (_: Exception) { 0L }

    private fun showConfirm(r: Region, url: String, size: Long, big: Boolean) {
        if (size > 0 && !RegionDownload.enoughSpace(act.filesDir.usableSpace, size)) {
            AlertDialog.Builder(act)
                .setTitle("Zu wenig Speicher")
                .setMessage("Frei sind etwa ${RegionDownload.sizeText(act.filesDir.usableSpace)}, gebraucht werden etwa ${RegionDownload.sizeText(RegionDownload.neededBytes(size))} (Datei, Arbeitsraum und fertige Kacheln). Schaffe Platz oder wähle eine kleinere Region.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val mobileSwitch = prefs.getBoolean(BridgeService.KEY_DL_MOBILE, false)
        val metered = isMetered() && !mobileSwitch // mit Schalter "Mobile Daten" keine Rückfrage
        val msg = StringBuilder()
        msg.append(r.name).append("\nGröße: ").append(RegionDownload.sizeText(size))
        msg.append("\n\nDie Datei wird geladen, eingelesen und danach wieder gelöscht.")
        if (big) msg.append("\n\nGroße Region: Beim Einlesen braucht sie viel Arbeitsspeicher und kann scheitern. Lieber die kleineren Teile einzeln laden.")
        if (metered) msg.append("\n\nDu bist nicht im WLAN. Das verbraucht mobile Daten.")
        else if (mobileSwitch) msg.append("\n\n\"Mobile Daten\" ist an: Ohne WLAN lädt die App über Mobilfunk weiter. Mit WLAN nutzt sie immer das WLAN.")
        AlertDialog.Builder(act)
            .setTitle(if (metered) "Mobile Daten verwenden?" else "Region laden?")
            .setMessage(msg.toString())
            .setPositiveButton(if (metered) "Trotzdem laden" else "Laden") { _, _ ->
                // Ein neuer Download ersetzt eine liegengebliebene Teildatei
                DownloadService.discard(act)
                DownloadService.start(act, url, r.id, r.name, metered)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    companion object {
        private const val INDEX_URL = "https://download.geofabrik.de/index-v1-nogeom.json"
        private const val UA = "OSMAndHudBridge (Android, private use; manual region download)"
    }
}
