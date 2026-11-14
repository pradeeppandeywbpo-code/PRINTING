package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.PageOrientation
import com.example.domain.model.PageRangeParser
import com.example.domain.model.PaperSize
import com.example.printer.network.NetworkPrinterTransport
import com.example.spooler.PrinterEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches PrintForge`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PrintForge", appName)
    }

    @Test
    fun `paper size 300 dpi dimensions are accurate`() {
        val a4W = PaperSize.A4.widthPx(300, PageOrientation.PORTRAIT)
        val a4H = PaperSize.A4.heightPx(300, PageOrientation.PORTRAIT)
        assertTrue("A4 width at 300 DPI should be ~2480", a4W in 2475..2485)
        assertTrue("A4 height at 300 DPI should be ~3508", a4H in 3500..3512)

        val letterW = PaperSize.LETTER.widthPx(300, PageOrientation.LANDSCAPE)
        val letterH = PaperSize.LETTER.heightPx(300, PageOrientation.LANDSCAPE)
        assertEquals(3300, letterW)
        assertEquals(2550, letterH)
    }

    @Test
    fun `page range parser parses custom expressions and rejects out of bounds`() {
        val parsed = PageRangeParser.parseZeroBasedIndices("1-2, 4", totalPages = 5, useAllPages = false)
        assertEquals(listOf(0, 1, 3), parsed.getOrThrow())

        val invalid = PageRangeParser.parseZeroBasedIndices("1-8", totalPages = 5, useAllPages = false)
        assertTrue(invalid.isFailure)
    }

    @Test
    fun `escpos pcl and postscript encoders produce valid headers`() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(10, 10, Color.BLACK)
        }

        val escPos = PrinterEncoder.encodeToEscPosRaster(bmp, paperWidthDots = 384, cutPaper = true)
        assertEquals(0x1B.toByte(), escPos[0])
        assertEquals(0x40.toByte(), escPos[1])
        assertEquals(0x1D.toByte(), escPos[2])
        assertEquals(0x76.toByte(), escPos[3])

        val pcl = PrinterEncoder.encodeToPclRaster(bmp, PaperSize.A4, dpi = 300)
        val pclAscii = pcl.toString(Charsets.US_ASCII)
        assertTrue(pclAscii.startsWith("\u001BE"))
        assertTrue(pclAscii.contains("\u001B*t300R"))

        val ps = PrinterEncoder.encodeToPostScript(bmp, PaperSize.LETTER, PageOrientation.PORTRAIT, color = false)
        val psText = ps.toString(Charsets.US_ASCII)
        assertTrue(psText.startsWith("%!PS-Adobe-3.0"))
        assertTrue(psText.contains("showpage"))

        val ippPacket = NetworkPrinterTransport().buildIppPrintJobPacket(
            printerUri = "ipp://192.168.1.50:631/ipp/print",
            jobName = "TestJob",
            documentFormat = "application/pdf",
            documentBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        )
        assertEquals(0x02.toByte(), ippPacket[0])
        assertEquals(0x00.toByte(), ippPacket[1])
        assertEquals(0x00.toByte(), ippPacket[2])
        assertEquals(0x02.toByte(), ippPacket[3])

        bmp.recycle()
    }
}
