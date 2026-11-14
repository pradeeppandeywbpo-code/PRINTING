package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.example.domain.model.ColorMode
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-resolution image processing module paired with [PdfRenderingEngine].
 *
 * Converts JPG and PNG files into print-ready 300 DPI [Bitmap]s with:
 * - Format validation (JPEG & PNG magic-byte/MIME verification)
 * - Automatic EXIF orientation normalization (including pre-API 28 fallback)
 * - Safe subsampling for oversized camera photos to prevent OOM
 * - Opaque white background compositing so transparent PNGs flatten cleanly
 * - Aspect-fitted 2% print margins matching [PdfRenderingEngine] sheet output
 */
class ImageProcessor(private val context: Context) {

    enum class SupportedImageFormat(val mimeType: String) {
        JPEG("image/jpeg"),
        PNG("image/png")
    }

    companion object {
        const val DEFAULT_PRINT_DPI = PdfRenderingEngine.DEFAULT_PRINT_DPI
        const val PRINT_MARGIN_RATIO = 0.02f
        const val MAX_SOURCE_DECODE_DIMENSION = 4200
        const val MAX_UNBOUNDED_PRINT_DIMENSION = 4200
    }

    /**
     * Converts a JPG or PNG image at [uri] into a high-quality [ARGB_8888] [Bitmap]
     * scaled for [dpi] (default 300 DPI).
     *
     * When [paperSize] is specified, the image is aspect-fitted inside a 2% margin
     * on the target paper dimensions for [orientation] (matching PDF rendering).
     * When [paperSize] is null, the image is scaled proportionally by `dpi / sourceDpi`
     * (capped at [MAX_UNBOUNDED_PRINT_DIMENSION]) over an opaque white canvas.
     */
    suspend fun processImageToBitmap(
        uri: Uri,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        colorMode: ColorMode = ColorMode.COLOR,
        sourceDpi: Float = PdfRenderingEngine.PDF_POINTS_PER_INCH
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            require(dpi > 0) { "DPI must be positive, was $dpi" }
            require(sourceDpi > 0f) { "Source DPI must be positive, was $sourceDpi" }

            detectSupportedFormat(uri)
                ?: throw IllegalArgumentException(
                    "Unsupported image format for $uri. Only JPG and PNG files are supported."
                )

            val decoded = decodeHighQualityBitmap(uri)
            try {
                val scaled = if (paperSize != null) {
                    compositeBitmapOnSheet(
                        source = decoded,
                        paperSize = paperSize,
                        orientation = orientation,
                        dpi = dpi
                    )
                } else {
                    scaleBitmapForDpi(
                        source = decoded,
                        dpi = dpi,
                        sourceDpi = sourceDpi
                    )
                }

                if (colorMode == ColorMode.GRAYSCALE) {
                    try {
                        toGrayscaleBitmap(scaled)
                    } finally {
                        if (!scaled.isRecycled) scaled.recycle()
                    }
                } else {
                    scaled
                }
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        }
    }

    suspend fun processImageToBitmap(
        file: File,
        dpi: Int = DEFAULT_PRINT_DPI,
        paperSize: PaperSize? = null,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        colorMode: ColorMode = ColorMode.COLOR,
        sourceDpi: Float = PdfRenderingEngine.PDF_POINTS_PER_INCH
    ): Result<Bitmap> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IOException("Image file does not exist: ${file.absolutePath}"))
        }
        return processImageToBitmap(
            uri = Uri.fromFile(file),
            dpi = dpi,
            paperSize = paperSize,
            orientation = orientation,
            colorMode = colorMode,
            sourceDpi = sourceDpi
        )
    }

    /**
     * Renders an already-decoded [Bitmap] (such as a web capture or camera frame)
     * onto a print-ready [paperSize] sheet at [dpi] (default 300 DPI), matching
     * [PdfRenderingEngine]'s 2% margin and opaque white background flattening.
     */
    fun compositeBitmapOnSheet(
        source: Bitmap,
        paperSize: PaperSize,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        dpi: Int = DEFAULT_PRINT_DPI
    ): Bitmap {
        require(dpi > 0) { "DPI must be positive, was $dpi" }
        val targetWidth = paperSize.widthPx(dpi, orientation)
        val targetHeight = paperSize.heightPx(dpi, orientation)

        val sheet = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val marginX = targetWidth * PRINT_MARGIN_RATIO
        val marginY = targetHeight * PRINT_MARGIN_RATIO
        val availW = (targetWidth - 2f * marginX).coerceAtLeast(1f)
        val availH = (targetHeight - 2f * marginY).coerceAtLeast(1f)

        val srcW = source.width.toFloat().coerceAtLeast(1f)
        val srcH = source.height.toFloat().coerceAtLeast(1f)
        val scale = min(availW / srcW, availH / srcH)
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val left = (targetWidth - drawnW) / 2f
        val top = (targetHeight - drawnH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + drawnW, top + drawnH),
            paint
        )
        return sheet
    }

    /**
     * Scales an image directly by `dpi / sourceDpi` (clamped cleanly within
     * [MAX_UNBOUNDED_PRINT_DIMENSION]) onto an opaque white [ARGB_8888] bitmap.
     */
    fun scaleBitmapForDpi(
        source: Bitmap,
        dpi: Int = DEFAULT_PRINT_DPI,
        sourceDpi: Float = PdfRenderingEngine.PDF_POINTS_PER_INCH
    ): Bitmap {
        require(dpi > 0) { "DPI must be positive, was $dpi" }
        require(sourceDpi > 0f) { "Source DPI must be positive, was $sourceDpi" }

        val srcW = source.width.toFloat().coerceAtLeast(1f)
        val srcH = source.height.toFloat().coerceAtLeast(1f)
        val requestedScale = dpi / sourceDpi
        val capScale = min(
            MAX_UNBOUNDED_PRINT_DIMENSION / srcW,
            MAX_UNBOUNDED_PRINT_DIMENSION / srcH
        )
        val effectiveScale = min(requestedScale, capScale)

        val targetWidth = (srcW * effectiveScale).roundToInt().coerceAtLeast(1)
        val targetHeight = (srcH * effectiveScale).roundToInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(
            source,
            null,
            RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            paint
        )
        return output
    }

    fun toGrayscaleBitmap(source: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) }
            )
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return grayscale
    }

    fun detectSupportedFormat(uri: Uri): SupportedImageFormat? {
        val header = readHeaderBytes(uri)
        if (header != null) {
            if (isJpegHeader(header)) return SupportedImageFormat.JPEG
            if (isPngHeader(header)) return SupportedImageFormat.PNG
            // Stream was readable and had magic bytes for some non-JPG/PNG format
            if (header.isNotEmpty()) return null
        }

        val mime = context.contentResolver.getType(uri)?.lowercase()
        return when (mime) {
            "image/jpeg", "image/jpg" -> SupportedImageFormat.JPEG
            "image/png" -> SupportedImageFormat.PNG
            else -> {
                val path = uri.path?.lowercase().orEmpty()
                when {
                    path.endsWith(".jpg") || path.endsWith(".jpeg") -> SupportedImageFormat.JPEG
                    path.endsWith(".png") -> SupportedImageFormat.PNG
                    else -> null
                }
            }
        }
    }

    private fun decodeHighQualityBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decodedWithImageDecoder = runCatching {
                val source = if (uri.scheme == "file") {
                    val path = uri.path ?: throw IOException("Invalid file URI: $uri")
                    val file = File(path)
                    if (!file.exists()) throw IOException("Image file does not exist: $path")
                    ImageDecoder.createSource(file)
                } else {
                    ImageDecoder.createSource(context.contentResolver, uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > MAX_SOURCE_DECODE_DIMENSION) {
                        val ratio = MAX_SOURCE_DECODE_DIMENSION.toFloat() / maxDim
                        decoder.setTargetSize(
                            (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                            (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
            if (decodedWithImageDecoder != null) {
                return decodedWithImageDecoder
            }
        }

        // API 24-27 and resilience fallback: two-pass bounds inspection + EXIF orientation normalization
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val sampleSize = calculateInSampleSize(
            width = boundsOpts.outWidth,
            height = boundsOpts.outHeight,
            maxDimension = MAX_SOURCE_DECODE_DIMENSION
        )
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IOException("Unable to decode image bitmap for $uri")

        val exifOrientation = readExifOrientation(uri)
        return applyExifOrientation(rawBitmap, exifOrientation)
    }

    private fun readHeaderBytes(uri: Uri): ByteArray? {
        return try {
            openInputStream(uri).use { stream ->
                val buffer = ByteArray(8)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openInputStream(uri: Uri): InputStream {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI: $uri")
            File(path).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open image stream for $uri")
        }
    }

    private fun readExifOrientation(uri: Uri): Int {
        return try {
            openInputStream(uri).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return transformed
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return inSampleSize
        while ((width / inSampleSize) > maxDimension || (height / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun isJpegHeader(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun isPngHeader(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
}
