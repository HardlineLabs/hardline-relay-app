package com.hardlinelabs.relay

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.PortNum

class DiagnosticProbeTest {
    @Test
    fun discoveryUsesOnlyNativeBroadcastUserInfo() {
        val calls = mutableListOf<String>()
        val service =
            Proxy.newProxyInstance(
                IMeshService::class.java.classLoader,
                arrayOf(IMeshService::class.java),
            ) { _, method, args ->
                calls.add(method.name)
                assertEquals("requestUserInfo", method.name)
                assertEquals(-1, args!![0])
                null
            } as IMeshService
        requestNodeDiscovery(service)
        assertEquals(listOf("requestUserInfo"), calls)
    }

    @Test
    fun probesCannotSendPublicChatBroadcastsOrChangeConfiguration() {
        val operations = mutableListOf<String>()
        val service =
            Proxy.newProxyInstance(
                IMeshService::class.java.classLoader,
                arrayOf(IMeshService::class.java),
            ) { _, method, args ->
                operations.add(method.name)
                when (method.name) {
                    "send" -> {
                        val p = args!![0] as DataPacket
                        assertEquals("!12345678", p.to)
                        assertEquals(PortNum.REPLY_APP.value, p.dataType)
                        assertEquals(1, p.bytes!!.size)
                        assertEquals(7, p.hopLimit)
                        assertTrue(p.wantAck)
                    }
                    "requestTelemetry" -> {
                        assertEquals(0x12345678, args!![1])
                        assertEquals(0, args[2])
                    }
                    else -> fail("Unexpected API operation: ${method.name}")
                }
                null
            } as IMeshService
        DiagnosticProbe.send(service, DiagnosticProbe.packet(0x12345678, 0, 15, 7))
        DiagnosticProbe.requestTelemetry(service, 0x12345678, 16)
        assertEquals(listOf("send", "requestTelemetry"), operations)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticProbe.packet(-1, 0, 1, 7) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticProbe.packet(0, 0, 1, 7) }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticProbe.requestTelemetry(service, -1, 1)
        }
    }
}
