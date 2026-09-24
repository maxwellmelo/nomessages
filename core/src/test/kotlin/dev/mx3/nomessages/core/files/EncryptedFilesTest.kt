package dev.mx3.nomessages.core.files

import dev.mx3.nomessages.core.crypto.NativeCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class EncryptedFilesTest {
    private val crypto = NativeCrypto()
    private val codec = EncryptedFiles(crypto)
    private val key = crypto.random(32)
    private val id = crypto.random(16)
    private fun encode(plain: ByteArray): ByteArray = ByteArrayOutputStream().also {
        codec.encrypt(ByteArrayInputStream(plain), it, key, id, 7, plain.size.toLong())
    }.toByteArray()
    private fun decode(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        codec.decrypt(ByteArrayInputStream(bytes), it, key, id, 7)
    }.toByteArray()

    @Test fun roundtripEmptyPartialAndMultipleChunks() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 150000)) {
            val plain = crypto.random(size)
            assertArrayEquals(plain, decode(encode(plain)))
        }
    }

    @Test fun rejectsTamperTruncationMissingTerminalAndTrailingData() {
        val bytes = encode(crypto.random(70000))
        val changed = bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        for (bad in listOf(changed, bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size - 44), bytes + 1.toByte())) {
            assertThrows(Exception::class.java) { decode(bad) }
        }
    }

    @Test fun rejectsReorderedFullChunksAndCrossFileOrEpoch() {
        val bytes = encode(crypto.random(131072))
        val headerSize = 38
        val recordSize = 4 + 65536 + 40
        val swapped = bytes.copyOf()
        bytes.copyInto(swapped, headerSize, headerSize + recordSize, headerSize + 2 * recordSize)
        bytes.copyInto(swapped, headerSize + recordSize, headerSize, headerSize + recordSize)
        assertThrows(SecurityException::class.java) { decode(swapped) }
        assertThrows(SecurityException::class.java) {
            codec.decrypt(bytes.inputStream(), ByteArrayOutputStream(), key, crypto.random(16), 7)
        }
        assertThrows(SecurityException::class.java) {
            codec.decrypt(bytes.inputStream(), ByteArrayOutputStream(), key, id, 8)
        }
    }

    @Test fun declaredLengthMustMatchInput() {
        assertThrows(Exception::class.java) { codec.encrypt(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), key, id, 7, 0) }
        assertThrows(Exception::class.java) { codec.encrypt(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), key, id, 7, 2) }
    }

    @Test fun interruptedFileOperationStopsBeforeWriting() {
        val output = ByteArrayOutputStream()
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.io.InterruptedIOException::class.java) {
                codec.encrypt(byteArrayOf(1).inputStream(), output, key, id, 7, 1)
            }
            assertEquals(0, output.size())
        } finally { Thread.interrupted() }
    }
}
