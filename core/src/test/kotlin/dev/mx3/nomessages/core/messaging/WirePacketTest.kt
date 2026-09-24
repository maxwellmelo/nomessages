package dev.mx3.nomessages.core.messaging

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WirePacketTest {
    @Test
    fun `round trips signal and MLS ciphertext without identity metadata`() {
        val ciphertext = byteArrayOf(0, 1, 2, 0xff.toByte())

        val signal = WirePacket.decode(WirePacket.encodeSignal(ciphertext))
        assertEquals(PacketKind.SIGNAL, signal.kind)
        assertArrayEquals(ciphertext, signal.payload)

        val mls = WirePacket.decode(WirePacket.encodeMls(ciphertext))
        assertEquals(PacketKind.MLS, mls.kind)
        assertArrayEquals(ciphertext, mls.payload)

        // BUNDLE (T4.16) carries a plaintext pairing envelope, but the framing is identical.
        val bundle = WirePacket.decode(WirePacket.encodeBundle(ciphertext))
        assertEquals(PacketKind.BUNDLE, bundle.kind)
        assertArrayEquals(ciphertext, bundle.payload)
    }

    @Test
    fun `packet prefix is only kind and big endian payload length`() {
        val encoded = WirePacket.encodeSignal(byteArrayOf(9, 8, 7))

        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 3, 9, 8, 7), encoded)
    }

    @Test
    fun `rejects empty oversized malformed and trailing packets`() {
        assertThrows(IllegalArgumentException::class.java) { WirePacket.encodeMls(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            WirePacket.encodeSignal(ByteArray(WirePacket.MAX_PACKET_PAYLOAD_BYTES + 1))
        }
        // Kind 4 is the first unassigned tag; kind 3 became BUNDLE in T4.16.
        assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(4, 0, 0, 0, 1, 9)) }
        assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(3, 0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(1, 0, 0, 0, 2, 9)) }
        assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(2, 0, 0, 0, 1, 9, 8)) }
        assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(ByteArray(WirePacket.MAX_PACKET_BYTES + 1)) }
    }
}
