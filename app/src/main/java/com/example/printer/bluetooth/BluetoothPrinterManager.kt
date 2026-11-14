package com.example.printer.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Discovers bonded and nearby Classic Bluetooth SPP printers and streams ESC/POS or raw bytes
 * over an RFCOMM socket.
 */
class BluetoothPrinterManager(private val context: Context) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK_SIZE = 4096
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _printers = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val printers: StateFlow<List<DiscoveredPrinter>> = _printers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val foundMap = LinkedHashMap<String, DiscoveredPrinter>()
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (!hasConnectPermission()) return
                    val device: BluetoothDevice? = getBluetoothDeviceExtra(intent)
                    device?.let { addDevice(it, bonded = it.bondState == BluetoothDevice.BOND_BONDED) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    fun hasScanAndConnectPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun loadBondedPrinters() {
        val btAdapter = adapter ?: return
        if (!hasConnectPermission()) return
        try {
            btAdapter.bondedDevices?.forEach { device ->
                addDevice(device, bonded = true)
            }
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) return false
        if (!hasScanAndConnectPermissions()) return false

        registerReceiver()
        loadBondedPrinters()

        return try {
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            val started = btAdapter.startDiscovery()
            _isScanning.value = started
            started
        } catch (_: SecurityException) {
            _isScanning.value = false
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            if (hasScanAndConnectPermissions() && adapter?.isDiscovering == true) {
                adapter?.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        } finally {
            _isScanning.value = false
        }
    }

    fun unregister() {
        stopDiscovery()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: IllegalArgumentException) {
            } finally {
                receiverRegistered = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendPrintData(
        macAddress: String,
        payload: ByteArray,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val btAdapter = adapter ?: throw IllegalStateException("Bluetooth adapter unavailable")
            if (!btAdapter.isEnabled) throw IllegalStateException("Bluetooth is turned off")
            if (!hasConnectPermission()) {
                throw SecurityException("BLUETOOTH_CONNECT permission is required")
            }

            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }

            val device = btAdapter.getRemoteDevice(macAddress)
            device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                socket.connect()
                val out = socket.outputStream
                var offset = 0
                val total = payload.size
                while (offset < total) {
                    val count = minOf(CHUNK_SIZE, total - offset)
                    out.write(payload, offset, count)
                    offset += count
                    onProgress(offset.toLong(), total.toLong())
                }
                out.flush()
                offset.toLong()
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            discoveryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, bonded: Boolean) {
        val address = device.address ?: return
        val name = try {
            device.name?.takeIf { it.isNotBlank() } ?: "BT Device $address"
        } catch (_: SecurityException) {
            "BT Device $address"
        }
        val stateLabel = if (bonded) "Paired · SPP" else "Nearby · SPP"
        val printer = DiscoveredPrinter(
            id = "bt:$address",
            name = name,
            connectionType = ConnectionType.BLUETOOTH,
            address = address,
            port = 0,
            defaultProtocol = PrinterProtocol.ESC_POS,
            hasPermission = hasConnectPermission(),
            isConnected = bonded,
            details = "$address · $stateLabel"
        )
        synchronized(foundMap) {
            foundMap[address] = printer
            _printers.value = foundMap.values.toList()
        }
    }

    @Suppress("DEPRECATION")
    private fun getBluetoothDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
