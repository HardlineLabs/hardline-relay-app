package com.hardlinelabs.relay.core

import org.junit.Assert.*
import org.junit.Test

class RadioEvidenceTest {
    @Test
    fun unknownHopFieldsNeverBecomeDirectReception() {
        assertNull(RadioEvidence.hops(0, 0))
        assertNull(RadioEvidence.hops(3, 4))
        assertEquals(0, RadioEvidence.hops(7, 7))
        assertEquals(2, RadioEvidence.hops(7, 5))
    }

    @Test
    fun equatorAndDatelineDistancesAreSupported() {
        assertTrue(RadioEvidence.validPosition(0.0, 45.0))
        assertFalse(RadioEvidence.validPosition(Double.NaN, 45.0))
        assertFalse(RadioEvidence.validPosition(0.0, 0.0))
        assertEquals(22_239.0, RadioEvidence.distance(0.0, 179.9, 0.0, -179.9), 5.0)
    }

    @Test
    fun rebootDoesNotProduceNegativeCounterRates() {
        assertNull(RadioEvidence.counterDelta(100, 3))
        assertNull(RadioEvidence.counterDelta(null, 3))
        assertEquals(7L, RadioEvidence.counterDelta(100, 107))
    }

    @Test
    fun surveyWaitsForReplyAndBacksOffAfterTimeout() {
        val s = RadioSurvey("one", listOf(11, 12), 0, 2)
        assertEquals(11, s.next(0, false))
        s.sent(11, 99, 0)
        assertNull(s.next(24_999, false))
        assertNull(s.next(25_000, false))
        assertEquals("Unconfirmed", s.attempts[0].outcome)
        assertNull(s.next(29_999, false))
        assertNull(s.next(30_000, true))
        assertEquals(12, s.next(30_000, false))
    }

    @Test
    fun unrelatedAndLateStatusesCannotInventSuccess() {
        val s = RadioSurvey("one", listOf(11), 0, 1)
        s.sent(11, 99, 0)
        assertFalse(s.status(100, "Acknowledged", 1_000))
        assertFalse(s.status(99, "Acknowledged", 181_000))
        assertTrue(s.status(99, "Acknowledged", 10_000))
        assertFalse(s.status(99, "Failed", 11_000))
        assertTrue(s.finished)
        assertEquals(10_000L, s.attempts[0].elapsed)
    }

    @Test
    fun stopAndOverallDeadlinePreventMoreTransmissions() {
        val s = RadioSurvey("one", listOf(11), 0, 4)
        s.sent(11, 99, 0)
        s.stop("Radio changed")
        assertNull(s.next(30_000, false))
        assertFalse(s.status(99, "Acknowledged", 31_000))
        val busy = RadioSurvey("two", listOf(11), 0, 4)
        assertNull(busy.next(360_000, true))
        assertTrue(busy.finished)
    }

    @Test
    fun surveyRejectsBroadcastsAndCapsBudget() {
        assertThrows(IllegalArgumentException::class.java) { RadioSurvey("x", listOf(-1), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { RadioSurvey("x", listOf(0), 0, 1) }
        assertEquals(12, RadioSurvey("x", (1..20).toList(), 0, 4).budget)
    }

    @Test
    fun wholeMeshChecksEachNodeThenIncludesNewDiscoveries() {
        val s = RadioSurvey("net", listOf(11, 12), 0, 2, wholeMesh = true)
        assertEquals(11, s.next(0, false))
        s.sent(11, 1, 0)
        s.status(1, "Acknowledged", 1000)
        assertEquals(11, s.next(15000, false))
        s.sent(11, 2, 15000)
        s.discover(listOf(12, 13, 13, 0, -1))
        s.status(2, "Acknowledged", 16000)
        assertEquals(listOf(11, 12, 13), s.targets)
        for (i in 2..5) {
            val time = i * 15000L
            val node = if (i < 4) 12 else 13
            assertEquals(node, s.next(time, false))
            s.sent(node, i + 1, time)
            s.status(i + 1, "Acknowledged", time + 1000)
        }
        assertTrue(s.finished)
        assertNull(s.next(100000, false))
    }

    @Test
    fun nextTestCanQueueImmediatelyWithoutReusingPreviousCompletion() {
        val old = RadioSurvey("old", listOf(11), 0, 4)
        old.sent(11, 1, 0)
        old.stop("Stopped")
        val next = RadioSurvey("new", listOf(12), 1000, 4, firstSendAt = 30000)
        assertFalse(next.finished)
        assertNull(next.next(1000, false))
        assertNull(next.next(30000, true))
        assertEquals(12, next.next(30000, false))
        assertFalse(next.status(1, "Acknowledged", 30000))
        next.sent(12, 2, 30000)
        assertEquals(1, next.attempts.size)
    }

    @Test
    fun wholeMeshIsBoundedButNotCutOffBySmallSurveyDeadline() {
        val s = RadioSurvey("net", listOf(11), 0, 2, wholeMesh = true)
        s.discover((1..2000).toList())
        assertEquals(1000, s.targets.size)
        assertEquals(2000, s.budget)
        assertEquals(11, s.next(400000, false))
        s.stop("Stopped")
        assertNull(s.next(500000, false))
    }
}
