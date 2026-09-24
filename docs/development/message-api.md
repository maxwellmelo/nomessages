# Messaging wire API

Package: `dev.mx3.nomessages.core.messaging`. All timestamps are nonnegative Unix epoch milliseconds. Every `id`, `receivedId`, `requestId`, and `groupId` is a lowercase canonical UUID string. Member and coordinator IDs are distinct lowercase 64-character Ed25519 public-key hex strings.

This codec is the application plaintext boundary. Encode an envelope before Signal or MLS encryption, and decode it only after authenticated decryption. It does not implement cryptography. Do not write an encoded envelope to plaintext storage.

## Kotlin API

```kotlin
sealed interface Envelope {
    val id: String
    val timestamp: Long
    val type: EnvelopeType

    data class Text(
        override val id: String,
        override val timestamp: Long,
        val body: String,
        val forwarded: Boolean = false,
    ) : Envelope

    data class Attachment(
        override val id: String,
        override val timestamp: Long,
        val name: String,
        val mime: String,
        val fileId: ByteArray,       // exactly 16 bytes
        val fileEpoch: Long,         // nonnegative
        val fileKey: ByteArray,      // exactly 32 bytes
        val ciphertext: ByteArray,   // 0 through 8 MiB + 64 KiB
        val forwarded: Boolean = false,
    ) : Envelope

    data class Ack(
        override val id: String,
        override val timestamp: Long,
        val receivedId: String,
    ) : Envelope

    data class Evidence(
        override val id: String,
        override val timestamp: Long,
        val proofs: List<ByteArray>,
    ) : Envelope

    data class KeyPackage(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
        val packageBytes: ByteArray,
    ) : Envelope

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
    ) : Envelope

    data class GroupCommit(
        override val id: String,
        override val timestamp: Long,
        val groupId: String,
        val members: List<String>,
        val proofs: List<ByteArray>,
        val commit: ByteArray,
    ) : Envelope

    data class KeyPackageRequest(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
    ) : Envelope

    data class GroupLeave(
        override val id: String,
        override val timestamp: Long,
        val groupId: String,
    ) : Envelope

    data class ControlRejected(
        override val id: String,
        override val timestamp: Long,
        val requestId: String,
        val reason: ControlRejectionReason,
    ) : Envelope
}

enum class EnvelopeType {
    TEXT, ATTACHMENT, ACK, EVIDENCE, KEY_PACKAGE,
    GROUP_INVITE, GROUP_COMMIT, KEY_PACKAGE_REQUEST, GROUP_LEAVE,
    CONTROL_REJECTED,
}

enum class ControlRejectionReason {
    KEY_PACKAGE_QUOTA, REQUEST_EXPIRED, GROUP_CAPACITY, INVALID_PREREQUISITE,
}

object EnvelopeCodec {
    fun encode(envelope: Envelope): ByteArray
    fun decode(bytes: ByteArray): Envelope
}

enum class PacketKind { SIGNAL, MLS }
data class Packet(val kind: PacketKind, val payload: ByteArray)

object WirePacket {
    const val MAX_PACKET_BYTES: Int
    const val MAX_PACKET_PAYLOAD_BYTES: Int
    fun encodeSignal(ciphertext: ByteArray): ByteArray
    fun encodeMls(ciphertext: ByteArray): ByteArray
    fun decode(bytes: ByteArray): Packet
}
```

`KeyPackage.requestId` correlates the response with the pending request and group-creation context. It deliberately carries no group identifier. An `Ack` has its own message ID, distinct from `receivedId`. The application must never acknowledge an `Ack`.

`ControlRejected.requestId` is the original control envelope's `id`, not an embedded request nonce. The rejection has its own distinct `id`. It is allowed only as authenticated Signal control traffic for a permanent control failure. The receiver acknowledges the rejection normally; the original sender removes the rejected request from its outbox and marks the related operation failed without marking it delivered. The enum is deliberately closed and the wire form has no error-text field, so a peer cannot inject arbitrary UI or log text. The runtime must accept it only when `requestId` identifies an outstanding rejectable control envelope; it must not use this type to reject `Text`, `Attachment`, `Ack`, or another `ControlRejected`.

`GroupInvite.members` contains 3 through 100 identities and includes `coordinator`. `GroupCommit.members` is the complete post-commit membership and contains 1 through 100 identities, allowing an established group to shrink. Lists preserve MLS order and reject duplicates. Each proof is 1 through 1,024 bytes; at most 4,950 proofs are accepted.

`Text.forwarded` and `Attachment.forwarded` mark a message the sender relayed from another conversation instead of composing it. It is one bit of provenance with no back-reference: the wire form deliberately has no origin chat id, no original author and no "forwarded N times" counter, because each of those would leak the sender's other conversations to the recipient. Re-forwarding keeps the bit `true`; it never accumulates. It is informational metadata only — no protocol decision is taken from its value, and a peer running a modified client can set or clear it freely, so it is not a provenance proof. It exists on these two types only; every control type is unchanged.

`Attachment.ciphertext` can be empty when an encrypted database record retains only attachment metadata and its wrapped file key. A transmitted incoming attachment should contain the encrypted file bytes and the runtime must validate the file format before accepting it. The 64 KiB allowance above the 8 MiB UI plaintext cap covers encrypted-file framing and tags.

## Envelope binary format

All integers are unsigned big-endian except `timestamp` and `fileEpoch`, which are signed 64-bit fields constrained to nonnegative values. Strings are strict UTF-8. A `u16-string` is `u16 byteLength || bytes`; a `blob` and normal string are `u32 byteLength || bytes`. Decoders reject malformed UTF-8, invalid or excessive lengths, unknown versions or types, noncanonical identifiers, and trailing bytes.

The common header is:

```
magic[4] = "NMFM"
version  = u16 2      // encoder always writes 2; decoder accepts 1 and 2
type     = u8
id       = u16-string canonical UUID
timestamp= i64 epoch milliseconds
```

Type tags and following fields are:

| Tag | Type | Fields in order |
|---:|---|---|
| 1 | Text | `body:string, forwarded:u8` (`forwarded` in version 2 only) |
| 2 | Attachment | `name:string, mime:string, fileId[16], fileEpoch:i64, fileKey[32], ciphertext:blob, forwarded:u8` (`forwarded` in version 2 only) |
| 3 | Ack | `receivedId:u16-string` |
| 4 | Evidence | `proofCount:u16, proofs[proofCount]:blob` |
| 5 | KeyPackage | `requestId:u16-string, packageBytes:blob` |
| 6 | GroupInvite | `groupId:u16-string, name:string, coordinator:u16-string, memberCount:u16, members:u16-string[], proofCount:u16, proofs:blob[], welcome:blob, requestId:u16-string` |
| 7 | GroupCommit | `groupId:u16-string, memberCount:u16, members:u16-string[], proofCount:u16, proofs:blob[], commit:blob` |
| 8 | KeyPackageRequest | `requestId:u16-string` |
| 9 | GroupLeave | `groupId:u16-string` |
| 11 | ControlRejected | `requestId:u16-string, reason:u8` |

Tag 10 is reserved and rejected by every decoder. `ControlRejectionReason` tags are 1 `KEY_PACKAGE_QUOTA`, 2 `REQUEST_EXPIRED`, 3 `GROUP_CAPACITY`, and 4 `INVALID_PREREQUISITE`; other values are rejected.

### Wire versions 1 and 2 (2026-09-17)

Version **1** is the original layout. Version **2** appends exactly one byte, the `forwarded` flag, to the **end of the TEXT and ATTACHMENT bodies** — after every field those bodies already had, so a version 1 body is a strict byte prefix of the version 2 one. The bodies of the other eight tags (`Ack`, `Evidence`, `KeyPackage`, `GroupInvite`, `GroupCommit`, `KeyPackageRequest`, `GroupLeave`, `ControlRejected`) are byte-identical under both versions.

- `encode` always emits the current version, `2`.
- `decode` accepts `1` and `2`, and rejects every other value (`Unsupported envelope version`). The first genuinely unsupported version number is therefore `3`.
- Backward compatibility rule: in a version 1 TEXT/ATTACHMENT the field is **absent**, not zero. The reader consumes no byte at all and yields the safe default `forwarded = false`, which is also what keeps the "no trailing bytes" check satisfied for both versions.
- In version 2 the byte must be exactly `0x00` or `0x01`. Any other value is a malformed envelope (`Invalid forwarded flag`), never a silently truthy value.

`EnvelopeCodecTest` covers this with a hand-built version 1 TEXT byte vector — magic, `u16 1`, tag, id, timestamp, body blob and nothing after it — asserting that it decodes with `forwarded = false` and that re-encoding the same payload produces a version 2 envelope exactly one byte longer whose bytes after the version field are otherwise unchanged.

The encoded envelope cap is 12 MiB. Text is capped at 8 MiB. Key package, welcome, and commit fields are each capped at 8 MiB + 64 KiB, while the overall envelope cap still applies. Text permits tab, LF, and CR but rejects other control characters and NUL. Display names reject controls; MIME types use an ASCII `type/subtype` form.

## Outer packet format

`WirePacket` is exactly `kind:u8 || payloadLength:u32 || ciphertext[payloadLength]`, where kind 1 is Signal and kind 2 is MLS. It carries no sender, contact, onion, or group identifier. The caller identifies a peer from its authenticated stream and trial-decrypts against eligible Signal sessions or MLS groups. Payloads must be nonempty. The complete packet cap is 16 MiB, matching `FrameCodec.MAX_MESSAGE`.

All validation failures from either decoder are reported as `IllegalArgumentException`.
