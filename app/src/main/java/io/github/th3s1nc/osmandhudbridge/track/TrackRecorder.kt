package io.github.th3s1nc.osmandhudbridge.track

import android.content.Context
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import java.io.BufferedWriter
import java.io.FileWriter
import io.github.th3s1nc.osmandhudbridge.limit.Tx

/**
 * Die laufende Aufnahme (nur auf dem Main-Thread benutzen). Der Dienst meldet jede GPS-Ortung mit [onFix], die Oberfläche
 * startet und beendet. Jeder aufgenommene Punkt wird sofort in current.csv gesichert.
 */
object TrackRecorder {
    @Volatile var session: TrackSession? = null
        private set
    private var writer: BufferedWriter? = null

    val active: Boolean get() = session != null

    /** Neue Aufnahme. Eine ältere, nicht gespeicherte Sicherung wird dabei überschrieben. */
    fun start(ctx: Context) {
        if (active) return
        session = TrackSession()
        openWriter(ctx, append = false)
        BridgeBus.log(Tx.t("Aufzeichnung gestartet", "Recording started"))
    }

    /** Macht mit einer zuvor beendeten Aufnahme weiter ([saved] kommt aus [finish] oder der Sicherung). */
    fun resume(ctx: Context, saved: List<TrackPoint>) {
        if (active) return
        session = TrackSession().also { it.restore(saved) }
        openWriter(ctx, append = true)
        BridgeBus.log(Tx.t("Aufzeichnung fortgesetzt", "Recording resumed"))
    }

    /** Beendet die Aufnahme und gibt die Punkte zurück. Die Sicherung bleibt, bis gespeichert oder verworfen wird. */
    fun finish(): List<TrackPoint> {
        val s = session ?: return emptyList()
        session = null
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        BridgeBus.log(Tx.t("Aufzeichnung beendet, ${s.points.size} Punkte", "Recording ended, ${s.points.size} points"))
        return s.points.toList()
    }

    fun onFix(p: TrackPoint) {
        val s = session ?: return
        if (s.onFix(p)) {
            try {
                writer?.write(RideStore.line(p))
                writer?.flush()
            } catch (e: Exception) {
                BridgeBus.log(Tx.t("Sichern der Fahrt gescheitert: ${e.message}", "Backing up the ride failed: ${e.message}"))
            }
        }
    }

    private fun openWriter(ctx: Context, append: Boolean) {
        try {
            writer = BufferedWriter(FileWriter(RideStore.currentFile(ctx), append))
        } catch (e: Exception) {
            writer = null
            BridgeBus.log(Tx.t("Sicherung der Fahrt nicht möglich: ${e.message}", "Backup of the ride not possible: ${e.message}"))
        }
    }
}
