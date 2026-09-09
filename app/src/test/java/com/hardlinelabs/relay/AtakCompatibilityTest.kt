package com.hardlinelabs.relay
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig
class AtakCompatibilityTest {
    @Test fun largestTextTransportEnvelopeFitsPinnedMeshtasticDataLimit() {
        val data = org.meshtastic.proto.Data(portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
            payload = okio.ByteString.of(*ByteArray(225)))
        assertTrue(data.encode().size <= org.meshtastic.proto.Constants.DATA_PAYLOAD_LEN.value)
    }

    @Test fun corePortFilterBlocksPrivateTrafficAndMissingSettingsAreNotGreen() {
        val result = AtakCompatibility.settings(LocalConfig(device = Config.DeviceConfig(rebroadcast_mode = Config.DeviceConfig.RebroadcastMode.CORE_PORTNUMS_ONLY)))
        assertTrue(result.any { it.level == "BLOCKED" && it.text.contains("CORE_PORTNUMS_ONLY") })
        assertTrue(result.any { it.level == "CHECK" && it.text.contains("unavailable") })
        assertFalse(AtakCompatibility.text(result).contains("good to go", true))
    }
}
