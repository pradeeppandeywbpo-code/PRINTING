package com.example.rendering

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.PageOrientation
import com.example.domain.model.PaperSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfRenderingEngineTest {

    @Test
    fun `invalid uri returns failure gracefully`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PdfRenderingEngine(context)

        val countResult = engine.getPageCount(Uri.parse("file:///nonexistent_document_xyz.pdf"))
        assertTrue(countResult.isFailure)

        val renderResult = engine.renderPageToBitmap(
            uri = Uri.parse("file:///nonexistent_document_xyz.pdf"),
            pageIndex = 0,
            dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
            paperSize = PaperSize.A4,
            orientation = PageOrientation.PORTRAIT
        )
        assertTrue(renderResult.isFailure)
    }

    @Test
    fun `non-positive dpi is rejected`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PdfRenderingEngine(context)
        val result = engine.renderPageToBitmap(
            uri = Uri.parse("file:///any.pdf"),
            pageIndex = 0,
            dpi = 0
        )
        assertTrue(result.isFailure)
    }
}
