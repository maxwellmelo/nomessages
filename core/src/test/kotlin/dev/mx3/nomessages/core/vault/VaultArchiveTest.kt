package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.NativeCrypto
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class VaultArchiveTest {
    private val crypto = NativeCrypto()
    private fun real() = "Q7vfN2rT8bLp4WzK6sHx".toCharArray()
    private fun panic() = "M9kP3vX7rB2nQ5sT8wLc".toCharArray()
    private val storage = TestStorage(crypto)
    private val manager = VaultManager(crypto, storage)
    private val archive = VaultArchive(crypto, storage)
    private fun fixture(): Pair<java.nio.file.Path, ByteArray> {
        val root = Files.createTempDirectory("archive").resolve("vault")
        manager.create(root, real(), panic(), KdfParams(65536, 1))
        val output = ByteArrayOutputStream()
        archive.export(root, real(), output)
        return root to output.toByteArray()
    }

    @Test fun archiveRoundtripPreservesBothIndependentKeys() {
        val (source, encoded) = fixture()
        val target = source.resolveSibling("imported")
        archive.import(encoded.inputStream(), target, panic())
        for (password in listOf(real(), panic())) {
            val copy = password.copyOf()
            manager.unlock(source, password).use { a -> manager.unlock(target, copy).use { b ->
                assertEquals(a.slot, b.slot); assertArrayEquals(a.keys.dbKey, b.keys.dbKey)
            } }
        }
    }

    @Test fun rejectsWrongPasswordTruncationExtraDataAndModifiedCiphertextWithoutPublishing() {
        val (source, encoded) = fixture()
        val changed = rewrite(encoded) { name, bytes -> if (name == "real.db") bytes.also { it[100] = (it[100].toInt() xor 1).toByte() } else bytes }
        for ((index, candidate) in listOf(encoded.copyOf(encoded.size - 1), encoded + 0, changed, encoded).withIndex()) {
            val target = source.resolveSibling("bad-$index")
            assertThrows(Exception::class.java) { archive.import(candidate.inputStream(), target, if (index == 3) "wrong".toCharArray() else real()) }
            assertFalse(Files.exists(target))
        }
    }

    @Test fun rejectsTraversalAndSymlinkEntries() {
        val parent = Files.createTempDirectory("malicious-zip")
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped")); zip.write(byteArrayOf(1)); zip.closeEntry()
        } }.toByteArray()
        assertThrows(Exception::class.java) { archive.import(bytes.inputStream(), parent.resolve("vault"), real()) }
        assertFalse(Files.exists(parent.resolve("escaped")))
        val (_, encoded) = fixture()
        val symlink = encoded.copyOf()
        val volumeLabel = encoded.copyOf()
        for (i in 0 until symlink.size - 46) {
            if (symlink[i] == 0x50.toByte() && symlink[i+1] == 0x4b.toByte() && symlink[i+2] == 1.toByte() && symlink[i+3] == 2.toByte()) {
                symlink[i+5] = 3 // Unix creator
                symlink[i+40] = 0xff.toByte(); symlink[i+41] = 0xa1.toByte() // S_IFLNK
                volumeLabel[i+38] = 8 // DOS volume-label entry is not a regular file
                break
            }
        }
        assertThrows(Exception::class.java) { archive.import(symlink.inputStream(), parent.resolve("vault"), real()) }
        assertThrows(Exception::class.java) { archive.import(volumeLabel.inputStream(), parent.resolve("vault"), real()) }
    }

    @Test fun sessionExportClosesAndWipesSession() {
        val (source, _) = fixture()
        val session = manager.unlock(source, real())
        val borrowed = session.keys.dbKey
        val output = ByteArrayOutputStream()
        archive.export(source, session, output)
        assertTrue(session.isClosed)
        assertTrue(borrowed.all { it == 0.toByte() })
        archive.import(output.toByteArray().inputStream(), source.resolveSibling("copy"), real())
    }

    @Test fun rejectsDuplicateNamesAndBoundedArchiveLimits() {
        val (source, _) = fixture()
        Files.write(source.resolve("real.files/a"), crypto.random(80))
        Files.write(source.resolve("real.files/b"), crypto.random(80))
        val output = ByteArrayOutputStream()
        archive.export(source, real(), output)
        val encoded = output.toByteArray()
        val duplicate = encoded.copyOf()
        val marker = "real.files/b".toByteArray()
        for (i in 0..duplicate.size - marker.size) {
            if (marker.indices.all { j -> duplicate[i+j] == marker[j] }) duplicate[i + marker.size - 1] = 'a'.code.toByte()
        }
        assertThrows(SecurityException::class.java) { archive.import(duplicate.inputStream(), source.resolveSibling("duplicate"), real()) }
        val bounded = VaultArchive(crypto, storage, ArchiveLimits(1024, 1024, 1024, 100))
        assertThrows(SecurityException::class.java) { bounded.import(encoded.inputStream(), source.resolveSibling("oversize"), real()) }
        assertFalse(Files.exists(source.resolveSibling("duplicate")))
        assertFalse(Files.exists(source.resolveSibling("oversize")))
    }

    private fun rewrite(encoded: ByteArray, change: (String, ByteArray) -> ByteArray): ByteArray {
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            ZipInputStream(encoded.inputStream()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    zip.putNextEntry(ZipEntry(entry.name)); zip.write(change(entry.name, input.readBytes())); zip.closeEntry()
                    entry = input.nextEntry
                }
            }
        } }.toByteArray()
    }
}
