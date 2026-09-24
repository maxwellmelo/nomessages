package dev.mx3.nomessages.storage

import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.crypto.SigningKeys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A real v3 onion address embeds a genuine Ed25519 public key: a point a key generator derived by
 * expanding and clamping a secret seed, which always lands in the curve's prime-order subgroup.
 * 32 raw random bytes are not that - they only decompress to a valid Edwards point about half the
 * time, and pass a subgroup check far less often - so if [DecoyFactory.syntheticOnion] ever went
 * back to building the address from [Crypto.random] instead of [Crypto.signingKeyPair], the decoy
 * vault's contacts would be distinguishable from real ones by decoding the address, no
 * cryptanalysis required. This pins the embedded key to the signing key pair's public half and
 * checks the resulting address against the same checksum and encoding rules as the real network.
 */
class DecoyFactoryTest {
    @Test fun syntheticOnionEmbedsTheSigningKeyPairsPublicKeyNotRawRandomBytes() {
        val signingPublicKey = ByteArray(32) { 0xAB.toByte() }
        val distinguishableRandomBytes = ByteArray(32) { 0xCD.toByte() }
        val crypto = FakeCrypto(signingPublicKey, distinguishableRandomBytes)

        val onion = DecoyFactory(crypto).syntheticOnion()

        assertTrue(onion.endsWith(".onion"))
        val encoded = onion.removeSuffix(".onion")
        assertEquals(56, encoded.length)
        assertTrue(encoded.all { it in "abcdefghijklmnopqrstuvwxyz234567" })

        val address = decodeBase32(encoded)
        assertEquals(35, address.size)
        val embeddedPublicKey = address.copyOfRange(0, 32)
        val embeddedChecksum = address.copyOfRange(32, 34)
        val embeddedVersion = address[34]

        // The defect this test guards against: the embedded key must come from the signing key
        // pair, never from raw randomness, even though both are 32 bytes and both are plausible
        // arguments to pass here.
        assertArrayEquals(signingPublicKey, embeddedPublicKey)
        assertFalse(embeddedPublicKey.contentEquals(distinguishableRandomBytes))

        val expectedChecksum = Sha3_256.digest(
            ".onion checksum".toByteArray(Charsets.US_ASCII) + signingPublicKey + byteArrayOf(3),
        ).copyOf(2)
        assertArrayEquals(expectedChecksum, embeddedChecksum)
        assertEquals(3, embeddedVersion)
    }

    /** Inverse of [DecoyFactory.base32]: RFC 4648 base32, lowercase, no padding. */
    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (character in value) {
            val symbol = alphabet.indexOf(character)
            require(symbol >= 0) { "Invalid base32 character '$character'" }
            buffer = (buffer shl 5) or symbol
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xff).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}

/** Implements only what [DecoyFactory.syntheticOnion] calls; everything else fails loudly. */
private class FakeCrypto(
    private val signingPublicKey: ByteArray,
    private val randomBytes: ByteArray,
) : Crypto {
    override fun random(size: Int): ByteArray {
        check(size == randomBytes.size) { "Unexpected random($size) call" }
        return randomBytes.copyOf()
    }

    override fun signingKeyPair(): SigningKeys = SigningKeys(signingPublicKey.copyOf(), ByteArray(64))

    override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray = fail()
    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray = fail()
    override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? = fail()
    override fun hash(message: ByteArray): ByteArray = fail()
    override fun mac(key: ByteArray, message: ByteArray): ByteArray = fail()
    override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray = fail()
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = fail()

    private fun fail(): Nothing = throw UnsupportedOperationException("Not used by syntheticOnion()")
}
