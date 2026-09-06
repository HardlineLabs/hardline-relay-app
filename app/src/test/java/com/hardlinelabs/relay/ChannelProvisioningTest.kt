package com.hardlinelabs.relay

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.Config
import okio.ByteString.Companion.toByteString
import java.util.Base64

class ChannelProvisioningTest {
    private fun privateChannel(name: String = "RelayTest") =
        ChannelSettings(name = name, psk = ByteArray(32) { 42 }.toByteString())
    private val primary = ChannelSettings(psk = byteArrayOf(1).toByteString())

    @Test fun generatesDistinct256BitKeys() {
        val first = ChannelProvisioning.create("RelayTest")
        val second = ChannelProvisioning.create("RelayTest")
        assertEquals(32, first.psk.size)
        assertNotEquals(first.psk, second.psk)
        assertFalse(first.uplink_enabled)
        assertFalse(first.downlink_enabled)
    }

    @Test fun qrRoundTripPreservesOnlyOnePrivateChannel() {
        val expected = privateChannel()
        assertEquals(expected, ChannelProvisioning.decode(ChannelProvisioning.encode(expected)))
    }

    @Test fun generatedQrCanBeDecodedByScannerEngine() {
        val expected = privateChannel()
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(ChannelProvisioning.encode(expected),
            com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(400 * 400) { index -> if (matrix[index % 400, index / 400]) -16777216 else -1 }
        val source = com.google.zxing.RGBLuminanceSource(400, 400, pixels)
        val result = com.google.zxing.MultiFormatReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
        assertEquals(expected, ChannelProvisioning.decode(result.text))
    }

    @Test fun rejectsInvalidNamesAndPublicKeys() {
        listOf("", "twelve_chars", "admin", "has space", "é").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ChannelProvisioning.create(name) }
        }
        assertThrows(IllegalArgumentException::class.java) { ChannelProvisioning.validate(primary.copy(name = "Test")) }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelProvisioning.validate(privateChannel().copy(uplink_enabled = true))
        }
    }

    @Test fun appendsWithoutReplacingPrimaryOrOtherChannels() {
        val existing = listOf(primary, privateChannel("Other"))
        assertEquals(2, ChannelProvisioning.selectSlot(existing, privateChannel(), 8))
        assertEquals(primary, existing[0])
        assertEquals("Other", existing[1].name)
    }

    @Test fun exactImportIsIdempotentButNameConflictFails() {
        val channel = privateChannel()
        assertEquals(1, ChannelProvisioning.selectSlot(listOf(primary, channel), channel, 8))
        assertThrows(IllegalArgumentException::class.java) {
            ChannelProvisioning.selectSlot(listOf(primary, channel), ChannelProvisioning.create(channel.name), 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelProvisioning.selectSlot(listOf(channel), channel, 8)
        }
    }

    @Test fun handlesEmptySlotsAndFullRadioSafely() {
        assertEquals(1, ChannelProvisioning.selectSlot(listOf(primary, ChannelSettings(), privateChannel("Other")), privateChannel(), 8))
        assertThrows(IllegalStateException::class.java) {
            ChannelProvisioning.selectSlot(listOf(primary, privateChannel("Other")), privateChannel(), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelProvisioning.selectSlot(emptyList(), privateChannel(), 8)
        }
    }

    @Test fun rejectsForeignMalformedAndConfigurationReplacingQr() {
        val valid = ChannelProvisioning.encode(privateChannel())
        listOf(valid.replace("meshtastic.org", "example.com"), valid.replace("https", "http"),
            valid.replace("?add=true", ""), "x".repeat(2049), "not a QR").forEach {
            assertThrows(IllegalArgumentException::class.java) { ChannelProvisioning.decode(it) }
        }
        val set = ChannelSet(settings = listOf(privateChannel()), lora_config = Config.LoRaConfig())
        val replacing = "https://meshtastic.org/e/?add=true#" + Base64.getUrlEncoder().withoutPadding().encodeToString(set.encode())
        assertThrows(IllegalArgumentException::class.java) { ChannelProvisioning.decode(replacing) }
    }
}
