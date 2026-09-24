package dev.mx3.nomessages.core.files

import dev.mx3.nomessages.core.crypto.Crypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class FileInfo(val fileId: ByteArray, val epoch: Long, val plaintextLength: Long)

/** 64 KiB independently authenticated chunks plus a mandatory authenticated terminal record. */
class EncryptedFiles(private val crypto: Crypto) {
    fun newFileKey(): ByteArray = crypto.random(32)

    fun encrypt(source: InputStream, destination: OutputStream, key: ByteArray,
                fileId: ByteArray, epoch: Long, plaintextLength: Long): FileInfo {
        require(key.size == 32 && fileId.size == 16 && epoch >= 0)
        require(plaintextLength in 0..MAX_LENGTH)
        ensureActive()
        val header = ByteBuffer.allocate(HEADER_SIZE).putInt(MAGIC).putShort(1).put(fileId).putLong(epoch).putLong(plaintextLength).array()
        val output = DataOutputStream(destination)
        output.write(header)
        val input = DataInputStream(source)
        var remaining = plaintextLength
        var index = 0L
        while (remaining > 0) {
            ensureActive()
            val length = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            val plain = ByteArray(length)
            try {
                input.readFully(plain)
                val sealed = crypto.seal(key, plain, aad(header, index, length, false))
                check(sealed.size == length + 40)
                output.writeInt(sealed.size)
                output.write(sealed)
            } finally { crypto.wipe(plain) }
            remaining -= length
            index++
        }
        if (input.read() != -1) throw SecurityException("File length mismatch")
        val terminal = crypto.seal(key, byteArrayOf(), aad(header, index, 0, true))
        check(terminal.size == 40)
        output.writeInt(terminal.size)
        output.write(terminal)
        output.flush()
        return FileInfo(fileId.copyOf(), epoch, plaintextLength)
    }

    /** Output is an authenticated prefix until this method returns. Discard it if any error occurs. */
    fun decrypt(source: InputStream, destination: OutputStream, key: ByteArray,
                expectedFileId: ByteArray, expectedEpoch: Long): FileInfo {
        require(key.size == 32 && expectedFileId.size == 16 && expectedEpoch >= 0)
        ensureActive()
        val input = DataInputStream(source)
        val header = ByteArray(HEADER_SIZE)
        try {
            input.readFully(header)
            val parsed = ByteBuffer.wrap(header)
            if (parsed.int != MAGIC || parsed.short.toInt() != 1) throw SecurityException("Invalid encrypted file")
            val id = ByteArray(16).also(parsed::get)
            val epoch = parsed.long
            val length = parsed.long
            if (!MessageDigest.isEqual(id, expectedFileId) || epoch != expectedEpoch || length !in 0..MAX_LENGTH) {
                throw SecurityException("Invalid encrypted file")
            }
            var remaining = length
            var index = 0L
            while (remaining > 0) {
                val size = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                val plain = readChunk(input, key, header, index, size, false)
                try { destination.write(plain) } finally { crypto.wipe(plain) }
                remaining -= size
                index++
            }
            val terminal = readChunk(input, key, header, index, 0, true)
            crypto.wipe(terminal)
            if (input.read() != -1) throw SecurityException("Extra encrypted file data")
            destination.flush()
            return FileInfo(id, epoch, length)
        } catch (error: EOFException) { throw SecurityException("Truncated encrypted file", error) }
    }

    private fun readChunk(input: DataInputStream, key: ByteArray, header: ByteArray, index: Long, length: Int, terminal: Boolean): ByteArray {
        ensureActive()
        if (input.readInt() != length + 40) throw SecurityException("Invalid encrypted chunk size")
        val sealed = ByteArray(length + 40)
        input.readFully(sealed)
        val plain = crypto.open(key, sealed, aad(header, index, length, terminal)) ?: throw SecurityException("Invalid encrypted chunk")
        if (plain.size != length) { crypto.wipe(plain); throw SecurityException("Invalid encrypted chunk") }
        return plain
    }

    private fun aad(header: ByteArray, index: Long, length: Int, terminal: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + 8 + 4 + 1).put(header).putLong(index).putInt(length).put(if (terminal) 1.toByte() else 0.toByte()).array()

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("File operation interrupted")
    }

    companion object {
        const val CHUNK_SIZE = 65536
        const val MAX_LENGTH = 4L * 1024 * 1024 * 1024
        private const val HEADER_SIZE = 38
        private const val MAGIC = 0x57464631
    }
}
