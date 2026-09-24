package dev.mx3.nomessages.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/**
 * Pure, platform-independent QR decode core shared by the CameraX analyzer (see
 * `ImageProxy.decodeQr()` in PairingScreen.kt) and the JVM unit test (QrDecodingTest.kt).
 *
 * Takes a single-channel luminance (Y) plane and tries every combination of rotation (0/90/180/270
 * degrees) and polarity (normal/inverted) before giving up. Both axes matter in practice: a camera
 * frame's rotation relative to the on-screen guide box cannot be assumed (front/back camera,
 * device/virtual-camera orientation quirks - confirmed on the T3.3 run2/run3 emulator: a captured
 * analysis frame decoded independently at ~-89 degrees), and some camera sources deliver inverted
 * luminance.
 *
 * The rotation here is done by hand on the raw byte buffer, NOT via
 * `LuminanceSource.rotateCounterClockwise()`/`isRotateSupported()`. The original implementation
 * called those on a [PlanarYUVLuminanceSource] expecting them to rotate the frame, but
 * [PlanarYUVLuminanceSource] does not override either method (confirmed by disassembling this
 * project's pinned `com.google.zxing:core:3.5.4` - it only overrides `isCropSupported()`/`crop()`),
 * so it silently inherits `LuminanceSource`'s defaults (`isRotateSupported()` returns `false`,
 * `rotateCounterClockwise()` throws if called). That made the original single-rotation fallback
 * (`if (source.isRotateSupported) ...`) dead code: it never actually rotated anything, in any
 * zxing-core version where `PlanarYUVLuminanceSource` lacks these overrides. This was the direct,
 * confirmed cause of the "never decodes" failure reported in T3.3 run2: an emulator analysis frame
 * that visibly contained a complete, high-contrast, independently-decodable QR code (verified with
 * `zxing-cpp`) was never even attempted at its actual ~90-degree-rotated orientation.
 *
 * @param data luminance bytes, row-major, [dataWidth] bytes per row. [dataWidth] may exceed [width]
 *   when the producer pads rows (e.g. a camera plane's `rowStride` after de-interleaving pixelStride
 *   > 1, or a Y plane whose rowStride already exceeds the visible width).
 * @param dataWidth number of bytes per row in [data] (must be >= [width]).
 * @param width visible width in pixels.
 * @param height visible height in pixels.
 * @return the decoded [Result], or null if no rotation/polarity combination decoded successfully.
 */
internal fun decodeQrLuminance(data: ByteArray, dataWidth: Int, width: Int, height: Int): Result? {
    val reader = MultiFormatReader()
    reader.setHints(
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, true)
        },
    )
    try {
        var currentData = if (dataWidth == width) data else compact(data, dataWidth, width, height)
        var currentWidth = width
        var currentHeight = height
        for (rotation in 0..3) {
            if (rotation > 0) {
                currentData = rotateClockwise90(currentData, currentWidth, currentHeight)
                val swapped = currentWidth
                currentWidth = currentHeight
                currentHeight = swapped
            }
            val source = PlanarYUVLuminanceSource(currentData, currentWidth, currentHeight, 0, 0, currentWidth, currentHeight, false)
            for (candidate in listOf(source, source.invert())) {
                val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))) }.getOrNull()
                reader.reset()
                if (result != null) return result
            }
        }
        return null
    } finally {
        reader.reset()
    }
}

/** Drops row padding: copies a [width]x[height] region out of a buffer whose rows are [dataWidth]
 *  bytes wide ([dataWidth] >= [width]) into a tightly packed [width] * [height] buffer. */
private fun compact(data: ByteArray, dataWidth: Int, width: Int, height: Int): ByteArray {
    val out = ByteArray(width * height)
    for (row in 0 until height) {
        System.arraycopy(data, row * dataWidth, out, row * width, width)
    }
    return out
}

/** Rotates a tightly packed [width]x[height] luminance buffer 90 degrees clockwise into a
 *  [height]x[width] one. Manual byte-level rotation - see the class doc comment above for why this
 *  cannot use [com.google.zxing.LuminanceSource.rotateCounterClockwise]. */
private fun rotateClockwise90(data: ByteArray, width: Int, height: Int): ByteArray {
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val newX = height - 1 - y
            val newY = x
            out[newY * height + newX] = data[y * width + x]
        }
    }
    return out
}
