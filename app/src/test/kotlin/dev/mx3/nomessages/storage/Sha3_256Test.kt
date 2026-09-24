package dev.mx3.nomessages.storage

import java.security.MessageDigest
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The bundled digest replaces a platform provider that Android does not ship, so it has to be
 * pinned to the FIPS 202 vectors and cross-checked against the JDK implementation, which is only
 * available off-device.
 */
class Sha3_256Test {
    @Test fun matchesTheFips202VectorsAroundThePaddingBoundaries() {
        // 0 bytes: the domain separator and the pad terminator share the same empty block.
        assertEquals(
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            hex(Sha3_256.digest(ByteArray(0))),
        )
        assertEquals(
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            hex(Sha3_256.digest("abc".toByteArray(Charsets.US_ASCII))),
        )
        // 135 bytes: padding collapses into the single remaining byte of the rate block.
        assertEquals(
            hex(reference(ByteArray(135) { 0x61 })),
            hex(Sha3_256.digest(ByteArray(135) { 0x61 })),
        )
        // 136 bytes: a full rate block forces an extra, entirely synthetic padding block.
        assertEquals(
            hex(reference(ByteArray(136) { 0x62 })),
            hex(Sha3_256.digest(ByteArray(136) { 0x62 })),
        )
    }

    @Test fun matchesTheReferenceImplementationOnMultiBlockInputs() {
        val random = Random(20260915)
        for (size in listOf(1, 64, 137, 271, 272, 1000, 4096)) {
            val message = ByteArray(size).also(random::nextBytes)
            assertEquals(hex(reference(message)), hex(Sha3_256.digest(message)), "size=$size")
        }
    }

    private fun reference(message: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA3-256").digest(message)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
