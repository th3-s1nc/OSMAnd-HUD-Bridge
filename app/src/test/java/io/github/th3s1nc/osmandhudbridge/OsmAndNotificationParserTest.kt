package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.nav.NoticeBus
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndNotificationParser
import io.github.th3s1nc.osmandhudbridge.nav.OsmAndVoice
import io.github.th3s1nc.osmandhudbridge.protocol.NavCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class OsmAndNotificationParserTest {
    @Test fun roundaboutNotificationFromDevice() {
        val i = OsmAndNotificationParser.parse(
            "2,3 km • Nehmen Sie die 2 Ausfahrt",
            listOf(
                "Nehmen Sie die 2 Ausfahrt St 2615 Hauptstraße 600 m",
                "2,9 km • Hauptstraße 7, Köfering",
                "8,5 km • 11 min • 15:35 • 68 km/h"
            )
        )
        assertEquals(2300, i.turnDistanceM)
        assertEquals(2, i.roundaboutExit)
        assertEquals(8500, i.routeDistanceM)
        assertEquals(11, i.routeMinutes)
        assertEquals(68, i.speedKmh)
        assertEquals(15, i.arrivalHour)
        assertEquals(35, i.arrivalMinute)
        assertEquals("St 2615 Hauptstra\u00dfe", i.nextStreet)
    }

    @Test fun streetOnlyWithKnownInstruction() {
        assertEquals("Bahnhofstr", OsmAndNotificationParser.parseStreet("Links abbiegen Bahnhofstr 300 m"))
        assertEquals("Main St", OsmAndNotificationParser.parseStreet("Turn right Main St 1.2 km"))
        assertEquals(null, OsmAndNotificationParser.parseStreet("Unbekannter Text Bahnhofstr 300 m"))
        assertEquals(null, OsmAndNotificationParser.parseStreet("2,9 km \u2022 Hauptstra\u00dfe 7, K\u00f6fering"))
        assertEquals(null, OsmAndNotificationParser.parseStreet("Links abbiegen 300 m"))
    }

    @Test fun thenIsNotPartOfStreet() {
        // Screenshot vom Geraet
        assertEquals("St 2615 Landshuter Stra\u00dfe", OsmAndNotificationParser.parseStreet("links abbiegen, dann St 2615 Landshuter Stra\u00dfe 600 m"))
        assertEquals("Hauptstr", OsmAndNotificationParser.parseStreet("Links abbiegen, dann rechts abbiegen, dann Hauptstr 200 m"))
        assertEquals(null, OsmAndNotificationParser.parseStreet("links abbiegen, dann 600 m"))
        assertEquals(null, OsmAndNotificationParser.parseStreet("links abbiegen, dann rechts abbiegen 600 m"))
        assertEquals("Main St", OsmAndNotificationParser.parseStreet("Turn right, then Main St 1.2 km"))
    }

    @Test fun clockParsing() {
        assertEquals(15 to 35, OsmAndNotificationParser.parseClock("15:35"))
        assertEquals(17 to 5, OsmAndNotificationParser.parseClock("5:05 PM"))
        assertEquals(0 to 10, OsmAndNotificationParser.parseClock("12:10 AM"))
        assertEquals(null, OsmAndNotificationParser.parseClock("68 km/h"))
    }

    @Test fun hoursAndMeters() {
        val i = OsmAndNotificationParser.parse("450 m • Links abbiegen", listOf("120 km • 1 h 5 min • 16:40 • 90 km/h"))
        assertEquals(450, i.turnDistanceM)
        assertEquals(null, i.roundaboutExit)
        assertEquals(120000, i.routeDistanceM)
        assertEquals(65, i.routeMinutes)
    }

    @Test fun noRouteLine() {
        val i = OsmAndNotificationParser.parse("Navigation", listOf("irgendein Text"))
        assertEquals(null, i.routeDistanceM)
        assertEquals(null, i.routeMinutes)
        assertEquals(null, i.turnDistanceM)
    }

    @Test fun senderCleaning() {
        assertEquals("Mum", NoticeBus.cleanSender("Mum (3 Nachrichten)"))
        assertEquals("Mum", NoticeBus.cleanSender("  Mum "))
        assertEquals("Familie", NoticeBus.cleanSender("Familie (2 Nachrichten von 2 Chats)"))
        assertEquals(null, NoticeBus.cleanSender("  "))
        assertEquals(null, NoticeBus.cleanSender(null))
    }

    @Test fun destinationReachedFromVoiceRouter() {
        // Aus dem Geraete-Log: cmds=[reached_destination, Arbeit]
        assertEquals(true, OsmAndVoice.isDestinationReached(listOf("reached_destination", "Arbeit")))
        assertEquals(false, OsmAndVoice.isDestinationReached(listOf("and_arrive_destination", "")))
        assertEquals(false, OsmAndVoice.isDestinationReached(listOf("turn", "left", "-1.0")))
        assertEquals(false, OsmAndVoice.isDestinationReached(null))
        assertEquals(true, OsmAndVoice.isViaReached(listOf("reached_intermediate", "Bäcker")))
        assertEquals(false, OsmAndVoice.isViaReached(listOf("reached_destination", "Arbeit")))
        assertEquals(false, OsmAndVoice.isViaReached(listOf("turn", "left", "-1.0")))
        assertEquals(false, OsmAndVoice.isViaReached(null))
    }

    @Test fun turnFromNotificationTitle() {
        // Titel aus dem Geraete-Log (OsmAnd~)
        fun t(title: String) = OsmAndNotificationParser.parse(title, emptyList()).turn
        assertEquals(NavCommand.TURN_RIGHT, t("400 m \u2022 rechts abbiegen, dann"))
        assertEquals(NavCommand.TURN_LEFT, t("5,0 km \u2022 links abbiegen, dann"))
        assertEquals(NavCommand.STRAIGHT, t("20 m \u2022 Geradeaus"))
        assertEquals(NavCommand.KEEP_LEFT, t("150 m \u2022 halb links abbiegen, dann"))
        assertEquals(NavCommand.KEEP_RIGHT, t("600 m \u2022 halb rechts abbiegen, dann")) // aus dem Log (Typ 6)
        assertEquals(NavCommand.SHARP_RIGHT, t("80 m \u2022 scharf rechts abbiegen"))
        assertEquals(NavCommand.KEEP_LEFT, t("300 m \u2022 links halten"))
        assertEquals(NavCommand.UTURN_LEFT, t("50 m \u2022 Bitte wenden"))
        assertEquals(NavCommand.ROUNDABOUT, t("2,3 km \u2022 Nehmen Sie die 2 Ausfahrt"))
        assertEquals(NavCommand.TURN_RIGHT, t("300 m \u2022 Turn right"))
        assertEquals(null, t("300 m \u2022 Irgendwas Unbekanntes"))
        assertEquals(null, t("Navigation"))
    }
}
