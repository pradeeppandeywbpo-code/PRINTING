package com.example.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates an on-device multi-page sample PDF containing vector shapes,
 * transparent approval stamps, and handwritten-style signature strokes so users
 * can immediately test 300 DPI PdfRenderer flattening and spooler output.
 */
object SamplePdfGenerator {

    suspend fun createSampleSignedPdf(context: Context): Uri = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "sample_signed_workorder.pdf")
        val document = PdfDocument()

        try {
            // Page 1: Work Order + Transparent Stamp + Digital Signature
            val pageInfo1 = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page1 = document.startPage(pageInfo1)
            drawPageOne(page1.canvas)
            document.finishPage(page1)

            // Page 2: Technical Specs & Dither Test Pattern
            val pageInfo2 = PdfDocument.PageInfo.Builder(595, 842, 2).create()
            val page2 = document.startPage(pageInfo2)
            drawPageTwo(page2.canvas)
            document.finishPage(page2)

            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
            }
        } finally {
            document.close()
        }

        Uri.fromFile(outputFile)
    }

    private fun drawPageOne(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
        }
        canvas.drawRect(36f, 36f, 559f, 118f, headerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("PRINTFORGE DISPATCH & WORK ORDER", 54f, 76f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("DOC #PF-2026-0841  ·  300 DPI RASTER VERIFICATION", 54f, 100f, subPaint)

        val bodyTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 11.5f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1.2f
        }

        canvas.drawText("Hardware Verification Checklist", 40f, 158f, bodyTitle)
        canvas.drawLine(40f, 168f, 555f, 168f, linePaint)

        val items = listOf(
            "1. USB OTG Host Mode Bulk OUT Endpoint Transfer (16 KB chunks)",
            "2. Wi-Fi mDNS / IPP 2.0 & Raw Socket Port 9100 Stream",
            "3. Bluetooth Classic SPP RFCOMM Thermal Receipt Driver (ESC/POS)",
            "4. Flattened Digital Signature & Alpha Stamp Preservation at 300 DPI",
            "5. Grayscale & Floyd-Steinberg Halftone Dither Compatibility"
        )
        var y = 196f
        items.forEach { item ->
            canvas.drawText(item, 44f, y, bodyText)
            canvas.drawLine(44f, y + 10f, 550f, y + 10f, linePaint)
            y += 32f
        }

        // Color calibration swatches
        canvas.drawText("Color & Grayscale Calibration Bar", 40f, 385f, bodyTitle)
        val swatchColors = listOf("#0057FF", "#0EA5E9", "#059669", "#D97706", "#DC2626", "#0F172A")
        swatchColors.forEachIndexed { idx, hex ->
            val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(hex)
            }
            val left = 40f + idx * 84f
            canvas.drawRoundRect(RectF(left, 400f, left + 74f, 448f), 8f, 8f, swatchPaint)
        }

        // Translucent Approved Stamp (tests alpha flattening in PdfRenderer)
        canvas.save()
        canvas.rotate(-12f, 430f, 555f)
        val stampBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 5, 150, 105)
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        val stampFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(32, 5, 150, 105)
            style = Paint.Style.FILL
        }
        val stampText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 5, 150, 105)
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val stampRect = RectF(325f, 520f, 535f, 590f)
        canvas.drawRoundRect(stampRect, 10f, 10f, stampFill)
        canvas.drawRoundRect(stampRect, 10f, 10f, stampBorder)
        canvas.drawText("VERIFIED · SIGNED", 430f, 554f, stampText)
        stampText.textSize = 10f
        canvas.drawText("SHA-256 FLATTENED LAYER", 430f, 574f, stampText)
        canvas.restore()

        // Signature Box with vector ink stroke
        val sigBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        canvas.drawRoundRect(RectF(40f, 640f, 340f, 750f), 8f, 8f, sigBox)
        canvas.drawText("Authorized Digital Signature", 52f, 660f, bodyText)

        val sigStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0057FF")
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val sigPath = Path().apply {
            moveTo(68f, 718f)
            cubicTo(90f, 676f, 112f, 738f, 140f, 694f)
            cubicTo(155f, 672f, 168f, 725f, 200f, 704f)
            cubicTo(222f, 690f, 250f, 718f, 305f, 696f)
        }
        canvas.drawPath(sigPath, sigStroke)
    }

    private fun drawPageTwo(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("Page 2 · Grayscale Gradient & Line Resolution Test", 40f, 64f, title)

        // Monochrome step gradient for Floyd-Steinberg dithering verification
        for (i in 0 until 10) {
            val shade = (i * 25).coerceIn(0, 255)
            val p = Paint().apply { color = Color.rgb(shade, shade, shade) }
            val left = 40f + i * 51f
            canvas.drawRect(left, 95f, left + 48f, 185f, p)
        }

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(40f, 220f, 555f, 760f, gridPaint)
        for (x in 80..520 step 40) {
            canvas.drawLine(x.toFloat(), 220f, x.toFloat(), 760f, gridPaint)
        }
        for (y in 260..720 step 40) {
            canvas.drawLine(40f, y.toFloat(), 555f, y.toFloat(), gridPaint)
        }
    }
}
