package dev.mx3.nomessages.ui

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import kotlin.math.min

/**
 * Shared anonymous-RAM-file (`memfd`) helpers: create one, fill it, rewind it, read it back, and
 * wipe-then-close it. Nothing built on top of this ever creates a filesystem path for decrypted
 * bytes - the descriptor only ever exists as an in-memory Linux `memfd`.
 *
 * Extracted from `MemoryPdfDocument`, which was the original (and until now, only) user of this
 * exact create/write/rewind/wipe-close sequence. [MemoryPdfDocument] and the AAC-in-memory audio
 * encoder (`MemoryAudioEncoder`) both build on this.
 */
internal object MemoryFd {
    private const val WIPE_CHUNK_BYTES = 64 * 1024
    private val ZERO_BUFFER = ByteArray(WIPE_CHUNK_BYTES)

    /** Creates an empty anonymous memfd, positioned at offset 0, ready to be written to. */
    @Suppress("DEPRECATION")
    fun createEmpty(name: String = "nomessages-mem"): FileDescriptor = Os.memfd_create(name, OsConstants.MFD_CLOEXEC)

    /** Creates a memfd pre-filled with [bytes] and rewound to offset 0, ready to be read from. */
    fun create(bytes: ByteArray, name: String = "nomessages-mem"): FileDescriptor {
        val file = createEmpty(name)
        try {
            writeAll(file, bytes)
            rewind(file)
            return file
        } catch (failure: Throwable) {
            wipeAndClose(file, bytes.size)
            throw failure
        }
    }

    fun writeAll(file: FileDescriptor, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = Os.write(file, bytes, offset, bytes.size - offset)
            check(written > 0) { "Could not populate memory file" }
            offset += written
        }
    }

    fun rewind(file: FileDescriptor) {
        Os.lseek(file, 0L, OsConstants.SEEK_SET)
    }

    /** Current size of the file, as reported by the kernel (e.g. after a `MediaMuxer` wrote to it). */
    fun currentSize(file: FileDescriptor): Long = Os.fstat(file).st_size

    /** Rewinds and reads the whole file into a new [ByteArray]. Does not close [file]. */
    fun readAll(file: FileDescriptor): ByteArray {
        val size = currentSize(file)
        require(size in 0..Int.MAX_VALUE) { "Memory file is too large to read back" }
        rewind(file)
        val out = ByteArray(size.toInt())
        var offset = 0
        while (offset < out.size) {
            val read = Os.read(file, out, offset, out.size - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == out.size) out else out.copyOf(offset)
    }

    /**
     * Overwrites the file with zeros, syncs, truncates it to zero length, then closes it. Best
     * effort: a failure while wiping still falls through to closing the descriptor. [byteCountHint]
     * is used only if the current on-disk (in-memory) size cannot be determined.
     */
    fun wipeAndClose(file: FileDescriptor, byteCountHint: Int) {
        try {
            val size = runCatching { currentSize(file) }.getOrDefault(byteCountHint.toLong())
                .coerceAtLeast(byteCountHint.toLong())
            Os.lseek(file, 0L, OsConstants.SEEK_SET)
            var remaining = size
            while (remaining > 0) {
                val requested = min(ZERO_BUFFER.size.toLong(), remaining).toInt()
                val written = Os.write(file, ZERO_BUFFER, 0, requested)
                if (written <= 0) break
                remaining -= written
            }
            runCatching { Os.fsync(file) }
            runCatching { Os.ftruncate(file, 0L) }
        } catch (_: Exception) {
            // Best effort - the caller is tearing this descriptor down regardless.
        } finally {
            runCatching { Os.close(file) }
        }
    }
}
