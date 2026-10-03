package io.github.th3s1nc.osmandhudbridge.nav

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import io.github.th3s1nc.osmandhudbridge.BridgeBus

/**
 * Meldet, wenn in einer Musik-App (Spotify, YouTube Music, ...) ein neuer Titel beginnt. Nutzt die Mediensitzungen von Android;
 * dafür reicht der Benachrichtigungszugriff, den die App ohnehin hat. Pausieren und Weiterspielen zählen nicht als neuer Titel.
 */
class MusicWatcher(
    private val ctx: Context,
    private val handler: Handler,
    private val onTrack: (artist: String?, title: String) -> Unit
) {
    private val msm: MediaSessionManager? = ctx.getSystemService(MediaSessionManager::class.java)
    private val comp = ComponentName(ctx, OsmAndNotificationListener::class.java)
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastKey = HashMap<String, String>()
    private val pending = HashMap<String, Runnable>()
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> register(list.orEmpty()) }

    /** false = keine Erlaubnis (Benachrichtigungszugriff fehlt). */
    fun start(): Boolean {
        if (started) return true
        val m = msm ?: return false
        return try {
            m.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
            started = true
            register(m.getActiveSessions(comp))
            true
        } catch (e: SecurityException) {
            BridgeBus.log("Spotify-Anzeige: Benachrichtigungszugriff fehlt (Werkzeuge, Einrichtung)")
            false
        } catch (e: Exception) {
            BridgeBus.log("Spotify-Anzeige: Fehler (${e.message ?: e.javaClass.simpleName})")
            false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        callbacks.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        callbacks.clear()
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
        lastKey.clear()
    }

    private fun register(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }
        for (c in callbacks.keys.toList()) {
            if (c.sessionToken !in tokens) {
                try { c.unregisterCallback(callbacks.getValue(c)) } catch (_: Exception) {}
                callbacks.remove(c)
            }
        }
        for (c in list) {
            if (callbacks.keys.any { it.sessionToken == c.sessionToken }) continue
            val pkg = c.packageName ?: continue
            if (pkg == ctx.packageName || pkg.startsWith("net.osmand")) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { handle(c, metadata, silent = false) }
            }
            try {
                c.registerCallback(cb, handler)
                callbacks[c] = cb
                handle(c, c.metadata, silent = true) // der gerade laufende Titel gilt als bekannt
            } catch (_: Exception) { }
        }
    }

    private fun handle(c: MediaController, md: MediaMetadata?, silent: Boolean) {
        val pkg = c.packageName ?: return
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim()
        val key = "$artist|$title"
        if (lastKey[pkg] == key) return
        lastKey[pkg] = key
        if (silent) return
        // Spotify meldet Titel und Interpret oft in zwei Schritten: kurz warten, dann den letzten Stand nehmen
        pending[pkg]?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(pkg)
            val st = c.playbackState?.state
            val playing = st == null || st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING
            if (playing && !isAd(artist, title)) onTrack(artist, title)
        }
        pending[pkg] = r
        handler.postDelayed(r, 700)
    }

    private fun isAd(artist: String?, title: String): Boolean =
        title.equals("Advertisement", true) || title.equals("Werbung", true) || artist.equals("Spotify", true)
}
