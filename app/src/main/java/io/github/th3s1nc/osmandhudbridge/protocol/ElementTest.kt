package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Element-Test (Werkzeuge, Karte HUD): zeigt ein einzelnes Anzeige-Element des HUD kurz an, damit man am Gerät sieht,
 * welche Codes der gewählte Bildschirm wirklich darstellt.
 *
 * Ein Element erscheint nur, wenn der Bildschirm es in seinem Layout hat und es in der Show-Liste steht; die meisten
 * brauchen zusätzlich einen Wert ([TestElement.field]). Codes ohne Wert zeichnet das HUD selbst (Akku, Bluetooth).
 */
class TestElement(val code: Int, val name: String, val field: ByteArray?)

object ElementTest {
    /** Anzeigedauer in Sekunden. */
    const val SHOW_SECONDS = 5

    /** Alle bekannten Navigations-Codes mit Testwert (Bluetooth und Akkus zeichnet das HUD selbst). Nicht enthalten: die frei belegbaren Textzeilen. */
    val catalog: List<TestElement> by lazy {
        val pointer = HudProtocol.pointerField(NavCommand.TURN_RIGHT, null)
        val roundabout = HudProtocol.pointerField(NavCommand.ROUNDABOUT, 2)
        val limit = HudProtocol.speedLimitField(50, true, CameraType.NONE)
        val camera = HudProtocol.speedLimitField(50, true, 1)
        val speed = HudProtocol.speedField(50f)
        val part = HudProtocol.partDistanceField(300)
        val elevation = HudProtocol.elevationField(250)
        val time = HudProtocol.timeField(12, 34)
        val battery = HudProtocol.batteryWarnField(true)
        listOf(
            TestElement(2, "Kompass", HudProtocol.compassField(90)),
            TestElement(3, "Pfeil", pointer),
            TestElement(4, "Restdistanz", HudProtocol.routeDistanceField(12000)),
            TestElement(5, "Restzeit", HudProtocol.routeDurationField(75)),
            TestElement(6, "Entfernung Abbiegung", part),
            TestElement(7, "Nächste Straße", HudProtocol.streetField(HudProtocol.F_NEXT_STREET, "Teststrasse")),
            TestElement(8, "Tacho", speed),
            TestElement(9, "Tacho-Balken", speed),
            TestElement(10, "Tempo-Ziffer", speed),
            TestElement(12, "Aktuelle Straße", HudProtocol.streetField(HudProtocol.F_CURRENT_STREET, "Teststrasse")),
            TestElement(13, "Spurinfo", HudProtocol.laneInfoField(listOf(Lane(LaneDirection.STRAIGHT or LaneDirection.RIGHT, 1), Lane(LaneDirection.RIGHT, 2)))),
            TestElement(16, "Uhrzeit", time),
            TestElement(17, "Tempolimit", limit),
            TestElement(21, "Ankunftszeit", HudProtocol.arrivalTimeField(13, 45)),
            TestElement(22, "Gefahrene Strecke", HudProtocol.tripDistanceField(12000)),
            TestElement(23, "Gefahrene Zeit", HudProtocol.tripDurationField(75)),
            TestElement(32, "Anruf", HudProtocol.eventField(HudProtocol.F_CALL_EVENT, 1, "Test", SHOW_SECONDS)),
            TestElement(33, "Nachricht", HudProtocol.eventField(HudProtocol.F_SMS_EVENT, 1, "Test", SHOW_SECONDS)),
            TestElement(35, "Handy-Akku (Explorer)", battery),
            TestElement(51, "Pfeil-Nummer", roundabout),
            TestElement(52, "Bluetooth", null),
            TestElement(53, "HUD-Akku", null),
            TestElement(81, "Entfernung Zwischenziel", Protobuf.message(F_VIA_DISTANCE, HudProtocol.distanceParts(5000))),
            TestElement(82, "Höhe", elevation),
            TestElement(83, "Höhenmeter aufwärts", Protobuf.message(F_ELEVATION_UP, HudProtocol.distanceParts(150))),
            TestElement(84, "Höhenmeter abwärts", Protobuf.message(F_ELEVATION_DOWN, HudProtocol.distanceParts(150))),
            TestElement(96, "Abbiegepfeil", pointer),
            TestElement(97, "Pfeil-Nummer", roundabout),
            TestElement(98, "Entfernung Abbiegung", part),
            TestElement(99, "Tempolimit-Schild", limit),
            TestElement(100, "Blitzer-Symbol", camera),
            TestElement(101, "Entfernung Blitzer", HudProtocol.cameraDistanceField(300)),
            TestElement(102, "Höhenmeter", elevation),
            TestElement(104, "Uhrzeit", time),
            TestElement(105, "Handy-Akku", battery),
            TestElement(106, "Bluetooth", null),
            TestElement(107, "HUD-Akku", null)
        )
    }

    // Feldnummern laut SDK (Distanz-Format wie die Entfernung zur Abbiegung); am Gerät noch nicht bestätigt
    private const val F_VIA_DISTANCE = 24
    private const val F_ELEVATION_UP = 27
    private const val F_ELEVATION_DOWN = 28

    fun find(code: Int): TestElement? = catalog.firstOrNull { it.code == code }

    /** Liste für einen Bildschirm: zuerst die Codes, die der Modus selbst zeigt, danach alle übrigen. */
    fun listFor(mode: DisplayMode): List<TestElement> {
        val own = catalog.filter { it.code in mode.show }
        return own + catalog.filter { it.code !in mode.show }
    }

    /** Blinkphase in Millisekunden (so lange an, so lange aus). */
    const val BLINK_MS = 600L

    /** Element ein- oder ausblenden (Blinken). */
    fun blink(screen: Int, code: Int, on: Boolean): HudMessage =
        HudMessage(if (on) HudProtocol.showHide(screen, emptyList(), listOf(code)) else HudProtocol.showHide(screen, listOf(code), emptyList()))

    /** Beginn des Tests eines Codes: Testwert senden (falls es einen gibt) und das Element einblenden. */
    fun start(screen: Int, code: Int): List<HudMessage> {
        val e = find(code) ?: return emptyList()
        val out = ArrayList<HudMessage>()
        e.field?.let { out += HudMessage(it) }
        out += blink(screen, code, true)
        return out
    }

    /** Ende des Tests eines Codes: Element so setzen, wie der Bildschirm es normalerweise hat (gezeigt oder versteckt). */
    fun finish(mode: DisplayMode, code: Int): HudMessage = blink(mode.screenId, code, code in mode.show)
}
