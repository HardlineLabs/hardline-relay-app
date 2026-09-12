package com.hardlinelabs.relay.core

import org.junit.Assert.*
import org.junit.Test

class NodeDiscoveryTest {
    @Test
    fun onlyRadioOriginsQualifyEvenWhenMqttCarriesSignalMetadata() {
        assertFalse(RadioEvidence.isLoRa(false, true, 1, -80))
        assertFalse(RadioEvidence.isLoRa(false, false, 5, -80))
        assertFalse(RadioEvidence.isLoRa(true, false, 1, -80))
        assertFalse(RadioEvidence.isLoRa(false, false, 0, 0))
        assertTrue(RadioEvidence.isLoRa(false, false, 1, -80))
        assertTrue(RadioEvidence.isLoRa(false, false, 0, -100))
    }

    @Test
    fun keepsListeningAndSpacesRequestsUntilExplicitStop() {
        val d = NodeDiscovery("discovery", emptySet(), 30_000)
        assertFalse(d.ready(29_999, false))
        assertFalse(d.ready(30_000, true))
        assertTrue(d.ready(30_000, false))
        d.submitted(30_000)
        assertFalse(d.finished)
        assertFalse(d.ready(629_999, false))
        assertTrue(d.ready(630_000, false))
        d.submitted(630_000)
        assertEquals(2, d.requests)
        d.stop("Stopped")
        assertFalse(d.ready(Long.MAX_VALUE, false))
    }

    @Test
    fun startingCacheClassificationDoesNotChangeWhenCacheChanges() {
        val cache = mutableSetOf(1, 2)
        val d = NodeDiscovery("discovery", cache, 0)
        cache.add(3)
        assertFalse(d.isNew(2))
        assertTrue(d.isNew(3))
        assertTrue(d.isNew(4))
    }
}
