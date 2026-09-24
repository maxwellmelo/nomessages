package dev.mx3.nomessages.core.vault

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class StrictZipTest {
    private fun validZip(): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        for (name in listOf("header.bin", "real.db", "decoy.db", "manifest.bin")) {
            zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(50)); zip.closeEntry()
        }
    } }.toByteArray()

    private fun inspect(bytes: ByteArray): List<StrictZip.Entry> {
        val path = Files.createTempFile("strict-zip-test", ".zip")
        try { Files.write(path, bytes); return StrictZip.validate(path, ArchiveLimits()) }
        finally { Files.deleteIfExists(path) }
    }

    @Test fun recognizesEveryEntryAndItsExactLengthInCanonicalZip() {
        val entries = inspect(validZip())
        assertEquals(listOf("header.bin", "real.db", "decoy.db", "manifest.bin"), entries.map { it.name })
        assertTrue(entries.all { it.size == 50L })
    }

    @Test fun requiresCompleteContainerWithNoPrefixSuffixOrTruncation() {
        val valid = validZip()
        for (bad in listOf(valid + 0, byteArrayOf(0) + valid, valid.copyOf(valid.size - 1), valid.copyOf(valid.size - 22))) {
            assertThrows(Exception::class.java) { inspect(bad) }
        }
    }

    @Test fun rejectsOverlappingEntriesEncryptedFlagsAndLocalCentralMismatch() {
        val valid = validZip()
        val central = (0 until valid.size - 46).first { i -> valid[i] == 0x50.toByte() && valid[i+1] == 0x4b.toByte() && valid[i+2] == 1.toByte() && valid[i+3] == 2.toByte() }
        val offset = valid.copyOf().also { it[central + 42] = 1 }
        val encrypted = valid.copyOf().also { it[central + 8] = (it[central + 8].toInt() or 1).toByte() }
        val mismatch = valid.copyOf().also { it[30] = 'x'.code.toByte() }
        for (bad in listOf(offset, encrypted, mismatch)) assertThrows(Exception::class.java) { inspect(bad) }
    }
}
