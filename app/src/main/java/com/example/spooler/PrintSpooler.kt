package com.example.spooler

import android.graphics.Bitmap
import com.example.data.local.PrintHistoryRepository
import com.example.data.local.PrintJobEntity
import com.example.data.remote.CloudPrintRepository
import com.example.domain.model.ColorMode
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.DocumentSource
import com.example.domain.model.PageRangeParser
import com.example.domain.model.PrintSettings
import com.example.domain.model.PrinterProtocol
import com.example.domain.model.SpoolerProgress
import com.example.printer.bluetooth.BluetoothPrinterManager
import com.example.printer.network.NetworkPrinterTransport
import com.example.printer.usb.UsbPrinterManager
import com.example.rendering.DocumentRenderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Coordinates end-to-end document rendering (300 DPI), driver protocol conversion
 * (PCL / PostScript / ESC-POS / PDF Stream), hardware transport (USB OTG / Wi-Fi / Bluetooth),
 * and local Room job history logging.
 */
class PrintSpooler(
    private val renderEngine: DocumentRenderEngine,
    private val usbPrinterManager: UsbPrinterManager,
    private val networkTransport: NetworkPrinterTransport,
    private val bluetoothManager: BluetoothPrinterManager,
    private val historyRepository: PrintHistoryRepository,
    private val cloudRepository: CloudPrintRepository
) {

    private val _progress = MutableStateFlow<SpoolerProgress>(SpoolerProgress.Idle)
    val progress: StateFlow<SpoolerProgress> = _progress.asStateFlow()

    fun resetProgress() {
        _progress.value = SpoolerProgress.Idle
    }

    suspend fun printDocument(
        source: DocumentSource,
        totalPages: Int,
        printer: DiscoveredPrinter,
        settings: PrintSettings
    ): Result<Long> = withContext(Dispatchers.IO) {
        val indicesResult = PageRangeParser.parseZeroBasedIndices(
            expression = settings.customPageRange,
            totalPages = totalPages,
            useAllPages = settings.useAllPages
        )
        val pageIndices = indicesResult.getOrElse { error ->
            val msg = error.message ?: "Invalid page range"
            _progress.value = SpoolerProgress.Failed(msg)
            return@withContext Result.failure(error)
        }

        val effectiveProtocol = resolveProtocol(settings.protocol, printer)
        val renderedPages = mutableListOf<Bitmap>()

        try {
            // 1. Render selected pages at 300 DPI (flattening signatures, stamps, and custom fonts)
            pageIndices.forEachIndexed { stepIdx, pageIdx ->
                _progress.value = SpoolerProgress.Rendering(
                    currentPage = stepIdx + 1,
                    totalPages = pageIndices.size,
                    dpi = settings.targetDpi
                )
                val pageBmp = renderEngine.renderPage(
                    source = source,
                    pageIndex = pageIdx,
                    paperSize = settings.paperSize,
                    orientation = settings.orientation,
                    colorMode = settings.colorMode,
                    dpi = settings.targetDpi
                ).getOrThrow()
                renderedPages.add(pageBmp)
            }

            // 2. Encode rendered bitmaps into the printer driver command stream
            val payloadBuffer = ByteArrayOutputStream()
            val copyCount = settings.copies.coerceIn(1, 99)

            if (effectiveProtocol == PrinterProtocol.PDF_STREAM) {
                _progress.value = SpoolerProgress.Encoding(
                    protocolName = effectiveProtocol.shortTag,
                    page = 1,
                    totalPages = renderedPages.size
                )
                val pdfBytes = PrinterEncoder.encodeBitmapsToPdfStream(
                    bitmaps = renderedPages,
                    paperSize = settings.paperSize,
                    orientation = settings.orientation
                )
                repeat(copyCount) {
                    payloadBuffer.write(pdfBytes)
                }
            } else {
                for (copy in 1..copyCount) {
                    renderedPages.forEachIndexed { idx, bmp ->
                        _progress.value = SpoolerProgress.Encoding(
                            protocolName = effectiveProtocol.shortTag,
                            page = idx + 1,
                            totalPages = renderedPages.size
                        )
                        val pageBytes = when (effectiveProtocol) {
                            PrinterProtocol.ESC_POS -> PrinterEncoder.encodeToEscPosRaster(
                                bitmap = bmp,
                                paperWidthDots = settings.paperSize.thermalDotsWidth(),
                                cutPaper = (idx == renderedPages.lastIndex)
                            )
                            PrinterProtocol.PCL_RASTER -> PrinterEncoder.encodeToPclRaster(
                                bitmap = bmp,
                                paperSize = settings.paperSize,
                                dpi = settings.targetDpi
                            )
                            PrinterProtocol.POSTSCRIPT -> PrinterEncoder.encodeToPostScript(
                                bitmap = bmp,
                                paperSize = settings.paperSize,
                                orientation = settings.orientation,
                                color = (settings.colorMode == ColorMode.COLOR)
                            )
                            else -> ByteArray(0)
                        }
                        payloadBuffer.write(pageBytes)
                    }
                }
            }

            // Recycle bitmaps right after encoding to free native heap before I/O
            renderedPages.forEach { if (!it.isRecycled) it.recycle() }
            renderedPages.clear()

            val finalPayload = payloadBuffer.toByteArray()

            // 3. Transmit over active USB OTG, Wi-Fi (IPP/Raw 9100), or Bluetooth SPP transport
            val sendResult = when (printer.connectionType) {
                ConnectionType.USB_OTG -> {
                    usbPrinterManager.sendPrintData(
                        deviceName = printer.usbDeviceName ?: printer.address,
                        payload = finalPayload
                    ) { sent, total ->
                        _progress.value = SpoolerProgress.Transmitting(sent, total, "USB OTG Bulk")
                    }
                }
                ConnectionType.WIFI_MDNS, ConnectionType.WIFI_RAW -> {
                    val useIpp = printer.port == 631 ||
                        printer.serviceType?.contains("_ipp") == true
                    if (useIpp) {
                        val docFormat = if (effectiveProtocol == PrinterProtocol.PDF_STREAM) {
                            "application/pdf"
                        } else {
                            "application/octet-stream"
                        }
                        networkTransport.sendViaIpp(
                            host = printer.address,
                            port = printer.port,
                            jobName = source.title,
                            documentFormat = docFormat,
                            payload = finalPayload,
                            useTls = printer.serviceType?.contains("_ipps") == true
                        ) { sent, total ->
                            _progress.value = SpoolerProgress.Transmitting(sent, total, "Wi-Fi IPP")
                        }
                    } else {
                        networkTransport.sendViaRawSocket(
                            host = printer.address,
                            port = printer.port,
                            payload = finalPayload
                        ) { sent, total ->
                            _progress.value = SpoolerProgress.Transmitting(
                                sent,
                                total,
                                "TCP :${printer.port}"
                            )
                        }
                    }
                }
                ConnectionType.BLUETOOTH -> {
                    bluetoothManager.sendPrintData(
                        macAddress = printer.address,
                        payload = finalPayload
                    ) { sent, total ->
                        _progress.value = SpoolerProgress.Transmitting(sent, total, "Bluetooth SPP")
                    }
                }
            }

            val bytesSent = sendResult.getOrThrow()

            val completedJob = PrintJobEntity(
                documentTitle = source.title,
                printerName = printer.name,
                connectionType = printer.connectionType.label,
                protocolUsed = effectiveProtocol.shortTag,
                paperSize = settings.paperSize.displayName,
                pageCount = pageIndices.size,
                copies = copyCount,
                dpi = settings.targetDpi,
                bytesTransferred = bytesSent,
                status = "Completed"
            )
            historyRepository.recordJob(completedJob)
            cloudRepository.recordPrintJob(completedJob)

            _progress.value = SpoolerProgress.Completed(
                jobTitle = source.title,
                bytesSent = bytesSent,
                protocolUsed = effectiveProtocol.shortTag
            )
            Result.success(bytesSent)
        } catch (t: Throwable) {
            renderedPages.forEach { if (!it.isRecycled) it.recycle() }
            val errorMsg = t.message ?: "Print spooler error"
            val failedJob = PrintJobEntity(
                documentTitle = source.title,
                printerName = printer.name,
                connectionType = printer.connectionType.label,
                protocolUsed = effectiveProtocol.shortTag,
                paperSize = settings.paperSize.displayName,
                pageCount = pageIndices.size,
                copies = settings.copies,
                dpi = settings.targetDpi,
                bytesTransferred = 0L,
                status = "Failed",
                errorMessage = errorMsg
            )
            historyRepository.recordJob(failedJob)
            cloudRepository.recordPrintJob(failedJob)
            _progress.value = SpoolerProgress.Failed(errorMsg)
            Result.failure(t)
        }
    }

    private fun resolveProtocol(requested: PrinterProtocol, printer: DiscoveredPrinter): PrinterProtocol {
        if (requested != PrinterProtocol.AUTO) return requested
        return when {
            printer.connectionType == ConnectionType.BLUETOOTH -> PrinterProtocol.ESC_POS
            printer.serviceType?.contains("_ipp") == true || printer.port == 631 ->
                PrinterProtocol.PDF_STREAM
            else -> printer.defaultProtocol.takeIf { it != PrinterProtocol.AUTO }
                ?: PrinterProtocol.PCL_RASTER
        }
    }
}
