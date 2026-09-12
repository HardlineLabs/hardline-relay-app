package com.hardlinelabs.relay.core

import java.io.File
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class RelayCoreTest {
    @Test
    fun pinnedAidlConnectionNamesAreMappedExactly() {
        assertEquals(MeshState.RADIO_CONNECTED, meshStateFromService("Connected"))
        for (value in listOf("Disconnected", "Connecting", "DeviceSleep")) {
            assertEquals(MeshState.DISCONNECTED, meshStateFromService(value))
        }
        assertEquals(MeshState.SERVICE_CONNECTED, meshStateFromService("future-state"))
    }

    @Test
    fun fakeMeshAndAtakCarryPacketsWithoutHardware() {
        val sent = mutableListOf<ByteArray>()
        val received = mutableListOf<ByteArray>()
        val core =
            BridgeCore(
                object : MeshTransport {
                    override fun send(payload: ByteArray) {
                        sent += payload
                    }
                },
                object : AtakTransport {
                    override fun receive(payload: ByteArray) {
                        received += payload
                    }
                },
            )
        assertThrows(IllegalStateException::class.java) { core.send(byteArrayOf(1)) }
        core.state = MeshState.RADIO_CONNECTED
        val payload = byteArrayOf(1, 2, 3)
        core.send(payload)
        payload[0] = 9
        core.receive(sent.single())
        assertArrayEquals(byteArrayOf(1, 2, 3), received.single())
        assertThrows(IllegalArgumentException::class.java) { core.send(ByteArray(201)) }
        core.state = MeshState.DISCONNECTED
        assertThrows(IllegalStateException::class.java) { core.send(byteArrayOf(1)) }
    }

    @Test
    fun aes256AuthenticatesPayloadAndContext() {
        val key = Aes256.newKey()
        assertEquals(32, key.encoded.size)
        val data = "test data".toByteArray()
        val context = "relay-test-v1".toByteArray()
        val encrypted = Aes256.encrypt(key, data, context)
        assertArrayEquals(data, Aes256.decrypt(key, encrypted, context))
        assertFalse(encrypted.contentEquals(Aes256.encrypt(key, data, context)))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { Aes256.decrypt(key, encrypted, context) }
        val intact = Aes256.encrypt(key, data, context)
        assertThrows(AEADBadTagException::class.java) {
            Aes256.decrypt(key, intact, byteArrayOf(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Aes256.decrypt(key, byteArrayOf(1), context)
        }
    }

    @Test
    fun statusMatchesContract() {
        File("../protocol/status-v1.tsv")
            .readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it[0] == "1" && it[2] != "future_state" }
            .forEach { row ->
                assertEquals(
                    row[3],
                    statusLabel(MeshState.valueOf(row[2].uppercase()), row[1] == "simulated"),
                )
            }
    }
}
