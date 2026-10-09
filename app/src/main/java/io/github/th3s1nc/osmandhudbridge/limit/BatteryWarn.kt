package io.github.th3s1nc.osmandhudbridge.limit

/**
 * Handy-Akku-Warnung: an, sobald der Akku auf oder unter die Grenze fällt (und nicht lädt). Wieder aus erst bei
 * Grenze + [HYSTERESIS] Prozent oder beim Laden, damit das Symbol bei schwankenden Werten nicht flackert.
 */
class BatteryWarn {
    var active = false
        private set

    /** Gibt zurück, ob die Warnung jetzt angezeigt werden soll. */
    fun update(enabled: Boolean, percent: Int, charging: Boolean, thresholdPercent: Int): Boolean {
        active = when {
            !enabled || charging || percent < 0 -> false
            percent <= thresholdPercent -> true
            percent >= thresholdPercent + HYSTERESIS -> false
            else -> active
        }
        return active
    }

    companion object {
        const val HYSTERESIS = 5
        const val MIN_PERCENT = 5
        const val MAX_PERCENT = 50
        const val DEFAULT_PERCENT = 20
    }
}
