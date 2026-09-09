package com.hardlinelabs.relay

import android.content.*
import android.os.*
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.toByteString

/** Opt-in two-phone RF comparison. No coordinates, secrets, configuration writes or public-room sends. */
class PrivateTransportAcceptanceTest {
    @Test fun activateNamedLabProfile() {
        val name = InstrumentationRegistry.getArguments().getString("configureProfile")
        assumeTrue(name != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1); var api: IMeshService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { api = IMeshService.Stub.asInterface(b); ready.countDown() }
            override fun onServiceDisconnected(n: ComponentName) { api = null }
        }
        assertTrue(context.bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            val client = MeshChannelClient(requireNotNull(api)); val before = client.read()
            val store = ProfileStore(context); store.selectRadio(before.node)
            val available = store.list()
            if (name == "list") {
                report("Saved profiles: " + available.joinToString { it.name + if (it.locked) " (locked)" else " slot=" + ChannelProfile.open(it, null).slot })
                return
            }
            val pkg = available.first { it.name == name }
            val opened = ChannelProfile.open(pkg, null)
            store.clearActive(); Thread.sleep(6000)
            val applied = client.activate(opened, before.node) {}
            store.activated(pkg, opened, before.node, applied.first)
            report("Verified private profile ${pkg.name}, slot ${opened.slot}, fingerprint ${ChannelProfile.digest(opened.settings.encode()).take(12)}")
        } finally { context.unbindService(connection) }
    }
    @Test fun compareTextAndPrivateDelivery() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("privateTransport") == "true")
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val ready = CountDownLatch(1); var api: IMeshService? = null
        val binding = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { api = IMeshService.Stub.asInterface(b); ready.countDown() }
            override fun onServiceDisconnected(n: ComponentName) { api = null }
        }
        assertTrue(context.bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"), binding, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        val service = requireNotNull(api)
        val before = MeshChannelClient(service).read()
        val profile = ProfileStore(context).apply { selectRadio(before.node) }.snapshot()
        val active = requireNotNull(profile.active) { "Activate the same private profile on both phones first" }
        assertFalse(profile.switching)
        val channel = active.getInt("index")
        val settings = before.channels.settings[channel]
        assertTrue(channel > 0 && settings.psk.size == 32 && !settings.uplink_enabled && !settings.downlink_enabled)
        assertEquals("RF slot mismatch; do not transmit", requireNotNull(args.getString("expectedSlot")).toInt(), before.config.lora?.channel_num)
        val start = requireNotNull(args.getString("start")).toLong()
        val role = requireNotNull(args.getString("role")).toInt(); require(role in 0..1)
        val received = mutableSetOf<Int>(); val latencies = mutableListOf<Long>()
        val counts = IntArray(4)
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION") override fun onReceive(c: Context, i: Intent) {
                runCatching {
                    i.setExtrasClassLoader(DataPacket::class.java.classLoader)
                    val packet = i.getParcelableExtra<DataPacket>("com.geeksville.mesh.Payload") ?: return
                    if (packet.from == service.myId || packet.viaMqtt || packet.channel != channel) return
                    val bytes = packet.bytes?.toByteArray() ?: return
                    if (bytes.size < 29 || !bytes.copyOfRange(0, 4).contentEquals("HLAB".toByteArray())) return
                    // ASCII is valid for standard channel text as well as private payloads.
                    val text = bytes.toString(Charsets.US_ASCII)
                    if (text.substring(4, 14).toLong() != start / 1000) return
                    val seq = text.substring(14, 16).toInt(); val mode = seq % 4
                    val sent = text.substring(16, 29).toLong()
                    synchronized(received) {
                        if (received.add(seq)) { counts[mode]++; latencies.add(System.currentTimeMillis() - sent) }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction("com.geeksville.mesh.RECEIVED.PRIVATE_APP"); addAction("com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP")
        }, ContextCompat.RECEIVER_EXPORTED)
        try {
            report("Ready role=$role slot=${before.config.lora?.channel_num} filter=${before.config.device?.rebroadcast_mode} role=${before.config.device?.role}")
            for (seq in 0 until 8) {
                val due = start + seq * 40_000L + role * 20_000L
                while (System.currentTimeMillis() < due) Thread.sleep(250)
                assertEquals("Bluetooth connection lost", "Connected", service.connectionState())
                val current = MeshChannelClient(service).read()
                assertEquals(before.config, current.config); assertEquals(before.channels, current.channels)
                val mode = seq % 4
                val body = "HLAB${start / 1000}${seq.toString().padStart(2, '0')}${System.currentTimeMillis()}".padEnd(if (mode == 3) 30 else 39, '.')
                val packet = DataPacket(to = DataPacket.ID_BROADCAST, channel = channel, id = service.packetId,
                    bytes = body.toByteArray(Charsets.US_ASCII).toByteString(), dataType = if (mode == 0) 1 else 256,
                    hopLimit = before.config.lora!!.hop_limit, wantAck = mode != 1)
                service.send(packet)
                report("TX sample=$seq type=${if (mode == 0) "text" else "private"} reliable=${packet.wantAck} bytes=${body.length}")
            }
            val end = start + 360_000
            while (System.currentTimeMillis() < end) Thread.sleep(500)
            synchronized(received) {
                report("RESULT text=${counts[0]}/2 privateUnreliable=${counts[1]}/2 privateReliable=${counts[2]}/2 compactReliable=${counts[3]}/2 rx=${received.size}/8 meanMs=${if (latencies.isEmpty()) -1 else latencies.average().toLong()} maxMs=${latencies.maxOrNull() ?: -1}")
                assertTrue("No peer private packets observed", counts[2] + counts[3] > 0)
            }
            assertEquals("Connected", service.connectionState())
        } finally { context.unregisterReceiver(receiver); context.unbindService(binding) }
    }
    private fun report(s: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$s\n") })
}
