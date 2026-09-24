package dev.mx3.nomessages.core.vault

import java.io.RandomAccessFile
import java.nio.file.Path

/** Validate the complete classic ZIP container, including Unix file type metadata Java ZipFile hides. */
internal object StrictZip {
    data class Entry(val name: String, val size: Long, val compressedSize: Long, val localOffset: Long,
                     val flags: Int, val method: Int, val crc: Long)

    fun validate(path: Path, limits: ArchiveLimits): List<Entry> = RandomAccessFile(path.toFile(), "r").use { file ->
        fun invalid(): Nothing = throw SecurityException("Invalid vault ZIP")
        fun u16(): Int = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun name(length: Int): String {
            if (length !in 1..512) invalid()
            val bytes = ByteArray(length); file.readFully(bytes)
            if (bytes.any { it.toInt() !in 32..126 }) invalid()
            return bytes.toString(Charsets.US_ASCII)
        }
        fun extras(length: Int) {
            val end = file.filePointer + length
            while (file.filePointer < end) {
                if (end - file.filePointer < 4) invalid()
                val id = u16(); val size = u16()
                // Disallow ZIP64 and Unicode filename aliases; paths have one canonical ASCII spelling.
                if (id == 1 || id == 0x7075 || size > end - file.filePointer) invalid()
                file.seek(file.filePointer + size)
            }
        }
        val length = file.length()
        if (length !in 22..limits.maxArchiveBytes) invalid()
        // No comments, ZIP64, prepended executables, multidisk archives or trailing bytes.
        file.seek(length - 22)
        if (u32() != 0x06054b50L || u16() != 0 || u16() != 0) invalid()
        val diskCount = u16(); val count = u16()
        val centralSize = u32(); val centralOffset = u32(); val comment = u16()
        if (diskCount != count || count !in 4..limits.maxEntries || count == 65535 || comment != 0 || centralOffset + centralSize != length - 22) invalid()
        val entries = ArrayList<Entry>(count)
        val names = HashSet<String>()
        file.seek(centralOffset)
        var total = 0L
        repeat(count) {
            if (file.filePointer + 46 > length - 22 || u32() != 0x02014b50L) invalid()
            u16() // creator version
            if (u16() > 20) invalid()
            val flags = u16(); val method = u16()
            if (flags and 0xf7f7 != 0 || method !in listOf(0, 8)) invalid()
            u16(); u16()
            val crc = u32(); val compressed = u32(); val size = u32()
            val nameLength = u16(); val extraLength = u16(); val commentLength = u16()
            if (u16() != 0) invalid()
            u16()
            val attributes = u32(); val local = u32()
            val mode = ((attributes shr 16) and 0xf000).toInt()
            if (mode != 0 && mode != 0x8000 || attributes and 0x18 != 0L) invalid()
            val entryName = name(nameLength)
            if (!VaultArchive.validArchivePath(entryName) || !names.add(entryName)) invalid()
            extras(extraLength)
            if (commentLength != 0) invalid()
            val max = if (entryName == VaultArchive.MANIFEST) VaultArchive.MAX_MANIFEST_BYTES.toLong() else limits.maxEntryBytes
            if (size > max || compressed > limits.maxArchiveBytes || size > limits.maxTotalBytes - total) invalid()
            total += size
            entries += Entry(entryName, size, compressed, local, flags, method, crc)
        }
        if (file.filePointer != centralOffset + centralSize || !names.containsAll(listOf("header.bin", "real.db", "decoy.db", VaultArchive.MANIFEST))) invalid()
        var nextOffset = 0L
        for (entry in entries.sortedBy { it.localOffset }) {
            if (entry.localOffset != nextOffset || entry.localOffset + 30 > centralOffset) invalid()
            file.seek(entry.localOffset)
            if (u32() != 0x04034b50L || u16() > 20 || u16() != entry.flags || u16() != entry.method) invalid()
            u16(); u16()
            val crc = u32(); val compressed = u32(); val size = u32()
            val nameLength = u16(); val extraLength = u16()
            if (name(nameLength) != entry.name) invalid()
            extras(extraLength)
            val contentEnd = file.filePointer + entry.compressedSize
            if (contentEnd > centralOffset) invalid()
            file.seek(contentEnd)
            if (entry.flags and 8 != 0) {
                if ((crc != 0L && crc != entry.crc) || (compressed != 0L && compressed != entry.compressedSize) || (size != 0L && size != entry.size)) invalid()
                val first = u32()
                val descriptorCrc = if (first == 0x08074b50L) u32() else first
                if (descriptorCrc != entry.crc || u32() != entry.compressedSize || u32() != entry.size) invalid()
            } else if (crc != entry.crc || compressed != entry.compressedSize || size != entry.size) invalid()
            nextOffset = file.filePointer
        }
        if (nextOffset != centralOffset) invalid()
        entries
    }
}
