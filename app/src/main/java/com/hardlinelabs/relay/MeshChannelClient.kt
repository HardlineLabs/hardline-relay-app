package com.hardlinelabs.relay

import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.Config
import org.meshtastic.proto.AdminMessage
import java.util.UUID

/** Worker-thread only. Never log configuration protobufs or channel keys. */
class MeshChannelClient(private val service: IMeshService, private val readBackTimeoutMillis: Long = 20_000,
                        private val activationTimeoutMillis: Long = 60_000) {
    class Snapshot(val node: Int, val maxChannels: Int, val channels: ChannelSet, val config: LocalConfig)

    fun read(): Snapshot {
        check(service.connectionState() == "Connected") { "Radio disconnected. Open Meshtastic and reconnect." }
        val info = service.myNodeInfo ?: error("Radio details unavailable. Wait for Meshtastic to finish connecting.")
        check(info.firmwareVersion == "2.7.15.567b8ea") { "Requires radio firmware 2.7.15.567b8ea." }
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

    fun activate(profile: ChannelProfile.Open, expectedNode: Int, progress: (String) -> Unit): Pair<Int, Snapshot> {
        require(profile.slot in 1..104) { "Invalid frequency slot." }
        val before = read()
        check(before.node == expectedNode) { "Connected radio changed. Refresh before activating." }
        val lora = before.config.lora ?: error("Radio configuration unavailable.")
        ChannelProfile.validateRadio(lora)
        check(lora.hop_limit == 7) { "Expected 7 hops. Inspect Meshtastic first." }
        progress("Sending channel settings…")
        val (index, added) = add(profile.settings, expectedNode)
        val expectedLora = lora.copy(channel_num = profile.slot, override_frequency = 0f)
        if (expectedLora != lora) {
            progress("Sending frequency settings · Radio may restart…")
            service.setConfig(Config(lora = expectedLora).encode())
        }
        progress("Settings sent · Waiting for radio connectivity…")
        val deadline = System.nanoTime() + activationTimeoutMillis * 1_000_000L
        var requested = false
        while (System.nanoTime() < deadline) {
            Thread.sleep(activationTimeoutMillis.coerceIn(1, 500))
            if (service.connectionState() != "Connected") {
                requested = false; progress("Radio reconnecting · Keep the radio powered and nearby…"); continue
            }
            if (!requested) {
                service.getRemoteConfig(service.packetId, expectedNode, AdminMessage.ConfigType.LORA_CONFIG.value)
                service.getRemoteChannel(service.packetId, expectedNode, index)
                requested = true; progress("Radio connected · Verifying channel and frequency…"); continue
            }
            val after = read()
            check(after.node == expectedNode) { "Connected radio changed during activation. Inspect both radios." }
            // ChannelSet also embeds LoRa settings, which this operation intentionally changes.
            if (after.channels.settings != added.channels.settings) continue
            check(after.config.copy(lora = before.config.lora) == before.config) { "Other radio settings changed during activation." }
            if (after.config.lora == expectedLora) return index to after
        }
        error("Activation unconfirmed. Settings may have changed. Reconnect in Meshtastic, then activate again to verify. No automatic rollback.")
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
