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
}
