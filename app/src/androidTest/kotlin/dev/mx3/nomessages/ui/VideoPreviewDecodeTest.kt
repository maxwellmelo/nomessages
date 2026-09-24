package dev.mx3.nomessages.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileDescriptor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises poster-frame + duration extraction - `MediaMetadataRetriever` over the in-memory
 * [MemoryMediaDataSource], the exact path `NoMessagesController.loadVideoPreview` uses - against a tiny
 * synthetic H.264/MP4 clip generated on-device with `MediaCodec` + `MediaMuxer` writing to a `memfd`
 * (via [MemoryFd], mirroring `MemoryAudioEncoder`'s in-memory muxing for the audio path). Needs a
 * real device/emulator: the JVM unit test sandbox cannot provide a working `MediaCodec` video
 * encoder.
 *
 * Buffer-mode H.264 video encoding is considerably less portable across devices than the audio path
 * (raw color-format expectations vary by encoder), so - exactly like `MemoryAudioEncoderTest` treats
 * a null `MemoryAudioEncoder.encode` result as acceptable - this test treats a failed/empty synthetic
 * encode as an acceptable outcome and only asserts the actual decode-side contract (a non-null,
 * bounded poster bitmap and a duration in the right ballpark) when the synthetic clip was produced
 * successfully. Either way the call above must return rather than throw or hang.
 */
@RunWith(AndroidJUnit4::class)
class VideoPreviewDecodeTest {
    private val width = 64
    private val height = 64
    private val frameCount = 8
    private val frameRateHz = 4

    @Test
    fun decodesPosterAndDurationFromSyntheticClipOrSkipsCleanly() {
        val clip = encodeSyntheticMp4() ?: return

        val dataSource = MemoryMediaDataSource(clip)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(dataSource)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            val poster = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            assertTrue("Expected a decodable poster frame from the synthetic clip", poster != null)
            checkNotNull(poster)
            assertTrue("Poster bitmap should have positive dimensions", poster.width > 0 && poster.height > 0)
            assertTrue("Poster's longer side should stay within the 512px bound", maxOf(poster.width, poster.height) <= 512)
            // frameCount frames at frameRateHz is a 2s clip; generous tolerance for encoder/muxer framing.
            assertTrue("Decoded duration ${durationMs}ms should be close to the encoded 2000ms", durationMs in 500..4000)
            poster.recycle()
        } finally {
            retriever.release()
            dataSource.close()
        }
    }

    /**
     * Encodes [frameCount] solid, slowly-changing-luma frames into a minimal H.264/MP4 clip, entirely
     * in memory (`MediaCodec` -> `MediaMuxer` -> `memfd`, exactly like `MemoryAudioEncoder` does for
     * AAC). Returns null instead of throwing on any failure - e.g. this device's encoder only
     * accepting `Surface` input rather than the raw buffer input requested below - since this fixture
     * is test-only scaffolding, not a path any real user's message send depends on (unlike the AAC
     * encoder, which the real send path falls back away from on failure).
     */
    private fun encodeSyntheticMp4(): ByteArray? {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var memoryFile: FileDescriptor? = null
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 250_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRateHz)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(mime).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            memoryFile = MemoryFd.createEmpty(name = "nomessages-video-test")
            muxer = MediaMuxer(memoryFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val frameSize = width * height * 3 / 2
            var trackIndex = -1
            var muxerStarted = false
            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / frameRateHz

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex)) { "No encoder input buffer" }
                        inputBuffer.clear()
                        val endOfStream = frameIndex >= frameCount
                        if (!endOfStream) inputBuffer.put(solidYuv420(frameIndex))
                        val presentationTimeUs = frameIndex * frameDurationUs
                        codec.queueInputBuffer(
                            inputIndex, 0, if (endOfStream) 0 else frameSize, presentationTimeUs,
                            if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        frameIndex++
                        if (endOfStream) inputDone = true
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
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
            MemoryFd.readAll(memoryFile).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            memoryFile?.let { MemoryFd.wipeAndClose(it, 0) }
        }
    }

    /** A flat I420-ish buffer (luma ramps across frames, chroma neutral) - content doesn't matter, only decodability. */
    private fun solidYuv420(frameIndex: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        val luma = (40 + frameIndex * 20).coerceIn(0, 255).toByte()
        for (i in 0 until ySize) out[i] = luma
        for (i in ySize until out.size) out[i] = 128.toByte()
        return out
    }
}
