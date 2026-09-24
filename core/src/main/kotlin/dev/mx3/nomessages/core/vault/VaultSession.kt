package dev.mx3.nomessages.core.vault

import java.nio.file.Path

enum class VaultSlot { REAL, DECOY }

/** Borrowed key arrays. Callers must not retain copies after close. */
class VaultKeys internal constructor(
    val dbKey: ByteArray,
    val fileKey: ByteArray,
    val identityKey: ByteArray,
) : AutoCloseable {
    override fun close() { dbKey.fill(0); fileKey.fill(0); identityKey.fill(0) }
}

interface VaultStorage {
    fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys)
    fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable
    fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys)
    fun beforeExport(directory: Path)

    /**
     * Tops up [slot]'s media directory with blind filler - the same shape `MessagingEngine`'s
     * blind-cover write uses, no [dev.mx3.nomessages.core.files] record, nothing that could be
     * mistaken for a tracked attachment - until its total allocated size matches [matchSlot]'s.
     * A no-op when [slot] is already at least as large as [matchSlot].
     *
     * Exists for `VaultManager.resetPanicPassword`: it rebuilds the decoy slot from scratch (a
     * handful of small fixed fixtures), while the real slot's media directory may since have grown
     * arbitrarily larger through ordinary use (`MessagingEngine.storeAttachment`'s blind-cover
     * growth keeps the two directories equal only while both are *live*; a from-scratch rebuild
     * starts back at the small fixture size). Without this, the parity [alignAllocations] enforces
     * would fail on every panic-password reset of a vault that has ever sent or received an
     * attachment - see the T4.1 follow-up in `docs/security-model.md`.
     */
    fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot)
}

class VaultSession internal constructor(
    val slot: VaultSlot,
    val epoch: Long,
    val keys: VaultKeys,
    private val storageHandle: AutoCloseable,
    internal val authKey: ByteArray,
    internal val wrappingKey: ByteArray,
) : AutoCloseable {
    var isClosed: Boolean = false
        private set
    override fun close() {
        if (isClosed) return
        isClosed = true
        try { storageHandle.close() } finally { keys.close(); authKey.fill(0); wrappingKey.fill(0) }
    }
}
