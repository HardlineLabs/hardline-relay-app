package com.hardlinelabs.relay

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.Config

class ChannelProfileTest {
    @Test fun protectedPackageNeedsPasswordAndRoundTripsOffline() {
        val settings = ChannelProvisioning.create("Alternate")
        val password = "test passphrase only".toCharArray()
        val pkg = ChannelProfile.create(settings, 37, password)
        val scanned = ChannelProfile.parse(pkg.qr())
        assertTrue(scanned.locked)
        assertEquals("Alternate", scanned.name)
        assertEquals(pkg.id, scanned.id)
        assertFalse(scanned.bytes.toList().windowed(32).any { it.toByteArray().contentEquals(settings.psk.toByteArray()) })
        assertThrows(Exception::class.java) { ChannelProfile.open(scanned, null) }
        assertThrows(Exception::class.java) { ChannelProfile.open(scanned, "wrong password".toCharArray()) }
        val opened = ChannelProfile.open(scanned, password)
        assertEquals(settings, opened.settings); assertEquals(37, opened.slot)
    }
    @Test fun ciphertextAndLabelTamperingFailClosed() {
        val pass = "test passphrase only".toCharArray()
        val pkg = ChannelProfile.create(ChannelProvisioning.create("Alpha"), 20, pass)
        val tampered = pkg.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { ChannelProfile.open(ChannelProfile.Package(pkg.name, true, tampered), pass) }
        val renamed = pkg.bytes.copyOf().also { it[2] = 'B'.code.toByte() }
        assertThrows(Exception::class.java) { ChannelProfile.open(ChannelProfile.Package("Blpha", true, renamed), pass) }
    }
    @Test fun openPackageAndSlotBounds() {
        val settings = ChannelProvisioning.create("Alpha")
        val pkg = ChannelProfile.parse(ChannelProfile.create(settings, 20, null).qr())
        assertFalse(pkg.locked); assertTrue(ChannelProfile.open(pkg, null).publicMesh)
        repeat(200) { assertTrue(ChannelProfile.newSlot(false) in (1..104).filter { it != 20 }) }
        assertEquals(20, ChannelProfile.newSlot(true))
        assertThrows(Exception::class.java) { ChannelProfile.create(settings, 0, null) }
        assertThrows(Exception::class.java) { ChannelProfile.create(settings, 105, null) }
        assertThrows(Exception::class.java) { ChannelProfile.create(settings, 20, "1234".toCharArray()) }
        assertThrows(Exception::class.java) { ChannelProfile.parse("https://example.com/channel") }
        assertThrows(Exception::class.java) { ChannelProfile.parse(ChannelProfile.PREFIX + "A".repeat(2000)) }
    }
    @Test fun foreignRadioConfigurationIsRejected() {
        val lora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US, use_preset = true,
            modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST)
        ChannelProfile.validateRadio(lora)
        assertThrows(Exception::class.java) { ChannelProfile.validateRadio(lora.copy(region = Config.LoRaConfig.RegionCode.UNSET)) }
        ChannelProfile.validateRadio(lora.copy(override_frequency = 906.875f))
        assertThrows(Exception::class.java) { ChannelProfile.validateRadio(lora.copy(frequency_offset = 1f)) }
    }
}
