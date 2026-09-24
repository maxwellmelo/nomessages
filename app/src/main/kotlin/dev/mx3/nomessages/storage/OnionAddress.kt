package dev.mx3.nomessages.storage

/**
 * Conversion between a Tor v3 onion address and the Ed25519 public key it embeds.
 *
 * A v3 address is `base32(publicKey[32] || checksum[2] || version[1]) + ".onion"`, where the
 * checksum is `SHA3-256(".onion checksum" || publicKey || version)[0..1]` (rend-spec-v3 §6). Only
 * the 32-byte key carries information: the remaining three bytes are derived from it, which is why
 * the pairing QR transports the key and not the 62-character text (see `Pairing.kt`, format 2).
 *
 * Extracted here on 2026-09-17 (T4.16) from `DecoyFactory.syntheticOnion`, which had the only copy
 * of the base32 and checksum code; that function now calls [address] so there is exactly one
 * implementation to keep byte-exact with the real network.
 */
internal object OnionAddress {
    const val KEY_BYTES = 32
    private const val ENCODED_LENGTH = 56
    private const val SUFFIX = ".onion"
    private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private val VERSION = byteArrayOf(3)
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    /** The v3 onion address of [publicKey]. [publicKey] is not modified; scratch buffers are wiped. */
    fun address(publicKey: ByteArray): String {
        require(publicKey.size == KEY_BYTES) { "A v3 onion identity key is $KEY_BYTES bytes" }
        val checksumInput = CHECKSUM_PREFIX + publicKey + VERSION
        val checksum = Sha3_256.digest(checksumInput)
        val payload = publicKey + checksum.copyOf(2) + VERSION
        return try {
            base32(payload) + SUFFIX
        } finally {
            checksumInput.fill(0)
            checksum.fill(0)
            payload.fill(0)
        }
    }

    /**
     * The Ed25519 public key embedded in [address].
     *
     * The checksum is verified rather than skipped: this is how a typo'd or truncated address is
     * caught before it is signed into a pairing offer, where it would be too late.
     */
    fun publicKey(address: String): ByteArray {
        require(address.length == ENCODED_LENGTH + SUFFIX.length && address.endsWith(SUFFIX)) {
            "Invalid v3 onion address"
        }
        val payload = unbase32(address.substring(0, ENCODED_LENGTH))
        require(payload[payload.lastIndex] == VERSION[0]) { "Unsupported onion address version" }
        val key = payload.copyOf(KEY_BYTES)
        val expected = address(key)
        require(expected == address) { "Onion address checksum mismatch" }
        return key
    }

    private fun base32(bytes: ByteArray): String {
        val output = StringBuilder(ENCODED_LENGTH)
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(BASE32[(buffer ushr bits) and 31])
            }
        }
        check(bits == 0 && output.length == ENCODED_LENGTH)
        return output.toString()
    }

    /**
     * 56 base32 characters carry 280 bits, of which the 35-byte payload uses 280 - so there are no
     * leftover bits to check, unlike a padded base32 alphabet.
     */
    private fun unbase32(encoded: String): ByteArray {
        val output = ByteArray(KEY_BYTES + 3)
        var buffer = 0
        var bits = 0
        var index = 0
        encoded.forEach { character ->
            val value = BASE32.indexOf(character)
            require(value >= 0) { "Invalid onion address character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output[index++] = ((buffer ushr bits) and 0xff).toByte()
            }
        }
        check(index == output.size && bits == 0)
        return output
    }
}
