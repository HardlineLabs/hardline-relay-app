package com.hardlinelabs.relay

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hardlinelabs.relay.core.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic UI traffic only; these tests never initiate radio transmissions. */
class ObservatoryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun text(view: View): String = (if (view is TextView) view.text.toString() else "") +
        if (view is ViewGroup) (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) } else ""
    @Test fun packetExpansionAndScrollSurviveLiveArrivals() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var feed: PacketFeed
            lateinit var list: ListView
            lateinit var first: View
            var originalHeight = 0
            val now = System.currentTimeMillis()
            val events = (1..120).map { RadioEvent(now - 121 + it, "RX", "Telemetry", source = "!00000002", packetId = it, size = 12, hops = 2) }
            val state = MonitorState(events = events)
            scenario.onActivity { a ->
                feed = PacketFeed(a, {}, {}); a.setContentView(feed); feed.update(state)
                list = feed.findViewWithTag("packet-list")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                first = list.getChildAt(0); originalHeight = first.height
                (first as ViewGroup).getChildAt(0).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue("Expansion must push following rows down", first.height > originalHeight)
                assertTrue(text(first).contains("Hops 2"))
                val newEvent = events.last().copy(time = now + 1, packetId = 999)
                feed.update(state.copy(events = events + newEvent))
                assertEquals("Reading freezes insertions", 120, list.count)
                assertSame(first, list.getChildAt(0))
                assertTrue(text(feed.findViewWithTag("packet-arrivals")).contains("1 new packets"))
                (first as ViewGroup).getChildAt(0).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(originalHeight, list.getChildAt(0).height)
                list.setSelectionFromTop(40, -10)
            }
            instrumentation.waitForIdleSync()
            var position = 0; var offset = 0
            scenario.onActivity {
                position = list.firstVisiblePosition; offset = list.getChildAt(0).top
                feed.update(state.copy(events = events + events.last().copy(time = now + 2, packetId = 1000)))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(position, list.firstVisiblePosition); assertEquals(offset, list.getChildAt(0).top)
                feed.findViewWithTag<Button>("packet-arrivals").performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(121, list.count); assertEquals(0, list.firstVisiblePosition) }
        }
    }
    @Test fun refreshPreservesPagesAndInspectorControlsTrackTheSelectedNode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var ui: ObservatoryUi
            lateinit var radio: ScrollView
            scenario.onActivity { a ->
                ui = ObservatoryUi(a, TextView(a), {}, {}, {}, { MonitorState() })
                a.setContentView(ui); ui.resume(); radio = ui.findViewWithTag("radio-scroll")
                radio.scrollTo(0, 150)
                ui.findViewWithTag<Button>("tab-3").performClick()
                ui.findViewWithTag<Button>("tab-0").performClick()
            }
            Thread.sleep(1200)
            scenario.onActivity {
                assertSame(radio, ui.findViewWithTag("radio-scroll")); ui.destroy()
            }
            var starts = 0; var stops = 0; var focused = 0
            lateinit var suite: NodeInspector
            val node = RadioNode(2, "!00000002", "Test node", 0, 0)
            var state = MonitorState(connected = true, nodes = listOf(node))
            scenario.onActivity { a ->
                suite = NodeInspector(a, 2, {}, { starts++ }, { stops++ }, { focused = it.number }); a.setContentView(suite); suite.update(state)
                assertFalse(suite.findViewWithTag<Button>("node-map").isEnabled)
                suite.update(state.copy(nodes = listOf(node.copy(latitude = 40.0, longitude = -105.0))))
                suite.findViewWithTag<Button>("node-map").performClick(); assertEquals(2, focused)
                suite.findViewWithTag<Button>("node-test").performClick(); assertEquals(1, starts)
                state = state.copy(surveying = true, surveyTargets = listOf(2), attempts = listOf(RadioSurvey.Attempt(2, 4, SystemClock.elapsedRealtime())))
                suite.update(state)
                assertFalse(suite.findViewWithTag<Button>("node-test").isEnabled)
                assertTrue(suite.findViewWithTag<Button>("node-stop").isEnabled)
                suite.findViewWithTag<Button>("node-stop").performClick(); assertEquals(1, stops)
                suite.update(state.copy(surveying = false, attempts = listOf(state.attempts.single().copy(outcome = "Relay acknowledgment", elapsed = 1200))))
                assertTrue(text(suite).contains("Relay acknowledgment"))
                suite.update(state.copy(surveying = false, surveyStopReason = "Stopped by you"))
                assertTrue(text(suite).contains("no more will start"))
            }
        }
    }
}
