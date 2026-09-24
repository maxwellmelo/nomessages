package dev.mx3.nomessages.core.messaging

/**
 * Authenticated application plaintext carried inside Signal or MLS.
 *
 * This model does not encrypt, authenticate, or route data. Callers must only decode it after
 * authenticated decryption and must encrypt the encoded bytes before transport or persistence.
 */
sealed interface Envelope {
    val id: String
    val timestamp: Long
    val type: EnvelopeType

    /**
     * A chat message body.
     *
     * [forwarded] marks a message the sender relayed from another conversation instead of composing
     * it. It is a single bit of provenance with no back-reference: there is deliberately no original
     * chat id, original author or "forwarded N times" counter, because any of those would leak the
     * sender's other conversations to the recipient. Forwarding again keeps the bit at `true`; it
     * never accumulates. Envelopes encoded before wire version 2 carry no such byte and decode as
     * `false` - see [EnvelopeCodec].
     */
    data class Text(
        override val id: String,
        override val timestamp: Long,
        val body: String,
        val forwarded: Boolean = false,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.TEXT
    }

    /** An attachment message body. See [Text.forwarded] for the semantics of [forwarded]. */
    data class Attachment(
        override val id: String,
        override val timestamp: Long,
        val name: String,
        val mime: String,
        val fileId: ByteArray,
        val fileEpoch: Long,
        val fileKey: ByteArray,
        val ciphertext: ByteArray,
        val forwarded: Boolean = false,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.ATTACHMENT
    }

    data class Ack(
        override val id: String,
        override val timestamp: Long,
        val receivedId: String,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.ACK
    }

    data class Evidence(
        override val id: String,
        override val timestamp: Long,
        val proofs: List<ByteArray>,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.EVIDENCE
    }

    data class KeyPackage(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
        val packageBytes: ByteArray,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.KEY_PACKAGE
    }

    data class GroupInvite(
        override val id: String,
        override val timestamp: Long,
        val groupId: String,
        val name: String,
        val coordinator: String,
        val members: List<String>,
        val proofs: List<ByteArray>,
        val welcome: ByteArray,
        val requestId: String,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.GROUP_INVITE
    }

    data class GroupCommit(
        override val id: String,
        override val timestamp: Long,
        val groupId: String,
        val members: List<String>,
        val proofs: List<ByteArray>,
        val commit: ByteArray,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.GROUP_COMMIT
    }

    data class KeyPackageRequest(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.KEY_PACKAGE_REQUEST
    }

    data class GroupLeave(
        override val id: String,
        override val timestamp: Long,
        val groupId: String,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.GROUP_LEAVE
    }

    data class ControlRejected(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
        val reason: ControlRejectionReason,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.CONTROL_REJECTED
    }

    /**
     * Asks a device being paired with for the PQXDH key bundle behind one of its own pairing offers.
     *
     * Introduced with QR format 2 (2026-09-17, T4.16): the pairing QR carries only
     * `SHA-256(bundle.encode())`, so the ~1.8 KB bundle travels over the peer's onion instead. This
     * is the **only** envelope pair that is exchanged before a Signal session exists, so - unlike
     * every other type here - it is not carried inside Signal ciphertext. It is carried by
     * `PacketKind.BUNDLE`, in the clear inside the Tor stream, which is exactly why it must never
     * carry anything secret: [nonce] is a value the requester read off a QR, and the answer is a
     * public key bundle whose integrity comes from the signed hash, not from the transport.
     *
     * [nonce] is the 16-byte nonce of the peer's offer. A device answers only for a nonce it minted
     * itself, only while that offer is live, and only once - see `PairingEngine.bundleFor`.
     */
    data class BundleRequest(
        override val id: String,
        override val timestamp: Long,
        val nonce: ByteArray,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.BUNDLE_REQUEST
    }

    /** The answer to a [BundleRequest]: see its documentation for why this is not secret. */
    data class BundleResponse(
        override val id: String,
        override val timestamp: Long,
        val nonce: ByteArray,
        val bundle: ByteArray,
    ) : Envelope {
        override val type: EnvelopeType = EnvelopeType.BUNDLE_RESPONSE
    }
}

enum class EnvelopeType {
    TEXT,
    ATTACHMENT,
    ACK,
    EVIDENCE,
    KEY_PACKAGE,
    GROUP_INVITE,
    GROUP_COMMIT,
    KEY_PACKAGE_REQUEST,
    GROUP_LEAVE,
    CONTROL_REJECTED,
    BUNDLE_REQUEST,
    BUNDLE_RESPONSE,
}

enum class ControlRejectionReason {
    KEY_PACKAGE_QUOTA,
    REQUEST_EXPIRED,
    GROUP_CAPACITY,
    INVALID_PREREQUISITE,
}

object EnvelopeLimits {
    const val MAX_ENVELOPE_BYTES: Int = 12 * 1024 * 1024
    const val MAX_TEXT_BYTES: Int = 8 * 1024 * 1024
    const val MAX_ATTACHMENT_CIPHERTEXT_BYTES: Int = 8 * 1024 * 1024 + 64 * 1024
    const val MAX_BINARY_FIELD_BYTES: Int = 8 * 1024 * 1024 + 64 * 1024
    const val MAX_PROOF_COUNT: Int = 4_950
    const val MAX_PROOF_BYTES: Int = 1_024
    const val MIN_MEMBER_COUNT: Int = 3
    const val MIN_COMMIT_MEMBER_COUNT: Int = 1
    const val MAX_MEMBER_COUNT: Int = 100
    const val MAX_NAME_BYTES: Int = 255
    const val MAX_MIME_BYTES: Int = 255

    /** Pairing offer nonce, matching `PairingEngine`'s 16-byte nonce. */
    const val PAIRING_NONCE_BYTES: Int = 16

    /**
     * Upper bound for a transported PQXDH bundle. The real encoding is ~1832 bytes (1569 of them
     * the Kyber-1024 public key); the headroom leaves the decoder a hard ceiling that does not have
     * to be revised for a key-size change, while staying four orders of magnitude below
     * [MAX_ENVELOPE_BYTES] so an unauthenticated pairing request can never allocate a large buffer.
     */
    const val MAX_KEY_BUNDLE_BYTES: Int = 4 * 1024
}
