package com.hardlinelabs.relay.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class MeshState {
    DISCONNECTED,
    SERVICE_CONNECTED,
    RADIO_CONNECTED,
}

/** 2.7.13 returns Kotlin data-object names, not legacy uppercase enum names. */
fun meshStateFromService(value: String): MeshState =
    when (value) {
        "Connected" -> MeshState.RADIO_CONNECTED
        "Disconnected",
        "Connecting",
        "DeviceSleep" -> MeshState.DISCONNECTED
        else -> MeshState.SERVICE_CONNECTED
    }

fun statusLabel(state: MeshState, simulated: Boolean = false): String {
    if (simulated)
        return "SIMULATION: " +
            when (state) {
                MeshState.DISCONNECTED -> "Mesh disconnected"
                MeshState.SERVICE_CONNECTED -> "Radio status unknown"
                MeshState.RADIO_CONNECTED -> "Radio connected"
            }
    return when (state) {
        MeshState.DISCONNECTED -> "Mesh disconnected"
        MeshState.SERVICE_CONNECTED -> "Radio status unknown"
        MeshState.RADIO_CONNECTED -> "Radio connected; mesh reachability unverified"
    }
}

/** Transport seam for tests; no on-air packet format is implied. */
interface MeshTransport {
    fun send(payload: ByteArray)
}

interface AtakTransport {
    fun receive(payload: ByteArray)
}

class BridgeCore(private val mesh: MeshTransport, private val atak: AtakTransport) {
    var state: MeshState = MeshState.DISCONNECTED

    fun send(payload: ByteArray) {
        check(state == MeshState.RADIO_CONNECTED) { "Radio is not connected" }
        require(payload.isNotEmpty() && payload.size <= 200) {
            "Payload outside initial test budget"
        }
        mesh.send(payload.copyOf())
    }

    fun receive(payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= 200) { "Invalid incoming payload" }
        atak.receive(payload.copyOf())
    }
}

/** Crypto capability foundation. Not the Meshtastic channel cipher or an on-air format. */
object Aes256 {
    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun encrypt(key: SecretKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size >= 28) { "Truncated ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }
}
