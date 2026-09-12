package com.hardlinelabs.relay

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings

/** Pure validation and planning. Never log protobufs: their string forms include PSKs. */
object ChannelProvisioning {
    fun validateName(name: String) {
        require(Regex("[A-Za-z0-9_-]{1,11}").matches(name)) {
            "Use 1–11 letters, numbers, underscores or hyphens."
        }
        require(!name.equals("admin", true)) { "The admin channel name is reserved." }
    }

    fun create(name: String): ChannelSettings {
        validateName(name)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return ChannelSettings(
                name = name,
                psk = bytes.toByteString(),
                uplink_enabled = false,
                downlink_enabled = false,
            )
            .also { bytes.fill(0) }
    }

    fun validate(settings: ChannelSettings) {
        validateName(settings.name)
        require(settings.psk.size == 32) { "A private channel requires a 256-bit key." }
        require(!settings.uplink_enabled && !settings.downlink_enabled) {
            "MQTT channel forwarding is not supported here."
        }
        require(settings == ChannelSettings(name = settings.name, psk = settings.psk)) {
            "This QR contains unsupported channel options."
        }
    }

    fun encode(settings: ChannelSettings): String {
        validate(settings)
        return "https://meshtastic.org/e/?add=true#" +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ChannelSet(settings = listOf(settings)).encode())
    }

    fun decode(value: String): ChannelSettings {
        require(value.length <= 2048) { "QR is too large." }
        return try {
            val uri = URI(value)
            require(
                uri.scheme == "https" &&
                    uri.host == "meshtastic.org" &&
                    uri.path == "/e/" &&
                    uri.rawQuery == "add=true" &&
                    uri.port == -1 &&
                    uri.userInfo == null
            )
            val set = ChannelSet.ADAPTER.decode(Base64.getUrlDecoder().decode(uri.rawFragment))
            require(
                set.settings.size == 1 && set.lora_config == null && set.unknownFields.size == 0
            )
            set.settings.single().also(::validate)
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "Use a single private-channel QR created by Relay. No settings were changed."
            )
        }
    }

    fun selectSlot(
        existing: List<ChannelSettings>,
        incoming: ChannelSettings,
        maxChannels: Int,
    ): Int {
        validate(incoming)
        require(existing.isNotEmpty()) { "Primary channel unavailable. Refresh first." }
        val matching = existing.indexOfFirst { it.name == incoming.name }
        if (matching >= 0) {
            require(matching > 0 && existing[matching] == incoming) {
                "That name is already in use with different settings. Nothing was overwritten."
            }
            return matching
        }
        val limit = maxChannels.coerceAtMost(8)
        return (1 until limit).firstOrNull {
            val item = existing.getOrNull(it)
            item == null || item == ChannelSettings()
        }
            ?: throw IllegalStateException(
                "No free secondary channel. Remove one deliberately in Meshtastic first."
            )
    }
}
