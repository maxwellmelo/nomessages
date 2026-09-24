package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.Crypto
import kotlin.math.ceil

data class KdfParams(val memoryKiB: Int = 65536, val iterations: Int = 3) {
    init {
        require(memoryKiB in 65536..262144) { "Unsupported KDF memory budget" }
        require(iterations in 1..20) { "Unsupported KDF iteration budget" }
    }
}

class KdfCalibrator(private val crypto: Crypto) {
    /** Estimates one derivation at 2.5 seconds. Unlock always performs TWO derivations. */
    fun calibrate(minimum: KdfParams = KdfParams(), targetMillis: Long = 2500): KdfParams {
        require(targetMillis in 500..10000)
        val password = crypto.random(32)
        var salt: ByteArray? = null
        try {
            val calibrationSalt = crypto.random(16)
            salt = calibrationSalt
            var params = minimum
            // Real measurements; increase cost only. Stop at a bounded device-safe maximum.
            repeat(4) {
                val start = System.nanoTime()
                val key = crypto.derive(password, calibrationSalt, params.memoryKiB, params.iterations)
                crypto.wipe(key)
                val elapsed = ((System.nanoTime() - start) / 1_000_000.0).coerceAtLeast(1.0)
                if (elapsed >= targetMillis * 0.9) return params
                val needed = ceil(params.iterations * targetMillis / elapsed).toInt().coerceAtMost(20)
                if (needed > params.iterations) params = params.copy(iterations = needed)
                else if (params.memoryKiB < 262144) params = params.copy(memoryKiB = (params.memoryKiB * 2).coerceAtMost(262144))
                else return params
            }
            return params
        } finally { crypto.wipe(password); salt?.let(crypto::wipe) }
    }
}
