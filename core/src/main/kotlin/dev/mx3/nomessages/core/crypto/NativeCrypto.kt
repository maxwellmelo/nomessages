package dev.mx3.nomessages.core.crypto

class NativeCrypto : Crypto {
    override fun random(size: Int): ByteArray = nativeRandom(size)

    override fun derive(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
    ): ByteArray = nativeDerive(password, salt, memoryKiB, iterations)

    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        nativeSeal(key, plaintext, aad)

    override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        nativeOpen(key, sealed, aad)

    override fun hash(message: ByteArray): ByteArray = nativeHash(message)

    override fun mac(key: ByteArray, message: ByteArray): ByteArray = nativeMac(key, message)

    override fun signingKeyPair(): SigningKeys {
        val encoded = nativeSigningKeyPair()
        try {
            check(encoded.size == SIGNING_KEY_PAIR_BYTES) { "Native cryptographic operation failed" }
            return SigningKeys(
                publicKey = encoded.copyOfRange(0, PUBLIC_KEY_BYTES),
                secretKey = encoded.copyOfRange(PUBLIC_KEY_BYTES, SIGNING_KEY_PAIR_BYTES),
            )
        } finally {
            encoded.fill(0)
        }
    }

    override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray =
        nativeSign(secretKey, message)

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        nativeVerify(publicKey, message, signature)

    private external fun nativeRandom(size: Int): ByteArray
    private external fun nativeDerive(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
    ): ByteArray
    private external fun nativeSeal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    private external fun nativeOpen(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?
    private external fun nativeHash(message: ByteArray): ByteArray
    private external fun nativeMac(key: ByteArray, message: ByteArray): ByteArray
    private external fun nativeSigningKeyPair(): ByteArray
    private external fun nativeSign(secretKey: ByteArray, message: ByteArray): ByteArray
    private external fun nativeVerify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val SIGNING_KEY_PAIR_BYTES = 96

        init {
            System.loadLibrary("nomessages")
        }
    }
}
