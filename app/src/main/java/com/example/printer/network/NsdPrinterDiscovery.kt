package com.example.printer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address

/**
 * Discovers Wi-Fi / LAN network printers via Android NsdManager (mDNS / DNS-SD)
 * across IPP (_ipp._tcp.), IPPS (_ipps._tcp.), Raw JetDirect (_pdl-datastream._tcp.),
 * and LPD (_printer._tcp.) service types.
 */
class NsdPrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "NsdPrinterDiscovery"
        private val SERVICE_TYPES = listOf(
            "_ipp._tcp.",
            "_ipps._tcp.",
            "_pdl-datastream._tcp.",
            "_printer._tcp."
        )
    }

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveMutex = Mutex()

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val printerMap = LinkedHashMap<String, DiscoveredPrinter>()

    fun startDiscovery() {
        val manager = nsdManager ?: return
        stopDiscovery()

        acquireMulticastLock()
        _isScanning.value = true

        SERVICE_TYPES.forEach { serviceType ->
            val listener = createDiscoveryListener(serviceType)
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                activeListeners.add(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start NSD discovery for $serviceType: ${e.message}")
            }
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager
        activeListeners.forEach { listener ->
            try {
                manager?.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
            } catch (_: Exception) {
            }
        }
        activeListeners.clear()
        releaseMulticastLock()
        _isScanning.value = false
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS discovery started: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    resolveServiceSequentially(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(printerMap) {
                    val key = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                    if (printerMap.remove(key) != null) {
                        _discoveredPrinters.value = printerMap.values.toList()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start mDNS failed for $serviceType ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop mDNS failed for $serviceType ($errorCode)")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveServiceSequentially(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        resolveMutex.withLock {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                            if (continuation.isActive) continuation.resume(Unit) {}
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = extractHostAddress(resolved)
                            val port = resolved.port
                            if (!host.isNullOrBlank() && port > 0) {
                                val protocol = when {
                                    resolved.serviceType.contains("_ipp") -> PrinterProtocol.PDF_STREAM
                                    resolved.serviceType.contains("_pdl-datastream") -> PrinterProtocol.PCL_RASTER
                                    else -> PrinterProtocol.PCL_RASTER
                                }
                                val id = "mdns:$host:$port"
                                val printer = DiscoveredPrinter(
                                    id = id,
                                    name = resolved.serviceName,
                                    connectionType = ConnectionType.WIFI_MDNS,
                                    address = host,
                                    port = port,
                                    defaultProtocol = protocol,
                                    isConnected = true,
                                    serviceType = resolved.serviceType,
                                    details = "$host:$port · ${resolved.serviceType.trimEnd('.')}"
                                )
                                synchronized(printerMap) {
                                    val key = "${resolved.serviceType}:${resolved.serviceName}"
                                    printerMap[key] = printer
                                    _discoveredPrinters.value = printerMap.values.toList()
                                }
                            }
                            if (continuation.isActive) continuation.resume(Unit) {}
                        }
                    })
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(Unit) {}
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractHostAddress(resolved: NsdServiceInfo): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val ipv4 = resolved.hostAddresses.firstOrNull { it is Inet4Address }
            return (ipv4 ?: resolved.hostAddresses.firstOrNull())?.hostAddress
        }
        return resolved.host?.hostAddress
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("PrintForgeMdnsLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }
}
