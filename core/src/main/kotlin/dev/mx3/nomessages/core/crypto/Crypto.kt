package dev.mx3.nomessages.core.crypto

/** All cryptographic operations are delegated to maintained native libraries. */
interface Crypto {
    fun random(size: Int): ByteArray
    fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray
    /** XChaCha20-Poly1305; encoding is 24-byte random nonce || ciphertext || tag. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?
    fun hash(message: ByteArray): ByteArray
    fun mac(key: ByteArray, message: ByteArray): ByteArray
    fun signingKeyPair(): SigningKeys
    fun sign(secretKey: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    fun wipe(bytes: ByteArray) { bytes.fill(0) }
}

class SigningKeys(val publicKey: ByteArray, val secretKey: ByteArray) : AutoCloseable {
    override fun close() { secretKey.fill(0) }
}
