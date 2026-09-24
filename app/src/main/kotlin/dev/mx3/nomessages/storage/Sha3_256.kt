package dev.mx3.nomessages.storage

/**
 * Self-contained SHA3-256 (FIPS 202, Keccak-f[1600] with a 136-byte rate).
 *
 * Android ships no `SHA3-256` MessageDigest: Conscrypt does not implement the SHA-3 family and the
 * platform's repackaged BouncyCastle dropped it, so `MessageDigest.getInstance("SHA3-256")` throws
 * `NoSuchAlgorithmException` on a device even though it resolves on a desktop JVM (JDK 9+). The
 * only caller is the Tor v3 address checksum used by the decoy fixture, which must stay byte-exact
 * with the real network, so the digest is implemented here instead of taken from a provider whose
 * availability depends on the API level.
 */
internal object Sha3_256 {
    private const val RATE_BYTES = 136
    private const val DIGEST_BYTES = 32
    private const val LANES = 25

    /** Round constants of the iota step, one per Keccak-f[1600] round. */
    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(),
        0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008AuL.toLong(), 0x0000000000000088uL.toLong(),
        0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
        0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(),
        0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
        0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(),
        0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
    )

    /** Rotation offsets of the rho step, indexed as `x + 5 * y`. */
    private val ROTATION_OFFSETS = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    fun digest(message: ByteArray): ByteArray {
        val state = LongArray(LANES)
        var offset = 0
        while (message.size - offset >= RATE_BYTES) {
            absorb(state, message, offset)
            offset += RATE_BYTES
        }

        // Final block: the remaining bytes, the SHA-3 domain separator 0x06 and the 0x80 terminator
        // of the pad10*1 rule, which may land on the same byte when only one byte of padding fits.
        val tail = ByteArray(RATE_BYTES)
        val remaining = message.size - offset
        message.copyInto(tail, 0, offset, message.size)
        tail[remaining] = 0x06
        tail[RATE_BYTES - 1] = (tail[RATE_BYTES - 1].toInt() or 0x80).toByte()
        absorb(state, tail, 0)
        tail.fill(0)

        val digest = ByteArray(DIGEST_BYTES)
        for (index in 0 until DIGEST_BYTES) {
            digest[index] = (state[index / 8] ushr (8 * (index % 8))).toByte()
        }
        state.fill(0)
        return digest
    }

    /** XORs one little-endian rate block into the state and applies the permutation. */
    private fun absorb(state: LongArray, block: ByteArray, offset: Int) {
        for (lane in 0 until RATE_BYTES / 8) {
            var value = 0L
            for (byte in 7 downTo 0) {
                value = (value shl 8) or (block[offset + lane * 8 + byte].toLong() and 0xff)
            }
            state[lane] = state[lane] xor value
        }
        permute(state)
    }

    private fun permute(state: LongArray) {
        val parity = LongArray(5)
        val theta = LongArray(5)
        val scratch = LongArray(LANES)
        for (round in ROUND_CONSTANTS.indices) {
            for (x in 0 until 5) {
                parity[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                theta[x] = parity[(x + 4) % 5] xor java.lang.Long.rotateLeft(parity[(x + 1) % 5], 1)
            }
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    state[x + 5 * y] = state[x + 5 * y] xor theta[x]
                }
            }
            // rho (rotate each lane) and pi (move lane (x, y) to (y, 2x + 3y)) in one pass.
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    val source = x + 5 * y
                    scratch[y + 5 * ((2 * x + 3 * y) % 5)] =
                        java.lang.Long.rotateLeft(state[source], ROTATION_OFFSETS[source])
                }
            }
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    state[x + 5 * y] = scratch[x + 5 * y] xor
                        (scratch[(x + 1) % 5 + 5 * y].inv() and scratch[(x + 2) % 5 + 5 * y])
                }
            }
            state[0] = state[0] xor ROUND_CONSTANTS[round]
        }
        parity.fill(0)
        theta.fill(0)
        scratch.fill(0)
    }
}
