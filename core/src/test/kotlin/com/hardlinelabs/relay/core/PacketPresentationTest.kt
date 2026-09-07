package com.hardlinelabs.relay.core

import org.junit.Assert.*
import org.junit.Test

class PacketPresentationTest {
    @Test fun onlyPacketsAppearAndMatchingStatusesUpdateTheSubmission() {
        val tx = RadioEvent(1000, "TX", "Probe", destination = "!00000002", packetId = 7, context = "one", survey = "s", outcome = "Submitting")
        val status = RadioEvent(2000, "STATUS", "Update", packetId = 7, context = "one", survey = "s", outcome = "Relay acknowledgment")
        val unrelated = status.copy(time = 3000, context = "two", outcome = "Acknowledged")
        val rx = RadioEvent(4000, "RX", "Telemetry", packetId = 7)
        val rows = RadioEvidence.packets(listOf(tx, status, unrelated, rx, RadioEvent(5000, "EVENT", "Gap")))
        assertEquals(2, rows.size)
        assertEquals("Relay acknowledgment", rows.first().outcome)
        assertEquals(rx, rows.last())
        assertEquals("Submitting", tx.outcome)
    }
    @Test fun reusedOrUnassociatedIdsDoNotInventConfirmation() {
        val tx = RadioEvent(1000, "TX", "Probe", destination = "!00000002", packetId = 7, context = "one", survey = "s")
        val status = RadioEvent(1001, "STATUS", "Update", destination = tx.destination, packetId = 7, context = tx.context, survey = tx.survey, outcome = "Acknowledged")
        for (bad in listOf(status.copy(time = 0), status.copy(time = 182000), status.copy(survey = "old"), status.copy(destination = "!00000003"), status.copy(packetId = 0))) {
            assertEquals(tx, RadioEvidence.packets(listOf(tx, bad)).single())
        }
        assertTrue(RadioEvidence.packets(listOf(status)).isEmpty())
    }
    @Test fun strongRelayedPacketsDoNotPromoteTheOriginToDirectRelayCandidate() {
        val n = RadioNode(2, "!00000002", "Candidate", 0, 0)
        val direct = RadioEvent(1000, "RX", "Telemetry", source = n.id, rssi = -95, snr = 4f, hops = 0, context = "one", transport = "LoRa")
        fun ranked(events: List<RadioEvent>, nodes: List<RadioNode> = listOf(n)) = RadioEvidence.significantUnlocated(nodes, events, 1, "one", 0, 5000)
        assertTrue(ranked(listOf(direct)).isEmpty())
        assertEquals(listOf(n), ranked(listOf(direct, direct.copy(time = 2000))))
        assertTrue(ranked(listOf(direct.copy(hops = 2), direct.copy(time = 2000, hops = 2))).isEmpty())
        assertTrue(ranked(listOf(direct.copy(transport = "MQTT"), direct.copy(time = 2000, transport = "MQTT"))).isEmpty())
        assertTrue(ranked(listOf(direct, direct.copy(time = 2000)), listOf(n.copy(latitude = 40.0, longitude = -100.0))).isEmpty())
        assertTrue(ranked(listOf(direct.copy(context = "old"), direct.copy(time = 2000, context = "old"))).isEmpty())
        assertTrue(RadioEvidence.significantUnlocated(listOf(n), listOf(direct, direct.copy(time = 2000)), 1, "one", 0, 900000).isEmpty())
    }
}
