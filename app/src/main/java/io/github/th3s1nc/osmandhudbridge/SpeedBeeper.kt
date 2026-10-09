package io.github.th3s1nc.osmandhudbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import kotlin.math.PI
import kotlin.math.sin

/**
 * Warnton "dü-dü-dü" bei Tempoüberschreitung und vor Blitzern, ein einzelner höherer Ton für Hinweise (Bahnübergang, Zebrastreifen, Verkehrsberuhigung). Läuft wie eine Navigationsansage: Musik wird kurz leiser, der Ton geht
 * dorthin, wo das Handy gerade Audio ausgibt (z. B. Bluetooth zur Intercom). Vorne eine kurze Stille, damit
 * Bluetooth-Geräte, die erst aufwachen müssen, den Anfang nicht verschlucken.
 */
class SpeedBeeper(private val context: Context, private val handler: Handler) {
    private val warnPcm: ShortArray = build(FREQ, BEEP_MS, 3)
    private val hintPcm: ShortArray = build(HINT_FREQ, HINT_MS, 1)

    /** [hint] = false: dreimal "Dü" (Tempo, Blitzer). [hint] = true: ein einzelner Ton (Bahnübergang, Zebrastreifen, Verkehrsberuhigung, Kurve). */
    fun beep(hint: Boolean = false) {
        val pcm = if (hint) hintPcm else warnPcm
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(focus)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = pcm.size * 1000L / RATE
            handler.postDelayed({
                try { track.release() } catch (_: Exception) { }
                try { am.abandonAudioFocusRequest(focus) } catch (_: Exception) { }
            }, durationMs + 300)
            BridgeBus.log("Warnton gespielt")
        } catch (e: Exception) {
            BridgeBus.log("Warnton nicht möglich (${e.message})")
        }
    }

    private fun build(freq: Double, toneMs: Int, count: Int): ShortArray {
        val out = ArrayList<Short>()
        fun silence(ms: Int) { repeat(RATE * ms / 1000) { out.add(0) } }
        fun tone(ms: Int) {
            val n = RATE * ms / 1000
            val fade = RATE * 12 / 1000
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                val t = 2 * PI * freq * i / RATE
                val s = (sin(t) + 0.2 * sin(2 * t) + 0.08 * sin(3 * t)) / 1.28
                out.add((s * env * 0.85 * Short.MAX_VALUE).toInt().toShort())
            }
        }
        silence(LEAD_MS)
        for (k in 0 until count) {
            if (k > 0) silence(GAP_MS)
            tone(toneMs)
        }
        return out.toShortArray()
    }

    private companion object {
        const val RATE = 44_100
        const val FREQ = 950.0
        const val BEEP_MS = 200
        const val GAP_MS = 120
        const val LEAD_MS = 250
        const val HINT_FREQ = 780.0
        const val HINT_MS = 340
    }
}
