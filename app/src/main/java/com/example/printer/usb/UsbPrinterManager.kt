package com.example.printer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages USB OTG Host Mode printer discovery, runtime USB permission requests,
 * UsbDeviceConnection lifecycle, IEEE-1284 device ID querying, and bulk endpoint data transfer.
 */
class UsbPrinterManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.aistudio.printforge.USB_PERMISSION"
        private const val BULK_CHUNK_SIZE = 16 * 1024
        private const val TRANSFER_TIMEOUT_MS = 10_000
        private const val PERMISSION_WAIT_TIMEOUT_MS = 30_000L

        // USB Printer Class-Specific Control Request: GET_DEVICE_ID (IEEE 1284)
        private const val USB_DIR_IN_CLASS_INTERFACE = 0xA1
        private const val GET_DEVICE_ID_REQUEST = 0x00
    }

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _usbPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val usbPrinters: StateFlow<List<DiscoveredPrinter>> = _usbPrinters.asStateFlow()

    private val _lastPermissionStatus = MutableStateFlow<String?>(null)
    val lastPermissionStatus: StateFlow<String?> = _lastPermissionStatus.asStateFlow()

    private val pendingPermissionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = getUsbDeviceExtra(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    _lastPermissionStatus.value = if (granted) {
                        "USB permission granted for ${device?.productName ?: device?.deviceName ?: "printer"}"
                    } else {
                        "USB permission denied by user"
                    }
                    device?.deviceName?.let { name ->
                        pendingPermissionWaiters.remove(name)?.complete(granted)
                    }
                    scanConnectedPrinters()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanConnectedPrinters()
                }
            }
        }
    }

    fun register() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // RECEIVER_EXPORTED is required so system USB permission/attach broadcasts are delivered on API 33+
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        isReceiverRegistered = true
        scanConnectedPrinters()
    }

    fun unregister() {
        pendingPermissionWaiters.values.forEach { it.cancel() }
        pendingPermissionWaiters.clear()
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        } finally {
            isReceiverRegistered = false
        }
    }

    fun getConnectedUsbDevices(): List<UsbDevice> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values.filter { device ->
            findPrinterInterface(device) != null || device.deviceClass == UsbConstants.USB_CLASS_PRINTER
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    fun scanConnectedPrinters(): List<DiscoveredPrinter> {
        val manager = usbManager ?: return emptyList()
        val printers = getConnectedUsbDevices().map { device ->
            val displayName = buildString {
                val mfg = device.manufacturerName?.trim().orEmpty()
                val prod = device.productName?.trim().orEmpty()
                if (mfg.isNotEmpty() || prod.isNotEmpty()) {
                    append("$mfg $prod".trim())
                } else {
                    append("USB Printer (${formatHexId(device.vendorId)}:${formatHexId(device.productId)})")
                }
            }
            val guessedProtocol = guessProtocolFromName(displayName)
            DiscoveredPrinter(
                id = "usb:${device.deviceName}",
                name = displayName,
                connectionType = ConnectionType.USB_OTG,
                address = device.deviceName,
                port = 0,
                defaultProtocol = guessedProtocol,
                vendorId = device.vendorId,
                productId = device.productId,
                usbDeviceName = device.deviceName,
                hasPermission = manager.hasPermission(device),
                isConnected = true,
                details = "VID ${formatHexId(device.vendorId)} · PID ${formatHexId(device.productId)} · Bulk USB"
            )
        }
        _usbPrinters.value = printers
        return printers
    }

    fun requestPermission(deviceName: String): Boolean {
        val manager = usbManager ?: return false
        val device = manager.deviceList[deviceName] ?: return false
        return requestPermission(device)
    }

    fun requestPermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) {
            scanConnectedPrinters()
            return true
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            permissionIntent,
            flags
        )
        manager.requestPermission(device, pendingIntent)
        return false
    }

    suspend fun requestPermissionAndAwait(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) return true

        register()
        val deferred = CompletableDeferred<Boolean>()
        pendingPermissionWaiters[device.deviceName] = deferred
        requestPermission(device)

        return try {
            withTimeoutOrNull(PERMISSION_WAIT_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            pendingPermissionWaiters.remove(device.deviceName)
        }
    }

    fun openConnection(device: UsbDevice): Result<UsbBulkSession> {
        val manager = usbManager ?: return Result.failure(
            IllegalStateException("USB Host service unavailable on this device")
        )
        if (!manager.hasPermission(device)) {
            return Result.failure(
                SecurityException("USB permission not granted for ${device.deviceName}")
            )
        }
        return openBulkSession(manager, device)
    }

    fun openConnection(deviceName: String): Result<UsbBulkSession> {
        val manager = usbManager ?: return Result.failure(
            IllegalStateException("USB Host service unavailable on this device")
        )
        val device = manager.deviceList[deviceName] ?: return Result.failure(
            IllegalStateException("USB printer '$deviceName' is no longer attached")
        )
        return openConnection(device)
    }

    suspend fun sendPrintData(
        deviceName: String,
        payload: ByteArray,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long> = withContext(Dispatchers.IO) {
        openConnection(deviceName).mapCatching { session ->
            session.use { active ->
                active.writeAll(payload, onProgress)
            }
        }
    }

    suspend fun sendPrintData(
        device: UsbDevice,
        payload: ByteArray,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long> = withContext(Dispatchers.IO) {
        openConnection(device).mapCatching { session ->
            session.use { active ->
                active.writeAll(payload, onProgress)
            }
        }
    }

    /**
     * Queries the IEEE-1284 Plug-and-Play Device ID string (e.g., MFG/MDL/CMD capabilities)
     * from a connected USB printer over its control endpoint.
     */
    fun readIeee1284DeviceId(session: UsbBulkSession): String? {
        val buffer = ByteArray(1024)
        val bytesRead = session.connection.controlTransfer(
            USB_DIR_IN_CLASS_INTERFACE,
            GET_DEVICE_ID_REQUEST,
            0,
            session.usbInterface.id shl 8,
            buffer,
            buffer.size,
            2_000
        )
        if (bytesRead <= 2) return null
        val declaredLen = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val actualLen = minOf(declaredLen - 2, bytesRead - 2).coerceAtLeast(0)
        return if (actualLen > 0) String(buffer, 2, actualLen, Charsets.US_ASCII).trim() else null
    }

    private fun openBulkSession(manager: UsbManager, device: UsbDevice): Result<UsbBulkSession> {
        val usbInterface = findPrinterInterface(device)
            ?: return Result.failure(IllegalStateException("No USB Printer interface with bulk OUT endpoint found"))

        val connection = manager.openDevice(device)
            ?: return Result.failure(IOException("Failed to open USB device connection"))

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return Result.failure(IOException("Failed to claim USB printer interface #${usbInterface.id}"))
        }

        var bulkOut: UsbEndpoint? = null
        var bulkIn: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) {
                    bulkOut = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_IN && bulkIn == null) {
                    bulkIn = endpoint
                }
            }
        }

        if (bulkOut == null) {
            connection.releaseInterface(usbInterface)
            connection.close()
            return Result.failure(IllegalStateException("USB printer interface has no Bulk OUT endpoint"))
        }

        return Result.success(
            UsbBulkSession(
                connection = connection,
                usbInterface = usbInterface,
                bulkOutEndpoint = bulkOut,
                bulkInEndpoint = bulkIn
            )
        )
    }

    private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        // Priority 1: Explicit USB Printer Class (0x07) with Bulk OUT endpoint
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER && hasBulkOutEndpoint(intf)) {
                return intf
            }
        }
        // Priority 2: Vendor-specific interface (0xFF) on devices that expose Printer class or thermal POS chips
        if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (hasBulkOutEndpoint(intf)) return intf
            }
        }
        return null
    }

    private fun hasBulkOutEndpoint(intf: UsbInterface): Boolean {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                return true
            }
        }
        return false
    }

    private fun guessProtocolFromName(name: String): PrinterProtocol {
        val lower = name.lowercase()
        return when {
            lower.contains("pos") || lower.contains("thermal") || lower.contains("xprinter") ||
                lower.contains("epson tm") || lower.contains("star") || lower.contains("munbyn") ->
                PrinterProtocol.ESC_POS
            lower.contains("postscript") -> PrinterProtocol.POSTSCRIPT
            else -> PrinterProtocol.PCL_RASTER
        }
    }

    private fun formatHexId(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')

    @Suppress("DEPRECATION")
    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    class UsbBulkSession(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val bulkOutEndpoint: UsbEndpoint,
        val bulkInEndpoint: UsbEndpoint?
    ) : Closeable {

        fun writeAll(
            payload: ByteArray,
            onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
        ): Long {
            var offset = 0
            val total = payload.size
            while (offset < total) {
                val length = minOf(BULK_CHUNK_SIZE, total - offset)
                val transferred = connection.bulkTransfer(
                    bulkOutEndpoint,
                    payload,
                    offset,
                    length,
                    TRANSFER_TIMEOUT_MS
                )
                if (transferred < 0) {
                    throw IOException("USB bulkTransfer failed at offset $offset (code $transferred)")
                }
                if (transferred == 0) {
                    throw IOException("USB printer stalled (0 bytes transferred at offset $offset)")
                }
                offset += transferred
                onProgress(offset.toLong(), total.toLong())
            }
            return offset.toLong()
        }

        override fun close() {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: Exception) {
            } finally {
                connection.close()
            }
        }
    }
}
