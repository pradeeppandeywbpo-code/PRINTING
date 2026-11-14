package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.domain.model.ColorMode
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dedicated PDF and high-resolution raster rendering engine powered by Android's
 * [PdfRenderer] API and the embedded [ImageProcessor] module.
 *
 * Converts PDF document pages and JPG/PNG files into high-resolution 300 DPI [Bitmap]s
 * over an opaque white canvas so that flattened elements such as digital signatures,
 * translucent stamps, alpha-channel PNGs, vector annotations, and custom fonts are
 * preserved faithfully for printing.
 */
class PdfRenderingEngine(private val context: Context) {

    /**
     * Integrated [ImageProcessor] module for converting JPG and PNG files into
     * 300 DPI print-ready [Bitmap]s that match the PDF rendering workflow.
     */
    val imageProcessor: ImageProcessor = ImageProcessor(context)

    companion object {
        const val DEFAULT_PRINT_DPI = 300
        const val PDF_POINTS_PER_INCH = 72f
    }

    suspend fun getPageCount(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            openParcelFileDescriptor(uri).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        }
    }

    suspend fun getPageCount(file: File): Result<Int> = getPageCount(Uri.fromFile(file))

    /**
     * Renders a single PDF page at [dpi] (default 300 DPI) into an [ARGB_8888] [Bitmap].
     *
     * When [paperSize] is provided, the rendered page is aspect-fitted onto the target
     * paper sheet dimensions at [dpi] and [orientation]. When [paperSize] is null, the
     * output dimensions are computed directly from the PDF page's 72-pt dimensions scaled
     * by `dpi / 72f`.
     */
    suspend fun renderPageToBitmap(
        uri: Uri,
        pageIndex: Int,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            require(dpi > 0) { "DPI must be positive, was $dpi" }
            openParcelFileDescriptor(uri).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderOpenPage(
                        renderer = renderer,
                        pageIndex = pageIndex,
                        dpi = dpi,
                        paperSize = paperSize,
                        orientation = orientation
                    )
                }
            }
        }
    }

    suspend fun renderPageToBitmap(
        file: File,
        pageIndex: Int,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT
    ): Result<Bitmap> = renderPageToBitmap(
        uri = Uri.fromFile(file),
        pageIndex = pageIndex,
        dpi = dpi,
        paperSize = paperSize,
        orientation = orientation
    )

    /**
     * Renders multiple PDF pages from a single [PdfRenderer] session at [dpi].
     */
    suspend fun renderPagesToBitmaps(
        uri: Uri,
        pageIndices: List<Int>? = null,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT
    ): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
        runCatching {
            require(dpi > 0) { "DPI must be positive, was $dpi" }
            openParcelFileDescriptor(uri).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val indices = pageIndices ?: (0 until renderer.pageCount).toList()
                    val bitmaps = mutableListOf<Bitmap>()
                    try {
                        for (index in indices) {
                            bitmaps.add(
                                renderOpenPage(
                                    renderer = renderer,
                                    pageIndex = index,
                                    dpi = dpi,
                                    paperSize = paperSize,
                                    orientation = orientation
                                )
                            )
                        }
                        bitmaps
                    } catch (t: Throwable) {
                        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                        throw t
                    }
                }
            }
        }
    }

    private fun renderOpenPage(
        renderer: PdfRenderer,
        pageIndex: Int,
        dpi: Int,
        paperSize: PaperSize?,
        orientation: PageOrientation
    ): Bitmap {
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            throw IndexOutOfBoundsException(
                "Requested page ${pageIndex + 1} out of ${renderer.pageCount}"
            )
        }

        return renderer.openPage(pageIndex).use { page ->
            val pageWidthPt = page.width.toFloat().coerceAtLeast(1f)
            val pageHeightPt = page.height.toFloat().coerceAtLeast(1f)

            val targetWidth: Int
            val targetHeight: Int
            val matrix = Matrix()

            if (paperSize != null) {
                targetWidth = paperSize.widthPx(dpi, orientation)
                targetHeight = paperSize.heightPx(dpi, orientation)

                // Fit PDF page cleanly inside target paper sheet with a 2% print margin
                val marginX = targetWidth * 0.02f
                val marginY = targetHeight * 0.02f
                val availW = (targetWidth - 2f * marginX).coerceAtLeast(1f)
                val availH = (targetHeight - 2f * marginY).coerceAtLeast(1f)

                val scale = min(availW / pageWidthPt, availH / pageHeightPt)
                val dx = (targetWidth - pageWidthPt * scale) / 2f
                val dy = (targetHeight - pageHeightPt * scale) / 2f

                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            } else {
                val scale = dpi / PDF_POINTS_PER_INCH
                targetWidth = (pageWidthPt * scale).roundToInt().coerceAtLeast(1)
                targetHeight = (pageHeightPt * scale).roundToInt().coerceAtLeast(1)
                matrix.setScale(scale, scale)
            }

            val bitmap = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
            )
            // Mandatory opaque white background before PdfRenderer.Page.render so transparent
            // signatures, stamps, and vector layers flatten cleanly without black/missing pixels.
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(
                bitmap,
                null,
                matrix,
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            )
            bitmap
        }
    }

    /**
     * Converts a JPG or PNG image [uri] via the embedded [imageProcessor] into a
     * 300 DPI print-ready [Bitmap], matching the PDF printing workflow.
     */
    suspend fun renderImageToBitmap(
        uri: Uri,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        colorMode: ColorMode = ColorMode.COLOR,
        sourceDpi: Float = PDF_POINTS_PER_INCH
    ): Result<Bitmap> = imageProcessor.processImageToBitmap(
        uri = uri,
        dpi = dpi,
        paperSize = paperSize,
        orientation = orientation,
        colorMode = colorMode,
        sourceDpi = sourceDpi
    )

    suspend fun renderImageToBitmap(
        file: File,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        colorMode: ColorMode = ColorMode.COLOR,
        sourceDpi: Float = PDF_POINTS_PER_INCH
    ): Result<Bitmap> = imageProcessor.processImageToBitmap(
        file = file,
        dpi = dpi,
        paperSize = paperSize,
        orientation = orientation,
        colorMode = colorMode,
        sourceDpi = sourceDpi
    )

    fun processBitmapForPrint(
        source: Bitmap,
        paperSize: PaperSize,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        dpi: Int = DEFAULT_PRINT_DPI,
        colorMode: ColorMode = ColorMode.COLOR
    ): Bitmap {
        val composited = imageProcessor.compositeBitmapOnSheet(
            source = source,
            paperSize = paperSize,
            orientation = orientation,
            dpi = dpi
        )
        return if (colorMode == ColorMode.GRAYSCALE) {
            try {
                imageProcessor.toGrayscaleBitmap(composited)
            } finally {
                if (!composited.isRecycled) composited.recycle()
            }
        } else {
            composited
        }
    }

    private fun openParcelFileDescriptor(uri: Uri): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI: $uri")
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open file descriptor for $uri")
        }
    }
}
