package com.example.domain.model

import android.net.Uri

enum class ConnectionType(val label: String) {
    USB_OTG("USB OTG"),
    WIFI_MDNS("Wi-Fi mDNS"),
    WIFI_RAW("Wi-Fi IP"),
    BLUETOOTH("Bluetooth")
}

enum class PrinterProtocol(val label: String, val shortTag: String) {
    AUTO("Auto Detect", "AUTO"),
    PCL_RASTER("PCL 5 Raster (Laser/Inkjet)", "PCL5"),
    POSTSCRIPT("PostScript Level 2", "PS2"),
    ESC_POS("ESC/POS Raster (Thermal)", "ESC/POS"),
    PDF_STREAM("PDF Stream (IPP / Direct PDF)", "PDF")
}

enum class PaperSize(
    val displayName: String,
    val widthMm: Float,
    val heightMm: Float,
    val pclCode: Int,
    val isThermalRoll: Boolean = false
) {
    A4("A4 (210 × 297 mm)", 210f, 297f, pclCode = 26),
    LETTER("Letter (8.5 × 11 in)", 215.9f, 279.4f, pclCode = 2),
    LEGAL("Legal (8.5 × 14 in)", 215.9f, 355.6f, pclCode = 3),
    THERMAL_80MM("80mm Thermal Roll", 72f, 150f, pclCode = 0, isThermalRoll = true),
    THERMAL_58MM("58mm Thermal Roll", 48f, 120f, pclCode = 0, isThermalRoll = true);

    fun widthPx(dpi: Int, orientation: PageOrientation): Int {
        val wMm = if (orientation == PageOrientation.LANDSCAPE && !isThermalRoll) heightMm else widthMm
        return ((wMm / 25.4f) * dpi).toInt().coerceAtLeast(64)
    }

    fun heightPx(dpi: Int, orientation: PageOrientation): Int {
        val hMm = if (orientation == PageOrientation.LANDSCAPE && !isThermalRoll) widthMm else heightMm
        return ((hMm / 25.4f) * dpi).toInt().coerceAtLeast(64)
    }

    fun thermalDotsWidth(): Int = when (this) {
        THERMAL_58MM -> 384
        else -> 576
    }

    fun aspectRatio(orientation: PageOrientation): Float {
        return if (orientation == PageOrientation.LANDSCAPE && !isThermalRoll) {
            heightMm / widthMm
        } else {
            widthMm / heightMm
        }
    }
}

enum class ColorMode(val label: String) {
    COLOR("Color"),
    GRAYSCALE("Black & White")
}

enum class PageOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

data class DiscoveredPrinter(
    val id: String,
    val name: String,
    val connectionType: ConnectionType,
    val address: String,
    val port: Int = 9100,
    val defaultProtocol: PrinterProtocol = PrinterProtocol.PCL_RASTER,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val usbDeviceName: String? = null,
    val hasPermission: Boolean = true,
    val isConnected: Boolean = false,
    val details: String = "",
    val serviceType: String? = null
)

sealed interface DocumentSource {
    val title: String

    data class Pdf(
        val uri: Uri,
        override val title: String,
        val pageCount: Int = 1
    ) : DocumentSource

    data class Image(
        val uri: Uri,
        override val title: String
    ) : DocumentSource

    data class WebCapture(
        val url: String,
        override val title: String,
        val pageFilePaths: List<String>
    ) : DocumentSource
}

data class PrintSettings(
    val paperSize: PaperSize = PaperSize.A4,
    val colorMode: ColorMode = ColorMode.COLOR,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val copies: Int = 1,
    val useAllPages: Boolean = true,
    val customPageRange: String = "1",
    val protocol: PrinterProtocol = PrinterProtocol.AUTO,
    val targetDpi: Int = 300
)

sealed interface SpoolerProgress {
    data object Idle : SpoolerProgress
    data class Rendering(val currentPage: Int, val totalPages: Int, val dpi: Int) : SpoolerProgress
    data class Encoding(val protocolName: String, val page: Int, val totalPages: Int) : SpoolerProgress
    data class Transmitting(val bytesSent: Long, val totalBytes: Long, val transportLabel: String) : SpoolerProgress
    data class Completed(val jobTitle: String, val bytesSent: Long, val protocolUsed: String) : SpoolerProgress
    data class Failed(val reason: String) : SpoolerProgress
}

object PageRangeParser {
    /**
     * Parses expressions such as "1-3, 5, 7-8" into sorted 0-based page indices.
     * Falls back to all valid pages if empty or invalid when [useAllPages] is true.
     */
    fun parseZeroBasedIndices(expression: String, totalPages: Int, useAllPages: Boolean): Result<List<Int>> {
        if (totalPages <= 0) return Result.failure(IllegalArgumentException("Document has 0 pages"))
        if (useAllPages) return Result.success((0 until totalPages).toList())

        val trimmed = expression.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a page range such as 1-$totalPages"))
        }

        val selected = linkedSetOf<Int>()
        val segments = trimmed.split(",")
        for (rawSegment in segments) {
            val segment = rawSegment.trim()
            if (segment.isEmpty()) continue
            if (segment.contains("-")) {
                val bounds = segment.split("-")
                if (bounds.size != 2) {
                    return Result.failure(IllegalArgumentException("Invalid range segment: '$segment'"))
                }
                val start = bounds[0].trim().toIntOrNull()
                val end = bounds[1].trim().toIntOrNull()
                if (start == null || end == null || start < 1 || end < start || end > totalPages) {
                    return Result.failure(
                        IllegalArgumentException("Range '$segment' must be within 1..$totalPages")
                    )
                }
                for (pageOneBased in start..end) {
                    selected.add(pageOneBased - 1)
                }
            } else {
                val pageOneBased = segment.toIntOrNull()
                if (pageOneBased == null || pageOneBased < 1 || pageOneBased > totalPages) {
                    return Result.failure(
                        IllegalArgumentException("Page '$segment' must be within 1..$totalPages")
                    )
                }
                selected.add(pageOneBased - 1)
            }
        }
        if (selected.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid pages specified"))
        }
        return Result.success(selected.toList().sorted())
    }
}
