package io.github.th3s1nc.osmandhudbridge.limit

/**
 * Wie oft das GPS gefragt wird. Mit verbundenem HUD jede Sekunde (Tempo und Pfeile flüssig), ohne HUD sparsam:
 * unterwegs alle 30 s, im Stand nur alle 5 min.
 * Reine Logik ohne Android, damit sie getestet werden kann.
 */
class GpsPolicy(
    private val fastHoldMs: Long = 60_000L,
    private val idleAfterMs: Long = 180_000L
) {
    enum class Mode(val intervalMs: Long) { FAST(1_000L), MOVING(30_000L), IDLE(300_000L) }

    private var lastFast = NONE
    private var lastMove = NONE

    /** Eine neue Ortung kam an; [speedMps] ist die vom GPS gemeldete Geschwindigkeit (null = unbekannt). */
    fun onFix(now: Long, speedMps: Float?) {
        if (speedMps != null && speedMps > MOVE_MPS) lastMove = now
    }

    fun mode(now: Long, hudReady: Boolean): Mode {
        if (lastMove == NONE) lastMove = now // direkt nach dem Start erst mal wie "unterwegs", bis klar ist, ob man steht
        if (hudReady) { lastFast = now; return Mode.FAST }
        if (lastFast != NONE && now - lastFast < fastHoldMs) return Mode.FAST // kurze Aussetzer des HUD nicht gleich bestrafen
        return if (now - lastMove < idleAfterMs) Mode.MOVING else Mode.IDLE
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        const val MOVE_MPS = 1.5f
    }
}
