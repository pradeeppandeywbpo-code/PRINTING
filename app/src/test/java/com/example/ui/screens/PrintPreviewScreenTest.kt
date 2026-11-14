package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.domain.model.DocumentSource
import com.example.domain.model.PageOrientation
import com.example.domain.model.PrintSettings
import com.example.ui.PrintUiState
import com.example.ui.theme.PrintForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrintPreviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `empty state shows choose document CTA`() {
        composeTestRule.setContent {
            PrintForgeTheme {
                PrintPreviewScreen(
                    uiState = PrintUiState(activeDocument = null),
                    onPageChange = {},
                    onUpdateSettings = {},
                    onPrintClick = {},
                    onChooseDocumentClick = {},
                    onDismissSpooler = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("empty_preview_choose_doc_button").assertIsDisplayed()
    }

    @Test
    fun `pdf preview toggles page range copies and orientation`() {
        val fallbackBmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        var state by mutableStateOf(
            PrintUiState(
                activeDocument = DocumentSource.Pdf(
                    uri = Uri.parse("file:///nonexistent_test.pdf"),
                    title = "Contract_Signed.pdf",
                    pageCount = 3
                ),
                totalPages = 3,
                currentPreviewPage = 0,
                previewBitmap = fallbackBmp,
                printSettings = PrintSettings(customPageRange = "1-3")
            )
        )

        composeTestRule.setContent {
            PrintForgeTheme {
                PrintPreviewScreen(
                    uiState = state,
                    onPageChange = { next -> state = state.copy(currentPreviewPage = next) },
                    onUpdateSettings = { transform ->
                        state = state.copy(printSettings = transform(state.printSettings))
                    },
                    onPrintClick = {},
                    onChooseDocumentClick = {},
                    onDismissSpooler = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify high-res PDF badge and page indicator
        composeTestRule.onNodeWithTag("preview_dpi_badge").assertTextContains("300 DPI", substring = true)
        composeTestRule.onNodeWithTag("page_indicator_text").assertTextContains("1 / 3")

        // Switch page via page pill
        composeTestRule.onNodeWithTag("page_pill_2").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, state.currentPreviewPage)

        // Toggle orientation to Landscape
        composeTestRule.onNodeWithTag("orient_landscape").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(PageOrientation.LANDSCAPE, state.printSettings.orientation)

        // Increment copies (1 -> 2)
        composeTestRule.onNodeWithTag("copies_plus_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, state.printSettings.copies)
        composeTestRule.onNodeWithTag("copies_count_text").assertTextContains("2")
        composeTestRule.onNodeWithTag("sheet_summary_badge").assertTextContains("3p × 2 = 6 sheets")

        // Toggle custom page range and validate input
        composeTestRule.onNodeWithTag("range_custom_chip").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_range_input").performScrollTo().performTextReplacement("1-2")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sheet_summary_badge").assertTextContains("2p × 2 = 4 sheets")
        composeTestRule.onNodeWithTag("print_document_button").assertIsEnabled()

        // Invalid out-of-bounds range disables Print button
        composeTestRule.onNodeWithTag("custom_range_input").performTextReplacement("99")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("print_document_button").assertIsNotEnabled()

        fallbackBmp.recycle()
    }
}
