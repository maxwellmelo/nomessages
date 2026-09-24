package dev.mx3.nomessages.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import dev.mx3.nomessages.runtime.SensitiveBuffer

/** Result of decoding an audio attachment's bytes into a waveform, entirely in memory. */
internal data class DecodedAudioPreview(val waveform: FloatArray, val durationMs: Int)

private const val TAG = "AudioWaveformDecoder"
private const val CODEC_TIMEOUT_US = 10_000L

// A generous but finite ceiling on decoded PCM: guards a decoder loop against ever growing
// unbounded on a pathological input. 8 MiB compressed audio decodes to well under this at 16 kHz
// mono, even accounting for a device decoding at a higher internal sample rate.
private const val MAX_DECODED_PCM_BYTES = 64 * 1024 * 1024
private const val MAX_CODEC_ITERATIONS = 500_000

/**
 * Decodes an in-memory audio attachment (WAV or AAC/M4A) into a 48-bucket waveform and its
 * duration, off the caller's thread of choice - this function itself is synchronous and should be
 * invoked from a background dispatcher. Never touches disk; the AAC path reuses
 * [MemoryMediaDataSource] the same way the full-screen media viewer does. Returns null instead of
 * throwing on any decode failure so the caller can render a flat/placeholder waveform.
 */
internal fun decodeAudioWaveform(bytes: ByteArray, mimeType: String): DecodedAudioPreview? {
    if (bytes.isEmpty()) return null
    return try {
        if (mimeType == "audio/wav" || mimeType == "audio/x-wav" || looksLikeWav(bytes)) {
            decodeWavWaveform(bytes)
        } else {
            decodeCompressedWaveform(bytes)
        }
    } catch (failure: Exception) {
        Log.w(TAG, "Audio waveform decode failed for mime=$mimeType", failure)
        null
    }
}

private fun looksLikeWav(bytes: ByteArray): Boolean =
    bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

private fun decodeWavWaveform(bytes: ByteArray): DecodedAudioPreview? {
    var offset = 12
    var channels = 1
    var bitsPerSample = 16
    var sampleRate = PCM_SAMPLE_RATE_HZ
    var dataOffset = -1
    var dataSize = 0
    while (offset + 8 <= bytes.size) {
        val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
        val chunkSize = readLeInt(bytes, offset + 4)
        val chunkDataStart = offset + 8
        if (chunkSize < 0 || chunkDataStart + chunkSize.toLong() > bytes.size + 1) break
        when (chunkId) {
            "fmt " -> if (chunkDataStart + 16 <= bytes.size) {
                channels = readLeShort(bytes, chunkDataStart + 2)
                sampleRate = readLeInt(bytes, chunkDataStart + 4)
                bitsPerSample = readLeShort(bytes, chunkDataStart + 14)
            }
            "data" -> {
                dataOffset = chunkDataStart
                dataSize = minOf(chunkSize, bytes.size - chunkDataStart)
            }
        }
        if (dataOffset >= 0) break
        offset = chunkDataStart + chunkSize + (chunkSize and 1)
    }
    if (dataOffset < 0 || dataSize <= 0 || bitsPerSample != 16 || channels < 1) return null
    // This is a copy of decrypted voice-message audio; it is zeroed as soon as the (tiny) waveform
    // summary has been computed from it, matching SensitiveBuffer/MemoryAudioRecorder elsewhere in
    // the project rather than leaving a full PCM copy of the clip on the heap for GC.
    val pcm = if (channels == 1) {
        bytes.copyOfRange(dataOffset, dataOffset + dataSize)
    } else {
        downmixToMono16(bytes, dataOffset, dataSize, channels)
    }
    try {
        return DecodedAudioPreview(computeWaveformBuckets(pcm), pcmDurationMs(pcm.size, sampleRate))
    } finally {
        pcm.fill(0)
    }
}

private fun readLeInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun readLeShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun downmixToMono16(bytes: ByteArray, dataOffset: Int, dataSize: Int, channels: Int): ByteArray {
    val bytesPerFrame = 2 * channels
    if (bytesPerFrame <= 0) return ByteArray(0)
    val frameCount = dataSize / bytesPerFrame
    val out = ByteArray(frameCount * 2)
    for (frame in 0 until frameCount) {
        var sum = 0
        for (channel in 0 until channels) {
            val base = dataOffset + frame * bytesPerFrame + channel * 2
            val lo = bytes[base].toInt() and 0xFF
            val hi = bytes[base + 1].toInt()
            sum += (hi shl 8) or lo
        }
        val average = (sum / channels).coerceIn(-32768, 32767)
        out[frame * 2] = (average and 0xFF).toByte()
        out[frame * 2 + 1] = ((average shr 8) and 0xFF).toByte()
    }
    return out
}

private fun decodeCompressedWaveform(bytes: ByteArray): DecodedAudioPreview? {
    val dataSource = MemoryMediaDataSource(bytes.copyOf())
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    // Decoded PCM of a decrypted voice message. SensitiveBuffer (the project's existing pattern,
    // see runtime/SensitiveBuffer.kt) zeroes every array it owns on growth and on close, which a
    // plain ByteArrayOutputStream does not: that one leaves each discarded doubling generation of
    // the clip's plaintext audio on the heap until GC happens to overwrite it.
    val pcmOut = SensitiveBuffer(MAX_DECODED_PCM_BYTES)
    try {
        extractor.setDataSource(dataSource)
        var trackIndex = -1
        var trackFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = index
                trackFormat = candidate
                break
            }
        }
        val format = trackFormat ?: return null
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        extractor.selectTrack(trackIndex)

        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else PCM_SAMPLE_RATE_HZ
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var iterations = 0

        while (!outputDone && iterations < MAX_CODEC_ITERATIONS) {
            iterations++
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val changed = codec.outputFormat
                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outputIndex >= 0 -> {
                    // `<=` (not `<`) against the ceiling: SensitiveBuffer refuses a write that would
                    // cross its limit instead of silently overshooting by one codec chunk.
                    if (bufferInfo.size > 0 && pcmOut.size() + bufferInfo.size <= MAX_DECODED_PCM_BYTES) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val chunk = ByteArray(bufferInfo.size)
                            try {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(chunk)
                                pcmOut.write(chunk, 0, chunk.size)
                            } finally {
                                chunk.fill(0)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        val pcm = pcmOut.toByteArray()
        try {
            if (pcm.isEmpty()) return null
            val mono = if (channels <= 1) pcm else downmixToMono16(pcm, 0, pcm.size - (pcm.size % (2 * channels)), channels)
            try {
                return DecodedAudioPreview(computeWaveformBuckets(mono), pcmDurationMs(mono.size, sampleRate))
            } finally {
                if (mono !== pcm) mono.fill(0)
            }
        } finally {
            pcm.fill(0)
        }
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        dataSource.close()
        // Zeroes and drops every PCM array this buffer still owns, on the success path and on
        // every early-return/throw path alike.
        runCatching { pcmOut.close() }
    }
}
