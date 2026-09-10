package com.hardlinelabs.relay

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.meshtastic.core.service.IMeshService

/** Attended, opt-in RF acceptance: one native discovery request, then listening and Stop. */
class NodeDiscoveryAcceptanceTest {
    @Test fun discoveryListensStopsAndPreservesRadioConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nodeDiscovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { context.startForegroundService(Intent(context, RadioMonitorService::class.java)) }
        fun waitUntil(timeout: Long, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(250)
            assertTrue("Discovery transition within deadline", condition())
        }
        waitUntil(30_000) { RadioMonitorService.instance?.state?.connected == true }
        val monitor = requireNotNull(RadioMonitorService.instance)
        val field = RadioMonitorService::class.java.getDeclaredField("mesh").apply { isAccessible = true }
        val api = field.get(monitor) as IMeshService
        val before = MeshChannelClient(api).read()
        try {
            assertFalse("Stop existing surveys before hardware acceptance", monitor.state.surveying)
            val known = monitor.state.nodes.map { it.number }.toSet() + api.nodes.orEmpty().map { it.num }
            monitor.startDiscovery()
            waitUntil(5_000) { monitor.state.activeDiscovery.isNotEmpty() }
            val id = monitor.state.activeDiscovery
            monitor.startSurvey(wholeMesh = true)
            Thread.sleep(1500)
            assertEquals("Directed survey cannot overlap discovery", id, monitor.state.activeDiscovery)
            assertTrue(monitor.state.activeSurvey.isEmpty())
            // Allows an existing persisted cooldown to finish; never shortens radio spacing.
            waitUntil(660_000) { monitor.state.events.any { it.survey == id && it.direction == "TX" } || !monitor.state.surveying }
            val tx = monitor.state.events.single { it.survey == id && it.direction == "TX" }
            assertEquals("Node discovery request", tx.kind)
            assertEquals("Submitted to Meshtastic", tx.outcome)
            repeat(6) {
                Thread.sleep(30_000)
                assertTrue("Discovery keeps listening", monitor.state.activeDiscovery == id)
                val r = monitor.state.surveys.first { it.id == id }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nDiscovery: requests=${r.requests}, RF origins=${r.nodes.size}, new=${r.newNodes.size}, located=${r.nodes.count { it.latitude != null }}, connected=${monitor.state.connected}\n") })
            }
            monitor.stopSurvey()
            waitUntil(5_000) { !monitor.state.surveying }
            val record = monitor.state.surveys.first { it.id == id }
            assertTrue(record.discovery)
            assertEquals(1, record.requests)
            assertTrue(record.ended >= record.started)
            assertTrue(record.nodes.all { it.observed >= record.started && it.number != monitor.state.local })
            assertTrue(record.newNodes.none { it in known })
            assertTrue(record.nodes.all { n -> monitor.state.events.any { it.survey == id && it.source == n.id && it.transport == "LoRa" } })
            monitor.startDiscovery()
            waitUntil(5_000) { monitor.state.activeDiscovery.isNotEmpty() }
            val second = monitor.state.activeDiscovery
            Thread.sleep(5000)
            assertEquals("Restart honors the existing request cooldown", 0, monitor.state.surveys.first { it.id == second }.requests)
            monitor.stopSurvey()
            waitUntil(5_000) { !monitor.state.surveying }
            val after = MeshChannelClient(api).read()
            assertEquals(before.node, after.node)
            assertTrue("Radio configuration preserved", before.config == after.config)
            assertTrue("Channel settings preserved", before.channels == after.channels)
        } finally {
            monitor.stopSurvey()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
