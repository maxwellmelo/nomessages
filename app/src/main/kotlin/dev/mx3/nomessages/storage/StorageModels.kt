package dev.mx3.nomessages.storage

enum class MessageDirection { INCOMING, OUTGOING }

enum class MessageStatus { PENDING, SENT, DELIVERED, READ, FAILED }

/**
 * One paired contact.
 *
 * The three doorbell fields are **reserved for the doorbell feature (T4.17)** and are read by
 * nothing today. All three are settled during the pairing exchange (QR format 2, T4.16) and stored
 * at pairing time because there is no second authenticated channel on which to obtain or agree them
 * later. Two describe the peer, one describes this device, and they are not interchangeable:
 *
 * - [doorbellOnion] - the peer's second, dedicated onion address, derived from a different vault
 *   seed than [onion] so the two are unlinkable. **Where to knock.**
 * - [doorbellToken] - the 32-byte secret that peer minted for this exchange and will expect to be
 *   presented when its doorbell is rung. **What to present when knocking.**
 * - [doorbellTokenIssued] - the 32-byte secret **this device** minted for this contact, inside its
 *   own signed offer. It is what this contact will present when it rings *our* doorbell, so it is
 *   the value the local listener has to recognise. **What to accept from this contact.**
 *
 * The first two come from the peer's signed offer and the third from ours, so the SAS the two humans
 * compared covers all three - each in one direction.
 *
 * Why the third one is a separate column rather than derivable: it is not derivable from anything.
 * Each side mints an independent random secret per exchange, so `doorbellTokenIssued` for contact X
 * has no relation to X's `doorbellToken`, to any other contact's columns, or to the vault identity.
 * Until schema v4 it was generated, published inside our offer, and then dropped on the floor - the
 * vault could ring a peer's doorbell but had nothing to check an incoming ring against.
 *
 * All three default to empty: contacts written before schema v3/v4 have no such columns, and a
 * display-only contact has no network identity at all.
 */
data class ContactRecord(
    val id: String,
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
    val doorbellOnion: String = "",
    val doorbellToken: ByteArray = ByteArray(0),
    val doorbellTokenIssued: ByteArray = ByteArray(0),
)

data class ChatRecord(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessage: ByteArray?,
    val lastTimestamp: Long?,
    val lastOutgoing: Boolean = false,
    val lastStatus: MessageStatus? = null,
)

/**
 * One stored chat message.
 *
 * [forwarded] mirrors the `forwarded` flag of the envelope in [body] and exists as its own column so
 * the chat list can be rendered without decoding every envelope. The two are always written
 * together - see `MessagingEngine.envelopeForwarded` - and default to `false` for rows written
 * before the flag existed (schema v1) and for every non-chat record.
 */
data class MessageRecord(
    val id: String,
    val peerOrGroup: String,
    val direction: MessageDirection,
    val timestamp: Long,
    val body: ByteArray,
    val status: MessageStatus,
    val forwarded: Boolean = false,
)

data class PendingFrame(
    val id: String,
    val destinationOnion: String,
    val frame: ByteArray,
    val createdAt: Long,
)

data class FileRecord(
    val id: String,
    val relativePath: String,
    val encryptedParameters: ByteArray,
    val size: Long,
    val epoch: Long,
    val displayName: String,
    val mimeType: String,
)

data class GroupRecord(
    val id: String,
    val name: String,
    val state: ByteArray,
    val members: List<String>,
    val coordinator: String,
    val createdAt: Long,
)

data class PairEvidence(
    val firstId: String,
    val secondId: String,
    val evidence: ByteArray,
    val pairedAt: Long,
)

object StorageKeys {
    const val IDENTITY = "identity"
    const val ONION_SEED = "onion_seed"
}
