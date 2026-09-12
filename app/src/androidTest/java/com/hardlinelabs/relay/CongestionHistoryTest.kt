package com.hardlinelabs.relay

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CongestionHistoryTest {
    @Test
    fun readingsArePerRadioDeduplicatedBoundedAndSurviveReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "congestion-test.db"
        context.deleteDatabase(name)
        val now = System.currentTimeMillis()
        try {
            ObservationStore(context, name).use { store ->
                store.radio = 1
                store.saveSnapshot(JSONObject())
                assertFalse(store.congestionSample(now, Float.NaN, "test", now))
                assertFalse(store.congestionSample(now, 101f, "test", now))
                assertTrue(store.congestionSample(now, 0f, "slot 1", now))
                assertFalse(store.congestionSample(now, 50f, "slot 2", now))
                assertEquals(0f, store.congestion().single().busy)
                store.radio = 2
                store.saveSnapshot(JSONObject())
                assertTrue(store.congestion().isEmpty())
                assertTrue(store.congestionSample(now, 60f, "slot 2", now))
            }
            ObservationStore(context, name).use { store ->
                store.radio = 1
                assertEquals("slot 1", store.congestion().single().context)
                store.radio = 2
                assertEquals(60f, store.congestion().single().busy)
                store.clear()
                assertTrue(store.congestion().isEmpty())
                // Fill directly to exercise trimming without 10,000 repetitive public API calls.
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    for (i in 1..10082) db.execSQL(
                        "INSERT INTO congestion VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(2, now - i, 50, "test"),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                store.congestionSample(now, 60f, "test", now)
                assertEquals(10080, store.congestion().size)
                for (id in 3..10) {
                    store.radio = id
                    store.saveSnapshot(JSONObject())
                }
                store.radio = 1
                assertTrue(store.congestion().isEmpty())
                store.radio = 2
                assertTrue(store.congestion().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
