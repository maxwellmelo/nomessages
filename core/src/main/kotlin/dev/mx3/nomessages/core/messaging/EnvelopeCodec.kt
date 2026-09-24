package dev.mx3.nomessages.core.messaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Canonical, versioned binary encoding for [Envelope].
 *
 * Layout: `magic("NMFM") | version(u16) | tag(u8) | id(short-UTF8) | timestamp(i64) | body`, where
 * `body` depends on the tag.
 *
 * Wire versions:
 * - **1** - the original layout.
 * - **2** - appends one `forwarded` byte (0 or 1) to the end of the TEXT and ATTACHMENT bodies.
 *   No other tag's body changed, so for them version 1 and version 2 are byte-identical.
 * - **3** - adds the BUNDLE_REQUEST and BUNDLE_RESPONSE tags of the pairing bundle fetch
 *   (2026-09-17, T4.16). No pre-existing body changed at all, so for every other tag versions 2 and
 *   3 are byte-identical; the two new tags simply do not exist below version 3 and are refused
 *   there rather than parsed with a guessed layout.
 *
 * [encode] always emits the current [version]; [decode] accepts every version up to it. A version 1
 * TEXT/ATTACHMENT has no `forwarded` byte at all (not a zero byte: the field is simply absent) and
 * decodes with the safe default `forwarded = false`, so history written before forwarding existed
 * still reads back.
 */
object EnvelopeCodec {
    private val magic = byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(), 'M'.code.toByte()) // "NMFM" envelope wire magic.
    private const val version = 3
    /** The first wire version that has the two pairing-bundle tags. */
    private const val bundleVersion = 3
    /** The first wire version that has the trailing TEXT/ATTACHMENT `forwarded` byte. */
    private const val forwardedVersion = 2
    private const val uuidBytes = 36
    private const val memberIdBytes = 64
    private const val fileIdBytes = 16
    private const val fileKeyBytes = 32

    fun encode(envelope: Envelope): ByteArray {
        validate(envelope)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(magic)
            writer.writeShort(version)
            writer.writeByte(tag(envelope.type))
            writer.writeShortUtf8(envelope.id)
            writer.writeLong(envelope.timestamp)
            when (envelope) {
                // The `forwarded` byte is appended after every pre-existing field of the body, so a
                // version 1 reader would stop exactly where the version 1 body ended. It is only
                // written for the two chat-message types; no other body changed in version 2.
                is Envelope.Text -> {
                    writer.writeUtf8(envelope.body)
                    writer.writeByte(if (envelope.forwarded) 1 else 0)
                }
                is Envelope.Attachment -> {
                    writer.writeUtf8(envelope.name)
                    writer.writeUtf8(envelope.mime)
                    writer.write(envelope.fileId)
                    writer.writeLong(envelope.fileEpoch)
                    writer.write(envelope.fileKey)
                    writer.writeBlob(envelope.ciphertext)
                    writer.writeByte(if (envelope.forwarded) 1 else 0)
                }
                is Envelope.Ack -> writer.writeShortUtf8(envelope.receivedId)
                is Envelope.Evidence -> writer.writeProofs(envelope.proofs)
                is Envelope.KeyPackage -> {
                    writer.writeShortUtf8(envelope.requestId)
                    writer.writeBlob(envelope.packageBytes)
                }
                is Envelope.GroupInvite -> {
                    writer.writeShortUtf8(envelope.groupId)
                    writer.writeUtf8(envelope.name)
                    writer.writeShortUtf8(envelope.coordinator)
                    writer.writeMembers(envelope.members)
                    writer.writeProofs(envelope.proofs)
                    writer.writeBlob(envelope.welcome)
                    writer.writeShortUtf8(envelope.requestId)
                }
                is Envelope.GroupCommit -> {
                    writer.writeShortUtf8(envelope.groupId)
                    writer.writeMembers(envelope.members)
                    writer.writeProofs(envelope.proofs)
                    writer.writeBlob(envelope.commit)
                }
                is Envelope.KeyPackageRequest -> writer.writeShortUtf8(envelope.requestId)
                is Envelope.GroupLeave -> writer.writeShortUtf8(envelope.groupId)
                is Envelope.ControlRejected -> {
                    writer.writeShortUtf8(envelope.requestId)
                    writer.writeByte(rejectionReasonTag(envelope.reason))
                }
                // Fixed-width nonce, no length prefix: `validate` already pinned it to exactly
                // PAIRING_NONCE_BYTES, so a length field would only be a second place to disagree.
                is Envelope.BundleRequest -> writer.write(envelope.nonce)
                is Envelope.BundleResponse -> {
                    writer.write(envelope.nonce)
                    writer.writeBlob(envelope.bundle)
                }
            }
        }
        return output.toByteArray().also {
            require(it.size <= EnvelopeLimits.MAX_ENVELOPE_BYTES) { "Envelope exceeds maximum size" }
        }
    }

    fun decode(bytes: ByteArray): Envelope {
        require(bytes.size <= EnvelopeLimits.MAX_ENVELOPE_BYTES) { "Envelope exceeds maximum size" }
        return try {
            val reader = Reader(bytes)
            require(reader.readBytes(magic.size).contentEquals(magic)) { "Invalid envelope magic" }
            // Every wire version up to the current one is accepted: version 2 only appended a
            // `forwarded` byte to the TEXT and ATTACHMENT bodies and version 3 only added two new
            // tags, so every pre-existing tag parses identically under all three.
            val wireVersion = reader.readUnsignedShort()
            require(wireVersion in 1..version) { "Unsupported envelope version" }
            val type = typeForTag(reader.readUnsignedByte())
            val id = reader.readShortUtf8(uuidBytes)
            val timestamp = reader.readLong()
            // Kotlin evaluates constructor arguments left to right, so each `reader.read*` below
            // consumes the wire in declaration order - readForwarded is last on purpose.
            val envelope = when (type) {
                EnvelopeType.TEXT -> Envelope.Text(
                    id,
                    timestamp,
                    reader.readUtf8(EnvelopeLimits.MAX_TEXT_BYTES),
                    reader.readForwarded(wireVersion),
                )
                EnvelopeType.ATTACHMENT -> Envelope.Attachment(
                    id,
                    timestamp,
                    reader.readUtf8(EnvelopeLimits.MAX_NAME_BYTES),
                    reader.readUtf8(EnvelopeLimits.MAX_MIME_BYTES),
                    reader.readBytes(fileIdBytes),
                    reader.readLong(),
                    reader.readBytes(fileKeyBytes),
                    reader.readBlob(EnvelopeLimits.MAX_ATTACHMENT_CIPHERTEXT_BYTES),
                    reader.readForwarded(wireVersion),
                )
                EnvelopeType.ACK -> Envelope.Ack(id, timestamp, reader.readShortUtf8(uuidBytes))
                EnvelopeType.EVIDENCE -> Envelope.Evidence(id, timestamp, reader.readProofs())
                EnvelopeType.KEY_PACKAGE -> Envelope.KeyPackage(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                    reader.readBlob(EnvelopeLimits.MAX_BINARY_FIELD_BYTES),
                )
                EnvelopeType.GROUP_INVITE -> Envelope.GroupInvite(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                    reader.readUtf8(EnvelopeLimits.MAX_NAME_BYTES),
                    reader.readShortUtf8(memberIdBytes),
                    reader.readMembers(EnvelopeLimits.MIN_MEMBER_COUNT),
                    reader.readProofs(),
                    reader.readBlob(EnvelopeLimits.MAX_BINARY_FIELD_BYTES),
                    reader.readShortUtf8(uuidBytes),
                )
                EnvelopeType.GROUP_COMMIT -> Envelope.GroupCommit(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                    reader.readMembers(EnvelopeLimits.MIN_COMMIT_MEMBER_COUNT),
                    reader.readProofs(),
                    reader.readBlob(EnvelopeLimits.MAX_BINARY_FIELD_BYTES),
                )
                EnvelopeType.KEY_PACKAGE_REQUEST -> Envelope.KeyPackageRequest(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                )
                EnvelopeType.GROUP_LEAVE -> Envelope.GroupLeave(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                )
                EnvelopeType.CONTROL_REJECTED -> Envelope.ControlRejected(
                    id,
                    timestamp,
                    reader.readShortUtf8(uuidBytes),
                    rejectionReasonForTag(reader.readUnsignedByte()),
                )
                EnvelopeType.BUNDLE_REQUEST -> {
                    requireBundleVersion(wireVersion)
                    Envelope.BundleRequest(id, timestamp, reader.readBytes(EnvelopeLimits.PAIRING_NONCE_BYTES))
                }
                EnvelopeType.BUNDLE_RESPONSE -> {
                    requireBundleVersion(wireVersion)
                    Envelope.BundleResponse(
                        id,
                        timestamp,
                        reader.readBytes(EnvelopeLimits.PAIRING_NONCE_BYTES),
                        reader.readBlob(EnvelopeLimits.MAX_KEY_BUNDLE_BYTES),
                    )
                }
            }
            require(reader.remaining == 0) { "Trailing envelope bytes" }
            validate(envelope)
            envelope
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Malformed envelope", exception)
        }
    }

    private fun validate(envelope: Envelope) {
        requireCanonicalUuid(envelope.id, "id")
        require(envelope.timestamp >= 0) { "Timestamp must be nonnegative epoch milliseconds" }
        when (envelope) {
            is Envelope.Text -> requireBody(envelope.body)
            is Envelope.Attachment -> {
                requireDisplayName(envelope.name, "Attachment name")
                requireMime(envelope.mime)
                require(envelope.fileId.size == fileIdBytes) { "File ID must be 16 bytes" }
                require(envelope.fileEpoch >= 0) { "File epoch must be nonnegative" }
                require(envelope.fileKey.size == fileKeyBytes) { "File key must be 32 bytes" }
                require(envelope.ciphertext.size <= EnvelopeLimits.MAX_ATTACHMENT_CIPHERTEXT_BYTES) {
                    "Attachment ciphertext exceeds maximum size"
                }
            }
            is Envelope.Ack -> {
                requireCanonicalUuid(envelope.receivedId, "receivedId")
                require(envelope.receivedId != envelope.id) { "Acknowledgement ID must differ from received message ID" }
            }
            is Envelope.Evidence -> requireProofs(envelope.proofs)
            is Envelope.KeyPackage -> {
                requireCanonicalUuid(envelope.requestId, "requestId")
                requireBinary(envelope.packageBytes, "Key package")
            }
            is Envelope.GroupInvite -> {
                requireCanonicalUuid(envelope.groupId, "groupId")
                requireDisplayName(envelope.name, "Group name")
                requireMemberId(envelope.coordinator, "coordinator")
                requireMembers(envelope.members, EnvelopeLimits.MIN_MEMBER_COUNT)
                require(envelope.coordinator in envelope.members) { "Coordinator must be a group member" }
                requireProofs(envelope.proofs)
                requireBinary(envelope.welcome, "MLS welcome")
                requireCanonicalUuid(envelope.requestId, "requestId")
            }
            is Envelope.GroupCommit -> {
                requireCanonicalUuid(envelope.groupId, "groupId")
                requireMembers(envelope.members, EnvelopeLimits.MIN_COMMIT_MEMBER_COUNT)
                requireProofs(envelope.proofs)
                requireBinary(envelope.commit, "MLS commit")
            }
            is Envelope.KeyPackageRequest -> requireCanonicalUuid(envelope.requestId, "requestId")
            is Envelope.GroupLeave -> requireCanonicalUuid(envelope.groupId, "groupId")
            is Envelope.ControlRejected -> {
                requireCanonicalUuid(envelope.requestId, "requestId")
                require(envelope.requestId != envelope.id) { "Rejection ID must differ from original envelope ID" }
            }
            is Envelope.BundleRequest -> requirePairingNonce(envelope.nonce)
            is Envelope.BundleResponse -> {
                requirePairingNonce(envelope.nonce)
                require(envelope.bundle.size in 1..EnvelopeLimits.MAX_KEY_BUNDLE_BYTES) {
                    "Key bundle has invalid size"
                }
            }
        }
    }

    private fun requirePairingNonce(value: ByteArray) {
        require(value.size == EnvelopeLimits.PAIRING_NONCE_BYTES) {
            "Pairing nonce must be ${EnvelopeLimits.PAIRING_NONCE_BYTES} bytes"
        }
    }

    /**
     * The two pairing-bundle tags did not exist before wire version 3. Refusing them outright is
     * what keeps the version number honest: a version 2 envelope claiming tag 12 is a forgery or a
     * corruption, never an older body that happens to parse.
     */
    private fun requireBundleVersion(wireVersion: Int) {
        require(wireVersion >= bundleVersion) { "Pairing bundle envelopes require wire version $bundleVersion" }
    }

    private fun requireBody(value: String) {
        val bytes = strictUtf8(value)
        require(bytes.size <= EnvelopeLimits.MAX_TEXT_BYTES) { "Text exceeds maximum size" }
        require(value.none(::forbiddenBodyCharacter)) { "Text contains a forbidden control character" }
    }

    private fun requireDisplayName(value: String, field: String) {
        val bytes = strictUtf8(value)
        require(value.isNotBlank()) { "$field must not be blank" }
        require(bytes.size <= EnvelopeLimits.MAX_NAME_BYTES) { "$field exceeds maximum size" }
        require(value.none { Character.isISOControl(it) }) { "$field contains a control character" }
    }

    private fun requireMime(value: String) {
        val bytes = strictUtf8(value)
        require(bytes.size <= EnvelopeLimits.MAX_MIME_BYTES) { "MIME type exceeds maximum size" }
        require(mimePattern.matches(value)) { "Invalid MIME type" }
    }

    private fun requireProofs(proofs: List<ByteArray>) {
        require(proofs.size <= EnvelopeLimits.MAX_PROOF_COUNT) { "Too many pairing proofs" }
        proofs.forEach {
            require(it.size in 1..EnvelopeLimits.MAX_PROOF_BYTES) { "Invalid pairing proof size" }
        }
    }

    private fun requireMembers(members: List<String>, minimum: Int) {
        require(members.size in minimum..EnvelopeLimits.MAX_MEMBER_COUNT) {
            "Invalid group membership count"
        }
        members.forEach { requireMemberId(it, "member") }
        require(members.toSet().size == members.size) { "Group members must be distinct" }
    }

    private fun requireMemberId(value: String, field: String) {
        require(memberIdPattern.matches(value)) { "$field must be lowercase Ed25519 public-key hex" }
    }

    private fun requireBinary(value: ByteArray, field: String) {
        require(value.size in 1..EnvelopeLimits.MAX_BINARY_FIELD_BYTES) { "$field has invalid size" }
    }

    private fun requireCanonicalUuid(value: String, field: String) {
        require(value.length == uuidBytes) { "$field must be a canonical UUID" }
        val parsed = try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("$field must be a canonical UUID", exception)
        }
        require(parsed.toString() == value) { "$field must be a canonical lowercase UUID" }
    }

    private fun forbiddenBodyCharacter(character: Char): Boolean =
        character == '\u0000' || (Character.isISOControl(character) && character !in allowedBodyControls)

    private fun tag(type: EnvelopeType): Int = when (type) {
        EnvelopeType.TEXT -> 1
        EnvelopeType.ATTACHMENT -> 2
        EnvelopeType.ACK -> 3
        EnvelopeType.EVIDENCE -> 4
        EnvelopeType.KEY_PACKAGE -> 5
        EnvelopeType.GROUP_INVITE -> 6
        EnvelopeType.GROUP_COMMIT -> 7
        EnvelopeType.KEY_PACKAGE_REQUEST -> 8
        EnvelopeType.GROUP_LEAVE -> 9
        EnvelopeType.CONTROL_REJECTED -> 11
        EnvelopeType.BUNDLE_REQUEST -> 12
        EnvelopeType.BUNDLE_RESPONSE -> 13
    }

    private fun typeForTag(tag: Int): EnvelopeType = when (tag) {
        1 -> EnvelopeType.TEXT
        2 -> EnvelopeType.ATTACHMENT
        3 -> EnvelopeType.ACK
        4 -> EnvelopeType.EVIDENCE
        5 -> EnvelopeType.KEY_PACKAGE
        6 -> EnvelopeType.GROUP_INVITE
        7 -> EnvelopeType.GROUP_COMMIT
        8 -> EnvelopeType.KEY_PACKAGE_REQUEST
        9 -> EnvelopeType.GROUP_LEAVE
        11 -> EnvelopeType.CONTROL_REJECTED
        12 -> EnvelopeType.BUNDLE_REQUEST
        13 -> EnvelopeType.BUNDLE_RESPONSE
        else -> throw IllegalArgumentException("Unknown envelope type")
    }

    private fun rejectionReasonTag(reason: ControlRejectionReason): Int = when (reason) {
        ControlRejectionReason.KEY_PACKAGE_QUOTA -> 1
        ControlRejectionReason.REQUEST_EXPIRED -> 2
        ControlRejectionReason.GROUP_CAPACITY -> 3
        ControlRejectionReason.INVALID_PREREQUISITE -> 4
    }

    private fun rejectionReasonForTag(tag: Int): ControlRejectionReason = when (tag) {
        1 -> ControlRejectionReason.KEY_PACKAGE_QUOTA
        2 -> ControlRejectionReason.REQUEST_EXPIRED
        3 -> ControlRejectionReason.GROUP_CAPACITY
        4 -> ControlRejectionReason.INVALID_PREREQUISITE
        else -> throw IllegalArgumentException("Unknown control rejection reason")
    }

    private fun DataOutputStream.writeShortUtf8(value: String) {
        val bytes = strictUtf8(value)
        require(bytes.size <= 0xffff) { "String is too long" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        writeBlob(strictUtf8(value))
    }

    private fun DataOutputStream.writeBlob(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeMembers(members: List<String>) {
        writeShort(members.size)
        members.forEach { writeShortUtf8(it) }
    }

    private fun DataOutputStream.writeProofs(proofs: List<ByteArray>) {
        writeShort(proofs.size)
        proofs.forEach { writeBlob(it) }
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun readUnsignedByte(): Int {
            requireRemaining(1)
            return bytes[offset++].toInt() and 0xff
        }

        fun readUnsignedShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()

        fun readInt(): Int = ByteBuffer.wrap(readBytes(Int.SIZE_BYTES)).int

        fun readLong(): Long = ByteBuffer.wrap(readBytes(Long.SIZE_BYTES)).long

        fun readBytes(length: Int): ByteArray {
            require(length >= 0) { "Negative field length" }
            requireRemaining(length)
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readShortUtf8(maxBytes: Int): String {
            val length = readUnsignedShort()
            require(length <= maxBytes) { "String exceeds maximum size" }
            return decodeUtf8(readBytes(length))
        }

        fun readUtf8(maxBytes: Int): String = decodeUtf8(readBlob(maxBytes))

        /**
         * Reads the trailing `forwarded` flag of a TEXT/ATTACHMENT body.
         *
         * Under wire version 1 the field does not exist, so nothing is consumed and the safe
         * default is returned - which is what keeps `require(remaining == 0)` satisfied for every
         * version. From version 2 on the byte must be exactly 0 or 1: anything else is a malformed
         * envelope, never a silently truthy value.
         */
        fun readForwarded(wireVersion: Int): Boolean {
            if (wireVersion < forwardedVersion) return false
            val flag = readUnsignedByte()
            require(flag == 0 || flag == 1) { "Invalid forwarded flag" }
            return flag == 1
        }

        fun readBlob(maxBytes: Int): ByteArray {
            val length = readInt()
            require(length in 0..maxBytes) { "Invalid binary field length" }
            return readBytes(length)
        }

        fun readMembers(minimum: Int): List<String> {
            val count = readUnsignedShort()
            require(count in minimum..EnvelopeLimits.MAX_MEMBER_COUNT) {
                "Invalid group member count"
            }
            return List(count) { readShortUtf8(memberIdBytes) }
        }

        fun readProofs(): List<ByteArray> {
            val count = readUnsignedShort()
            require(count <= EnvelopeLimits.MAX_PROOF_COUNT) { "Too many pairing proofs" }
            return List(count) {
                val proof = readBlob(EnvelopeLimits.MAX_PROOF_BYTES)
                require(proof.isNotEmpty()) { "Pairing proof must not be empty" }
                proof
            }
        }

        private fun requireRemaining(length: Int) {
            require(length <= remaining) { "Truncated envelope" }
        }
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (exception: Exception) {
        throw IllegalArgumentException("String is not valid Unicode", exception)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: Exception) {
        throw IllegalArgumentException("Malformed UTF-8", exception)
    }

    private val allowedBodyControls = setOf('\t', '\n', '\r')
    private val memberIdPattern = Regex("[0-9a-f]{$memberIdBytes}")
    private val mimePattern = Regex("[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]{0,126}")
}
