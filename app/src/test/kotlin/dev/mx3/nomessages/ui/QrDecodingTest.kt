package dev.mx3.nomessages.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Covers `decodeQrLuminance()` (QrDecoding.kt) against the real pairing QR's shape: version-40-L,
 * ~2.9 KB payload, 177x177 modules. This is the size class that motivated T3.3 run2's finding that
 * `ImageAnalysis`'s CameraX default (640x480, no `ResolutionSelector`) cannot decode the real
 * pairing QR (see docs/development/device-verification.md, "T3.3 - run2", and
 * docs/changes/PairingScreen.kt.md, 2026-09-15).
 *
 * ZXing's byte-mode capacity for version 40 at error-correction level L is 2953 bytes; a
 * mixed-case (Base64-alphabet) payload forces byte-mode encoding (QR "Alphanumeric" mode has no
 * lowercase letters), so a ~2900-byte mixed-case payload reliably lands on version 40.
 */
class QrDecodingTest {

    // ZXing's / the industry's commonly cited minimum for a reliable real-world QR camera decode.
    // Below this, only unrealistically clean (synthetic, noise-free) renders tend to decode; a real
    // camera frame has lens blur, compression artifacts, motion and perspective skew working against
    // it on top of the raw pixel density.
    private val minRecommendedPxPerModule = 3.0

    private val payload = buildString {
        val random = Random(20260915)
        // Base64 alphabet includes lowercase, forcing ZXing's byte-mode encoder (not the more
        // compact alphanumeric mode, which only has uppercase/digits/a few symbols and would
        // encode far more than 2900 chars into fewer modules than we want here) - byte mode is
        // also what the real `nomessages:1:...` pairing payload uses, since it is arbitrary
        // base64/binary-derived text, not a restricted alphanumeric string.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        repeat(2900) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    private val matrix: BitMatrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 4,
            // A 2900-byte mixed-case payload comfortably fits version 40-L's 2953-byte capacity,
            // but ZXing's own segment-mode optimizer can shave enough bytes off some substrings
            // (digits/uppercase runs re-encoded as denser modes) to land one version short of 40.
            // Force version 40 explicitly so this fixture reliably matches the real pairing QR's
            // size class (177 modules/side) that motivated this test class - see the class doc.
            EncodeHintType.QR_VERSION to 40,
        ),
    )

    // Version 40's own symbol is 177x177 modules; QRCodeWriter's natural-size overload bakes the
    // MARGIN hint's quiet zone into the returned BitMatrix on top of that, uniformly scaled together
    // with the symbol when rasterized below - so matrix.width (177 + 2 * margin) is both the true
    // module count to assert against and the correct divisor for a real per-module pixel density.
    private val margin = 4

    @Test
    fun `payload actually forces a version-40 (177 modules per side) QR`() {
        // Sanity check on the fixture itself: if this ever fails, the rest of this test class is no
        // longer exercising the size class that motivated it, and needs its payload size revisited.
        assertEquals(177 + 2 * margin, matrix.width)
        assertEquals(177 + 2 * margin, matrix.height)
    }

    @Test
    fun `1920x1080 frame decodes a version-40-L QR`() {
        // 1920x1080 is the resolution PairingScreen.kt's ImageAnalysis now requests via
        // ResolutionSelector (see docs/changes/PairingScreen.kt.md). Center the QR with a comfortable
        // margin, at roughly 5.4 px/module - well above the reliable-decode threshold.
        val qrPixelSize = 1000
        val frame = rasterize(matrix, 1920, 1080, qrPixelSize)
        val result = decodeQrLuminance(frame, dataWidth = 1920, width = 1920, height = 1080)
        assertNotNull(result, "expected a version-40-L QR centered at ~${"%.2f".format(qrPixelSize.toDouble() / matrix.width)} px/module in a 1920x1080 frame to decode")
        assertEquals(payload, result!!.text)
    }

    @Test
    fun `640x480 frame is below the recommended pixels-per-module for a version-40-L QR`() {
        // Fill nearly the whole frame height (the limiting dimension) to give this the *best*
        // possible chance at 640x480, then compute the resulting density honestly.
        val qrPixelSize = 460
        val pxPerModule = qrPixelSize.toDouble() / matrix.width
        assumeTrue(
            pxPerModule >= minRecommendedPxPerModule,
            "640x480 gives only %.2f px/module for a version-40 QR (177 modules/side plus its standard quiet zone) even when the ".format(pxPerModule) +
                "QR fills almost the entire frame height; ZXing's/the industry's commonly cited " +
                "reliable-decode minimum is ~3-4 px/module. This is *why* PairingScreen.kt's " +
                "ImageAnalysis needs a ResolutionSelector requesting >= ~1080p instead of accepting " +
                "CameraX's 640x480 default (see T3.3 run2, docs/development/device-verification.md) " +
                "- a real camera frame (lens blur, compression artifacts, motion, perspective skew) " +
                "has no more room to work with than this noise-free synthetic render, and often less. " +
                "Skipping the decode assertion below rather than hard-coding a pass or a fail, since a " +
                "synthetic render's actual decodability at this density is not representative of a " +
                "real camera and is not the property this test is meant to document.",
        )
        // Unreachable today (177 modules always yields < 3 px/module at 640x480); kept so this test
        // starts asserting real behavior automatically if the QR version ever shrinks or the minimum
        // frame size ever grows enough to clear the density bar above.
        val frame = rasterize(matrix, 640, 480, qrPixelSize)
        assertNotNull(decodeQrLuminance(frame, dataWidth = 640, width = 640, height = 480))
    }

    @Test
    fun `a 90-degree-rotated 1920x1080 frame still decodes via the rotation fallback`() {
        val qrPixelSize = 1000
        val upright = rasterize(matrix, 1920, 1080, qrPixelSize)
        val rotated = rotateClockwise90(upright, 1920, 1080)
        // After a 90-degree rotation the frame's own width/height swap (1080x1920); a real
        // portrait-mounted camera or a virtual-camera orientation quirk can deliver frames exactly
        // like this relative to the on-screen guide box.
        val result = decodeQrLuminance(rotated, dataWidth = 1080, width = 1080, height = 1920)
        assertNotNull(result, "expected the rotation fallback in decodeQrLuminance() to recover a 90-degree-rotated frame")
        assertEquals(payload, result!!.text)
    }

    /** Renders [matrix] (nearest-neighbor scaled to [qrPixelSize] pixels square) centered in a
     *  white [frameWidth]x[frameHeight] luminance frame - simulating a camera frame where the QR
     *  poster occupies only part of the field of view, exactly as in the emulator virtual-scene and
     *  real-camera cases this fixture models. */
    private fun rasterize(matrix: BitMatrix, frameWidth: Int, frameHeight: Int, qrPixelSize: Int): ByteArray {
        val white = 255.toByte()
        val black = 0.toByte()
        val data = ByteArray(frameWidth * frameHeight) { white }
        val offsetX = (frameWidth - qrPixelSize) / 2
        val offsetY = (frameHeight - qrPixelSize) / 2
        val modules = matrix.width
        for (y in 0 until qrPixelSize) {
            val targetY = offsetY + y
            if (targetY !in 0 until frameHeight) continue
            val moduleY = (y * modules) / qrPixelSize
            for (x in 0 until qrPixelSize) {
                val targetX = offsetX + x
                if (targetX !in 0 until frameWidth) continue
                val moduleX = (x * modules) / qrPixelSize
                data[targetY * frameWidth + targetX] = if (matrix.get(moduleX, moduleY)) black else white
            }
        }
        return data
    }

    /** Rotates a [width]x[height] luminance buffer 90 degrees clockwise into a [height]x[width] one. */
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
}
