package com.hardlinelabs.relay

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

/** Offline, versioned QR packages. A protected package never contains a plaintext PSK. */
object ChannelProfile {
    const val PREFIX = "hardline://channel/v1/"
    private const val ROUNDS = 600_000
    private val random = SecureRandom()

    class Package(val name: String, val locked: Boolean, val bytes: ByteArray) {
        val id: String
            get() = digest(bytes)

        fun qr(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    class Open(val settings: ChannelSettings, val slot: Int) {
        val publicMesh
            get() = slot == 20
    }

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun validateRadio(lora: Config.LoRaConfig) {
        require(
            lora.region == Config.LoRaConfig.RegionCode.US &&
                lora.use_preset &&
                lora.modem_preset == Config.LoRaConfig.ModemPreset.LONG_FAST &&
                lora.frequency_offset == 0f
        ) {
            "Profiles require US / LongFast with zero calibration offset. Detected ${lora.region}, preset ${lora.modem_preset} (enabled ${lora.use_preset}), offset ${lora.frequency_offset}. No settings changed."
        }
    }

    fun newSlot(publicMesh: Boolean): Int {
        if (publicMesh) return 20
        var slot: Int
        do {
            slot = random.nextInt(104) + 1
        } while (slot == 20)
        return slot
    }

    fun create(settings: ChannelSettings, slot: Int, password: CharArray?): Package {
        ChannelProvisioning.validate(settings)
        require(slot in 1..104)
        if (password != null)
            require(password.size in 12..128) { "Use a passphrase of 12–128 characters." }
        val name = settings.name.toByteArray(Charsets.US_ASCII)
        val header =
            ByteBuffer.allocate(2 + name.size)
                .put(if (password == null) 0 else 1)
                .put(name.size.toByte())
                .put(name)
                .array()
        val plain =
            ByteBuffer.allocate(4 + settings.encode().size)
                .putInt(slot)
                .put(settings.encode())
                .array()
        return try {
            val payload =
                if (password == null) plain.copyOf()
                else {
                    val salt = ByteArray(16).also(random::nextBytes)
                    val iv = ByteArray(12).also(random::nextBytes)
                    salt + iv + crypt(Cipher.ENCRYPT_MODE, password, salt, iv, header, plain)
                }
            Package(settings.name, password != null, header + payload)
        } finally {
            plain.fill(0)
        }
    }

    fun parse(qr: String): Package {
        require(qr.startsWith(PREFIX) && qr.length <= 1400) { "Scan a Hardline channel QR." }
        val bytes =
            try {
                Base64.getUrlDecoder().decode(qr.removePrefix(PREFIX))
            } catch (_: IllegalArgumentException) {
                error("Invalid channel QR.")
            }
        require(bytes.size in 10..900)
        val mode = bytes[0].toInt()
        val length = bytes[1].toInt()
        require(mode in 0..1 && length in 1..11 && bytes.size > length + 2)
        val name = String(bytes, 2, length, Charsets.US_ASCII)
        ChannelProvisioning.validateName(name)
        require(name.toByteArray(Charsets.US_ASCII).contentEquals(bytes.copyOfRange(2, 2 + length)))
        if (mode == 1) require(bytes.size >= 2 + length + 16 + 12 + 16 + 5)
        return Package(name, mode == 1, bytes).also { if (!it.locked) open(it, null) }
    }

    fun open(pkg: Package, password: CharArray?): Open {
        val start = 2 + pkg.bytes[1].toInt()
        val plain =
            if (!pkg.locked) pkg.bytes.copyOfRange(start, pkg.bytes.size)
            else {
                require(password != null && password.size in 1..128) {
                    "Enter the channel passphrase."
                }
                try {
                    crypt(
                        Cipher.DECRYPT_MODE,
                        password,
                        pkg.bytes.copyOfRange(start, start + 16),
                        pkg.bytes.copyOfRange(start + 16, start + 28),
                        pkg.bytes.copyOfRange(0, start),
                        pkg.bytes.copyOfRange(start + 28, pkg.bytes.size),
                    )
                } catch (_: Exception) {
                    error("Incorrect passphrase or damaged channel package.")
                }
            }
        return try {
            require(plain.size in 5..400)
            val slot = ByteBuffer.wrap(plain).int
            require(slot in 1..104)
            val settings = ChannelSettings.ADAPTER.decode(plain.copyOfRange(4, plain.size))
            ChannelProvisioning.validate(settings)
            require(settings.name == pkg.name)
            Open(settings, slot)
        } finally {
            plain.fill(0)
        }
    }

    private fun crypt(
        mode: Int,
        password: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        header: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        val key =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(PREFIX.toByteArray(Charsets.US_ASCII) + header)
                doFinal(input)
            }
        } finally {
            key.fill(0)
        }
    }
}
