package com.hardlinelabs.relay

import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.LocalConfig
import java.util.UUID

/** Worker-thread only. Never log protobufs or store channel keys outside Meshtastic. */
class MeshChannelClient(private val service: IMeshService, private val readBackTimeoutMillis: Long = 20_000) {
    class Snapshot(val node: Int, val maxChannels: Int, val channels: ChannelSet, val config: LocalConfig)

    fun read(): Snapshot {
        check(service.connectionState() == "Connected") { "Radio disconnected. Open Meshtastic and reconnect." }
        val info = service.myNodeInfo ?: error("Radio details unavailable. Wait for Meshtastic to finish connecting.")
        check(info.firmwareVersion == "2.7.15.567b8ea") { "This milestone requires radio firmware 2.7.15.567b8ea." }
        return Snapshot(info.myNodeNum, info.maxChannels,
            ChannelSet.ADAPTER.decode(service.channelSet), LocalConfig.ADAPTER.decode(service.config))
    }

    fun add(settings: ChannelSettings, expectedNode: Int): Pair<Int, Snapshot> {
        val before = read()
        check(before.node == expectedNode) { "Connected radio changed since confirmation. Refresh before retrying." }
        val index = ChannelProvisioning.selectSlot(before.channels.settings, settings, before.maxChannels)
        // Single secondary-channel write; never replace primary or modify RF configuration.
        if (before.channels.settings.getOrNull(index) != settings) {
            service.setChannel(Channel(index = index, role = Channel.Role.SECONDARY, settings = settings).encode())
        }
        service.getRemoteChannel(service.packetId, before.node, index)
        val deadline = System.nanoTime() + readBackTimeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            Thread.sleep(readBackTimeoutMillis.coerceIn(1, 400))
            val after = read()
            check(after.node == before.node) { "Connected radio changed. Stop and inspect both radios." }
            check(after.config == before.config) { "Radio configuration changed concurrently. Inspect in Meshtastic." }
            before.channels.settings.forEachIndexed { slot, original ->
                if (slot != index) check(after.channels.settings.getOrNull(slot) == original) {
                    "Another channel changed concurrently. Inspect in Meshtastic."
                }
            }
            if (after.channels.settings.getOrNull(index) == settings) return index to after
        }
        error("Write sent, but radio read-back unconfirmed. Refresh before retrying; nothing was rolled back.")
    }

    fun send(index: Int, expected: ChannelSettings, expectedNode: Int): String {
        val current = read()
        check(current.node == expectedNode && index > 0 && current.channels.settings.getOrNull(index) == expected) {
            "Radio or channel changed. Refresh and select the channel again."
        }
        ChannelProvisioning.validate(expected)
        val token = UUID.randomUUID().toString().take(8)
        val packet = DataPacket(DataPacket.ID_BROADCAST, index, "Hardline Relay test $token").apply {
            hopLimit = current.config.lora?.hop_limit ?: 0
        }
        service.send(packet)
        return "Test $token submitted on ${expected.name}. Confirm receipt on the other phone; submitted is not delivered."
    }
}
