package com.hardlinelabs.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hardlinelabs.relay.core.RadioEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservationStorageTest {
    @Test fun onlyMetadataSurvivesReopeningAndExpiration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "observation-test.db"
        context.deleteDatabase(name)
        try {
            val now = System.currentTimeMillis()
            val event = RadioEvent(now, "RX", "Text message", source = "!12345678", size = 42, snr = 0f,
                context = "US / LONG_FAST", note = "Signal describes final reception")
            ObservationStore(context, name).use {
                it.append(event.copy(time = now - 8 * 86_400_000L))
                it.append(event)
            }
            ObservationStore(context, name).use {
                assertEquals(listOf(event), it.read())
                val json = ObservationStore.encode(it.read().single())
                assertFalse(json.has("payload")); assertFalse(json.has("bytes")); assertFalse(json.has("psk"))
                assertEquals(0.0, json.getDouble("snr"), 0.0)
                it.clear(); assertTrue(it.read().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun radiosAndSurveyPositionsRemainIsolatedAcrossRestarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "radio-isolation-test.db"
        context.deleteDatabase(name)
        try {
            val now = System.currentTimeMillis()
            val own = com.hardlinelabs.relay.core.RadioNode(1, "!00000001", "Origin", 0, now, 40.0, -105.0)
            val heard = own.copy(number = 2, id = "!00000002", latitude = 40.1, observed = now, hops = 3)
            val record = SurveyRecord("one", now, now + 1000, "Complete", "US", own, listOf(heard), listOf(2),
                discovery = true, newNodes = listOf(2), requests = 2)
            ObservationStore(context, name).use {
                it.radio = 1
                it.saveSnapshot(org.json.JSONObject().put("surveys", org.json.JSONArray(listOf(record.json())))
                    .put("discoveryDueWall", now + 600_000))
                it.append(RadioEvent(now, "RX", "Telemetry", source = heard.id))
                it.radio = 3
                assertTrue(it.read().isEmpty()); assertNull(it.snapshot())
                it.saveSnapshot(org.json.JSONObject().put("label", "Other radio"))
                it.append(RadioEvent(now, "RX", "Position update", source = "!00000004"))
            }
            ObservationStore(context, name).use {
                it.radio = 1
                assertEquals("!00000002", it.read().single().source)
                val restored = SurveyRecord.read(it.snapshot()!!.getJSONArray("surveys").getJSONObject(0))
                assertEquals(record, restored)
                assertEquals(now + 600_000, it.snapshot()!!.getLong("discoveryDueWall"))
                assertTrue(restored.rangeMeters()!! > 11000)
                it.clear()
                it.radio = 3
                assertEquals("!00000004", it.read().single().source)
                assertEquals("Other radio", it.snapshot()!!.getString("label"))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun fullSurveyHistoryUsesBoundedRowsAndEvictsOldRadios() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "survey-capacity-test.db"
        context.deleteDatabase(name)
        try {
            val now = System.currentTimeMillis()
            val nodes = (1..1000).map { com.hardlinelabs.relay.core.RadioNode(it, "node$it", "Synthetic survey node $it", 0, now,
                40.0, -105.0, now, now, rssi = -90, snr = 5f, hops = 3) }
            ObservationStore(context, name).use { store ->
                store.radio = 1
                val records = (1..20).map { SurveyRecord("survey$it", now + it, now + 100, "Complete", "US", nodes[0], nodes).json() }
                store.saveSnapshot(org.json.JSONObject().put("surveys", org.json.JSONArray(records)))
                assertEquals(20, store.snapshot()!!.getJSONArray("surveys").length())
                assertEquals(1000, SurveyRecord.read(store.snapshot()!!.getJSONArray("surveys").getJSONObject(0)).nodes.size)
                for (node in 2..9) {
                    Thread.sleep(2)
                    store.radio = node; store.saveSnapshot(org.json.JSONObject())
                }
                store.radio = 1; assertNull(store.snapshot())
                store.radio = 9; assertNotNull(store.snapshot())
            }
        } finally { context.deleteDatabase(name) }
    }
}
