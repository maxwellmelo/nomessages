package dev.mx3.nomessages.core.messaging

import java.nio.ByteBuffer

enum class PacketKind {
    SIGNAL,
    MLS,

    /**
     * Pairing key-bundle fetch (2026-09-17, T4.16).
     *
     * The only kind whose payload is **not** ciphertext: it carries a plaintext [Envelope]
     * (BUNDLE_REQUEST or BUNDLE_RESPONSE) because it is exchanged before any Signal session exists.
     * Confidentiality here is whatever the Tor stream provides; authenticity comes entirely from the
     * SHA-256 hash the peer signed inside the pairing QR, which the receiver checks before the bytes
     * are allowed to become a session. Nothing secret may ever be put in this kind - see
     * `docs/security-model.md`.
     */
    BUNDLE,
}

data class Packet(val kind: PacketKind, val payload: ByteArray)

/** Identity-free dispatch prefix for authenticated Signal and MLS ciphertext. */
object WirePacket {
    private const val HEADER_BYTES = 5
    const val MAX_PACKET_BYTES: Int = 16 * 1024 * 1024
    const val MAX_PACKET_PAYLOAD_BYTES: Int = MAX_PACKET_BYTES - HEADER_BYTES

    fun encodeSignal(ciphertext: ByteArray): ByteArray = encode(PacketKind.SIGNAL, ciphertext)

    fun encodeMls(ciphertext: ByteArray): ByteArray = encode(PacketKind.MLS, ciphertext)

    /** See [PacketKind.BUNDLE]: [plaintext] is an encoded pairing envelope, not ciphertext. */
    fun encodeBundle(plaintext: ByteArray): ByteArray = encode(PacketKind.BUNDLE, plaintext)

    fun decode(bytes: ByteArray): Packet {
        require(bytes.size in HEADER_BYTES..MAX_PACKET_BYTES) { "Invalid packet size" }
        val reader = ByteBuffer.wrap(bytes)
        val kind = when (reader.get().toInt() and 0xff) {
            1 -> PacketKind.SIGNAL
            2 -> PacketKind.MLS
            3 -> PacketKind.BUNDLE
            else -> throw IllegalArgumentException("Unknown packet kind")
        }
        val length = reader.int
        require(length in 1..MAX_PACKET_PAYLOAD_BYTES) { "Invalid packet payload length" }
        require(length == reader.remaining()) { "Packet length mismatch" }
        return Packet(kind, ByteArray(length).also(reader::get))
    }

    private fun encode(kind: PacketKind, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size in 1..MAX_PACKET_PAYLOAD_BYTES) { "Invalid packet payload size" }
        val tag = when (kind) {
            PacketKind.SIGNAL -> 1
            PacketKind.MLS -> 2
            PacketKind.BUNDLE -> 3
        }
        return ByteBuffer.allocate(HEADER_BYTES + ciphertext.size)
            .put(tag.toByte())
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }
}
