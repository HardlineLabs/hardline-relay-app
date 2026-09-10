package com.hardlinelabs.relay

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hardlinelabs.relay.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Packaged map integration using synthetic coordinates, without RF transmissions. */
class SatelliteMapTest {
    @Test fun discoveryHasSeparateHistoryColorsAndFreshOnlyNodes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var panel: SatelliteMap
            lateinit var web: WebView
            val now = System.currentTimeMillis()
            val known = RadioNode(2, "!00000002", "Known", 0, now, 40.0, -105.0, observed = now)
            val fresh = known.copy(number = 3, id = "!00000003", name = "New", latitude = 40.1)
            val cache = known.copy(number = 4, id = "!00000004", name = "Cache only", observed = 0)
            val unlocated = fresh.copy(number = 5, id = "!00000005", latitude = null, longitude = null)
            val discovery = SurveyRecord("discovery", now, context = "US", nodes = listOf(known, fresh, unlocated),
                discovery = true, newNodes = listOf(3, 5), requests = 1)
            val restored = SurveyRecord.read(discovery.json())
            assertEquals(discovery, restored)
            val old = SurveyRecord.read(org.json.JSONObject().put("id", "old").put("started", now))
            assertFalse(old.discovery)
            assertTrue(old.newNodes.isEmpty())
            val survey = SurveyRecord("survey", now, context = "US", nodes = listOf(cache))
            val state = MonitorState(local = 1, connected = true, nodes = listOf(known, fresh, cache, unlocated),
                surveys = listOf(survey, restored), activeDiscovery = "discovery", surveying = true)
            scenario.onActivity { a ->
                val ui = ObservatoryUi(a, View(a), {}, {}, {}, stateSource = { state })
                a.setContentView(ui)
                ui.findViewWithTag<android.widget.Button>("tab-2").performClick()
                panel = ui.findViewWithTag("map-panel")
                web = panel.findViewWithTag("satellite-map"); panel.update(state)
            }
            val deadline = System.currentTimeMillis() + 20000
            while (js(web, "typeof relayUpdate") != "\"function\"" && System.currentTimeMillis() < deadline) Thread.sleep(250)
            instrumentation.runOnMainSync { panel.showDiscovery() }
            assertEquals("false", js(web, "nodes.some(n=>n.number===4)"))
            assertEquals("true", js(web, "nodes.some(n=>n.number===2 && n.color==='#74aaff' && n.freshness.includes('Known'))"))
            assertEquals("true", js(web, "nodes.some(n=>n.number===3 && n.color==='#5ddac4' && n.freshness.includes('New'))"))
            assertEquals("true", js(web, "document.getElementById('legend').textContent.includes('new this discovery')"))
            instrumentation.runOnMainSync { assertTrue("Map retains usable height", web.height >= (160 * panel.resources.displayMetrics.density).toInt()) }
            Thread.sleep(1000)
            assertEquals("Discovery markers fit after native controls resize the map", "true", js(web,
                "[...markers.values()].every(m=>{const r=m.getElement().getBoundingClientRect();return r.x>=0 && r.y>=0 && r.right<=innerWidth && r.bottom<=innerHeight})"))
            if (InstrumentationRegistry.getArguments().getString("captureMap") == "true") {
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.cacheDir, "synthetic-discovery-map.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            instrumentation.runOnMainSync {
                assertEquals(listOf(2, 3, 5), panel.inspectionState()!!.nodes.map { it.number })
                panel.findViewWithTag<android.widget.Switch>("survey-mode").isChecked = true
                assertFalse(panel.findViewWithTag<android.widget.Switch>("discovery-mode").isChecked)
                assertFalse(panel.findViewWithTag<android.widget.Button>("map-survey-start").isEnabled)
            }
            assertEquals("true", js(web, "nodes.some(n=>n.number===4) && !nodes.some(n=>n.number===3)"))
            instrumentation.runOnMainSync {
                panel.showDiscovery()
                panel.update(state.copy(surveys = listOf(restored.copy(id = "next", nodes = emptyList(), newNodes = emptyList()), restored), activeDiscovery = "next"))
            }
            assertEquals("0", js(web, "nodes.length"))
            scenario.onActivity { panel.destroy() }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun js(web: WebView, code: String): String {
        val done = CountDownLatch(1); var answer = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(code) { answer = it; done.countDown() } }
        assertTrue("Map script callback", done.await(5, TimeUnit.SECONDS)); return answer
    }
    @Test fun mapRetainsViewportAndHandlesSelectionOfflineAndTileFailures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var panel: SatelliteMap
            lateinit var web: WebView
            val selected = AtomicInteger()
            val n = RadioNode(2, "!00000002", "Synthetic <node>", 0, 0, 40.0, -105.0, positionPrecision = 16)
            val state = MonitorState(nodes = listOf(n))
            scenario.onActivity { a ->
                panel = SatelliteMap(a, { selected.set(it.number) }, {}); a.setContentView(panel)
                web = panel.findViewWithTag("satellite-map"); panel.update(state)
            }
            val deadline = System.currentTimeMillis() + 20000
            while (js(web, "typeof relayUpdate") != "\"function\"" && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertEquals("\"function\"", js(web, "typeof relayUpdate"))
            instrumentation.runOnMainSync { panel.update(state) }
            assertEquals("true", js(web, "document.querySelector('.node.coarse') !== null"))
            assertEquals("true", js(web, "document.querySelector('.name').textContent.includes('<node>')"))
            js(web, "map.setView([41,-104],9,{animate:false})")
            val before = js(web, "JSON.stringify([map.getCenter().lat,map.getCenter().lng,map.getZoom()])")
            instrumentation.runOnMainSync { panel.update(state.copy(message = "Updated")) }
            assertEquals(before, js(web, "JSON.stringify([map.getCenter().lat,map.getCenter().lng,map.getZoom()])"))
            instrumentation.runOnMainSync { panel.focus(n.id) }
            assertEquals("true", js(web, "map.getCenter().lat===40 && map.getCenter().lng===-105 && map.getZoom()>=14"))
            assertEquals("true", js(web, "document.querySelector('.node-marker.focused') !== null"))
            assertEquals("true", js(web, "document.querySelector('.name').getBoundingClientRect().width>60"))
            js(web, "markers.get('!00000002').fire('click')")
            instrumentation.waitForIdleSync(); assertEquals(2, selected.get())
            js(web, "relayUpdate({local:0,online:false,nodes:[]})")
            assertEquals("true", js(web, "document.getElementById('map').classList.contains('offline')"))
            assertEquals("true", js(web, "document.getElementById('notice').textContent.includes('offline')"))
            js(web, "relayUpdate({local:0,online:true,nodes:[]}); imagery.fire('tileerror')")
            assertEquals("true", js(web, "document.getElementById('notice').textContent.includes('Reload')"))
            js(web, "relayReload()")
            assertEquals("0", js(web, "errors"))
            scenario.onActivity { panel.destroy() }
        }
    }
    @Test fun activeSurveyExcludesCacheAndRangeIncludesHoppedReception() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var panel: SatelliteMap
            lateinit var web: WebView
            val now = System.currentTimeMillis()
            val own = RadioNode(1, "!00000001", "Origin", 0, now, 40.0, -105.0)
            val heard = own.copy(number = 2, id = "!00000002", name = "Heard", latitude = 40.1, observed = now, hops = 3)
            val cached = own.copy(number = 3, id = "!00000003", name = "Cache", latitude = 41.0)
            val unlocated = RadioNode(4, "!00000004", "No GPS", 0, now, observed = now, hops = 4)
            val record = SurveyRecord("survey", now, now + 1000, "Complete", "US", own, listOf(heard, unlocated))
            val state = MonitorState(local = 1, nodes = listOf(own, heard, cached, unlocated), surveys = listOf(record))
            scenario.onActivity { a ->
                panel = SatelliteMap(a, {}, {}); a.setContentView(panel)
                web = panel.findViewWithTag("satellite-map"); panel.update(state)
            }
            val deadline = System.currentTimeMillis() + 20000
            while (js(web, "typeof relayUpdate") != "\"function\"" && System.currentTimeMillis() < deadline) Thread.sleep(250)
            instrumentation.runOnMainSync {
                panel.findViewWithTag<android.widget.Switch>("survey-mode").isChecked = true
                panel.findViewWithTag<android.widget.CheckBox>("survey-range").isChecked = true
            }
            assertEquals("false", js(web, "nodes.some(n=>n.number===3)"))
            assertEquals("true", js(web, "nodes.some(n=>n.number===2 && n.color==='#5ddac4')"))
            assertEquals("true", js(web, "rangeCircle.getRadius()>11000"))
            instrumentation.runOnMainSync {
                assertEquals(listOf(2, 4, 1), panel.inspectionState()!!.nodes.map { it.number })
                panel.update(state.copy(surveys = listOf(record.copy(id = "new", ended = 0, nodes = emptyList()), record), activeSurvey = "new"))
            }
            assertEquals("false", js(web, "nodes.some(n=>n.number===2)"))
            assertEquals("true", js(web, "rangeCircle===null"))
            scenario.onActivity { panel.destroy() }
        }
    }
}
