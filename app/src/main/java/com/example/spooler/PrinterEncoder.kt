package com.example.spooler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Converts high-resolution rendered Bitmaps into hardware printer command streams:
 * - ESC/POS Raster Bit Image (GS v 0) with Floyd-Steinberg dithering for thermal printers
 * - PCL 5 Raster Graphics (300 DPI) for HP LaserJet / office printers
 * - PostScript Level 2 image/colorimage for PS-capable laser printers
 * - Flattened 300 DPI PDF Stream for IPP / PDF-Direct printers
 */
object PrinterEncoder {

    /**
     * Converts a Bitmap into an ESC/POS raster stream (`ESC @` + `GS v 0` + feed/cut).
     */
    fun encodeToEscPosRaster(
        bitmap: Bitmap,
        paperWidthDots: Int = 576,
        cutPaper: Boolean = true
    ): ByteArray {
        val scaledHeight = ((bitmap.height.toFloat() / bitmap.width.toFloat()) * paperWidthDots)
            .roundToInt()
            .coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, paperWidthDots, scaledHeight, true)
        val width = resized.width
        val height = resized.height
        val widthBytes = (width + 7) / 8

        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        if (resized !== bitmap) resized.recycle()

        // Luminance buffer for Floyd-Steinberg error diffusion dithering
        val lum = FloatArray(width * height) { idx ->
            val c = pixels[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        val rasterData = ByteArray(widthBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldVal = lum[idx]
                val newVal = if (oldVal < 128f) 0f else 255f
                val err = oldVal - newVal

                if (newVal == 0f) {
                    val byteIndex = y * widthBytes + (x ushr 3)
                    val bitMask = 0x80 ushr (x and 7)
                    rasterData[byteIndex] = (rasterData[byteIndex].toInt() or bitMask).toByte()
                }

                if (x + 1 < width) lum[idx + 1] += err * (7f / 16f)
                if (y + 1 < height) {
                    val nextRow = (y + 1) * width
                    if (x - 1 >= 0) lum[nextRow + x - 1] += err * (3f / 16f)
                    lum[nextRow + x] += err * (5f / 16f)
                    if (x + 1 < width) lum[nextRow + x + 1] += err * (1f / 16f)
                }
            }
        }

        val out = ByteArrayOutputStream(rasterData.size + 32)
        // ESC @ (Initialize printer)
        out.write(byteArrayOf(0x1B, 0x40))
        // GS v 0 m xL xH yL yH (Print raster bit image, normal mode m=0)
        out.write(
            byteArrayOf(
                0x1D,
                0x76,
                0x30,
                0x00,
                (widthBytes and 0xFF).toByte(),
                ((widthBytes shr 8) and 0xFF).toByte(),
                (height and 0xFF).toByte(),
                ((height shr 8) and 0xFF).toByte()
            )
        )
        out.write(rasterData)
        // ESC d 4 (Feed 4 lines)
        out.write(byteArrayOf(0x1B, 0x64, 0x04))
        if (cutPaper) {
            // GS V 66 0 (Feed and partial cut)
            out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        }
        return out.toByteArray()
    }

    /**
     * Converts a 300 DPI Bitmap into PCL 5 monochrome raster graphics commands
     * (`ESC E`, page size, `ESC *t300R`, `ESC *r1A`, `ESC *b<N>W`, `ESC *rB`, Form Feed).
     */
    fun encodeToPclRaster(
        bitmap: Bitmap,
        paperSize: PaperSize,
        dpi: Int = 300
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = ByteArrayOutputStream(widthBytes * height / 2 + 256)
        // ESC E (Reset)
        out.write("\u001BE".toByteArray(Charsets.US_ASCII))
        // Page size command if standard sheet
        if (paperSize.pclCode > 0) {
            out.write("\u001B&l${paperSize.pclCode}A".toByteArray(Charsets.US_ASCII))
        }
        // Top margin 0, set raster resolution, start raster graphics
        out.write("\u001B&l0E\u001B*t${dpi}R\u001B*r1A".toByteArray(Charsets.US_ASCII))

        val rowBytes = ByteArray(widthBytes)
        for (y in 0 until height) {
            rowBytes.fill(0)
            val rowOffset = y * width
            for (x in 0 until width) {
                val c = pixels[rowOffset + x]
                val lum = (((c shr 16) and 0xFF) * 30 +
                    ((c shr 8) and 0xFF) * 59 +
                    (c and 0xFF) * 11) / 100
                // Ordered 2x2 Bayer threshold for crisp PCL text & halftones
                val bayer = when (((y and 1) shl 1) or (x and 1)) {
                    0 -> 64
                    1 -> 192
                    2 -> 128
                    else -> 224
                }
                if (lum < bayer) {
                    val bIdx = x ushr 3
                    rowBytes[bIdx] = (rowBytes[bIdx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            out.write("\u001B*b${widthBytes}W".toByteArray(Charsets.US_ASCII))
            out.write(rowBytes)
        }

        // End raster graphics + Form Feed + Printer Reset
        out.write("\u001B*rB\u000C\u001BE".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * Converts a rendered Bitmap into a PostScript Level 2 page stream.
     */
    fun encodeToPostScript(
        bitmap: Bitmap,
        paperSize: PaperSize,
        orientation: PageOrientation,
        color: Boolean
    ): ByteArray {
        val ptW = ((if (orientation == PageOrientation.LANDSCAPE && !paperSize.isThermalRoll) {
            paperSize.heightMm
        } else {
            paperSize.widthMm
        }) / 25.4f * 72f).roundToInt()
        val ptH = ((if (orientation == PageOrientation.LANDSCAPE && !paperSize.isThermalRoll) {
            paperSize.widthMm
        } else {
            paperSize.heightMm
        }) / 25.4f * 72f).roundToInt()

        // Scale to max 1800px edge for compact PostScript ASCII-hex spooling
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        val psBitmap = if (maxEdge > 1800) {
            val scale = 1800f / maxEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val w = psBitmap.width
        val h = psBitmap.height
        val pixels = IntArray(w * h)
        psBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (psBitmap !== bitmap) psBitmap.recycle()

        val sb = StringBuilder(w * h * if (color) 6 else 2)
        sb.append("%!PS-Adobe-3.0\n")
        sb.append("%%BoundingBox: 0 0 $ptW $ptH\n")
        sb.append("gsave\n")
        sb.append("$ptW $ptH scale\n")
        sb.append("/picstr $w ${if (color) 3 else 1} mul string def\n")
        sb.append("$w $h 8 [$w 0 0 -$h 0 $h]\n")
        sb.append("{ currentfile picstr readhexstring pop } ")
        if (color) {
            sb.append("false 3 colorimage\n")
        } else {
            sb.append("image\n")
        }

        val hexChars = "0123456789ABCDEF"
        var col = 0
        for (pixel in pixels) {
            if (color) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sb.append(hexChars[r ushr 4]).append(hexChars[r and 0xF])
                sb.append(hexChars[g ushr 4]).append(hexChars[g and 0xF])
                sb.append(hexChars[b ushr 4]).append(hexChars[b and 0xF])
                col += 6
            } else {
                val lum = (((pixel shr 16) and 0xFF) * 30 +
                    ((pixel shr 8) and 0xFF) * 59 +
                    (pixel and 0xFF) * 11) / 100
                sb.append(hexChars[lum ushr 4]).append(hexChars[lum and 0xF])
                col += 2
            }
            if (col >= 72) {
                sb.append('\n')
                col = 0
            }
        }
        sb.append("\nshowpage\ngrestore\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * Wraps already-flattened 300 DPI Bitmaps into a portable PDF stream via Android PdfDocument,
     * ensuring all signatures, stamps, and fonts remain 100% intact.
     */
    fun encodeBitmapsToPdfStream(
        bitmaps: List<Bitmap>,
        paperSize: PaperSize,
        orientation: PageOrientation
    ): ByteArray {
        val ptW = ((if (orientation == PageOrientation.LANDSCAPE && !paperSize.isThermalRoll) {
            paperSize.heightMm
        } else {
            paperSize.widthMm
        }) / 25.4f * 72f).roundToInt().coerceAtLeast(72)
        val ptH = ((if (orientation == PageOrientation.LANDSCAPE && !paperSize.isThermalRoll) {
            paperSize.widthMm
        } else {
            paperSize.heightMm
        }) / 25.4f * 72f).roundToInt().coerceAtLeast(72)

        val pdfDoc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val out = ByteArrayOutputStream()

        try {
            bitmaps.forEachIndexed { idx, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(ptW, ptH, idx + 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, ptW.toFloat(), ptH.toFloat()), paint)
                pdfDoc.finishPage(page)
            }
            pdfDoc.writeTo(out)
        } finally {
            pdfDoc.close()
        }
        return out.toByteArray()
    }
}
