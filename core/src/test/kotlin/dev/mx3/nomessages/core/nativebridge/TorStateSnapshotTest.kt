package dev.mx3.nomessages.core.nativebridge

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

class TorStateSnapshotTest {
    private val guards = """{"default":{"guards":[{"rsa_identity":"fixture-relay"}],"confirmed":[]},"restricted":{"guards":[],"confirmed":[]}}""".toByteArray()

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("nomessages-guard-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun write(root: File, path: String, bytes: ByteArray) {
        val file = File(root, path); file.parentFile.mkdirs(); file.writeBytes(bytes)
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }

    @Test fun guardHistorySurvivesClearAndRestoreWithoutServiceKeysOrCache() = fixture { root ->
        val session = File(root, "tor-session").apply { mkdir() }
        write(session, "state/guards.json", guards)
        write(session, "state/circuit_timeouts.json", "{\"build_times\":[1,2,3]}".toByteArray())
        write(session, "state/vanguards.json", "{\"layer2\":[]}".toByteArray())
        write(session, "hss/nomessages/ipts.json", "DO_NOT_RESTORE".toByteArray())
        write(session, "keystore/key", "DO_NOT_RESTORE".toByteArray())
        write(session, "cache/consensus", "DO_NOT_RESTORE".toByteArray())
        val snapshot = requireNotNull(TorStateSnapshot.capture(session))
        try {
            assertFalse(snapshot.toString(Charsets.UTF_8).contains("DO_NOT_RESTORE"))
            TorStateSnapshot.clear(session)
            assertFalse(session.exists())
            TorStateSnapshot.restore(session, snapshot)
            assertArrayEquals(guards, File(session, "state/guards.json").readBytes())
            assertTrue(File(session, "state/circuit_timeouts.json").isFile)
            assertFalse(File(session, "hss").exists())
            assertFalse(File(session, "keystore").exists())
            assertArrayEquals(snapshot, TorStateSnapshot.capture(session))
        } finally { snapshot.fill(0) }
    }

    @Test fun emptyMissingMalformedAndOversizedStateCannotReplaceHistory() = fixture { root ->
        val session = File(root, "tor-session").apply { mkdir() }
        assertNull(TorStateSnapshot.capture(session))
        write(session, "state/guards.json", "{\"default\":{\"guards\":[],\"confirmed\":[]}}".toByteArray())
        assertNull(TorStateSnapshot.capture(session))
        write(session, "state/guards.json", guards)
        val saved = requireNotNull(TorStateSnapshot.capture(session))
        try {
            write(session, "state/guards.json", "{\"default\":".toByteArray())
            rejects { TorStateSnapshot.capture(session) }
            RandomAccessFile(File(session, "state/guards.json"), "rw").use { it.setLength(TorStateSnapshot.MAX_SNAPSHOT_BYTES.toLong() + 1) }
            rejects { TorStateSnapshot.capture(session) }
            TorStateSnapshot.clear(session)
            TorStateSnapshot.restore(session, saved)
            assertArrayEquals(guards, File(session, "state/guards.json").readBytes())
        } finally { saved.fill(0) }
    }

    @Test fun corruptedTruncatedAndForbiddenPathsRejectBeforeRestoreWrites() = fixture { root ->
        val session = File(root, "source").apply { mkdir() }
        write(session, "state/guards.json", guards)
        val snapshot = requireNotNull(TorStateSnapshot.capture(session))
        try {
            val target = File(root, "target")
            val corrupted = snapshot.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
            try { rejects { TorStateSnapshot.restore(target, corrupted) }; assertFalse(target.exists()) } finally { corrupted.fill(0) }
            val truncated = snapshot.copyOf(snapshot.size - 1)
            try { rejects { TorStateSnapshot.restore(target, truncated) }; assertFalse(target.exists()) } finally { truncated.fill(0) }
            val forbidden = snapshot.copyOf()
            try {
                // Same-sized path with an authenticated snapshot checksum still fails the allowlist.
                val data = ByteBuffer.wrap(forbidden); data.position(12)
                val size = data.int; val path = ByteArray(size) { 'x'.code.toByte() }; data.put(path)
                val hash = MessageDigest.getInstance("SHA-256")
                hash.update(forbidden, 0, forbidden.size - 32)
                hash.digest().copyInto(forbidden, forbidden.size - 32)
                rejects { TorStateSnapshot.restore(target, forbidden) }; assertFalse(target.exists())
            } finally { forbidden.fill(0) }
            rejects { TorStateSnapshot.restore(session, snapshot) }
            assertArrayEquals(guards, File(session, "state/guards.json").readBytes())
        } finally { snapshot.fill(0) }
    }

    @Test fun symlinksAreRejectedForCaptureAndNeverFollowedByClear() = fixture { root ->
        val outside = File(root, "outside").apply { mkdir() }
        val marker = File(outside, "marker").apply { writeText("keep") }
        val session = File(root, "tor-session").apply { mkdir() }
        Files.createSymbolicLink(File(session, "state").toPath(), outside.toPath())
        rejects { TorStateSnapshot.capture(session) }
        TorStateSnapshot.clear(session)
        assertTrue(marker.isFile)
        Files.createSymbolicLink(session.toPath(), outside.toPath())
        rejects { TorStateSnapshot.capture(session) }
        rejects { TorStateSnapshot.restore(session, null) }
        rejects { TorStateSnapshot.clear(session) }
        Files.delete(session.toPath())
        assertTrue(marker.isFile)
    }
}
