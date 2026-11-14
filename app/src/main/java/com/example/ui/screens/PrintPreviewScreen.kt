package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ColorMode
import com.example.domain.model.DocumentSource
import com.example.domain.model.PageOrientation
import com.example.domain.model.PageRangeParser
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintSettings
import com.example.domain.model.PrinterProtocol
import com.example.rendering.PdfRenderingEngine
import com.example.ui.PrintUiState
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.EmeraldReady

@Composable
fun PrintPreviewScreen(
    uiState: PrintUiState,
    onPageChange: (Int) -> Unit,
    onUpdateSettings: ((PrintSettings) -> PrintSettings) -> Unit,
    onPrintClick: () -> Unit,
    onChooseDocumentClick: () -> Unit,
    onDismissSpooler: () -> Unit,
    modifier: Modifier = Modifier,
    pdfRenderingEngine: PdfRenderingEngine? = null
) {
    val doc = uiState.activeDocument
    val settings = uiState.printSettings

    if (doc == null) {
        EmptyPreviewCard(
            onChooseDocumentClick = onChooseDocumentClick,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val resolvedPdfEngine = remember(context, pdfRenderingEngine) {
        pdfRenderingEngine ?: PdfRenderingEngine(context)
    }

    var highResPdfBitmap by remember(doc) { mutableStateOf<Bitmap?>(null) }
    var isRenderingHighRes by remember(doc) { mutableStateOf(false) }
    var highResError by remember(doc) { mutableStateOf<String?>(null) }
    var detectedPdfPageCount by remember(doc) { mutableIntStateOf(uiState.totalPages.coerceAtLeast(1)) }

    // Render selected PDF page or JPG/PNG image directly via PdfRenderingEngine at 300 DPI
    LaunchedEffect(doc, uiState.currentPreviewPage, settings.paperSize, settings.orientation, resolvedPdfEngine) {
        when (doc) {
            is DocumentSource.Pdf -> {
                isRenderingHighRes = true
                highResError = null

                val totalPages = if (uiState.totalPages > 0) {
                    uiState.totalPages
                } else {
                    resolvedPdfEngine.getPageCount(doc.uri).getOrDefault(doc.pageCount).coerceAtLeast(1)
                }
                detectedPdfPageCount = totalPages
                val clampedPage = uiState.currentPreviewPage.coerceIn(0, totalPages - 1)

                val renderResult = resolvedPdfEngine.renderPageToBitmap(
                    uri = doc.uri,
                    pageIndex = clampedPage,
                    dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                    paperSize = settings.paperSize,
                    orientation = settings.orientation
                )

                renderResult.fold(
                    onSuccess = { newBitmap ->
                        val previous = highResPdfBitmap
                        highResPdfBitmap = newBitmap
                        if (previous != null && previous !== newBitmap && !previous.isRecycled) {
                            previous.recycle()
                        }
                        isRenderingHighRes = false
                        highResError = null
                    },
                    onFailure = { error ->
                        isRenderingHighRes = false
                        highResError = error.message ?: "Unable to render PDF page at 300 DPI"
                    }
                )
            }
            is DocumentSource.Image -> {
                isRenderingHighRes = true
                highResError = null
                detectedPdfPageCount = 1

                val renderResult = resolvedPdfEngine.renderImageToBitmap(
                    uri = doc.uri,
                    dpi = PdfRenderingEngine.DEFAULT_PRINT_DPI,
                    paperSize = settings.paperSize,
                    orientation = settings.orientation
                )

                renderResult.fold(
                    onSuccess = { newBitmap ->
                        val previous = highResPdfBitmap
                        highResPdfBitmap = newBitmap
                        if (previous != null && previous !== newBitmap && !previous.isRecycled) {
                            previous.recycle()
                        }
                        isRenderingHighRes = false
                        highResError = null
                    },
                    onFailure = { error ->
                        isRenderingHighRes = false
                        highResError = error.message ?: "Unable to process image at 300 DPI"
                    }
                )
            }
            else -> {
                val previous = highResPdfBitmap
                highResPdfBitmap = null
                if (previous != null && !previous.isRecycled) {
                    previous.recycle()
                }
                isRenderingHighRes = false
                highResError = null
                detectedPdfPageCount = uiState.totalPages.coerceAtLeast(1)
            }
        }
    }

    DisposableEffect(doc) {
        onDispose {
            val bmp = highResPdfBitmap
            highResPdfBitmap = null
            if (bmp != null && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    val effectiveTotalPages = if (doc is DocumentSource.Pdf) {
        maxOf(uiState.totalPages, detectedPdfPageCount).coerceAtLeast(1)
    } else {
        uiState.totalPages.coerceAtLeast(1)
    }

    val pageRangeResult = remember(settings.useAllPages, settings.customPageRange, effectiveTotalPages) {
        PageRangeParser.parseZeroBasedIndices(
            settings.customPageRange,
            effectiveTotalPages,
            settings.useAllPages
        )
    }

    val usesHighResEngine = doc is DocumentSource.Pdf || doc is DocumentSource.Image
    val activePreviewBitmap = when {
        usesHighResEngine && highResPdfBitmap != null && highResPdfBitmap?.isRecycled == false -> highResPdfBitmap
        else -> uiState.previewBitmap
    }
    val activeRendering = (usesHighResEngine && isRenderingHighRes) || uiState.isRenderingPreview
    val activeError = if (activePreviewBitmap == null) highResError ?: uiState.previewError else null

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideLayout = maxWidth >= 840.dp

        if (isWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PreviewDocumentHeader(
                        docTitle = doc.title,
                        settings = settings,
                        effectiveDpi = if (usesHighResEngine) PdfRenderingEngine.DEFAULT_PRINT_DPI else settings.targetDpi,
                        bitmap = activePreviewBitmap,
                        currentPage = uiState.currentPreviewPage,
                        totalPages = effectiveTotalPages,
                        selectedPageIndices = pageRangeResult.getOrNull(),
                        onPageChange = onPageChange
                    )
                    PreviewSheetCanvas(
                        bitmap = activePreviewBitmap,
                        isPdf = doc is DocumentSource.Pdf,
                        isImage = doc is DocumentSource.Image,
                        settings = settings,
                        currentPage = uiState.currentPreviewPage,
                        isRendering = activeRendering,
                        errorMessage = activeError
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PageSetupCard(
                        settings = settings,
                        totalPages = effectiveTotalPages,
                        currentPreviewPage = uiState.currentPreviewPage,
                        pageRangeResult = pageRangeResult,
                        onUpdateSettings = onUpdateSettings
                    )
                    PrintActionButton(
                        printerName = uiState.selectedPrinter?.name,
                        enabled = pageRangeResult.isSuccess,
                        selectedPageCount = pageRangeResult.getOrNull()?.size ?: 0,
                        copies = settings.copies,
                        onPrintClick = onPrintClick
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PreviewDocumentHeader(
                        docTitle = doc.title,
                        settings = settings,
                        effectiveDpi = if (usesHighResEngine) PdfRenderingEngine.DEFAULT_PRINT_DPI else settings.targetDpi,
                        bitmap = activePreviewBitmap,
                        currentPage = uiState.currentPreviewPage,
                        totalPages = effectiveTotalPages,
                        selectedPageIndices = pageRangeResult.getOrNull(),
                        onPageChange = onPageChange
                    )
                    PreviewSheetCanvas(
                        bitmap = activePreviewBitmap,
                        isPdf = doc is DocumentSource.Pdf,
                        isImage = doc is DocumentSource.Image,
                        settings = settings,
                        currentPage = uiState.currentPreviewPage,
                        isRendering = activeRendering,
                        errorMessage = activeError
                    )
                    PageSetupCard(
                        settings = settings,
                        totalPages = effectiveTotalPages,
                        currentPreviewPage = uiState.currentPreviewPage,
                        pageRangeResult = pageRangeResult,
                        onUpdateSettings = onUpdateSettings
                    )
                    PrintActionButton(
                        printerName = uiState.selectedPrinter?.name,
                        enabled = pageRangeResult.isSuccess,
                        selectedPageCount = pageRangeResult.getOrNull()?.size ?: 0,
                        copies = settings.copies,
                        onPrintClick = onPrintClick
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    SpoolerProgressDialog(
        progress = uiState.spoolerProgress,
        onDismiss = onDismissSpooler
    )
}

@Composable
private fun EmptyPreviewCard(
    onChooseDocumentClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 420.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.preview_no_doc_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.preview_no_doc_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onChooseDocumentClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("empty_preview_choose_doc_button")
                ) {
                    Text(stringResource(R.string.preview_open_hub))
                }
            }
        }
    }
}

@Composable
private fun PreviewDocumentHeader(
    docTitle: String,
    settings: PrintSettings,
    effectiveDpi: Int,
    bitmap: Bitmap?,
    currentPage: Int,
    totalPages: Int,
    selectedPageIndices: List<Int>?,
    onPageChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = docTitle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val dimSuffix = if (bitmap != null && !bitmap.isRecycled) {
                    " · ${bitmap.width}×${bitmap.height} px"
                } else {
                    ""
                }
                Text(
                    text = "$effectiveDpi DPI · ${settings.paperSize.name} · ${settings.orientation.label} · ${settings.colorMode.label}$dimSuffix",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (totalPages > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onPageChange(currentPage - 1) },
                        enabled = currentPage > 0,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("prev_page_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.preview_previous_page)
                        )
                    }
                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.testTag("page_indicator_text")
                    )
                    IconButton(
                        onClick = { onPageChange(currentPage + 1) },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("next_page_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.preview_next_page)
                        )
                    }
                }
            }
        }

        if (totalPages > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((0 until totalPages).toList()) { pageIdx ->
                    val isCurrent = pageIdx == currentPage
                    val isIncluded = selectedPageIndices?.contains(pageIdx) ?: true
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.primary
                            isIncluded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = if (!isIncluded && !isCurrent) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        } else {
                            null
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPageChange(pageIdx) }
                            .minimumInteractiveComponentSize()
                            .testTag("page_pill_${pageIdx + 1}")
                    ) {
                        Text(
                            text = "P${pageIdx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.onPrimary
                                isIncluded -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSheetCanvas(
    bitmap: Bitmap?,
    isPdf: Boolean,
    isImage: Boolean,
    settings: PrintSettings,
    currentPage: Int,
    isRendering: Boolean,
    errorMessage: String?
) {
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(currentPage, settings.paperSize, settings.orientation) {
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    val grayscaleFilter = remember(settings.colorMode) {
        if (settings.colorMode == ColorMode.GRAYSCALE) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        }
    }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val sheetAspect = settings.paperSize.aspectRatio(settings.orientation).coerceIn(0.32f, 1.8f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = when {
                        isPdf -> stringResource(R.string.preview_high_res_badge)
                        isImage -> stringResource(R.string.preview_image_high_res_badge)
                        else -> "${settings.targetDpi} DPI · ${settings.paperSize.displayName}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("preview_dpi_badge")
                )
            }

            AssistChip(
                onClick = {
                    if (zoomScale > 1.1f) {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    } else {
                        zoomScale = 2f
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (zoomScale > 1.1f) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        if (zoomScale > 1.1f) {
                            stringResource(R.string.preview_zoom_reset)
                        } else {
                            stringResource(R.string.preview_zoom_in)
                        }
                    )
                },
                modifier = Modifier.testTag("preview_zoom_toggle")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .drawBehind {
                    val step = 28.dp.toPx()
                    var x = step
                    while (x < size.width) {
                        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (zoomScale * zoom).coerceIn(1f, 3.5f)
                        zoomScale = nextScale
                        panOffset = if (nextScale <= 1.02f) {
                            Offset.Zero
                        } else {
                            Offset(
                                x = (panOffset.x + pan.x).coerceIn(-360f, 360f),
                                y = (panOffset.y + pan.y).coerceIn(-480f, 480f)
                            )
                        }
                    }
                }
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(if (sheetAspect > 1f) 0.96f else 0.80f)
                    .aspectRatio(sheetAspect)
                    .shadow(12.dp, RoundedCornerShape(4.dp))
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = panOffset.x,
                        translationY = panOffset.y
                    )
                    .testTag("preview_paper_sheet")
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap != null && !bitmap.isRecycled) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${currentPage + 1} preview",
                            contentScale = ContentScale.Fit,
                            colorFilter = grayscaleFilter,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("preview_bitmap_image")
                        )
                    }
                    if (isRendering) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .size(28.dp),
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = CrimsonError,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSetupCard(
    settings: PrintSettings,
    totalPages: Int,
    currentPreviewPage: Int,
    pageRangeResult: Result<List<Int>>,
    onUpdateSettings: ((PrintSettings) -> PrintSettings) -> Unit
) {
    val selectedIndices = pageRangeResult.getOrNull()
    val currentPageIncluded = selectedIndices?.contains(currentPreviewPage) ?: false

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.page_setup),
                    style = MaterialTheme.typography.titleLarge
                )
                if (selectedIndices != null) {
                    val sheetTotal = selectedIndices.size * settings.copies
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EmeraldReady.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "${selectedIndices.size}p × ${settings.copies} = $sheetTotal sheet${if (sheetTotal == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldReady,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("sheet_summary_badge")
                        )
                    }
                }
            }

            // Paper Size
            Text(text = stringResource(R.string.paper_size), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PaperSize.entries) { paper ->
                    FilterChip(
                        selected = settings.paperSize == paper,
                        onClick = { onUpdateSettings { it.copy(paperSize = paper) } },
                        label = { Text(paper.displayName) },
                        modifier = Modifier.testTag("paper_${paper.name.lowercase()}")
                    )
                }
            }

            // Color Mode + Orientation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.color_mode), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ColorMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = settings.colorMode == mode,
                                onClick = { onUpdateSettings { it.copy(colorMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(idx, ColorMode.entries.size),
                                modifier = Modifier.testTag("color_${mode.name.lowercase()}")
                            ) {
                                Text(
                                    text = if (mode == ColorMode.GRAYSCALE) "B&W" else "Color",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.orientation), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PageOrientation.entries.forEachIndexed { idx, orient ->
                            SegmentedButton(
                                selected = settings.orientation == orient,
                                onClick = { onUpdateSettings { it.copy(orientation = orient) } },
                                shape = SegmentedButtonDefaults.itemShape(idx, PageOrientation.entries.size),
                                enabled = !settings.paperSize.isThermalRoll,
                                icon = {
                                    Icon(
                                        imageVector = if (orient == PageOrientation.PORTRAIT) {
                                            Icons.Default.CropPortrait
                                        } else {
                                            Icons.Default.CropLandscape
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.testTag("orient_${orient.name.lowercase()}")
                            ) {
                                Text(orient.label, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Page Range & Copies
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1.3f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = stringResource(R.string.page_range), style = MaterialTheme.typography.labelLarge)
                        if (!settings.useAllPages && totalPages > 1) {
                            Text(
                                text = stringResource(
                                    if (currentPageIncluded) R.string.preview_page_included else R.string.preview_page_excluded
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (currentPageIncluded) EmeraldReady else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = settings.useAllPages,
                            onClick = { onUpdateSettings { it.copy(useAllPages = true) } },
                            label = { Text(stringResource(R.string.preview_all_pages, totalPages)) },
                            modifier = Modifier.testTag("range_all_chip")
                        )
                        FilterChip(
                            selected = !settings.useAllPages,
                            onClick = { onUpdateSettings { it.copy(useAllPages = false) } },
                            label = { Text(stringResource(R.string.preview_custom_range)) },
                            modifier = Modifier.testTag("range_custom_chip")
                        )
                    }
                    AnimatedVisibility(visible = !settings.useAllPages) {
                        OutlinedTextField(
                            value = settings.customPageRange,
                            onValueChange = { value ->
                                onUpdateSettings { it.copy(customPageRange = value) }
                            },
                            label = { Text(stringResource(R.string.preview_custom_range_hint)) },
                            isError = pageRangeResult.isFailure,
                            supportingText = pageRangeResult.exceptionOrNull()?.message?.let { msg ->
                                { Text(msg) }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .testTag("custom_range_input")
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(0.9f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = stringResource(R.string.copies), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    onUpdateSettings {
                                        it.copy(copies = (it.copies - 1).coerceAtLeast(1))
                                    }
                                },
                                enabled = settings.copies > 1,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copies_minus_button")
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = stringResource(R.string.preview_decrease_copies)
                                )
                            }
                            Text(
                                text = settings.copies.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .testTag("copies_count_text")
                            )
                            IconButton(
                                onClick = {
                                    onUpdateSettings {
                                        it.copy(copies = (it.copies + 1).coerceAtMost(99))
                                    }
                                },
                                enabled = settings.copies < 99,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copies_plus_button")
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.preview_increase_copies)
                                )
                            }
                        }
                    }
                }
            }

            // Driver Protocol Selection
            Text(text = stringResource(R.string.driver_protocol), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PrinterProtocol.entries) { proto ->
                    FilterChip(
                        selected = settings.protocol == proto,
                        onClick = { onUpdateSettings { it.copy(protocol = proto) } },
                        label = { Text(proto.label) },
                        modifier = Modifier.testTag("protocol_${proto.name.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
private fun PrintActionButton(
    printerName: String?,
    enabled: Boolean,
    selectedPageCount: Int,
    copies: Int,
    onPrintClick: () -> Unit
) {
    Button(
        onClick = onPrintClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("print_document_button")
    ) {
        Icon(Icons.Default.Print, contentDescription = null)
        Spacer(modifier = Modifier.width(10.dp))
        val targetPrinter = printerName ?: stringResource(R.string.preview_select_printer)
        val totalSheets = selectedPageCount * copies
        val countHint = if (enabled && totalSheets > 0) " ($totalSheets)" else ""
        Text(
            text = "${stringResource(R.string.print_now)}$countHint · $targetPrinter",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
