# Vault API / task 2 plan

All code is synchronous, JDK 21 / Kotlin, package `dev.mx3.nomessages.core.vault` or `.files`. Call from one serialized IO context. Never call concurrently with close/export.

```kotlin
enum class VaultSlot { REAL, DECOY } // internal application routing only; never user-facing/logged
data class KdfParams(val memoryKiB: Int = 65536, val iterations: Int = 3)
class KdfCalibrator(crypto: Crypto) {
  fun calibrate(minimum: KdfParams = KdfParams(), targetMillis: Long = 2500): KdfParams
}
class PasswordPolicy { fun validate(password: CharArray); fun validatePair(real: CharArray, panic: CharArray) }
data class PasswordStrength(val score: Int, val bitsEstimate: Double, val feedback: List<String>)
fun estimateStrength(password: CharArray): PasswordStrength // score 0..4; policy accepts >= 3
object PasswordFeedback { // the complete set of keys estimateStrength emits; UI maps each to a string resource
  const val TOO_SHORT; SEQUENTIAL_CHARS; REPEATED_CHARS; COMMON_WORD; DATE_OR_YEAR
  const val TRIVIAL_SUFFIX_PATTERN; ADD_LENGTH; ADD_VARIETY; LOOKS_STRONG
}
class VaultKeys : AutoCloseable {
  val dbKey: ByteArray; val fileKey: ByteArray; val identityKey: ByteArray
}
interface VaultStorage {
  fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys)
  fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable
  fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys)
  fun beforeExport(directory: Path) // assert all DB handles closed, checkpoint WAL
}
class VaultSession : AutoCloseable {
  val slot: VaultSlot; val epoch: Long; val keys: VaultKeys
  // close closes storage handle, then always wipes arrays even if handle.close throws
}
class VaultManager(crypto: Crypto, storage: VaultStorage) {
  fun recover(directory: Path): Boolean // call at startup BEFORE checking header existence
  fun create(directory: Path, realPassword: CharArray, panicPassword: CharArray,
             params: KdfParams? = null): Unit // null calibrates inside guarded password lifetime
  fun unlock(directory: Path, password: CharArray): VaultSession
  fun resetPanicPassword(directory: Path, realSession: VaultSession, password: CharArray)
}
class VaultArchive(crypto: Crypto, storage: VaultStorage, limits: ArchiveLimits = ArchiveLimits()) {
  fun export(directory: Path, password: CharArray, destination: OutputStream)
  fun export(directory: Path, session: VaultSession, destination: OutputStream)
  fun import(source: InputStream, directory: Path, password: CharArray)
}
```

`create` initializes BOTH slots in a private sibling staging directory, calls `alignAllocations`, verifies `real.db` and `decoy.db` have equal byte lengths, atomically moves to a previously absent destination. Adapter must initialize real DB and plausible decoy contents (including media), use exclusively encrypted storage, and produce equal allocated DB sizes. No append-padding raw SQLCipher files: reserve encrypted pages inside each DB. Runtime must keep size alignment policy on growth. `open` mounts ONLY selected slot. `beforeExport` must checkpoint/close DBs before archive snapshot; caller closes session first. Password arrays are consumed and zero-filled on every exit, including errors. Keys are borrowed arrays valid only during session; do not retain copies. JVM/IME immutable intermediates cannot be guaranteed erased.

Session-export overload consumes and closes the session and never needs the original password. The session privately retains its header authentication key and selected wrapping key; all are wiped on close. Panic reset is real-session-only, consumes/closes that session, rejects equality with the real password by a KDF check, and **recreates decoy contents and identity**. Present this destructive decoy reset explicitly in UI. Reset stages encrypted files and uses directory renames with rollback on failure. `recover(directory)` restores only the deterministic sibling `.<name>.reset-backup` if the destination is absent and the backup has structurally valid bounded header and matching regular DBs. Authentication still happens on password unlock. If both directories exist, neither is removed or chosen; the next reset refuses unresolved backup state. Call `recover` during cold start before deciding whether to show setup. This is a recovery protocol, not a claim of crash-atomic replacement or guaranteed filesystem durability. Reserve identical encrypted DB capacity in setup and cap SQLite growth; maintenance beyond the reserve needs both passwords.

Archive defaults: maximum ZIP bytes 1 GiB, decompressed total 1 GiB, individual file 512 MiB, 10,000 entries. Limits can be explicitly changed using `ArchiveLimits`, but classic ZIP only (<4 GiB) is intentional. Allowed files: `header.bin`, `real.db`, `decoy.db`, and ASCII path components under `real.files/` and `decoy.files/`; WAL/SHM must be checkpointed/removed before export. `manifest.bin` is reserved for the keyed archive manifest.

Header v1 has independent DB/file/identity seed keys plus a shared header/archive authentication key wrapped under BOTH independent password KDFs. This extra key permits one keyed BLAKE2b tag over the complete header and authenticated ciphertext manifests without sharing either vault's data keys. Both KDFs and both AEAD opens execute before selecting. Bounds are validated before any KDF allocation (64–256 MiB, 1–20 passes, p=1). The 4-byte header magic ("NMSG" — see runtime-catalog.md §6) is validated on every `unlock`/`open`.

```kotlin
// dev.mx3.nomessages.core.files
data class FileInfo(val fileId: ByteArray, val epoch: Long, val plaintextLength: Long)
class EncryptedFiles(crypto: Crypto) {
  fun newFileKey(): ByteArray
  fun encrypt(source: InputStream, destination: OutputStream, key: ByteArray,
              fileId: ByteArray, epoch: Long, plaintextLength: Long): FileInfo
  fun decrypt(source: InputStream, destination: OutputStream, key: ByteArray,
              expectedFileId: ByteArray, expectedEpoch: Long): FileInfo
}
```

File IDs are 16 random bytes; per-file key must be fresh 32 random bytes, enveloped in encrypted DB/message using session `fileKey`. File streaming emits plaintext ONLY to caller-provided output (internal memory/viewer; never a disk stream). Each chunk authenticates header/file id/epoch/total length/index/length/terminal. A mandatory final empty chunk authenticates stream termination. Decrypt may emit an authenticated prefix before reporting later truncation: caller must discard the output on any failure, and must not treat it as a complete file until return.

Archives stage only ciphertext, constrain ZIP compressed/decompressed bytes and entry count, reject traversal, symlink/nonregular entries, duplicates, unknown paths and truncated/extra ZIP data, authenticate every file with a keyed manifest, verify password/header, then atomically publish. Import only accepts a virgin absent directory. No plaintext materialization.

Implementation sequence: (1) failing session/header/password tests then bounded dual-wrap/session implementation, (2) adversarial streaming tests then file codec, (3) malicious ZIP/manifest and roundtrip tests then archive, (4) real libsodium host tests and report. No commits or agent delegation in this assigned task.
