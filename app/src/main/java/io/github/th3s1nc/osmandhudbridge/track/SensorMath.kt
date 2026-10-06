package io.github.th3s1nc.osmandhudbridge.track

/**
 * Höhe aus Luftdruck (Barometer), die langsam an die GPS-Höhe angeglichen wird. Der Druck folgt kleinen Höhenänderungen
 * viel genauer als das GPS, driftet aber mit dem Wetter; das GPS gibt das Niveau vor. Ohne Barometer gilt die geglättete GPS-Höhe.
 */
class AltitudeFilter {
    private var baro: Double? = null
    private var offset: Double? = null
    private var offsetN = 0
    private var gps: Double? = null

    fun onPressure(hPa: Float) {
        val a = 44330.0 * (1.0 - Math.pow(hPa / 1013.25, 0.1903))
        val b = baro
        baro = if (b == null) a else b + (a - b) * 0.1
    }

    fun onGps(altM: Double) {
        val g = gps
        gps = if (g == null) altM else g + (altM - g) * 0.2
        val b = baro ?: return
        val d = altM - b
        offsetN++
        val o = offset
        // erst ein laufender Mittelwert, danach sehr langsam nachführen
        val alpha = if (o == null) 1.0 else maxOf(1.0 / offsetN, 0.02)
        offset = if (o == null) d else o + (d - o) * alpha
    }

    fun altitude(): Double? {
        val b = baro
        val o = offset
        return if (b != null && o != null) b + o else gps
    }
}

/**
 * Schräglage-Schätzung aus Drehsensor und GPS-Tempo: Die Drehrate um die senkrechte Achse (aus der Schwerkraft errechnet,
 * deshalb egal wie das Handy liegt) mal Tempo geteilt durch g ergibt den Tangens des Winkels. Das ist nur eine Schätzung für
 * gleichmäßige Kurven. Unter [MIN_MPS] gibt es keinen Wert. Rechts positiv.
 */
class LeanEstimator {
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var haveG = false
    private var yaw = 0.0

    /** Schwerkraft (oder Beschleunigung) im Handy-Koordinatensystem. */
    fun onGravity(x: Float, y: Float, z: Float) {
        if (!haveG) { gx = x.toDouble(); gy = y.toDouble(); gz = z.toDouble(); haveG = true; return }
        gx += (x - gx) * 0.1; gy += (y - gy) * 0.1; gz += (z - gz) * 0.1
    }

    /** Drehrate in rad/s im Handy-Koordinatensystem. */
    fun onGyro(x: Float, y: Float, z: Float) {
        if (!haveG) return
        val n = Math.sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1.0) return
        // Android-Schwerkraft zeigt nach oben: Drehung gegen den Uhrzeigersinn (Linkskurve) ist positiv, daher Vorzeichen drehen
        val r = -(x * gx + y * gy + z * gz) / n
        yaw += (r - yaw) * 0.2
    }

    fun lean(speedMps: Float): Float? {
        if (!haveG || speedMps < MIN_MPS) return null
        val deg = Math.toDegrees(Math.atan(speedMps * yaw / 9.81)).toFloat()
        return deg.coerceIn(-MAX_DEG, MAX_DEG)
    }

    companion object {
        const val MIN_MPS = 4.2f // ca. 15 km/h
        const val MAX_DEG = 65f
    }
}
