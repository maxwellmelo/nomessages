package dev.mx3.nomessages.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryPdfDocumentTest {
    @Test
    fun rendersGeneratedPagesFromAnonymousMemory() {
        val encoded = createTwoPagePdf()
        try {
            MemoryPdfDocument.open(encoded).use { document ->
                assertEquals(2, document.pageCount)
                repeat(document.pageCount) { index ->
                    val bitmap = document.renderPage(index)
                    try {
                        assertFalse(bitmap.isRecycled)
                        assertTrue(bitmap.width in 1..2_048)
                        assertTrue(bitmap.height in 1..2_048)
                        assertTrue(bitmap.width.toLong() * bitmap.height <= 4_000_000L)
                    } finally {
                        bitmap.eraseColor(Color.TRANSPARENT)
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            encoded.fill(0)
        }
    }

    private fun createTwoPagePdf(): ByteArray {
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawText("NoMessages page ${index + 1}", 48f, 96f, Paint().apply { textSize = 24f })
                pdf.finishPage(page)
            }
            return ByteArrayOutputStream().use { output ->
                pdf.writeTo(output)
                output.toByteArray()
            }
        } finally {
            pdf.close()
        }
    }
}
