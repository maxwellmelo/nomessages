package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.Crypto
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class VaultManager(private val crypto: Crypto, private val storage: VaultStorage) {
    /** Restore only the deterministic old-directory backup when the destination is absent.
     * No password is available here: this validates structure only; unlock still authenticates it.
     * If both directories exist, neither is modified or automatically selected.
     */
    fun recover(directory: Path): Boolean {
        val absolute = directory.toAbsolutePath()
        if (Files.exists(absolute, NOFOLLOW_LINKS)) return false
        val backup = resetBackup(absolute)
        if (!Files.exists(backup, NOFOLLOW_LINKS)) return false
        require(Files.isDirectory(backup, NOFOLLOW_LINKS) && !Files.isSymbolicLink(backup)) { "Invalid vault recovery directory" }
        VaultHeader.requireStructure(readHeader(backup))
        requireEqualDatabases(backup)
        Files.move(backup, absolute, ATOMIC_MOVE)
        return true
    }

    fun create(directory: Path, realPassword: CharArray, panicPassword: CharArray,
               params: KdfParams? = null) {
        var stage: Path? = null
        var real: VaultKeys? = null
        var decoy: VaultKeys? = null
        var normalizedReal: ByteArray? = null
        var normalizedPanic: ByteArray? = null
        try {
            PasswordPolicy().validatePair(realPassword, panicPassword)
            require(!Files.exists(directory, NOFOLLOW_LINKS)) { "Vault destination must not exist" }
            require(!Files.exists(resetBackup(directory.toAbsolutePath()), NOFOLLOW_LINKS)) { "Vault recovery is required" }
            normalizedReal = normalizePassword(realPassword)
            normalizedPanic = normalizePassword(panicPassword)
            realPassword.fill('\u0000'); panicPassword.fill('\u0000')
            val calibrated = params ?: KdfCalibrator(crypto).calibrate()
            real = newKeys(); decoy = newKeys()
            val absolute = directory.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            stage = Files.createTempDirectory(absolute.parent, ".vault-create-")
            Files.createDirectory(stage.resolve("real.files"))
            Files.createDirectory(stage.resolve("decoy.files"))
            val header = VaultHeader.create(crypto, calibrated, System.currentTimeMillis(), normalizedReal, normalizedPanic, real, decoy)
            crypto.wipe(normalizedReal); normalizedReal = null
            crypto.wipe(normalizedPanic); normalizedPanic = null
            Files.write(stage.resolve("header.bin"), header)
            storage.initialize(stage, VaultSlot.REAL, real)
            storage.initialize(stage, VaultSlot.DECOY, decoy)
            storage.alignAllocations(stage, real, decoy)
            requireEqualDatabases(stage)
            Files.move(stage, absolute, ATOMIC_MOVE)
            stage = null
        } finally {
            realPassword.fill('\u0000'); panicPassword.fill('\u0000')
            normalizedReal?.let(crypto::wipe); normalizedPanic?.let(crypto::wipe)
            real?.close(); decoy?.close()
            stage?.let(::discardCiphertext)
        }
    }

    fun unlock(directory: Path, password: CharArray): VaultSession {
        var normalized: ByteArray? = null
        var header: UnlockedHeader? = null
        try {
            recover(directory)
            normalized = normalizePassword(password)
            password.fill('\u0000')
            header = VaultHeader.unlock(crypto, readHeader(directory), normalized)
            crypto.wipe(normalized); normalized = null
            val handle = storage.open(directory, header.slot, header.keys)
            val session = VaultSession(header.slot, header.epoch, header.keys, handle, header.authKey, header.wrappingKey)
            header = null // session now owns the keys
            return session
        } finally { password.fill('\u0000'); normalized?.let(crypto::wipe); header?.close() }
    }

    /** Replaces decoy contents with a fresh identity. Consumes and closes the real session. */
    fun resetPanicPassword(directory: Path, realSession: VaultSession, password: CharArray) {
        var normalized: ByteArray? = null
        var stage: Path? = null
        var replacementKeys: VaultKeys? = null
        var realKeys: VaultKeys? = null
        try {
            require(!realSession.isClosed && realSession.slot == VaultSlot.REAL) { "Real vault session required" }
            PasswordPolicy().validate(password)
            normalized = normalizePassword(password)
            password.fill('\u0000')
            replacementKeys = newKeys()
            realKeys = VaultKeys(realSession.keys.dbKey.copyOf(), realSession.keys.fileKey.copyOf(), realSession.keys.identityKey.copyOf())
            val absolute = directory.toAbsolutePath()
            val backup = resetBackup(absolute)
            require(!Files.exists(backup, NOFOLLOW_LINKS)) { "Previous vault backup must be reviewed before another reset" }
            val replacement = VaultHeader.resetPanic(crypto, readHeader(directory), realSession, normalized, replacementKeys)
            crypto.wipe(normalized); normalized = null
            realSession.close()
            storage.beforeExport(directory)
            stage = Files.createTempDirectory(absolute.parent, ".vault-reset-")
            copyCiphertextTree(absolute, stage)
            Files.delete(stage.resolve("decoy.db"))
            deleteTree(stage.resolve("decoy.files"))
            Files.createDirectory(stage.resolve("decoy.files"))
            storage.initialize(stage, VaultSlot.DECOY, replacementKeys)
            // The rebuild above starts the decoy media directory back at the small fixed fixture
            // size; `real.files` may since have grown arbitrarily larger through ordinary use. Top
            // the decoy back up to match before the parity check below, or a panic-password reset of
            // any vault that has ever sent/received an attachment would fail every time (T4.1
            // follow-up, docs/security-model.md).
            storage.padMediaToMatch(stage, VaultSlot.DECOY, VaultSlot.REAL)
            storage.alignAllocations(stage, realKeys, replacementKeys)
            requireEqualDatabases(stage)
            Files.write(stage.resolve("header.bin"), replacement)
            Files.move(absolute, backup, ATOMIC_MOVE)
            try { Files.move(stage, absolute, ATOMIC_MOVE); stage = null }
            catch (error: Throwable) {
                try { Files.move(backup, absolute, ATOMIC_MOVE) } catch (rollback: Throwable) { error.addSuppressed(rollback) }
                throw error
            }
            // Credentials are committed. A cleanup failure must not imply the previous password still applies.
            discardCiphertext(backup)
        } finally {
            password.fill('\u0000'); normalized?.let(crypto::wipe)
            replacementKeys?.close(); realKeys?.close(); realSession.close()
            stage?.let(::discardCiphertext)
        }
    }

    /**
     * Decoy-slot counterpart to [resetPanicPassword], for `NoMessagesController.changePanicPassword`
     * when the open session is the decoy: a coerced inspection must see the "trocar senha de
     * pânico" button behave exactly like the real one (same button, same dialog, same password
     * meter/validation, same wall-clock delay, same disk activity, same success notice) with
     * nothing that could reveal or change the real password. See `security-model.md` ("Oráculos de
     * isca", T4.7).
     *
     * There is no real session to authenticate here - only the real vault has a panic password to
     * replace - so this pays exactly the KDF cost [resetPanic] pays against the vault's own
     * calibrated [KdfParams] (one derivation for the "must differ from the current password" check,
     * one for the replacement master key) and discards every result. It also pays equivalent disk
     * I/O: [resetPanicPassword]'s cost is not just its two KDF calls but a full copy of the vault's
     * ciphertext tree (`copyCiphertextTree`), a fresh full-capacity rewrite of `decoy.db`
     * ([VaultStorage.initialize]) and the discard of the old tree afterwards - all of it scaling
     * with actual vault/media size, not a fixed constant. Matching only the KDF count left a
     * wall-clock and disk-bytes oracle open on any vault with real content (see the T4.7 follow-up
     * in `security-model.md`). This copies the same ciphertext tree into a throwaway staging
     * directory, rewrites a database-sized file the same way [resetPanicPassword] rewrites
     * `decoy.db`, and discards the staging directory - so total bytes read/written and wall-clock
     * time track [resetPanicPassword] regardless of vault/media size. The vault's own directory
     * ([directory]) is only ever read here, never written: the staging copy is the only thing
     * mutated, and it is fully discarded before returning. [directory] only has to hold a valid
     * `header.bin` plus `real.db`/`decoy.db`, which is guaranteed by the caller already having an
     * open session (real or decoy) against this same vault.
     */
    fun fakePanicPasswordChange(directory: Path, password: CharArray) {
        var normalized: ByteArray? = null
        var stage: Path? = null
        try {
            PasswordPolicy().validate(password)
            normalized = normalizePassword(password)
            password.fill('\u0000')
            val secret = normalized
            val absolute = directory.toAbsolutePath()
            val params = VaultHeader.kdfParams(readHeader(absolute))
            repeat(2) {
                val salt = crypto.random(16)
                try {
                    val discarded = crypto.derive(secret, salt, params.memoryKiB, params.iterations)
                    crypto.wipe(discarded)
                } finally { crypto.wipe(salt) }
            }
            // Pay the same disk I/O `resetPanicPassword` pays (full ciphertext-tree copy, then a
            // fresh full-capacity database rewrite, then a full discard) against a throwaway
            // staging directory, so the decoy path is not distinguishable from the real one by
            // wall-clock time or bytes touched. Nothing under `absolute` is ever written.
            stage = Files.createTempDirectory(absolute.parent, ".vault-fake-reset-")
            copyCiphertextTree(absolute, stage)
            Files.copy(stage.resolve("real.db"), stage.resolve("decoy.db"), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            password.fill('\u0000'); normalized?.let(crypto::wipe)
            stage?.let(::discardCiphertext)
        }
    }

    private fun newKeys(): VaultKeys {
        val allocated = arrayOfNulls<ByteArray>(3)
        try {
            for (index in allocated.indices) allocated[index] = crypto.random(32)
            return VaultKeys(allocated[0]!!, allocated[1]!!, allocated[2]!!)
        } catch (error: Throwable) {
            for (key in allocated) key?.let(crypto::wipe)
            throw error
        }
    }
}

internal fun resetBackup(directory: Path): Path = directory.resolveSibling("." + directory.fileName + ".reset-backup")

internal fun copyCiphertextTree(source: Path, destination: Path) {
    Files.walk(source).use { paths -> paths.forEach { path ->
        require(!Files.isSymbolicLink(path)) { "Symlink in vault" }
        val target = destination.resolve(source.relativize(path))
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target)
        else {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Nonregular vault file" }
            Files.copy(path, target)
        }
    } }
}

internal fun readHeader(directory: Path): ByteArray {
    val path = directory.resolve("header.bin")
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != VaultHeader.SIZE.toLong()) throw SecurityException("Invalid vault")
    return Files.readAllBytes(path)
}

internal fun requireEqualDatabases(directory: Path) {
    val real = directory.resolve("real.db")
    val decoy = directory.resolve("decoy.db")
    require(Files.isRegularFile(real, NOFOLLOW_LINKS) && Files.isRegularFile(decoy, NOFOLLOW_LINKS)) { "Vault databases missing" }
    require(Files.size(real) > 0 && Files.size(real) == Files.size(decoy)) { "Vault allocation policy failed" }
}

internal fun deleteTree(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

/** Cleanup never changes an already committed outcome or masks the original operation failure. */
internal fun discardCiphertext(path: Path) {
    try { deleteTree(path) }
    catch (_: Exception) {
        // Leave only encrypted recovery/staging data. A pending reset backup remains protected by recover().
    }
}
