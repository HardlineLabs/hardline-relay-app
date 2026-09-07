package com.hardlinelabs.relay

import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.PortNum
import okio.ByteString.Companion.toByteString

/** Addressed binary diagnostic, never chat, broadcast, configuration, or a position transmission. */
object DiagnosticProbe {
    fun packet(destination: Int, channel: Int, id: Int, hops: Int): DataPacket {
        require(destination != 0 && destination != -1 && id != 0)
        require(channel in 0..7 && hops in 1..7)
        return DataPacket(
            to = "!" + destination.toUInt().toString(16).padStart(8, '0'),
            bytes = byteArrayOf(1).toByteString(), dataType = PortNum.REPLY_APP.value,
            id = id, channel = channel, hopLimit = hops, wantAck = true,
        )
    }

    fun send(service: IMeshService, packet: DataPacket) {
        require(packet.dataType == PortNum.REPLY_APP.value && packet.to?.startsWith("!") == true &&
            packet.to != "!ffffffff" && packet.to != "!00000000")
        service.send(packet)
    }

    fun requestTelemetry(service: IMeshService, destination: Int, id: Int) {
        require(destination != 0 && destination != -1 && id != 0)
        service.requestTelemetry(id, destination, 0) // DEVICE metrics, native directed request.
    }
}
