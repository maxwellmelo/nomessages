package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.NativeCrypto
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run with the Linux host test/native/vault_cleanup_faults.c preload fixture. */
class VaultCleanupFaultTest {
    private fun real() = "Q7vfN2rT8bLp4WzK6sHx".toCharArray()
    private fun panic() = "M9kP3vX7rB2nQ5sT8wLc".toCharArray()

    @Test fun committedResetSucceedsWhenRetiredBackupCannotBeDeleted() {
        assumeTrue("Filesystem fault-injection profile required", System.getenv("NOMESSAGES_TEST_DENY_CLEANUP") == "1")
        val crypto = NativeCrypto()
        val manager = VaultManager(crypto, TestStorage(crypto))
        val directory = Files.createTempDirectory("vault-cleanup-reset").resolve("vault")
        manager.create(directory, real(), panic(), KdfParams(65536, 1))
        val next = "A6mR9xH2kV5zL8pQ3nTc".toCharArray()
        manager.resetPanicPassword(directory, manager.unlock(directory, real()), next.copyOf())
        assertTrue("Actual deletion denial must leave the encrypted backup", Files.isDirectory(resetBackup(directory)))
        manager.unlock(directory, next).use { assertEquals(VaultSlot.DECOY, it.slot) }
        manager.unlock(directory, real()).close()
    }

    @Test fun committedImportSucceedsWhenTemporaryZipCannotBeDeleted() {
        assumeTrue("Filesystem fault-injection profile required", System.getenv("NOMESSAGES_TEST_DENY_CLEANUP") == "1")
        val crypto = NativeCrypto()
        val storage = TestStorage(crypto)
        val manager = VaultManager(crypto, storage)
        val parent = Files.createTempDirectory("vault-cleanup-import")
        val directory = parent.resolve("vault")
        manager.create(directory, real(), panic(), KdfParams(65536, 1))
        val archive = VaultArchive(crypto, storage)
        val output = ByteArrayOutputStream()
        archive.export(directory, real(), output)
        val target = parent.resolve("imported")
        archive.import(output.toByteArray().inputStream(), target, real())
        manager.unlock(target, real()).close()
        Files.list(parent).use { paths ->
            assertTrue("Actual deletion denial must leave the encrypted ZIP", paths.anyMatch {
                it.fileName.toString().startsWith(".vault-import-") && it.fileName.toString().endsWith(".zip")
            })
        }
    }
}
