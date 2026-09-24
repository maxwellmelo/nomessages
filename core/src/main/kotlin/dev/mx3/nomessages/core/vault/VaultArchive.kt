package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.Crypto
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveLimits(
    val maxArchiveBytes: Long = 1024L * 1024 * 1024,
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxEntries: Int = 10000,
) {
    init {
        require(maxArchiveBytes in 1024..0xfffffffeL && maxTotalBytes in 1024..0xfffffffeL)
        require(maxEntryBytes in 1..maxTotalBytes && maxEntries in 4..60000)
    }
}

class VaultArchive(private val crypto: Crypto, private val storage: VaultStorage,
                   private val limits: ArchiveLimits = ArchiveLimits()) {
    fun export(directory: Path, password: CharArray, destination: OutputStream) {
        var normalized: ByteArray? = null
        var header: UnlockedHeader? = null
        try {
            normalized = normalizePassword(password)
            password.fill('\u0000')
            header = VaultHeader.unlock(crypto, readHeader(directory), normalized)
            crypto.wipe(normalized); normalized = null
            storage.beforeExport(directory)
            exportAuthenticated(directory, header.authKey, destination)
        } finally { password.fill('\u0000'); normalized?.let(crypto::wipe); header?.close() }
    }

    /** Consumes the open session; the caller must transition UI to locked state. */
    fun export(directory: Path, session: VaultSession, destination: OutputStream) {
        check(!session.isClosed) { "Session is closed" }
        val auth = session.authKey.copyOf()
        try {
            val header = readHeader(directory)
            if (!MessageDigest.isEqual(crypto.mac(auth, header.copyOfRange(0, header.size - 32)), header.copyOfRange(header.size - 32, header.size))) {
                throw SecurityException("Vault changed during session")
            }
            session.close()
            storage.beforeExport(directory)
            exportAuthenticated(directory, auth, destination)
        } finally { crypto.wipe(auth); session.close() }
    }

    private fun exportAuthenticated(directory: Path, authKey: ByteArray, destination: OutputStream) {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) { "Invalid vault directory" }
        requireEqualDatabases(directory)
        val files = ArrayList<Path>()
        Files.walk(directory).use { paths -> paths.forEach { path ->
            if (path != directory) {
                require(!Files.isSymbolicLink(path)) { "Symlink in vault" }
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    check(files.size < limits.maxEntries - 1) { "Too many vault files" }
                    val name = directory.relativize(path).toString().replace(File.separatorChar, '/')
                    require(validArchivePath(name) && name != MANIFEST) { "Unexpected vault file" }
                    files.add(path)
                } else require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Nonregular vault file" }
            }
        } }
        files.sortBy { directory.relativize(it).toString() }
        var total = 0L
        val records = files.map { path ->
            val size = Files.size(path)
            require(size in 0..limits.maxEntryBytes) { "Vault file exceeds archive limit" }
            total += size
            require(total <= limits.maxTotalBytes) { "Vault exceeds archive limit" }
            Record(directory.relativize(path).toString().replace(File.separatorChar, '/'), size, digest(path))
        }
        val manifest = encodeManifest(records, authKey)
        require(total + manifest.size <= limits.maxTotalBytes) { "Vault exceeds archive limit" }
        val nonClosing = object : FilterOutputStream(destination) { override fun close() { flush() } }
        val bounded = object : FilterOutputStream(nonClosing) {
            var written = 0L
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(length.toLong() <= limits.maxArchiveBytes - written) { "Archive exceeds size limit" }
                out.write(bytes, offset, length); written += length
            }
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        }
        ZipOutputStream(bounded).use { zip ->
            zip.setLevel(0) // ciphertext does not compress; avoid wasting CPU
            for (record in records) {
                zip.putNextEntry(ZipEntry(record.name))
                val actual = Files.newInputStream(directory.resolve(record.name), NOFOLLOW_LINKS).use { input ->
                    copyDigest(input, zip, record.size)
                }
                if (actual.first != record.size || !MessageDigest.isEqual(actual.second, record.hash)) {
                    throw SecurityException("Vault changed during export")
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
        }
    }

    fun import(source: InputStream, directory: Path, password: CharArray) {
        var archive: Path? = null
        var stage: Path? = null
        var header: UnlockedHeader? = null
        var normalized: ByteArray? = null
        try {
            normalized = normalizePassword(password)
            password.fill('\u0000')
            require(!Files.exists(directory, NOFOLLOW_LINKS)) { "Vault destination must not exist" }
            require(!Files.exists(resetBackup(directory.toAbsolutePath()), NOFOLLOW_LINKS)) { "Vault recovery is required" }
            val absolute = directory.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            archive = Files.createTempFile(absolute.parent, ".vault-import-", ".zip")
            Files.newOutputStream(archive).use { copyDigest(source, it, limits.maxArchiveBytes) }
            val zipEntries = StrictZip.validate(archive, limits)
            stage = Files.createTempDirectory(absolute.parent, ".vault-import-")
            val staged = stage
            var manifest: ByteArray? = null
            val actual = mutableListOf<Record>()
            var total = 0L
            ZipFile(archive.toFile()).use { zip ->
                for (metadata in zipEntries) {
                    val entry = zip.getEntry(metadata.name) ?: throw SecurityException("ZIP entry missing")
                    if (entry.size != metadata.size || entry.compressedSize != metadata.compressedSize) throw SecurityException("ZIP metadata mismatch")
                    val max = if (entry.name == MANIFEST) MAX_MANIFEST_BYTES.toLong() else limits.maxEntryBytes
                    if (entry.size !in 0..max || entry.size > limits.maxTotalBytes - total) throw SecurityException("Archive size limit exceeded")
                    total += entry.size
                    if (entry.name == MANIFEST) {
                        val output = ByteArrayOutputStream()
                        val read = zip.getInputStream(entry).use { copyDigest(it, output, entry.size) }
                        if (read.first != entry.size) throw SecurityException("ZIP length mismatch")
                        manifest = output.toByteArray()
                    } else {
                        val target = staged.resolve(entry.name).normalize()
                        if (!target.startsWith(staged)) throw SecurityException("Invalid archive path")
                        Files.createDirectories(target.parent)
                        val read = zip.getInputStream(entry).use { input -> Files.newOutputStream(target, CREATE_NEW, WRITE).use { output -> copyDigest(input, output, entry.size) } }
                        if (read.first != entry.size) throw SecurityException("ZIP length mismatch")
                        actual += Record(entry.name, read.first, read.second)
                    }
                }
            }
            header = VaultHeader.unlock(crypto, readHeader(staged), normalized)
            crypto.wipe(normalized); normalized = null
            val declared = decodeManifest(manifest ?: throw SecurityException("Missing archive manifest"), header.authKey)
            if (actual.size != declared.size) throw SecurityException("Archive manifest mismatch")
            val byName = declared.associateBy { it.name }
            for (record in actual) {
                val expected = byName[record.name] ?: throw SecurityException("Unlisted archive file")
                if (expected.size != record.size || !MessageDigest.isEqual(expected.hash, record.hash)) throw SecurityException("Archive authentication failed")
            }
            requireEqualDatabases(staged)
            Files.createDirectories(staged.resolve("real.files"))
            Files.createDirectories(staged.resolve("decoy.files"))
            // Ensure selected DB accepts its authenticated key before publishing; no other slot is opened.
            storage.open(staged, header.slot, header.keys).use { }
            Files.move(staged, absolute, ATOMIC_MOVE)
            stage = null
        } finally {
            password.fill('\u0000'); normalized?.let(crypto::wipe); header?.close()
            stage?.let(::discardCiphertext); archive?.let(::discardCiphertext)
        }
    }

    private data class Record(val name: String, val size: Long, val hash: ByteArray)

    private fun encodeManifest(records: List<Record>, key: ByteArray): ByteArray {
        val stream = ByteArrayOutputStream()
        DataOutputStream(stream).use { output ->
            output.writeInt(0x57464d31); output.writeInt(records.size)
            for (record in records) {
                require(stream.size() + record.name.length + 2 + 8 + 32 + 32 <= MAX_MANIFEST_BYTES) { "Archive manifest exceeds size limit" }
                output.writeUTF(record.name); output.writeLong(record.size); output.write(record.hash)
            }
        }
        val body = stream.toByteArray()
        require(body.size + 32 <= MAX_MANIFEST_BYTES)
        return body + crypto.mac(key, body)
    }

    private fun decodeManifest(encoded: ByteArray, key: ByteArray): List<Record> {
        if (encoded.size !in 40..MAX_MANIFEST_BYTES) throw SecurityException("Invalid archive manifest")
        val body = encoded.copyOfRange(0, encoded.size - 32)
        if (!MessageDigest.isEqual(crypto.mac(key, body), encoded.copyOfRange(body.size, encoded.size))) throw SecurityException("Archive authentication failed")
        val input = DataInputStream(body.inputStream())
        if (input.readInt() != 0x57464d31) throw SecurityException("Invalid archive manifest")
        val count = input.readInt()
        if (count !in 3 until limits.maxEntries) throw SecurityException("Invalid manifest entry count")
        val names = HashSet<String>()
        val records = List(count) {
            val name = input.readUTF()
            if (!validArchivePath(name) || name == MANIFEST || !names.add(name)) throw SecurityException("Invalid manifest path")
            val size = input.readLong()
            if (size !in 0..limits.maxEntryBytes) throw SecurityException("Invalid manifest file size")
            Record(name, size, ByteArray(32).also(input::readFully))
        }
        if (input.read() != -1 || !names.containsAll(listOf("header.bin", "real.db", "decoy.db"))) throw SecurityException("Invalid archive manifest")
        return records
    }

    private fun digest(path: Path): ByteArray = Files.newInputStream(path, NOFOLLOW_LINKS).use {
        copyDigest(it, object : OutputStream() { override fun write(value: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) = Unit }, limits.maxEntryBytes).second
    }

    private fun copyDigest(input: InputStream, output: OutputStream, maximum: Long): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive operation interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) { // tolerate streams returning zero without spinning forever
                val one = input.read()
                if (one < 0) break
                if (total == maximum) throw SecurityException("Archive size limit exceeded")
                output.write(one); digest.update(one.toByte()); total++
            } else {
                if (count > maximum - total) throw SecurityException("Archive size limit exceeded")
                output.write(buffer, 0, count); digest.update(buffer, 0, count); total += count
            }
        }
        return total to digest.digest()
    }

    companion object {
        internal const val MANIFEST = "manifest.bin"
        internal const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        internal fun validArchivePath(name: String): Boolean {
            if (name in setOf("header.bin", "real.db", "decoy.db", MANIFEST)) return true
            if (name.length > 512 || !(name.startsWith("real.files/") || name.startsWith("decoy.files/"))) return false
            val parts = name.split('/')
            return parts.size in 2..8 && parts.drop(1).all {
                it != "." && it != ".." && it.length in 1..128 && it[0].isLetterOrDigit() && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-" }
            }
        }
    }
}
