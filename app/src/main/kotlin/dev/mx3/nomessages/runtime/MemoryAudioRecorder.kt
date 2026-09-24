package dev.mx3.nomessages.runtime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.mx3.nomessages.ui.computeWaveformBuckets
import dev.mx3.nomessages.ui.pcmDurationMs
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A finished recording: an always-available uncompressed WAV, an optional AAC/M4A compressed
 * version ([aacBytes] is null when this device's `MediaCodec`/`MediaMuxer`/memfd combination
 * failed - see [MemoryAudioEncoder]), and a waveform/duration computed from the same raw PCM so the
 * sender's own bubble never has to re-decrypt its own just-sent attachment to draw one.
 */
data class AudioRecording(
    val wavBytes: ByteArray,
    val aacBytes: ByteArray?,
    val waveform: FloatArray,
    val durationMs: Int,
)

/** PCM recording stays in RAM until the caller immediately encrypts the attachment. */
class MemoryAudioRecorder {
    private val pcm = SensitiveBuffer(MAX_PCM_BYTES)
    private val deviceGuard = Any()
    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        check(!recording)
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microfone indisponível") }
        try {
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        synchronized(deviceGuard) { recorder = audio }
        recording = true
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (recording && isActive) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    synchronized(pcm) {
                        if (pcm.size() + count <= MAX_PCM_BYTES) pcm.write(buffer, 0, count)
                        else recording = false
                    }
                }
            } finally { buffer.fill(0); stopDevice() }
        }.also { it.invokeOnCompletion { stopDevice() } }
        } catch (failure: Throwable) {
            recording = false
            recorder = null
            try { audio.stop() } catch (_: IllegalStateException) { }
            audio.release()
            synchronized(pcm) { pcm.close() }
            throw failure
        }
    }

    suspend fun finish(): AudioRecording {
        stopDevice()
        job?.join()
        val raw = synchronized(pcm) {
            val bytes = pcm.toByteArray()
            pcm.close()
            bytes
        }
        try {
            if (raw.isEmpty()) throw MessagingError(MessagingErrorCode.NO_AUDIO_RECORDED, "no audio recorded")
            val waveform = computeWaveformBuckets(raw)
            val durationMs = pcmDurationMs(raw.size, SAMPLE_RATE_HZ)
            val wav = buildWav(raw)
            val aac = try {
                MemoryAudioEncoder.encode(raw, SAMPLE_RATE_HZ)
            } catch (failure: Throwable) {
                Log.w("MemoryAudioRecorder", "AAC encode threw unexpectedly; falling back to WAV", failure)
                null
            }
            return AudioRecording(wav, aac, waveform, durationMs)
        } finally { raw.fill(0) }
    }

    private fun buildWav(raw: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44 + raw.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + raw.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE_HZ).putInt(SAMPLE_RATE_HZ * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(raw.size).put(raw)
        return header.array()
    }

    suspend fun discard() = withContext(NonCancellable) {
        try {
            stopDevice()
            job?.cancelAndJoin()
        } finally { synchronized(pcm) { pcm.close() } }
    }
    private fun stopDevice() = synchronized(deviceGuard) {
        recording = false
        val audio = recorder
        recorder = null
        if (audio != null) {
            try { audio.stop() } catch (_: IllegalStateException) { }
            finally { audio.release() }
        }
    }
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        private const val MAX_PCM_BYTES = SAMPLE_RATE_HZ * 2 * 120
    }
}
