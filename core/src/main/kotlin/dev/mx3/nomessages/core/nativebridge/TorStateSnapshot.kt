package dev.mx3.nomessages.core.nativebridge

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Guard metadata snapshots are secret: persist only in the encrypted vault, then wipe the bytes. */
object TorStateSnapshot {
    const val META_KEY = "tor_guard_state"
    const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
    private val magic = byteArrayOf(87, 70, 84, 79, 82, 48, 48, 49) // WFTOR001
    private val names = listOf("state/guards.json", "state/vanguards.json", "state/circuit_timeouts.json")

    /** Two identical reads provide a best-effort live checkpoint. After process death, capture is quiescent.
     * Null means no usable checkpoint; callers MUST preserve the previously saved vault value. */
    fun capture(stateDirectory: File): ByteArray? {
        val root = stateDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return null
        directory(root)
        repeat(3) {
            var first: ByteArray? = null
            var second: ByteArray? = null
            try {
                first = captureOnce(root) ?: return null
                second = captureOnce(root) ?: return null
                if (MessageDigest.isEqual(first, second)) return second.also { second = null }
            } catch (_: Changed) {
                // Atomic Arti replacement raced this live checkpoint. Retry without replacing vault state.
            } finally {
                first?.fill(0)
                second?.fill(0)
            }
        }
        return null
    }

    /** Parse before writing; destination must be empty/nonexistent, and Tor must not be running. */
    fun restore(stateDirectory: File, snapshot: ByteArray?) {
        val entries = if (snapshot == null) emptyList() else decode(snapshot)
        try {
            val root = stateDirectory.toPath().toAbsolutePath().normalize()
            if (Files.exists(root, NOFOLLOW_LINKS)) {
                directory(root)
                Files.newDirectoryStream(root).use { require(!it.iterator().hasNext()) { "Tor state directory is not empty" } }
            } else {
                directory(requireNotNull(root.parent))
                Files.createDirectory(root)
            }
            privateMode(root.toFile(), true)
            if (entries.isNotEmpty()) {
                val state = root.resolve("state")
                Files.createDirectory(state)
                privateMode(state.toFile(), true)
                for ((name, bytes) in entries) {
                    val path = root.resolve(name)
                    Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
                        privateMode(path.toFile(), false)
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                }
            }
        } finally { entries.forEach { it.second.fill(0) } }
    }

    /** Remove every session file, including hss and lock files. Never traverse a symbolic link. */
    fun clear(stateDirectory: File) {
        val root = stateDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        directory(root)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private class Changed : IOException("Tor state changed during capture")
    private fun attrs(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun directory(path: Path): BasicFileAttributes = attrs(path).also {
        require(it.isDirectory && !it.isSymbolicLink && !it.isOther) { "Unsafe Tor state directory" }
    }
    private fun same(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()

    private fun captureOnce(root: Path): ByteArray? {
        val rootBefore = directory(root)
        val state = root.resolve("state")
        if (!Files.exists(state, NOFOLLOW_LINKS)) return null
        val stateBefore = directory(state)
        val entries = mutableListOf<Pair<String, ByteArray>>()
        var total = magic.size + 4 + 32
        try {
            for (name in names) {
                val path = root.resolve(name)
                if (!Files.exists(path, NOFOLLOW_LINKS)) continue
                val before = attrs(path)
                require(before.isRegularFile && !before.isSymbolicLink && !before.isOther) { "Unsafe Tor state file" }
                require(before.size() in 1..MAX_SNAPSHOT_BYTES.toLong()) { "Invalid Tor state size" }
                val size = before.size().toInt()
                total += 4 + name.length + 4 + size
                require(total <= MAX_SNAPSHOT_BYTES) { "Tor state exceeds snapshot limit" }
                val bytes = ByteArray(size)
                try {
                    Files.newByteChannel(path, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw Changed()
                        if (channel.read(ByteBuffer.allocate(1)) != -1) throw Changed()
                    }
                    if (!same(before, attrs(path))) throw Changed()
                    val hasGuards = JsonCheck(bytes).validate()
                    if (name == names[0] && !hasGuards) return null
                    entries += name to bytes
                } finally { if (entries.lastOrNull()?.second !== bytes) bytes.fill(0) }
            }
            if (entries.none { it.first == names[0] }) return null
            if (!same(rootBefore, directory(root)) || !same(stateBefore, directory(state))) throw Changed()
            val result = ByteArray(total)
            val out = ByteBuffer.wrap(result)
            out.put(magic).putInt(entries.size)
            for ((name, bytes) in entries) {
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                out.putInt(nameBytes.size).put(nameBytes).putInt(bytes.size).put(bytes)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(result, 0, out.position())
            out.put(digest.digest())
            return result
        } finally { entries.forEach { it.second.fill(0) } }
    }

    private fun decode(snapshot: ByteArray): List<Pair<String, ByteArray>> {
        require(snapshot.size in 45..MAX_SNAPSHOT_BYTES) { "Invalid Tor snapshot size" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(snapshot, 0, snapshot.size - 32)
        require(MessageDigest.isEqual(digest.digest(), snapshot.copyOfRange(snapshot.size - 32, snapshot.size))) { "Invalid Tor snapshot digest" }
        val input = ByteBuffer.wrap(snapshot, 0, snapshot.size - 32)
        require(magic.all { input.get() == it }) { "Unsupported Tor snapshot" }
        val count = input.int
        require(count in 1..names.size) { "Invalid Tor snapshot entries" }
        val entries = mutableListOf<Pair<String, ByteArray>>()
        try {
            repeat(count) {
                require(input.remaining() >= 4)
                val length = input.int
                require(length in 1..64 && input.remaining() >= length + 4)
                val name = ByteArray(length).also { input.get(it) }.toString(Charsets.US_ASCII)
                require(name in names && entries.none { it.first == name }) { "Forbidden Tor snapshot path" }
                val size = input.int
                require(size in 1..MAX_SNAPSHOT_BYTES && input.remaining() >= size)
                val bytes = ByteArray(size).also { input.get(it) }
                entries += name to bytes
                val hasGuards = JsonCheck(bytes).validate()
                require(name != names[0] || hasGuards) { "Empty guard history" }
            }
            require(entries.any { it.first == names[0] } && !input.hasRemaining()) { "Incomplete Tor snapshot" }
            return entries
        } catch (error: Exception) {
            entries.forEach { it.second.fill(0) }
            throw error
        }
    }

    private fun privateMode(file: File, directory: Boolean) {
        require(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false))
        require(file.setReadable(true, true) && file.setWritable(true, true))
        if (directory) require(file.setExecutable(true, true))
    }

    /** Bounded JSON syntax check; guards must contain at least one sampled object.
     * Arti still performs the authoritative versioned schema validation on load. */
    private class JsonCheck(private val bytes: ByteArray) {
        private var at = 0
        private var nodes = 0
        private var sampled = false
        private var defaultSet = false
        fun validate(): Boolean {
            whitespace(); require(peek() == '{'.code) { "Invalid Tor JSON" }
            value(0); whitespace(); require(at == bytes.size) { "Trailing Tor JSON" }
            return sampled && defaultSet
        }
        private fun peek(): Int = if (at < bytes.size) bytes[at].toInt() and 255 else -1
        private fun whitespace() { while (true) { when (peek()) { 9, 10, 13, 32 -> at++; else -> return } } }
        private fun consume(code: Int) { whitespace(); require(peek() == code) { "Invalid Tor JSON" }; at++ }
        private fun value(depth: Int, field: String? = null) {
            require(depth <= 32 && ++nodes <= 100000) { "Tor JSON exceeds parser limits" }; whitespace()
            when (peek()) {
                '{'.code -> {
                    if (depth == 1 && field == "default") defaultSet = true
                    at++; whitespace()
                    if (peek() == '}'.code) { at++; return }
                    while (true) {
                        whitespace(); val key = string(true); consume(':'.code); value(depth + 1, key); whitespace()
                        if (peek() == '}'.code) { at++; break }; consume(','.code)
                    }
                }
                '['.code -> {
                    at++; whitespace()
                    if (peek() == ']'.code) { at++; return }
                    if (field == "guards" && peek() == '{'.code) sampled = true
                    while (true) { value(depth + 1); whitespace(); if (peek() == ']'.code) { at++; break }; consume(','.code) }
                }
                '"'.code -> string(false)
                't'.code -> literal("true")
                'f'.code -> literal("false")
                'n'.code -> literal("null")
                else -> number()
            }
        }
        private fun literal(expected: String) { for (c in expected) { require(peek() == c.code); at++ } }
        private fun string(key: Boolean): String? {
            require(peek() == '"'.code); at++; val begin = at
            while (true) {
                val c = peek(); require(c >= 32) { "Invalid JSON string" }
                if (c == '"'.code) { val end = at++; return if (key && end - begin <= 64) String(bytes, begin, end - begin, Charsets.UTF_8) else null }
                at++
                if (c == '\\'.code) {
                    val escaped = peek(); at++
                    if (escaped == 'u'.code) repeat(4) { require(peek() in '0'.code..'9'.code || peek() in 'a'.code..'f'.code || peek() in 'A'.code..'F'.code); at++ }
                    else require(escaped in listOf('"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code))
                } else if (c >= 128) {
                    val count = when (c) { in 0xc2..0xdf -> 1; in 0xe0..0xef -> 2; in 0xf0..0xf4 -> 3; else -> error("Invalid UTF-8") }
                    val first = peek()
                    require(c != 0xe0 || first >= 0xa0); require(c != 0xed || first < 0xa0)
                    require(c != 0xf0 || first >= 0x90); require(c != 0xf4 || first < 0x90)
                    repeat(count) { require(peek() in 0x80..0xbf); at++ }
                }
            }
        }
        private fun number() {
            if (peek() == '-'.code) at++
            if (peek() == '0'.code) at++ else { require(peek() in '1'.code..'9'.code); while (peek() in '0'.code..'9'.code) at++ }
            if (peek() == '.'.code) { at++; require(peek() in '0'.code..'9'.code); while (peek() in '0'.code..'9'.code) at++ }
            if (peek() == 'e'.code || peek() == 'E'.code) { at++; if (peek() == '+'.code || peek() == '-'.code) at++; require(peek() in '0'.code..'9'.code); while (peek() in '0'.code..'9'.code) at++ }
        }
    }
}
