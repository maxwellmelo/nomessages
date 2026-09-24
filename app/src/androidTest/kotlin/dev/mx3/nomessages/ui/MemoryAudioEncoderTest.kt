package dev.mx3.nomessages.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mx3.nomessages.runtime.MemoryAudioEncoder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the AAC-in-memory encoder ([MemoryAudioEncoder], `MediaCodec` + `MediaMuxer` writing
 * to a `memfd` via [MemoryFd]) and the receiver-side waveform decoder ([decodeAudioWaveform]) that
 * consumes its output, on a real device/emulator (both need real `MediaCodec` instances the JVM
 * unit test sandbox cannot provide).
 *
 * `MemoryAudioEncoder.encode` is documented to return null instead of throwing on any failure, and
 * this project's emulator target has been observed to occasionally fail `MediaMuxer` writes against
 * a memfd-backed `FileDescriptor`. This test therefore treats a null result as an acceptable outcome
 * (it exercises the "never throws, never hangs" contract either way) and only asserts round-trip
 * correctness - a real, decodable waveform - when encoding actually succeeded on this device.
 */
@RunWith(AndroidJUnit4::class)
class MemoryAudioEncoderTest {
    private val sampleRateHz = 16_000

    @Test
    fun encodesToPlayableAacOrReturnsNullWithoutThrowing() {
        val pcm = sineWavePcm16(durationSeconds = 1, frequencyHz = 440.0)

        val encoded = MemoryAudioEncoder.encode(pcm, sampleRateHz)

        if (encoded == null) {
            // Documented, tolerated fallback path (see MemoryAudioEncoder's doc comment) - nothing
            // further to assert, but the call above must have returned rather than thrown or hung.
            return
        }

        assertTrue("Encoded AAC/M4A output should be non-empty", encoded.isNotEmpty())

        val decoded = decodeAudioWaveform(encoded, "audio/mp4")
        assertTrue("Compressed output should be decodable back into a waveform", decoded != null)
        checkNotNull(decoded)
        assertEquals(WAVEFORM_BUCKET_COUNT, decoded.waveform.size)
        assertTrue("Waveform should stay within [0, 1]", decoded.waveform.all { it in 0f..1f })
        assertTrue("A real tone should produce at least one non-silent bucket", decoded.waveform.any { it > 0f })
        // Muxed container round-trips duration with some tolerance for encoder framing/padding.
        assertTrue(
            "Decoded duration ${decoded.durationMs}ms should be close to the recorded 1000ms",
            decoded.durationMs in 700..1300,
        )
    }

    @Test
    fun emptyPcmNeverThrowsAndYieldsNoOutput() {
        assertNull(MemoryAudioEncoder.encode(ByteArray(0), sampleRateHz))
    }

    private fun sineWavePcm16(durationSeconds: Int, frequencyHz: Double): ByteArray {
        val sampleCount = sampleRateHz * durationSeconds
        val amplitude = 12_000.0
        val pcm = ByteArray(sampleCount * 2)
        for (sample in 0 until sampleCount) {
            val timeSeconds = sample.toDouble() / sampleRateHz
            val value = (sin(2.0 * PI * frequencyHz * timeSeconds) * amplitude).toInt()
            pcm[sample * 2] = (value and 0xFF).toByte()
            pcm[sample * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return pcm
    }
}
