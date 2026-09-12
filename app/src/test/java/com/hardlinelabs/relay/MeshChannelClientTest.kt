package com.hardlinelabs.relay

import java.lang.reflect.Proxy
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig

class MeshChannelClientTest {
    private class FakeRadio {
        var connected = true
        var version = "2.7.15.567b8ea"
        var channels =
            ChannelSet(settings = listOf(ChannelSettings(psk = byteArrayOf(1).toByteString())))
        var config =
            LocalConfig(
                lora =
                    Config.LoRaConfig(
                        hop_limit = 7,
                        use_preset = true,
                        region = Config.LoRaConfig.RegionCode.US,
                        modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
                    )
            )
        var configWrites = 0
        var pendingConfig: Config? = null
        var pending: Channel? = null
        var writes = 0
        var reads = 0
        var dropReadBack = false
        var sent: DataPacket? = null
        val channelWrites = mutableListOf<Channel>()
        var rebooting = false
        var reboots = 0
        var shutdowns = 0
        val service =
            Proxy.newProxyInstance(
                IMeshService::class.java.classLoader,
                arrayOf(IMeshService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "connectionState" ->
                        if (rebooting) {
                            rebooting = false
                            "Disconnected"
                        } else if (connected) "Connected" else "Disconnected"
                    "getMyNodeInfo" ->
                        MyNodeInfo(
                            123,
                            false,
                            "HELTEC_V3",
                            version,
                            false,
                            false,
                            1L,
                            30000,
                            0,
                            8,
                            false,
                            0f,
                            0f,
                            null,
                        )
                    "getConfig" -> config.encode()
                    "getChannelSet" -> channels.encode()
                    "getPacketId" -> 55
                    "setChannel" -> {
                        pending = Channel.ADAPTER.decode(args!![0] as ByteArray)
                        channelWrites.add(pending!!)
                        writes++
                        null
                    }
                    "getRemoteChannel" -> {
                        assertEquals(123, args!![1])
                        pending
                            ?.takeUnless { dropReadBack }
                            ?.let { ch ->
                                assertEquals(Channel.Role.SECONDARY, ch.role)
                                val settings = channels.settings.toMutableList()
                                while (settings.size <= ch.index) settings.add(ChannelSettings())
                                settings[ch.index] = ch.settings!!
                                channels = channels.copy(settings = settings)
                            }
                        reads++
                        null
                    }
                    "setConfig" -> {
                        pendingConfig = Config.ADAPTER.decode(args!![0] as ByteArray)
                        configWrites++
                        null
                    }
                    "getRemoteConfig" -> {
                        if (!dropReadBack)
                            pendingConfig?.let {
                                config = config.copy(lora = it.lora)
                                channels = channels.copy(lora_config = it.lora)
                            }
                        null
                    }
                    "send" -> {
                        sent = args!![0] as DataPacket
                        null
                    }
                    "requestShutdown" -> {
                        assertEquals(123, args!![1])
                        shutdowns++
                        null
                    }
                    "requestReboot" -> {
                        assertEquals(123, args!![1])
                        reboots++
                        rebooting = true
                        if (!dropReadBack) {
                            val settings = channels.settings.toMutableList()
                            channelWrites.forEach { settings[it.index] = it.settings!! }
                            channels =
                                channels.copy(
                                    settings = settings.dropLastWhile { it == ChannelSettings() }
                                )
                        }
                        null
                    }
                    else -> error("Unexpected API operation: ${method.name}")
                }
            } as IMeshService
    }

    @Test
    fun removalVerifiesFreshChannelSetAndPreservesUnrelatedSlots() {
        val radio = FakeRadio()
        radio.channels =
            radio.channels.copy(
                settings =
                    radio.channels.settings +
                        ChannelProvisioning.create("Remove") +
                        ChannelProvisioning.create("Keep")
            )
        val before = MeshChannelClient(radio.service).read()
        val after = MeshChannelClient(radio.service, 20, 1500).remove(1, before) {}
        assertEquals(ChannelSettings(), after.channels.settings[1])
        assertEquals(before.channels.settings[0], after.channels.settings[0])
        assertEquals(before.channels.settings[2], after.channels.settings[2])
        assertEquals(Channel.Role.DISABLED, radio.channelWrites.single().role)
        assertEquals(1, radio.reboots)
        assertEquals(0, radio.configWrites)
    }

    @Test
    fun removingPrimaryMovesOneExplicitKeyChannelToPrimary() {
        val radio = FakeRadio()
        val keep = ChannelProvisioning.create("Keep")
        radio.channels = radio.channels.copy(settings = radio.channels.settings + keep)
        val client = MeshChannelClient(radio.service, 20, 1500)
        val after = client.remove(0, client.read()) {}
        assertEquals(listOf(keep), after.channels.settings)
        assertEquals(
            listOf(Channel.Role.PRIMARY, Channel.Role.DISABLED),
            radio.channelWrites.map { it.role },
        )
        assertEquals(0, radio.configWrites)
    }

    @Test
    fun lostRemovalReadbackAndConcurrentChangesNeverClaimSuccess() {
        val radio = FakeRadio().apply { dropReadBack = true }
        radio.channels =
            radio.channels.copy(
                settings = radio.channels.settings + ChannelProvisioning.create("Lab")
            )
        val client = MeshChannelClient(radio.service, 20, 30)
        val before = client.read()
        assertThrows(IllegalStateException::class.java) { client.remove(1, before) {} }
        assertEquals(1, radio.writes)
        radio.config = radio.config.copy(lora = radio.config.lora!!.copy(hop_limit = 3))
        assertThrows(IllegalStateException::class.java) { client.remove(1, before) {} }
        assertEquals(1, radio.writes)
    }

    @Test
    fun solePrimaryAndInheritedKeysCannotBeRemovedAccidentally() {
        val radio = FakeRadio()
        val client = MeshChannelClient(radio.service)
        assertThrows(IllegalStateException::class.java) { client.remove(0, client.read()) {} }
        radio.channels =
            radio.channels.copy(
                settings = radio.channels.settings + ChannelSettings(name = "Inherited")
            )
        assertThrows(IllegalStateException::class.java) { client.remove(0, client.read()) {} }
        assertEquals(0, radio.writes)
    }

    @Test
    fun writesOnlySecondaryAndVerifiesWithoutChangingPrimaryOrHops() {
        val radio = FakeRadio()
        val original = radio.channels.settings[0]
        val wanted = ChannelProvisioning.create("Lab")
        val (slot, result) = MeshChannelClient(radio.service).add(wanted, 123)
        assertEquals(1, slot)
        assertEquals(original, result.channels.settings[0])
        assertEquals(wanted, result.channels.settings[1])
        assertEquals(7, result.config.lora!!.hop_limit)
        assertEquals(1, radio.writes)
        assertEquals(1, radio.reads)
    }

    @Test
    fun disconnectedOrWrongFirmwareNeverWrites() {
        val radio = FakeRadio()
        val client = MeshChannelClient(radio.service)
        radio.connected = false
        assertThrows(IllegalStateException::class.java) {
            client.add(ChannelProvisioning.create("Lab"), 123)
        }
        radio.connected = true
        radio.version = "unexpected"
        assertThrows(IllegalStateException::class.java) {
            client.add(ChannelProvisioning.create("Lab"), 123)
        }
        assertEquals(0, radio.writes)
    }

    @Test
    fun timeoutNeverClaimsSuccessOrRetriesWrite() {
        val radio = FakeRadio().apply { dropReadBack = true }
        assertThrows(IllegalStateException::class.java) {
            MeshChannelClient(radio.service, 20).add(ChannelProvisioning.create("Lab"), 123)
        }
        assertEquals(1, radio.writes)
        assertEquals(1, radio.reads)
        assertEquals(1, radio.channels.settings.size)
    }

    @Test
    fun switchedRadioCannotReceiveConfirmedChannelWrite() {
        val radio = FakeRadio()
        assertThrows(IllegalStateException::class.java) {
            MeshChannelClient(radio.service).add(ChannelProvisioning.create("Lab"), 999)
        }
        assertEquals(0, radio.writes)
    }

    @Test
    fun sendsNormalTextOnSelectedChannelAndPreservesHopLimit() {
        val radio = FakeRadio()
        val wanted = ChannelProvisioning.create("Lab")
        radio.channels = radio.channels.copy(settings = radio.channels.settings + wanted)
        MeshChannelClient(radio.service).send(1, wanted, 123)
        assertEquals(1, radio.sent!!.dataType)
        assertEquals(1, radio.sent!!.channel)
        assertEquals(7, radio.sent!!.hopLimit)
        assertEquals(DataPacket.ID_BROADCAST, radio.sent!!.to)
        assertTrue(radio.sent!!.text!!.startsWith("Hardline Relay test "))
        assertThrows(IllegalStateException::class.java) {
            MeshChannelClient(radio.service).send(0, wanted, 123)
        }
        assertThrows(IllegalStateException::class.java) {
            MeshChannelClient(radio.service).send(1, wanted, 999)
        }
    }

    @Test
    fun activationVerifiesFrequencyAndPreservesOtherSettings() {
        val radio = FakeRadio()
        radio.config =
            radio.config.copy(lora = radio.config.lora!!.copy(override_frequency = 906.875f))
        val before = radio.config
        val profile = ChannelProfile.Open(ChannelProvisioning.create("Backup"), 37)
        val stages = mutableListOf<String>()
        val (_, after) = MeshChannelClient(radio.service).activate(profile, 123) { stages.add(it) }
        assertEquals(37, after.config.lora!!.channel_num)
        assertEquals(0f, after.config.lora!!.override_frequency)
        assertEquals(
            before.lora!!.copy(channel_num = 37, override_frequency = 0f),
            after.config.lora,
        )
        assertEquals(1, radio.configWrites)
        assertTrue(stages.any { it.contains("Waiting") })
        assertTrue(stages.any { it.contains("Verifying") })
    }

    @Test
    fun rejectedRegionAndNodeNeverWrite() {
        val radio = FakeRadio()
        val profile = ChannelProfile.Open(ChannelProvisioning.create("Backup"), 37)
        assertThrows(Exception::class.java) {
            MeshChannelClient(radio.service).activate(profile, 999) {}
        }
        radio.config =
            radio.config.copy(
                lora = radio.config.lora!!.copy(region = Config.LoRaConfig.RegionCode.EU_868)
            )
        assertThrows(Exception::class.java) {
            MeshChannelClient(radio.service).activate(profile, 123) {}
        }
        assertEquals(0, radio.writes)
        assertEquals(0, radio.configWrites)
    }

    @Test
    fun lostFrequencyReadBackNeverClaimsActive() {
        val radio = FakeRadio()
        val profile = ChannelProfile.Open(ChannelProvisioning.create("Backup"), 37)
        radio.channels = radio.channels.copy(settings = radio.channels.settings + profile.settings)
        radio.dropReadBack = true
        assertThrows(IllegalStateException::class.java) {
            MeshChannelClient(radio.service, 20, 30).activate(profile, 123) {}
        }
        assertEquals(1, radio.configWrites)
        assertEquals(0, radio.config.lora!!.channel_num)
    }

    @Test
    fun shutdownOnlyAddressesTheConfirmedConnectedRadio() {
        val radio = FakeRadio()
        val client = MeshChannelClient(radio.service)
        assertThrows(IllegalStateException::class.java) { client.shutdown(456) }
        assertEquals(0, radio.shutdowns)
        client.shutdown(123)
        assertEquals(1, radio.shutdowns)
        radio.connected = false
        assertThrows(IllegalStateException::class.java) { client.shutdown(123) }
        assertEquals(1, radio.shutdowns)
    }
}
