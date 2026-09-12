package com.hardlinelabs.relay

import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.meshtastic.core.service.IMeshService

/** Explicit private-channel hardware acceptance. Does not send chat or record keys. */
class ChannelAcceptanceTest {
    @Test
    fun activationAndIndependentRemoval() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("channelRegression") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var api: IMeshService? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    api = IMeshService.Stub.asInterface(binder)
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    api = null
                }
            }
        assertTrue(
            context.bindService(
                Intent()
                    .setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val client = MeshChannelClient(requireNotNull(api))
            val before = client.read()
            val store = ProfileStore(context)
            store.selectRadio(before.node)
            val active =
                requireNotNull(store.active()) {
                    "A verified active profile is needed to restore the lab radio."
                }
            val prior = store.list().first { it.id == active.getString("id") }
            val settings = before.channels.settings[active.getInt("index")]
            check(
                active.getInt("node") == before.node &&
                    active.getInt("slot") == before.config.lora?.channel_num &&
                    active.getString("fingerprint") == ChannelProfile.digest(settings.encode())
            )
            val priorOpen = ChannelProfile.Open(settings, active.getInt("slot"))
            val temporary =
                ChannelProfile.create(
                    ChannelProvisioning.create(
                        "QA" + java.util.UUID.randomUUID().toString().take(8)
                    ),
                    priorOpen.slot,
                    null,
                )
            val opened = ChannelProfile.open(temporary, null)
            RadioMonitorService.instance?.stopSurvey("Channel acceptance")
            store.save(temporary)
            var removalVerified = false
            try {
                store.clearActive()
                Thread.sleep(6000)
                val installed = client.activate(opened, before.node) {}
                store.activated(temporary, opened, before.node, installed.first)
                assertTrue("RF configuration preserved", before.config == installed.second.config)
                store.remove(temporary.id)
                assertTrue(
                    "Deleting the app profile preserves the radio key",
                    opened.settings == client.read().channels.settings[installed.first],
                )
                store.save(temporary)
                store.clearActive()
                Thread.sleep(6000)
                val removed = client.remove(installed.first, installed.second) {}
                store.removalVerified(emptySet())
                assertTrue(store.list().any { it.id == temporary.id })
                assertFalse(removed.channels.settings.any { it == opened.settings })
                removalVerified = true
            } finally {
                // Retain a saved profile if a radio write cannot be verified; never conceal an
                // uncertain key.
                if (removalVerified) {
                    val restored = client.activate(priorOpen, before.node) {}
                    store.activated(prior, priorOpen, before.node, restored.first)
                    store.remove(temporary.id)
                    assertTrue(
                        "Original channel set restored",
                        before.channels.settings == restored.second.channels.settings,
                    )
                    assertTrue(
                        "Original RF configuration restored",
                        before.config == restored.second.config,
                    )
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
