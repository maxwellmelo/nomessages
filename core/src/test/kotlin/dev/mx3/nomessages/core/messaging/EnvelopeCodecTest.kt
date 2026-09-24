package dev.mx3.nomessages.core.messaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvelopeCodecTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
    private val otherId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"
    private val requestId = "cccccccc-cccc-4ccc-8ccc-ccccccccccc3"
    private val groupId = "dddddddd-dddd-4ddd-8ddd-ddddddddddd4"
    private val members = listOf("a1".repeat(32), "b2".repeat(32), "c3".repeat(32))

    @Test
    fun `round trips every envelope type`() {
        val envelopes = listOf(
            Envelope.Text(id, 1_700_000_000_001, "hello\nworld"),
            Envelope.Attachment(
                id,
                1_700_000_000_002,
                "photo.jpg",
                "image/jpeg",
                ByteArray(16) { it.toByte() },
                7,
                ByteArray(32) { (it + 16).toByte() },
                ByteArray(257) { (it * 3).toByte() },
            ),
            Envelope.Ack(id, 1_700_000_000_003, otherId),
            Envelope.Evidence(id, 1_700_000_000_004, listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))),
            Envelope.KeyPackage(id, 1_700_000_000_005, requestId, byteArrayOf(5, 6, 7)),
            Envelope.GroupInvite(
                id,
                1_700_000_000_006,
                groupId,
                "Family",
                members[0],
                members,
                listOf(byteArrayOf(8), byteArrayOf(9)),
                byteArrayOf(10, 11),
                requestId,
            ),
            Envelope.GroupCommit(
                id,
                1_700_000_000_007,
                groupId,
                members,
                listOf(byteArrayOf(12)),
                byteArrayOf(13, 14),
            ),
            Envelope.KeyPackageRequest(id, 1_700_000_000_008, requestId),
            Envelope.GroupLeave(id, 1_700_000_000_009, groupId),
            Envelope.ControlRejected(id, 1_700_000_000_010, requestId, ControlRejectionReason.GROUP_CAPACITY),
            Envelope.BundleRequest(id, 1_700_000_000_014, ByteArray(16) { (it + 1).toByte() }),
            Envelope.BundleResponse(id, 1_700_000_000_015, ByteArray(16) { (it + 2).toByte() }, ByteArray(1832) { (it * 7).toByte() }),
        )

        envelopes.forEach { expected ->
            assertEnvelopeEquals(expected, EnvelopeCodec.decode(EnvelopeCodec.encode(expected)))
        }
    }

    @Test
    fun `encoded envelope starts with fixed magic version and type`() {
        val bytes = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))

        assertArrayEquals(byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(), 'M'.code.toByte()), bytes.copyOfRange(0, 4)) // "NMFM" envelope wire magic
        // Wire version 3 since the pairing bundle envelopes landed (T4.16); encode always emits the
        // current version, and 1 and 2 stay decodable.
        assertArrayEquals(byteArrayOf(0, 3), bytes.copyOfRange(4, 6))
        assertEquals(1, bytes[6].toInt())
    }

    @Test
    fun `rejects illegal magic version type and trailing bytes`() {
        val valid = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))

        assertRejected(valid.copyOf().also { it[0] = 'X'.code.toByte() })
        // 4 and not 3: version 3 is the current one and versions 1-2 are accepted for backward
        // compatibility, so the first genuinely unsupported version is the next unallocated one.
        assertRejected(valid.copyOf().also { it[5] = 4.toByte() })
        assertRejected(valid.copyOf().also { it[6] = 127.toByte() })
        assertRejected(valid + 0.toByte())
    }

    @Test
    fun `round trips the forwarded flag for text and attachments`() {
        listOf(false, true).forEach { forwarded ->
            val text = Envelope.Text(id, 1_700_000_000_011, "encaminhada", forwarded = forwarded)
            val decodedText = EnvelopeCodec.decode(EnvelopeCodec.encode(text))
            assertEnvelopeEquals(text, decodedText)
            assertEquals(forwarded, (decodedText as Envelope.Text).forwarded)

            val attachment = Envelope.Attachment(
                id,
                1_700_000_000_012,
                "photo.jpg",
                "image/jpeg",
                ByteArray(16) { it.toByte() },
                7,
                ByteArray(32) { (it + 16).toByte() },
                ByteArray(64) { (it * 5).toByte() },
                forwarded = forwarded,
            )
            val decodedAttachment = EnvelopeCodec.decode(EnvelopeCodec.encode(attachment))
            assertEnvelopeEquals(attachment, decodedAttachment)
            assertEquals(forwarded, (decodedAttachment as Envelope.Attachment).forwarded)
        }
    }

    @Test
    fun `forwarded flag is the last byte of the body and must be zero or one`() {
        val plain = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))
        val forwarded = EnvelopeCodec.encode(Envelope.Text(id, 1, "x", forwarded = true))

        // Same length, differing only in the trailing flag byte: the flag is appended after every
        // pre-existing field, so a version 1 body is a strict prefix of the version 2 one.
        assertEquals(plain.size, forwarded.size)
        assertEquals(0, plain[plain.lastIndex].toInt())
        assertEquals(1, forwarded[forwarded.lastIndex].toInt())
        assertArrayEquals(plain.copyOfRange(6, plain.lastIndex), forwarded.copyOfRange(6, forwarded.lastIndex))
        assertRejected(plain.copyOf().also { it[it.lastIndex] = 2.toByte() })
    }

    @Test
    fun `decodes a version one text envelope with forwarded defaulting to false`() {
        // A hand-built wire-version-1 TEXT envelope: exactly what this codec emitted before the
        // `forwarded` flag existed, so nothing at all follows the body blob. Proves that history
        // written by an older build still decodes, with the safe default.
        val body = "mensagem antiga"
        val timestamp = 1_700_000_000_013L
        val legacy = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { writer ->
                writer.write("NMFM".toByteArray(Charsets.US_ASCII)) // magic, 4 bytes
                writer.writeShort(1) // wire version 1, big-endian u16
                writer.writeByte(1) // tag 1 = TEXT
                val idBytes = id.toByteArray(Charsets.UTF_8) // canonical UUID, 36 bytes
                writer.writeShort(idBytes.size)
                writer.write(idBytes)
                writer.writeLong(timestamp)
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                writer.writeInt(bodyBytes.size) // blob length prefix, big-endian i32
                writer.write(bodyBytes)
                // No forwarded byte: it did not exist in version 1.
            }
        }.toByteArray()

        // Offsets double-checked against EnvelopeCodec: 4 magic + 2 version + 1 tag + 2 id length
        // + 36 id + 8 timestamp + 4 body length + body.
        assertEquals(4 + 2 + 1 + 2 + 36 + 8 + 4 + body.toByteArray(Charsets.UTF_8).size, legacy.size)

        val decoded = EnvelopeCodec.decode(legacy)

        assertInstanceOf(Envelope.Text::class.java, decoded)
        decoded as Envelope.Text
        assertEquals(id, decoded.id)
        assertEquals(timestamp, decoded.timestamp)
        assertEquals(body, decoded.body)
        assertFalse(decoded.forwarded)
        // And the same payload re-encoded is now a version 3 envelope, one byte longer - version 3
        // added no TEXT field, so the only difference from version 2 is the version number itself.
        val reencoded = EnvelopeCodec.encode(decoded)
        assertArrayEquals(byteArrayOf(0, 3), reencoded.copyOfRange(4, 6))
        assertEquals(legacy.size + 1, reencoded.size)
        assertTrue(reencoded.copyOfRange(6, reencoded.lastIndex).contentEquals(legacy.copyOfRange(6, legacy.size)))
    }

    @Test
    fun `rejects truncated and malicious declared lengths`() {
        val valid = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))
        val bodyLengthOffset = 4 + 2 + 1 + 2 + 36 + 8

        assertRejected(valid.dropLast(1).toByteArray())
        assertRejected(valid.copyOf().also {
            it[bodyLengthOffset] = 0x7f.toByte()
            it[bodyLengthOffset + 1] = 0xff.toByte()
            it[bodyLengthOffset + 2] = 0xff.toByte()
            it[bodyLengthOffset + 3] = 0xff.toByte()
        })
        assertRejected(ByteArray(EnvelopeLimits.MAX_ENVELOPE_BYTES + 1))
    }

    @Test
    fun `rejects malformed UTF8 before constructing text`() {
        val bytes = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))
        val bodyOffset = 4 + 2 + 1 + 2 + 36 + 8 + 4
        bytes[bodyOffset] = 0xc3.toByte()

        assertRejected(bytes)
    }

    @Test
    fun `requires canonical UUIDs and distinct acknowledgement IDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.Text(id.uppercase(), 1, "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.Ack(id, 1, id))
        }

        val valid = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))
        val idOffset = 4 + 2 + 1 + 2
        assertRejected(valid.copyOf().also { it[idOffset + 14] = 'A'.code.toByte() })
    }

    @Test
    fun `control rejection is a bounded enum correlated to a distinct original envelope ID`() {
        ControlRejectionReason.entries.forEach { reason ->
            val envelope = Envelope.ControlRejected(id, 1, requestId, reason)
            val encoded = EnvelopeCodec.encode(envelope)

            assertEquals(11, encoded[6].toInt())
            assertEquals(92, encoded.size)
            assertEnvelopeEquals(envelope, EnvelopeCodec.decode(encoded))
        }

        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.ControlRejected(id, 1, "not-a-uuid", ControlRejectionReason.REQUEST_EXPIRED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.ControlRejected(id, 1, id, ControlRejectionReason.REQUEST_EXPIRED))
        }

        val invalidReason = EnvelopeCodec.encode(
            Envelope.ControlRejected(id, 1, requestId, ControlRejectionReason.INVALID_PREREQUISITE),
        ).also { it[it.lastIndex] = 127.toByte() }
        assertRejected(invalidReason)
    }

    @Test
    fun `enforces attachment key identity epoch and ciphertext limits`() {
        val valid = Envelope.Attachment(
            id,
            1,
            "a.bin",
            "application/octet-stream",
            ByteArray(16),
            0,
            ByteArray(32),
            ByteArray(EnvelopeLimits.MAX_ATTACHMENT_CIPHERTEXT_BYTES),
        )
        assertInstanceOf(Envelope.Attachment::class.java, EnvelopeCodec.decode(EnvelopeCodec.encode(valid)))

        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(valid.copy(fileId = ByteArray(15)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(valid.copy(fileKey = ByteArray(31)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(valid.copy(fileEpoch = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(valid.copy(ciphertext = ByteArray(EnvelopeLimits.MAX_ATTACHMENT_CIPHERTEXT_BYTES + 1)))
        }
    }

    @Test
    fun `rejects null and inappropriate control characters in strings`() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.Text(id, 1, "before\u0000after"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(
                Envelope.Attachment(id, 1, "bad\nname", "image/png", ByteArray(16), 0, ByteArray(32), byteArrayOf(1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(
                Envelope.GroupInvite(id, 1, groupId, "bad\u0007name", members[0], members, emptyList(), byteArrayOf(1), requestId),
            )
        }
    }

    @Test
    fun `enforces proof count and individual proof limit`() {
        val maximum = Envelope.Evidence(id, 1, List(EnvelopeLimits.MAX_PROOF_COUNT) { byteArrayOf(1) })
        assertEquals(EnvelopeLimits.MAX_PROOF_COUNT, (EnvelopeCodec.decode(EnvelopeCodec.encode(maximum)) as Envelope.Evidence).proofs.size)

        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.Evidence(id, 1, List(EnvelopeLimits.MAX_PROOF_COUNT + 1) { byteArrayOf(1) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.Evidence(id, 1, listOf(ByteArray(EnvelopeLimits.MAX_PROOF_BYTES + 1))))
        }

        val valid = EnvelopeCodec.encode(Envelope.Evidence(id, 1, emptyList()))
        val countOffset = 4 + 2 + 1 + 2 + 36 + 8
        assertRejected(valid.copyOf().also {
            it[countOffset] = 0x13.toByte()
            it[countOffset + 1] = 0x57.toByte()
        })
    }

    @Test
    fun `requires canonical distinct group membership between three and one hundred`() {
        val invite = Envelope.GroupInvite(
            id,
            1,
            groupId,
            "Group",
            members[0],
            members,
            emptyList(),
            byteArrayOf(1),
            requestId,
        )
        assertInstanceOf(Envelope.GroupInvite::class.java, EnvelopeCodec.decode(EnvelopeCodec.encode(invite)))

        listOf(
            invite.copy(members = members.take(2)),
            invite.copy(members = List(101) { "%064x".format(it + 1) }),
            invite.copy(members = listOf(members[0], members[1], members[1])),
            invite.copy(members = listOf(members[0].uppercase(), members[1], members[2])),
            invite.copy(coordinator = "ff".repeat(32)),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { EnvelopeCodec.encode(invalid) }
        }
    }

    @Test
    fun `group commits may shrink an established group to one member`() {
        val oneMember = Envelope.GroupCommit(
            id,
            1,
            groupId,
            listOf(members[0]),
            emptyList(),
            byteArrayOf(1),
        )

        assertInstanceOf(Envelope.GroupCommit::class.java, EnvelopeCodec.decode(EnvelopeCodec.encode(oneMember)))
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(oneMember.copy(members = emptyList()))
        }
    }

    @Test
    fun `pairing bundle envelopes round trip and bound their fields`() {
        val nonce = ByteArray(16) { it.toByte() }
        val bundle = ByteArray(1832) { (it % 251).toByte() }

        val request = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.BundleRequest(id, 9, nonce)))
        assertInstanceOf(Envelope.BundleRequest::class.java, request)
        assertArrayEquals(nonce, (request as Envelope.BundleRequest).nonce)

        val response = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.BundleResponse(id, 9, nonce, bundle)))
        assertInstanceOf(Envelope.BundleResponse::class.java, response)
        response as Envelope.BundleResponse
        assertArrayEquals(nonce, response.nonce)
        assertArrayEquals(bundle, response.bundle)

        // The nonce is fixed-width, so a wrong length is refused at encode rather than silently
        // consuming the neighbouring field at decode.
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.BundleRequest(id, 9, ByteArray(15)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.BundleResponse(id, 9, nonce, ByteArray(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope.BundleResponse(id, 9, nonce, ByteArray(EnvelopeLimits.MAX_KEY_BUNDLE_BYTES + 1)))
        }
    }

    @Test
    fun `pairing bundle envelopes are refused under wire versions one and two`() {
        // A version 3 tag inside a version 2 envelope is a forgery or a corruption, never an older
        // body that happens to parse - so it is refused instead of being read with a guessed layout.
        val encoded = EnvelopeCodec.encode(Envelope.BundleRequest(id, 9, ByteArray(16) { it.toByte() }))
        assertEquals(12, encoded[6].toInt())
        assertRejected(encoded.copyOf().also { it[5] = 2.toByte() })
        assertRejected(encoded.copyOf().also { it[5] = 1.toByte() })
    }

    @Test
    fun `version two envelopes of every pre-existing type still decode unchanged`() {
        // Version 3 changed no pre-existing body at all, so relabelling a freshly encoded envelope
        // as version 2 must decode to exactly the same value - which is what "backward compatible"
        // has to mean for the eight tags that predate T4.16.
        val envelopes = listOf(
            Envelope.Text(id, 1, "compat", forwarded = true),
            Envelope.Ack(id, 2, otherId),
            Envelope.Evidence(id, 3, listOf(byteArrayOf(1, 2, 3))),
            Envelope.KeyPackage(id, 4, requestId, byteArrayOf(4, 5)),
            Envelope.KeyPackageRequest(id, 5, requestId),
            Envelope.GroupLeave(id, 6, groupId),
            Envelope.ControlRejected(id, 7, requestId, ControlRejectionReason.REQUEST_EXPIRED),
        )
        envelopes.forEach { expected ->
            val asVersionTwo = EnvelopeCodec.encode(expected).also { it[5] = 2.toByte() }
            assertEnvelopeEquals(expected, EnvelopeCodec.decode(asVersionTwo))
        }
    }

    private fun assertRejected(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { EnvelopeCodec.decode(bytes) }
    }

    private fun assertEnvelopeEquals(expected: Envelope, actual: Envelope) {
        assertEquals(expected.type, actual.type)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.timestamp, actual.timestamp)
        when (expected) {
            is Envelope.Text -> {
                actual as Envelope.Text
                assertEquals(expected.body, actual.body)
                assertEquals(expected.forwarded, actual.forwarded)
            }
            is Envelope.Attachment -> {
                actual as Envelope.Attachment
                assertEquals(expected.forwarded, actual.forwarded)
                assertEquals(expected.name, actual.name)
                assertEquals(expected.mime, actual.mime)
                assertArrayEquals(expected.fileId, actual.fileId)
                assertEquals(expected.fileEpoch, actual.fileEpoch)
                assertArrayEquals(expected.fileKey, actual.fileKey)
                assertArrayEquals(expected.ciphertext, actual.ciphertext)
            }
            is Envelope.Ack -> assertEquals(expected.receivedId, (actual as Envelope.Ack).receivedId)
            is Envelope.Evidence -> assertByteArrayListsEqual(expected.proofs, (actual as Envelope.Evidence).proofs)
            is Envelope.KeyPackage -> {
                actual as Envelope.KeyPackage
                assertEquals(expected.requestId, actual.requestId)
                assertArrayEquals(expected.packageBytes, actual.packageBytes)
            }
            is Envelope.GroupInvite -> {
                actual as Envelope.GroupInvite
                assertEquals(expected.groupId, actual.groupId)
                assertEquals(expected.name, actual.name)
                assertEquals(expected.coordinator, actual.coordinator)
                assertEquals(expected.members, actual.members)
                assertByteArrayListsEqual(expected.proofs, actual.proofs)
                assertArrayEquals(expected.welcome, actual.welcome)
                assertEquals(expected.requestId, actual.requestId)
            }
            is Envelope.GroupCommit -> {
                actual as Envelope.GroupCommit
                assertEquals(expected.groupId, actual.groupId)
                assertEquals(expected.members, actual.members)
                assertByteArrayListsEqual(expected.proofs, actual.proofs)
                assertArrayEquals(expected.commit, actual.commit)
            }
            is Envelope.KeyPackageRequest -> assertEquals(expected.requestId, (actual as Envelope.KeyPackageRequest).requestId)
            is Envelope.GroupLeave -> assertEquals(expected.groupId, (actual as Envelope.GroupLeave).groupId)
            is Envelope.ControlRejected -> {
                actual as Envelope.ControlRejected
                assertEquals(expected.requestId, actual.requestId)
                assertEquals(expected.reason, actual.reason)
            }
            is Envelope.BundleRequest -> assertArrayEquals(expected.nonce, (actual as Envelope.BundleRequest).nonce)
            is Envelope.BundleResponse -> {
                actual as Envelope.BundleResponse
                assertArrayEquals(expected.nonce, actual.nonce)
                assertArrayEquals(expected.bundle, actual.bundle)
            }
        }
    }

    private fun assertByteArrayListsEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedBytes, actualBytes) -> assertArrayEquals(expectedBytes, actualBytes) }
    }
}
