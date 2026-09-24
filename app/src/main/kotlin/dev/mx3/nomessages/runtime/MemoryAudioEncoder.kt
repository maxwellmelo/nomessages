package dev.mx3.nomessages.runtime

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dev.mx3.nomessages.ui.MemoryFd
import java.io.FileDescriptor

/**
 * Encodes mono 16-bit PCM into an AAC-LC / M4A container entirely in memory: a `MediaCodec`
 * encoder feeds a `MediaMuxer` that writes to an anonymous `memfd` (via [MemoryFd]), never a
 * filesystem path. The muxed bytes are read back from that memfd and the memfd is wiped and closed
 * before returning.
 *
 * Synchronous and blocking - call it from a background dispatcher, never the main thread.
 *
 * Returns null instead of throwing on *any* failure. This device class (the project's emulator
 * target) has been observed to sometimes fail `MediaMuxer` writes against a memfd-backed
 * `FileDescriptor`; rather than treat that as fatal, callers fall back to the always-available
 * uncompressed WAV attachment so a flaky encoder/muxer can never crash or hang message sending.
 */
internal object MemoryAudioEncoder {
    private const val TAG = "MemoryAudioEncoder"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 32_000
    private const val CHANNEL_COUNT = 1
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm16: ByteArray, sampleRateHz: Int): ByteArray? {
        if (pcm16.isEmpty()) return null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var memoryFile: FileDescriptor? = null
        var result: ByteArray? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRateHz, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            codec = MediaCodec.createEncoderByType(MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            memoryFile = MemoryFd.createEmpty(name = "nomessages-audio")
            muxer = MediaMuxer(memoryFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var trackIndex = -1
            var muxerStarted = false
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex)) { "No encoder input buffer" }
                        inputBuffer.clear()
                        val remaining = pcm16.size - inputOffset
                        val chunkSize = minOf(remaining, inputBuffer.remaining())
                        if (chunkSize > 0) {
                            inputBuffer.put(pcm16, inputOffset, chunkSize)
                            inputOffset += chunkSize
                        }
                        val endOfStream = inputOffset >= pcm16.size
                        codec.queueInputBuffer(
                            inputIndex, 0, chunkSize, 0L,
                            if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        if (endOfStream) inputDone = true
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted && outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            check(muxerStarted) { "Encoder never produced an output format; nothing was muxed" }
            codec.stop()
            muxer.stop()
            result = MemoryFd.readAll(memoryFile)
        } catch (failure: Throwable) {
            Log.w(TAG, "AAC/memfd audio muxing failed; caller will fall back to WAV", failure)
            result = null
        } finally {
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            memoryFile?.let { MemoryFd.wipeAndClose(it, 0) }
        }
        return result?.takeIf { it.isNotEmpty() }
    }
}
