package com.hardlinelabs.relay

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit hardware-only check. Ordinary instrumentation/CI never initiates RF diagnostics. */
class PublicMeshAcceptanceTest {
    @Test fun observeAndOptionallySurveyOneRadio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("publicMesh") == "true")
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { context.startForegroundService(Intent(context, RadioMonitorService::class.java)) }
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (RadioMonitorService.instance?.state?.connected != true && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
        val monitor = requireNotNull(RadioMonitorService.instance)
        assertTrue("Pinned radio must be connected", monitor.state.connected)
        if (args.getString("survey") == "true") {
            monitor.startSurvey()
            Thread.sleep(2000)
            if (args.getString("stopAfterFirst") == "true") {
                val firstDeadline = SystemClock.elapsedRealtime() + 30_000
                while (monitor.state.attempts.isEmpty() && SystemClock.elapsedRealtime() < firstDeadline) Thread.sleep(500)
                assertTrue("A check must start before testing Stop", monitor.state.attempts.isNotEmpty())
                monitor.stopSurvey()
                Thread.sleep(2000)
                val count = monitor.state.attempts.size
                Thread.sleep(20_000)
                assertEquals("Stop must prevent new diagnostic submissions", count, monitor.state.attempts.size)
                assertFalse(monitor.state.surveying)
                report(monitor.state)
                instrumentation.runOnMainSync { activity.finish() }
                return
            }
            val end = SystemClock.elapsedRealtime() + 365_000
            while (monitor.state.surveying && SystemClock.elapsedRealtime() < end) {
                Thread.sleep(5000)
                report(monitor.state)
            }
            assertFalse("Survey must be bounded", monitor.state.surveying)
            assertTrue("At least one diagnostic must be attempted", monitor.state.attempts.isNotEmpty())
        } else Thread.sleep(20_000)
        report(monitor.state)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun immediateNextNodeAndActiveSurveyHistory() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("surveyRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { context.startForegroundService(Intent(context, RadioMonitorService::class.java)) }
        fun waitUntil(timeout: Long, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(250)
            assertTrue("Expected live survey transition within deadline", condition())
        }
        waitUntil(30000) { RadioMonitorService.instance?.state?.connected == true }
        val monitor = requireNotNull(RadioMonitorService.instance)
        try {
            val targets = monitor.state.nodes.filter { it.number != monitor.state.local && it.number != 0 && it.number != -1 && it.channel in 0..7 }.take(2)
            assertEquals("Two cached nodes required", 2, targets.size)
            monitor.startSurvey(targets[0].number)
            waitUntil(35000) { monitor.state.attempts.isNotEmpty() }
            val old = monitor.state.surveyId
            monitor.stopSurvey(); waitUntil(3000) { !monitor.state.surveying }
            monitor.startSurvey(targets[1].number)
            waitUntil(3000) { monitor.state.surveying && monitor.state.surveyId != old }
            assertEquals(listOf(targets[1].number), monitor.state.surveyTargets)
            waitUntil(35000) { monitor.state.attempts.isNotEmpty() }
            monitor.stopSurvey(); waitUntil(3000) { !monitor.state.surveying }
            monitor.startSurvey(wholeMesh = true)
            waitUntil(3000) { monitor.state.activeSurvey.isNotEmpty() }
            val id = monitor.state.activeSurvey
            Thread.sleep(35000)
            assertTrue("Bluetooth must remain connected", monitor.state.connected)
            monitor.stopSurvey(); waitUntil(3000) { !monitor.state.surveying }
            val record = monitor.state.surveys.first { it.id == id }
            assertTrue(record.ended >= record.started)
            assertTrue(record.nodes.all { it.observed >= record.started })
            report(monitor.state)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSurvey regression passed: queued next node, active-map history, fresh-only RF nodes=${record.nodes.size}.\n") })
        } finally {
            monitor.stopSurvey()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun report(s: MonitorState) {
        val current = s.events.filter { it.time >= s.started }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nCapture: connected=${s.connected}, cachedNodes=${s.nodes.size}, RF=${current.count { it.transport == "LoRa" }}, " +
                "TX=${current.count { it.direction == "TX" }}, checks=${s.attempts.size}, " +
                "destinationAck=${s.attempts.count { it.outcome == "Acknowledged" }}, routingAck=${s.attempts.count { it.outcome == "Relay acknowledgment" }}, " +
                "unconfirmed=${s.attempts.count { it.outcome == "Unconfirmed" }}, failed=${s.attempts.count { it.outcome == "Failed" }}, locatedNodes=${s.nodes.count { it.latitude != null }}, " +
                "busy=${s.utilization}, metricAgeSeconds=${(System.currentTimeMillis() - s.metricTime) / 1000}\n")
        })
    }
}
