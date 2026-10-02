package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Pfeile des HUD (Werte 0 bis 21). Ob das HUD die SHARP_*-Werte darstellt, ist ungeprüft.
 */
enum class NavCommand(val id: Int) {
    NONE(0),
    STRAIGHT(1),
    MERGE(2),
    TURN_LEFT(3),
    TURN_RIGHT(4),
    KEEP_LEFT(5),
    KEEP_RIGHT(6),
    RAMP_LEFT(7),
    RAMP_RIGHT(8),
    UTURN(9),
    GOAL(10),
    GOAL_LEFT(11),
    GOAL_RIGHT(12),
    SHARP_LEFT(13),
    SHARP_RIGHT(14),
    VIA(15),
    ROUNDABOUT(16),
    ROUNDABOUT_RIGHT(17),
    ROUNDABOUT_LEFT(18),
    ROUNDABOUT_FINISH(19),
    UTURN_LEFT(20),
    UTURN_RIGHT(21);

    /** Nur bei diesen Pfeilen wird die Ausfahrtsnummer gesendet. */
    val hasExit: Boolean get() = this == ROUNDABOUT || this == ROUNDABOUT_LEFT || this == ROUNDABOUT_RIGHT

    companion object {
        fun fromId(id: Int): NavCommand? = values().firstOrNull { it.id == id }
    }
}

/** Fahrspur-Richtungen als Bitmaske (eine Richtung je Bit). */
object LaneDirection {
    const val STRAIGHT = 1
    const val SLIGHTLY_RIGHT = 2
    const val RIGHT = 4
    const val SHARP_RIGHT = 8
    const val U_TURN_LEFT = 16
    const val SHARP_LEFT = 32
    const val LEFT = 64
    const val SLIGHTLY_LEFT = 128
    const val MERGE_RIGHT = 256
    const val MERGE_LEFT = 512
    const val MERGE_LANES = 1024
    const val U_TURN_RIGHT = 2048
    const val SECOND_RIGHT = 4096
    const val SECOND_LEFT = 8192
}

/** Spur-Empfehlung. */
object LaneRecommendation {
    const val NOT_AVAILABLE = 0
    const val NOT_RECOMMENDED = 1
    const val HIGHLY_RECOMMENDED = 2
    const val RECOMMENDED = 3
}

data class Lane(val directions: Int, val recommendation: Int)

/** Radarfallen-Typ. */
object CameraType {
    const val NONE = 0
    const val FIXED = 1
    const val MOBILE = 2
}
