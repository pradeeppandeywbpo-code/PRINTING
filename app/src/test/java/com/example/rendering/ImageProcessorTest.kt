package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.ColorMode
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageProcessorTest {

    @Test
    fun `jpeg converts to 300 dpi A4 portrait and landscape bitmaps via PdfRenderingEngine`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PdfRenderingEngine(context)

        val jpgFile = File(context.cacheDir, "test_photo_sample.jpg")
        val src = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 90, 220))
        }
        FileOutputStream(jpgFile).use { out ->
            src.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        src.recycle()

        try {
            val portraitResult = engine.renderImageToBitmap(
                file = jpgFile,
                dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                paperSize = PaperSize.A4,
                orientation = PageOrientation.PORTRAIT
            )
            assertTrue("JPEG portrait conversion should succeed", portraitResult.isSuccess)
            val portraitBmp = portraitResult.getOrThrow()
            assertEquals(PaperSize.A4.widthPx(300, PageOrientation.PORTRAIT), portraitBmp.width)
            assertEquals(PaperSize.A4.heightPx(300, PageOrientation.PORTRAIT), portraitBmp.height)
            assertEquals(Color.WHITE, portraitBmp.getPixel(5, 5))
            portraitBmp.recycle()

            val landscapeResult = engine.renderImageToBitmap(
                file = jpgFile,
                dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                paperSize = PaperSize.A4,
                orientation = PageOrientation.LANDSCAPE
            )
            assertTrue("JPEG landscape conversion should succeed", landscapeResult.isSuccess)
            val landscapeBmp = landscapeResult.getOrThrow()
            assertEquals(PaperSize.A4.widthPx(300, PageOrientation.LANDSCAPE), landscapeBmp.width)
            assertEquals(PaperSize.A4.heightPx(300, PageOrientation.LANDSCAPE), landscapeBmp.height)
            landscapeBmp.recycle()
        } finally {
            jpgFile.delete()
        }
    }

    @Test
    fun `transparent png flattens onto opaque white 300 dpi bitmap and supports grayscale`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PdfRenderingEngine(context)

        val pngFile = File(context.cacheDir, "test_stamp_alpha.png")
        val src = Bitmap.createBitmap(144, 72, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(this)
            val paint = Paint().apply { color = Color.rgb(220, 40, 40) }
            canvas.drawRect(36f, 18f, 108f, 54f, paint)
        }
        FileOutputStream(pngFile).use { out ->
            src.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        src.recycle()

        try {
            // Unbounded 300 DPI scaling matches 300 / 72f point ratio of PdfRenderingEngine
            val nativeScaledResult = engine.imageProcessor.processImageToBitmap(
                file = pngFile,
                dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                paperSize = null
            )
            assertTrue("PNG 300 DPI native scaling should succeed", nativeScaledResult.isSuccess)
            val scaledBmp = nativeScaledResult.getOrThrow()
            assertEquals(600, scaledBmp.width)
            assertEquals(300, scaledBmp.height)
            // Transparent outer pixels are flattened onto opaque white
            assertEquals(Color.WHITE, scaledBmp.getPixel(2, 2))
            scaledBmp.recycle()

            // Grayscale sheet mode
            val graySheetResult = engine.renderImageToBitmap(
                file = pngFile,
                dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                paperSize = PaperSize.LETTER,
                orientation = PageOrientation.PORTRAIT,
                colorMode = ColorMode.GRAYSCALE
            )
            assertTrue("PNG grayscale sheet rendering should succeed", graySheetResult.isSuccess)
            val grayBmp = graySheetResult.getOrThrow()
            assertEquals(PaperSize.LETTER.widthPx(300, PageOrientation.PORTRAIT), grayBmp.width)
            assertEquals(PaperSize.LETTER.heightPx(300, PageOrientation.PORTRAIT), grayBmp.height)
            val centerPixel = grayBmp.getPixel(grayBmp.width / 2, grayBmp.height / 2)
            assertEquals(Color.red(centerPixel), Color.green(centerPixel))
            assertEquals(Color.green(centerPixel), Color.blue(centerPixel))
            grayBmp.recycle()
        } finally {
            pngFile.delete()
        }
    }

    @Test
    fun `unsupported non-image file is rejected cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PdfRenderingEngine(context)

        val badFile = File(context.cacheDir, "not_an_image.txt")
        badFile.writeText("plain text payload")
        try {
            val result = engine.renderImageToBitmap(file = badFile)
            assertTrue("Non-JPG/PNG file should fail", result.isFailure)
        } finally {
            badFile.delete()
        }
    }
}
