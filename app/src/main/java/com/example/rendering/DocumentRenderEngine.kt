package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.domain.model.ColorMode
import com.example.domain.model.DocumentSource
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Core Document Rendering Engine.
 *
 * Delegates PDFs (via Android PdfRenderer with RENDER_MODE_FOR_PRINT), JPG/PNG images
 * (via [PdfRenderingEngine.imageProcessor]), and WebView page captures to [PdfRenderingEngine]
 * so all document types render onto opaque white 300 DPI Bitmaps sized to the selected
 * [PaperSize] and [PageOrientation].
 */
class DocumentRenderEngine(context: Context) {

    val pdfRenderingEngine = PdfRenderingEngine(context)
    val imageProcessor: ImageProcessor
        get() = pdfRenderingEngine.imageProcessor

    companion object {
        const val PRINT_DPI = PdfRenderingEngine.DEFAULT_PRINT_DPI
        const val PREVIEW_DPI = 120
    }

    suspend fun getPdfPageCount(uri: Uri): Result<Int> = pdfRenderingEngine.getPageCount(uri)

    suspend fun renderPage(
        source: DocumentSource,
        pageIndex: Int,
        paperSize: PaperSize,
        orientation: PageOrientation,
        colorMode: ColorMode,
        dpi: Int = PRINT_DPI
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            when (source) {
                is DocumentSource.Pdf -> {
                    val rawSheet = pdfRenderingEngine.renderPageToBitmap(
                        uri = source.uri,
                        pageIndex = pageIndex,
                        dpi = dpi,
                        paperSize = paperSize,
                        orientation = orientation
                    ).getOrThrow()

                    if (colorMode == ColorMode.GRAYSCALE) {
                        try {
                            imageProcessor.toGrayscaleBitmap(rawSheet)
                        } finally {
                            if (!rawSheet.isRecycled) rawSheet.recycle()
                        }
                    } else {
                        rawSheet
                    }
                }
                is DocumentSource.Image -> {
                    pdfRenderingEngine.renderImageToBitmap(
                        uri = source.uri,
                        dpi = dpi,
                        paperSize = paperSize,
                        orientation = orientation,
                        colorMode = colorMode
                    ).getOrThrow()
                }
                is DocumentSource.WebCapture -> {
                    val filePath = source.pageFilePaths.getOrElse(pageIndex) {
                        throw IndexOutOfBoundsException("Web page $pageIndex not found")
                    }
                    val decoded = BitmapFactory.decodeFile(filePath)
                        ?: throw IOException("Failed to decode captured web page: $filePath")
                    try {
                        pdfRenderingEngine.processBitmapForPrint(
                            source = decoded,
                            paperSize = paperSize,
                            orientation = orientation,
                            dpi = dpi,
                            colorMode = colorMode
                        )
                    } finally {
                        if (!decoded.isRecycled) decoded.recycle()
                    }
                }
            }
        }
    }

    fun toGrayscaleBitmap(source: Bitmap): Bitmap = imageProcessor.toGrayscaleBitmap(source)
}
