package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.NativeCrypto
import dev.mx3.nomessages.core.crypto.Crypto
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class VaultTest {
    private val crypto: Crypto = NativeCrypto()
    private fun real() = "Q7vfN2rT8bLp4WzK6sHx".toCharArray()
    private fun panic() = "M9kP3vX7rB2nQ5sT8wLc".toCharArray()

    @Test fun distinctPasswordsOpenOnlyTheirOwnStorageAndCloseErasesBorrowedKeys() {
        val root = Files.createTempDirectory("vault-test").resolve("vault")
        val storage = TestStorage(crypto)
        var derivations = 0
        val counted = object : Crypto by crypto {
            override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
                derivations++
                return crypto.derive(password, salt, memoryKiB, iterations)
            }
        }
        val manager = VaultManager(counted, storage)
        val password = real()
        manager.create(root, password, panic(), KdfParams(65536, 1))
        assertTrue(password.all { it == '\u0000' })
        derivations = 0
        val r = manager.unlock(root, real())
        assertEquals(2, derivations)
        assertEquals(VaultSlot.REAL, r.slot)
        val rkey = r.keys.dbKey
        val expected = rkey.copyOf()
        r.close()
        assertTrue(rkey.all { it == 0.toByte() })
        derivations = 0
        manager.unlock(root, panic()).use {
            assertEquals(VaultSlot.DECOY, it.slot)
            assertFalse(expected.contentEquals(it.keys.dbKey))
        }
        assertEquals(2, derivations)
        derivations = 0
        assertThrows(SecurityException::class.java) { manager.unlock(root, "incorrect".toCharArray()) }
        assertEquals(2, derivations)
        assertEquals(listOf(VaultSlot.REAL, VaultSlot.DECOY), storage.opened)
    }

    @Test fun changedOtherWrapIsRejectedEvenWithValidPassword() {
        val root = Files.createTempDirectory("vault-tamper").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val file = root.resolve("header.bin")
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 1).toByte()
        Files.write(file, bytes)
        assertThrows(SecurityException::class.java) { manager.unlock(root, real()) }
    }

    @Test fun passwordPolicyRejectsWeakShortAndEqualPasswordsWhileAcceptingSymbols() {
        val policy = PasswordPolicy()
        // "Q7vfN2rT8bLp4WzK!" used to be in this list only because it carried a symbol. Symbols are
        // accepted since T4.10 and that string has no weakness pattern, so it moved to the positive
        // case below; "Senha123456!" replaces it as a genuinely weak symbol password (common word,
        // ascending digit run, capitalized-word-plus-trailing-symbol shape).
        for (password in listOf("p4ssw0rdp4ssw0rd", "qwertyuiopasdfghjkl", "Q7vfN2rT", "Senha123456!")) {
            assertThrows(IllegalArgumentException::class.java) { policy.validate(password.toCharArray()) }
        }
        policy.validate(real())
        policy.validate("Q7vfN2rT8bLp4WzK!".toCharArray())
        assertThrows(IllegalArgumentException::class.java) { policy.validatePair(real(), real()) }
        assertThrows(IllegalArgumentException::class.java) { KdfParams(1024, 1) }
        assertThrows(IllegalArgumentException::class.java) { KdfParams(65536, Int.MAX_VALUE) }
        val composed = "Q7vfN2rT8bLp4WzK6sHé".toCharArray()
        val decomposed = "Q7vfN2rT8bLp4WzK6sHe\u0301".toCharArray()
        policy.validate(composed)
        policy.validate(decomposed)
        assertThrows(IllegalArgumentException::class.java) { policy.validatePair(composed, decomposed) }
    }

    @Test fun maliciousKdfBudgetRejectedBeforeAnyAllocation() {
        val root = Files.createTempDirectory("vault-limits").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val bytes = Files.readAllBytes(root.resolve("header.bin"))
        java.nio.ByteBuffer.wrap(bytes).putInt(6, Int.MAX_VALUE)
        Files.write(root.resolve("header.bin"), bytes)
        var calls = 0
        val counted = object : Crypto by crypto {
            override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
                calls++; return crypto.derive(password, salt, memoryKiB, iterations)
            }
        }
        assertThrows(SecurityException::class.java) { VaultManager(counted, TestStorage(crypto)).unlock(root, real()) }
        assertEquals(0, calls)
    }

    @Test fun derivationFailureStillAttemptsOtherDerivationAndNeverOpensStorage() {
        val root = Files.createTempDirectory("vault-kdf-error").resolve("vault")
        val storage = TestStorage(crypto)
        VaultManager(crypto, storage).create(root, real(), panic(), KdfParams(65536, 1))
        var calls = 0
        val failing = object : Crypto by crypto {
            override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
                calls++
                if (calls == 1) throw IllegalStateException("Native allocation failed")
                return crypto.derive(password, salt, memoryKiB, iterations)
            }
        }
        assertThrows(SecurityException::class.java) { VaultManager(failing, storage).unlock(root, panic()) }
        assertEquals(2, calls)
        assertTrue(storage.opened.isEmpty())
    }

    @Test fun storageCloseFailureStillWipesEverySessionSecret() {
        val root = Files.createTempDirectory("vault-close-error").resolve("vault")
        val delegate = TestStorage(crypto)
        val storage = object : VaultStorage by delegate {
            override fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable = AutoCloseable { throw IllegalStateException("close failed") }
        }
        val manager = VaultManager(crypto, storage)
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val session = manager.unlock(root, real())
        val borrowed = listOf(session.keys.dbKey, session.keys.fileKey, session.keys.identityKey, session.authKey, session.wrappingKey)
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertTrue(borrowed.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(session.isClosed)
    }

    @Test fun resetPanicPreservesRealKeysReplacesDecoyAndRevokesPreviousPanic() {
        val root = Files.createTempDirectory("vault-reset").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val oldDecoy = manager.unlock(root, panic()).use { it.keys.dbKey.copyOf() }
        val session = manager.unlock(root, real())
        val realKey = session.keys.dbKey.copyOf()
        val next = "A6mR9xH2kV5zL8pQ3nTc".toCharArray()
        manager.resetPanicPassword(root, session, next.copyOf())
        assertTrue(session.isClosed)
        manager.unlock(root, real()).use { assertArrayEquals(realKey, it.keys.dbKey) }
        manager.unlock(root, next).use { assertEquals(VaultSlot.DECOY, it.slot); assertFalse(oldDecoy.contentEquals(it.keys.dbKey)) }
        assertThrows(SecurityException::class.java) { manager.unlock(root, panic()) }
    }

    /**
     * T4.7(a): the decoy's "trocar senha de pânico" button has to *cost* the same as the real
     * reset - the same two Argon2id derivations `resetPanic` pays (the "must differ" check plus
     * the replacement master key) - or the delay itself becomes the oracle even after the UI stopped
     * being one. Nothing on disk may change: no header rewrite, no new decoy generation, and both
     * passwords must still resolve exactly as they did before the call.
     */
    @Test fun fakePanicPasswordChangePaysTheSameKdfCostAsResetAndTouchesNothing() {
        val root = Files.createTempDirectory("vault-fake-panic").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val headerBefore = Files.readAllBytes(root.resolve("header.bin"))
        var derivations = 0
        val counted = object : Crypto by crypto {
            override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
                derivations++
                return crypto.derive(password, salt, memoryKiB, iterations)
            }
        }
        val decoyLookingPassword = "A6mR9xH2kV5zL8pQ3nTc".toCharArray()
        VaultManager(counted, TestStorage(crypto)).fakePanicPasswordChange(root, decoyLookingPassword)
        assertEquals(2, derivations)
        assertTrue(decoyLookingPassword.all { it == '\u0000' })
        assertArrayEquals(headerBefore, Files.readAllBytes(root.resolve("header.bin")))
        manager.unlock(root, real()).use { assertEquals(VaultSlot.REAL, it.slot) }
        manager.unlock(root, panic()).use { assertEquals(VaultSlot.DECOY, it.slot) }
    }

    /** Same acceptance floor as the real reset: `PasswordPolicy().validate` runs first, before any KDF call. */
    @Test fun fakePanicPasswordChangeRejectsAWeakPasswordLikeTheRealResetDoes() {
        val root = Files.createTempDirectory("vault-fake-panic-weak").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        var derivations = 0
        val counted = object : Crypto by crypto {
            override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
                derivations++
                return crypto.derive(password, salt, memoryKiB, iterations)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultManager(counted, TestStorage(crypto)).fakePanicPasswordChange(root, "weak".toCharArray())
        }
        assertEquals(0, derivations)
    }

    /**
     * Follow-up review (2026-09-23): matching only the KDF call count left the two branches
     * trivially distinguishable by wall-clock time and bytes touched, because only
     * `resetPanicPassword` copied/rewrote/discarded the vault's ciphertext tree - `fakePanicPasswordChange`
     * did zero file copies, zero database writes and zero deletes. `fakePanicPasswordChange` now pays
     * that same disk I/O (copy the ciphertext tree into a throwaway staging directory, rewrite a
     * database-sized file the same way `resetPanicPassword` rewrites `decoy.db`, discard the staging
     * directory), scaled to the vault's actual on-disk size including media, and never touching the
     * vault's own directory. This seeds media into both slots, then checks (a) the vault's own
     * directory is byte-for-byte unchanged by the decoy path, and (b) the two branches' wall-clock
     * times stay within the same order of magnitude of each other on that seeded content - a loose
     * bound, to avoid flaking on shared CI hardware, not a claim of exact parity (exact parity also
     * is not the goal: the two branches doing genuinely equivalent work is).
     */
    @Test fun fakePanicPasswordChangePaysComparableDiskIoToTheRealResetOnASeededVault() {
        val root = Files.createTempDirectory("vault-fake-panic-io").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }
        Files.write(root.resolve("real.files").resolve("seed.bin"), payload)
        Files.write(root.resolve("decoy.files").resolve("seed.bin"), payload)
        val snapshotBefore = snapshotTree(root)

        val fakePassword = "B7pK4wX1qM6yN9zR2sVd".toCharArray()
        val fakeStartNanos = System.nanoTime()
        VaultManager(crypto, TestStorage(crypto)).fakePanicPasswordChange(root, fakePassword)
        val fakeMillis = (System.nanoTime() - fakeStartNanos) / 1_000_000

        assertEquals(snapshotBefore, snapshotTree(root))

        val session = manager.unlock(root, real())
        val nextPanic = "T3fG8jL5oQ0uZ4xC7wEy".toCharArray()
        val resetStartNanos = System.nanoTime()
        manager.resetPanicPassword(root, session, nextPanic)
        val resetMillis = (System.nanoTime() - resetStartNanos) / 1_000_000

        // Loose bound: both branches are now dominated by the same vault-sized copy/rewrite, so
        // neither should be an order of magnitude cheaper than the other. The `+ 50` absorbs
        // scheduling noise on near-instant runs without weakening the check on the seeded content
        // this test actually exercises.
        assertTrue("fakeMillis=$fakeMillis resetMillis=$resetMillis", fakeMillis <= resetMillis * 5 + 50)
        assertTrue("fakeMillis=$fakeMillis resetMillis=$resetMillis", resetMillis <= fakeMillis * 5 + 50)
    }

    @Test fun resetRejectsRealPasswordAndDecoySessionWithoutDestroyingVault() {
        val root = Files.createTempDirectory("vault-reset-reject").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val bytes = Files.readAllBytes(root.resolve("header.bin"))
        val realSession = manager.unlock(root, real())
        assertThrows(IllegalArgumentException::class.java) { manager.resetPanicPassword(root, realSession, real()) }
        val decoy = manager.unlock(root, panic())
        assertThrows(IllegalArgumentException::class.java) { manager.resetPanicPassword(root, decoy, real()) }
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("header.bin")))
    }

    @Test fun interruptedResetRestoresOnlyBackupWhenDestinationAbsentAndStillRequiresAuthentication() {
        val root = Files.createTempDirectory("vault-recovery").resolve("vault")
        val manager = VaultManager(crypto, TestStorage(crypto))
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val backup = resetBackup(root)
        Files.move(root, backup)
        assertTrue(manager.recover(root))
        assertTrue(Files.exists(root))
        assertFalse(Files.exists(backup))
        assertThrows(SecurityException::class.java) { manager.unlock(root, "wrong".toCharArray()) }
        manager.unlock(root, real()).close()
        copyCiphertextTree(root, backup)
        assertFalse(manager.recover(root))
        assertTrue(Files.exists(backup)) // never chooses between two extant generations
    }

    @Test fun calibrationNeverLowersAnExistingWorkFactor() {
        val minimum = KdfParams(131072, 3)
        val result = KdfCalibrator(crypto).calibrate(minimum, 500)
        assertTrue(result.memoryKiB >= minimum.memoryKiB)
        assertTrue(result.iterations >= minimum.iterations)
        assertTrue(result.memoryKiB <= 262144 && result.iterations <= 20)
    }

    @Test fun setupRandomFailureWipesPartiallyAcquiredKeysAndPasswords() {
        val allocated = mutableListOf<ByteArray>()
        val failing = object : Crypto by crypto {
            override fun random(size: Int): ByteArray {
                if (allocated.isNotEmpty()) throw IllegalStateException("Random provider failed")
                return crypto.random(size).also(allocated::add)
            }
        }
        val password = real()
        val other = panic()
        val root = Files.createTempDirectory("vault-random-error").resolve("vault")
        assertThrows(IllegalStateException::class.java) { VaultManager(failing, TestStorage(crypto)).create(root, password, other, KdfParams(65536, 1)) }
        assertTrue(password.all { it == '\u0000' } && other.all { it == '\u0000' })
        assertTrue(allocated.single().all { it == 0.toByte() })
        assertFalse(Files.exists(root))
    }

    @Test fun calibrationRandomFailureWipesAlreadyAcquiredSecret() {
        val secret = ByteArray(32) { 42 }
        var calls = 0
        val failing = object : Crypto by crypto {
            override fun random(size: Int): ByteArray {
                if (++calls == 1) return secret
                throw IllegalStateException("Random provider failed")
            }
        }
        assertThrows(IllegalStateException::class.java) { KdfCalibrator(failing).calibrate() }
        assertTrue(secret.all { it == 0.toByte() })
    }
}

/** Byte-for-byte snapshot of every regular file under [root], keyed by its path relative to [root]. */
internal fun snapshotTree(root: Path): Map<Path, List<Byte>> {
    val result = LinkedHashMap<Path, List<Byte>>()
    Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.forEach { result[root.relativize(it)] = Files.readAllBytes(it).toList() }
    }
    return result
}

internal class TestStorage(private val crypto: Crypto) : VaultStorage {
    val opened = mutableListOf<VaultSlot>()
    override fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys) {
        Files.write(directory.resolve(if (slot == VaultSlot.REAL) "real.db" else "decoy.db"),
            crypto.seal(keys.dbKey, ByteArray(4096) { slot.ordinal.toByte() }, byteArrayOf()))
    }
    override fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable {
        check(crypto.open(keys.dbKey, Files.readAllBytes(directory.resolve(if (slot == VaultSlot.REAL) "real.db" else "decoy.db")), byteArrayOf()) != null)
        opened += slot
        return AutoCloseable { }
    }
    override fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys) = Unit
    override fun beforeExport(directory: Path) = Unit
    // This fake never tracks media allocation (alignAllocations above is already a no-op for the
    // same reason), so there is nothing to top up. AndroidVaultStorageTest exercises the real
    // implementation against real media directories.
    override fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot) = Unit
}
