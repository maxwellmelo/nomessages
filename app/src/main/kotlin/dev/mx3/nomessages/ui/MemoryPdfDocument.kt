package dev.mx3.nomessages.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val MAX_PDF_BYTES = 16 * 1024 * 1024
internal const val MAX_PDF_PAGES = 128
private const val MAX_PDF_RENDER_DIMENSION = 2_048
private const val MAX_PDF_RENDER_PIXELS = 4_000_000L

/** A PDF backed only by an anonymous RAM file. It never creates a filesystem path. */
internal class MemoryPdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val memoryFile: FileDescriptor,
    private val byteCount: Int,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val rendererLock = Any()

    val pageCount: Int = renderer.pageCount

    fun renderPage(index: Int): Bitmap {
        check(!closed.get()) { "PDF is closed" }
        require(index in 0 until pageCount) { "Page index is out of bounds" }
        synchronized(rendererLock) {
            check(!closed.get()) { "PDF is closed" }
            renderer.openPage(index).use { page ->
                val pageWidth = page.width
                val pageHeight = page.height
                require(pageWidth > 0 && pageHeight > 0) { "PDF page has invalid dimensions" }
                val scale = min(
                    1.0,
                    min(
                        MAX_PDF_RENDER_DIMENSION.toDouble() / maxOf(pageWidth, pageHeight),
                        sqrt(MAX_PDF_RENDER_PIXELS.toDouble() / (pageWidth.toDouble() * pageHeight)),
                    ),
                )
                val width = (pageWidth * scale).roundToInt().coerceAtLeast(1)
                val height = (pageHeight * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                } catch (failure: Throwable) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeResources()
    }

    private fun closeResources() {
        synchronized(rendererLock) {
            try {
                renderer.close()
            } finally {
                MemoryFd.wipeAndClose(memoryFile, byteCount)
            }
        }
    }

    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        try {
            Thread({ closeResources() }, "nomessages-pdf-close").apply { isDaemon = true }.start()
        } catch (_: RuntimeException) {
            runCatching(::closeResources)
        } catch (_: OutOfMemoryError) {
            runCatching(::closeResources)
        }
    }

    companion object {
        fun open(bytes: ByteArray): MemoryPdfDocument {
            require(bytes.isNotEmpty() && bytes.size <= MAX_PDF_BYTES) { "PDF size is out of bounds" }
            val memoryFile = MemoryFd.create(bytes, name = "nomessages-pdf")
            var renderer: PdfRenderer? = null
            try {
                val rendererDescriptor = ParcelFileDescriptor.dup(memoryFile)
                renderer = try {
                    PdfRenderer(rendererDescriptor)
                } catch (failure: Throwable) {
                    rendererDescriptor.close()
                    throw failure
                }
                require(renderer.pageCount in 1..MAX_PDF_PAGES) { "PDF page count is out of bounds" }
                return MemoryPdfDocument(renderer, memoryFile, bytes.size)
            } catch (failure: Throwable) {
                runCatching { renderer?.close() }
                MemoryFd.wipeAndClose(memoryFile, bytes.size)
                throw failure
            }
        }
    }
}
