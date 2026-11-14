package com.example.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.local.PrintDatabase
import com.example.data.local.PrintHistoryRepository
import com.example.data.local.PrintJobEntity
import com.example.data.remote.CloudPrintRepository
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.DocumentSource
import com.example.domain.model.PrintSettings
import com.example.domain.model.PrinterProtocol
import com.example.domain.model.SpoolerProgress
import com.example.printer.bluetooth.BluetoothPrinterManager
import com.example.printer.network.NetworkPrinterTransport
import com.example.printer.network.NsdPrinterDiscovery
import com.example.printer.usb.UsbPrinterManager
import com.example.rendering.DocumentRenderEngine
import com.example.rendering.SamplePdfGenerator
import com.example.spooler.PrintSpooler
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiscoveryTab(val label: String) {
    USB("USB OTG"),
    WIFI("Wi-Fi / LAN"),
    BLUETOOTH("Bluetooth")
}

data class PrintUiState(
    val selectedTab: DiscoveryTab = DiscoveryTab.USB,
    val usbPrinters: List<DiscoveredPrinter> = emptyList(),
    val wifiPrinters: List<DiscoveredPrinter> = emptyList(),
    val bluetoothPrinters: List<DiscoveredPrinter> = emptyList(),
    val savedPrinters: List<DiscoveredPrinter> = emptyList(),
    val isWifiScanning: Boolean = false,
    val isBluetoothScanning: Boolean = false,
    val selectedPrinter: DiscoveredPrinter? = null,
    val activeDocument: DocumentSource? = null,
    val totalPages: Int = 0,
    val currentPreviewPage: Int = 0,
    val previewBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val previewError: String? = null,
    val printSettings: PrintSettings = PrintSettings(),
    val spoolerProgress: SpoolerProgress = SpoolerProgress.Idle,
    val printJobs: List<PrintJobEntity> = emptyList(),
    val statusBanner: String? = null
)

class PrintViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    userDisplayName: String?,
    userEmail: String?,
    val usbPrinterManager: UsbPrinterManager,
    val nsdDiscovery: NsdPrinterDiscovery,
    val bluetoothManager: BluetoothPrinterManager,
    private val renderEngine: DocumentRenderEngine,
    private val localHistoryRepository: PrintHistoryRepository,
    private val cloudRepository: CloudPrintRepository,
    private val printSpooler: PrintSpooler
) : ViewModel() {

    private val localState = MutableStateFlow(PrintUiState())
    private var previewRenderJob: Job? = null

    private val hardwareStreams = combine(
        usbPrinterManager.usbPrinters,
        nsdDiscovery.discoveredPrinters,
        nsdDiscovery.isScanning,
        bluetoothManager.printers,
        bluetoothManager.isScanning
    ) { usb, wifi, wifiScan, bt, btScan ->
        HardwareSnapshot(usb, wifi, wifiScan, bt, btScan)
    }

    private val cloudSavedPrintersFlow = cloudRepository.observeSavedPrinters(currentUserId)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing cloud saved printers", firestoreError ?: error)
            emit(emptyList())
        }

    private val cloudPrintJobsFlow = cloudRepository.observePrintJobs(currentUserId)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing cloud print jobs", firestoreError ?: error)
            emit(emptyList())
        }

    private val persistenceStreams = combine(
        cloudSavedPrintersFlow,
        localHistoryRepository.savedPrinters,
        cloudPrintJobsFlow,
        localHistoryRepository.printJobs,
        printSpooler.progress
    ) { cloudSaved, localSaved, cloudJobs, localJobs, spoolProgress ->
        val mergedPrinters = (cloudSaved + localSaved).distinctBy { it.id }
        val mergedJobs = if (cloudJobs.isNotEmpty()) cloudJobs else localJobs
        PersistenceSnapshot(mergedPrinters, mergedJobs, spoolProgress)
    }

    val uiState: StateFlow<PrintUiState> = combine(
        localState,
        hardwareStreams,
        persistenceStreams,
        usbPrinterManager.lastPermissionStatus
    ) { base, hw, persist, usbPermStatus ->
        val refreshedSelected = base.selectedPrinter?.let { sel ->
            hw.usb.firstOrNull { it.id == sel.id } ?: sel
        }
        base.copy(
            usbPrinters = hw.usb,
            wifiPrinters = hw.wifi,
            isWifiScanning = hw.wifiScan,
            bluetoothPrinters = hw.bt,
            isBluetoothScanning = hw.btScan,
            savedPrinters = persist.savedPrinters,
            printJobs = persist.jobs,
            spoolerProgress = persist.spoolProgress,
            selectedPrinter = refreshedSelected,
            statusBanner = base.statusBanner ?: usbPermStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PrintUiState()
    )

    init {
        usbPrinterManager.register()
        if (bluetoothManager.hasConnectPermission()) {
            bluetoothManager.loadBondedPrinters()
        }
        nsdDiscovery.startDiscovery()
        viewModelScope.launch {
            cloudRepository.ensureUserProfile(
                displayName = userDisplayName ?: "PrintForge User",
                email = userEmail
            )
        }
    }

    fun selectDiscoveryTab(tab: DiscoveryTab) {
        localState.update { it.copy(selectedTab = tab) }
    }

    fun scanUsbPrinters() {
        val found = usbPrinterManager.scanConnectedPrinters()
        localState.update {
            it.copy(
                statusBanner = if (found.isEmpty()) {
                    "No USB printer detected. Attach a printer via USB OTG."
                } else {
                    "Found ${found.size} USB printer(s)"
                }
            )
        }
    }

    fun requestUsbPermission(printer: DiscoveredPrinter) {
        val deviceName = printer.usbDeviceName ?: printer.address
        val alreadyGranted = usbPrinterManager.requestPermission(deviceName)
        if (alreadyGranted) {
            selectPrinter(printer.copy(hasPermission = true))
        }
    }

    fun toggleWifiDiscovery() {
        if (uiState.value.isWifiScanning) {
            nsdDiscovery.stopDiscovery()
        } else {
            nsdDiscovery.startDiscovery()
        }
    }

    fun addManualNetworkPrinter(
        name: String,
        host: String,
        port: Int,
        protocol: PrinterProtocol
    ) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        val cleanName = name.trim().ifEmpty { "LAN Printer ($cleanHost)" }
        val printer = DiscoveredPrinter(
            id = "raw:$cleanHost:$port",
            name = cleanName,
            connectionType = ConnectionType.WIFI_RAW,
            address = cleanHost,
            port = port,
            defaultProtocol = protocol,
            isConnected = true,
            details = "$cleanHost:$port · Manual ${protocol.shortTag}"
        )
        viewModelScope.launch {
            localHistoryRepository.savePrinter(printer)
            cloudRepository.savePrinter(printer)
        }
        selectPrinter(printer)
        localState.update { it.copy(statusBanner = "Added & synced $cleanName ($cleanHost:$port)") }
    }

    fun startBluetoothScan() {
        bluetoothManager.loadBondedPrinters()
        val started = bluetoothManager.startDiscovery()
        localState.update {
            it.copy(
                statusBanner = if (started) {
                    "Scanning for Classic Bluetooth SPP printers..."
                } else {
                    "Enable Bluetooth and grant scan permission to discover printers."
                }
            )
        }
    }

    fun selectPrinter(printer: DiscoveredPrinter) {
        localState.update {
            it.copy(
                selectedPrinter = printer,
                statusBanner = "Active printer: ${printer.name}"
            )
        }
        viewModelScope.launch {
            localHistoryRepository.savePrinter(printer)
            cloudRepository.savePrinter(printer)
        }
    }

    fun removeSavedPrinter(id: String) {
        viewModelScope.launch {
            localHistoryRepository.removeSavedPrinter(id)
            cloudRepository.deletePrinter(id)
        }
        if (localState.value.selectedPrinter?.id == id) {
            localState.update { it.copy(selectedPrinter = null) }
        }
    }

    fun loadPdfUri(uri: Uri) {
        viewModelScope.launch {
            val title = resolveDisplayName(appContext.contentResolver, uri) ?: "Document.pdf"
            val pageCount = renderEngine.getPdfPageCount(uri).getOrElse { error ->
                localState.update {
                    it.copy(
                        previewError = "Could not open PDF: ${error.message}",
                        statusBanner = "Failed to load PDF"
                    )
                }
                return@launch
            }
            val source = DocumentSource.Pdf(uri = uri, title = title, pageCount = pageCount)
            setActiveDocument(source, pageCount)
        }
    }

    fun loadImageUri(uri: Uri) {
        val title = resolveDisplayName(appContext.contentResolver, uri) ?: "Photo.jpg"
        val source = DocumentSource.Image(uri = uri, title = title)
        setActiveDocument(source, totalPages = 1)
    }

    fun loadWebCapture(url: String, title: String, pageFilePaths: List<String>) {
        if (pageFilePaths.isEmpty()) return
        val source = DocumentSource.WebCapture(
            url = url,
            title = title.ifBlank { "Web Page" },
            pageFilePaths = pageFilePaths
        )
        setActiveDocument(source, totalPages = pageFilePaths.size)
    }

    fun loadSampleSignedPdf() {
        viewModelScope.launch {
            val uri = SamplePdfGenerator.createSampleSignedPdf(appContext)
            val pageCount = renderEngine.getPdfPageCount(uri).getOrDefault(2)
            val source = DocumentSource.Pdf(
                uri = uri,
                title = "Sample_Signed_WorkOrder.pdf",
                pageCount = pageCount
            )
            setActiveDocument(source, pageCount)
        }
    }

    private fun setActiveDocument(source: DocumentSource, totalPages: Int) {
        localState.update {
            it.copy(
                activeDocument = source,
                totalPages = totalPages,
                currentPreviewPage = 0,
                previewError = null,
                printSettings = it.printSettings.copy(
                    useAllPages = true,
                    customPageRange = if (totalPages > 1) "1-$totalPages" else "1"
                ),
                statusBanner = "Loaded ${source.title} ($totalPages page${if (totalPages == 1) "" else "s"})"
            )
        }
        renderCurrentPreview()
    }

    fun setPreviewPage(pageIndex: Int) {
        val maxIdx = (localState.value.totalPages - 1).coerceAtLeast(0)
        val clamped = pageIndex.coerceIn(0, maxIdx)
        if (clamped != localState.value.currentPreviewPage) {
            localState.update { it.copy(currentPreviewPage = clamped) }
            renderCurrentPreview()
        }
    }

    fun updateSettings(transform: (PrintSettings) -> PrintSettings) {
        val updated = transform(localState.value.printSettings)
        localState.update { it.copy(printSettings = updated) }
        renderCurrentPreview()
    }

    private fun renderCurrentPreview() {
        val state = localState.value
        val doc = state.activeDocument ?: return
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            localState.update { it.copy(isRenderingPreview = true, previewError = null) }
            val result = renderEngine.renderPage(
                source = doc,
                pageIndex = state.currentPreviewPage,
                paperSize = state.printSettings.paperSize,
                orientation = state.printSettings.orientation,
                colorMode = state.printSettings.colorMode,
                dpi = DocumentRenderEngine.PREVIEW_DPI
            )
            result.fold(
                onSuccess = { bmp ->
                    localState.update {
                        it.copy(
                            previewBitmap = bmp,
                            isRenderingPreview = false,
                            previewError = null
                        )
                    }
                },
                onFailure = { err ->
                    localState.update {
                        it.copy(
                            isRenderingPreview = false,
                            previewError = err.message ?: "Rendering failed"
                        )
                    }
                }
            )
        }
    }

    fun startPrintJob() {
        val current = uiState.value
        val doc = current.activeDocument
        val printer = current.selectedPrinter
        if (doc == null) {
            localState.update { it.copy(statusBanner = "Select a document to print first.") }
            return
        }
        if (printer == null) {
            localState.update { it.copy(statusBanner = "Select a USB, Wi-Fi, or Bluetooth printer first.") }
            return
        }
        if (printer.connectionType == ConnectionType.USB_OTG && !printer.hasPermission) {
            requestUsbPermission(printer)
            return
        }

        viewModelScope.launch {
            printSpooler.printDocument(
                source = doc,
                totalPages = current.totalPages,
                printer = printer,
                settings = current.printSettings
            )
        }
    }

    fun dismissSpoolerProgress() {
        printSpooler.resetProgress()
    }

    fun clearJobHistory() {
        viewModelScope.launch {
            localHistoryRepository.clearJobs()
            cloudRepository.clearPrintJobs()
        }
    }

    fun clearBanner() {
        localState.update { it.copy(statusBanner = null) }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbPrinterManager.unregister()
        nsdDiscovery.stopDiscovery()
        bluetoothManager.unregister()
    }

    private data class HardwareSnapshot(
        val usb: List<DiscoveredPrinter>,
        val wifi: List<DiscoveredPrinter>,
        val wifiScan: Boolean,
        val bt: List<DiscoveredPrinter>,
        val btScan: Boolean
    )

    private data class PersistenceSnapshot(
        val savedPrinters: List<DiscoveredPrinter>,
        val jobs: List<PrintJobEntity>,
        val spoolProgress: SpoolerProgress
    )

    companion object {
        private const val TAG = "PrintViewModel"

        fun provideFactory(
            currentUserId: String,
            userDisplayName: String?,
            userEmail: String?
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val appCtx = app.applicationContext
                val databaseId = appCtx.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(databaseId)
                val cloudRepo = CloudPrintRepository(firestore)
                val localRepo = PrintHistoryRepository(PrintDatabase.getInstance(appCtx).printDao())
                val usbMgr = UsbPrinterManager(appCtx)
                val nsd = NsdPrinterDiscovery(appCtx)
                val btMgr = BluetoothPrinterManager(appCtx)
                val netTransport = NetworkPrinterTransport()
                val renderEngine = DocumentRenderEngine(appCtx)
                val spooler = PrintSpooler(
                    renderEngine = renderEngine,
                    usbPrinterManager = usbMgr,
                    networkTransport = netTransport,
                    bluetoothManager = btMgr,
                    historyRepository = localRepo,
                    cloudRepository = cloudRepo
                )
                PrintViewModel(
                    appContext = appCtx,
                    currentUserId = currentUserId,
                    userDisplayName = userDisplayName,
                    userEmail = userEmail,
                    usbPrinterManager = usbMgr,
                    nsdDiscovery = nsd,
                    bluetoothManager = btMgr,
                    renderEngine = renderEngine,
                    localHistoryRepository = localRepo,
                    cloudRepository = cloudRepo,
                    printSpooler = spooler
                )
            }
        }
    }
}
