package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import com.example.ui.DiscoveryTab
import com.example.ui.PrintUiState
import com.example.ui.theme.AmberPending
import com.example.ui.theme.EmeraldReady

@Composable
fun PrintersHubScreen(
    uiState: PrintUiState,
    onSelectTab: (DiscoveryTab) -> Unit,
    onScanUsb: () -> Unit,
    onRequestUsbPermission: (DiscoveredPrinter) -> Unit,
    onToggleWifiScan: () -> Unit,
    onAddManualPrinter: (name: String, host: String, port: Int, protocol: PrinterProtocol) -> Unit,
    onScanBluetooth: () -> Unit,
    onSelectPrinter: (DiscoveredPrinter) -> Unit,
    onRemoveSavedPrinter: (String) -> Unit,
    onPickPdf: () -> Unit,
    onPickImage: () -> Unit,
    onOpenWebPrint: () -> Unit,
    onLoadSamplePdf: () -> Unit,
    onOpenPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showManualDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroStudioCard(
                    selectedPrinter = uiState.selectedPrinter,
                    activeDocumentTitle = uiState.activeDocument?.title,
                    totalPages = uiState.totalPages,
                    onOpenPreview = onOpenPreview
                )
            }

            item {
                Text(
                    text = stringResource(R.string.section_document_sources),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                DocumentSourceGrid(
                    onPickPdf = onPickPdf,
                    onPickImage = onPickImage,
                    onOpenWebPrint = onOpenWebPrint,
                    onLoadSamplePdf = onLoadSamplePdf
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.section_discovery),
                        style = MaterialTheme.typography.titleLarge
                    )
                    val activeTab = uiState.selectedTab
                    when (activeTab) {
                        DiscoveryTab.USB -> FilledTonalButton(
                            onClick = onScanUsb,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scan_usb_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.usb_scan_btn))
                        }
                        DiscoveryTab.WIFI -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showManualDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("add_manual_ip_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.wifi_add_manual))
                            }
                            FilledTonalButton(
                                onClick = onToggleWifiScan,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("scan_wifi_button")
                            ) {
                                if (uiState.isWifiScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (uiState.isWifiScanning) "Stop" else stringResource(R.string.wifi_scan_btn))
                            }
                        }
                        DiscoveryTab.BLUETOOTH -> FilledTonalButton(
                            onClick = onScanBluetooth,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scan_bluetooth_button")
                        ) {
                            if (uiState.isBluetoothScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.bt_scan_btn))
                        }
                    }
                }
            }

            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DiscoveryTab.entries.forEachIndexed { index, tab ->
                        val count = when (tab) {
                            DiscoveryTab.USB -> uiState.usbPrinters.size
                            DiscoveryTab.WIFI -> uiState.wifiPrinters.size
                            DiscoveryTab.BLUETOOTH -> uiState.bluetoothPrinters.size
                        }
                        SegmentedButton(
                            selected = uiState.selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DiscoveryTab.entries.size
                            ),
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                        ) {
                            Text(if (count > 0) "${tab.label} ($count)" else tab.label)
                        }
                    }
                }
            }

            val visiblePrinters = when (uiState.selectedTab) {
                DiscoveryTab.USB -> uiState.usbPrinters
                DiscoveryTab.WIFI -> uiState.wifiPrinters
                DiscoveryTab.BLUETOOTH -> uiState.bluetoothPrinters
            }

            if (visiblePrinters.isEmpty()) {
                item {
                    DiscoveryEmptyCard(tab = uiState.selectedTab)
                }
            } else {
                items(visiblePrinters, key = { it.id }) { printer ->
                    PrinterListCard(
                        printer = printer,
                        isSelected = uiState.selectedPrinter?.id == printer.id,
                        onSelect = { onSelectPrinter(printer) },
                        onRequestPermission = { onRequestUsbPermission(printer) }
                    )
                }
            }

            if (uiState.savedPrinters.isNotEmpty()) {
                item {
                    Text(
                        text = "Saved Printers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(uiState.savedPrinters, key = { "saved_${it.id}" }) { saved ->
                            SavedPrinterChip(
                                printer = saved,
                                isSelected = uiState.selectedPrinter?.id == saved.id,
                                onSelect = { onSelectPrinter(saved) },
                                onDelete = { onRemoveSavedPrinter(saved.id) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showManualDialog) {
        ManualIpPrinterDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { name, host, port, protocol ->
                showManualDialog = false
                onAddManualPrinter(name, host, port, protocol)
            }
        )
    }
}

@Composable
private fun HeroStudioCard(
    selectedPrinter: DiscoveredPrinter?,
    activeDocumentTitle: String?,
    totalPages: Int,
    onOpenPreview: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_printer_hero),
                    contentDescription = "PrintForge hardware studio illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hero_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedPrinter != null) {
                            "Printer: ${selectedPrinter.name}"
                        } else {
                            "No Printer Selected"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (activeDocumentTitle != null) {
                            "Doc: $activeDocumentTitle ($totalPages pg)"
                        } else {
                            "Ready for PDF, Image, or Web capture"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                AnimatedVisibility(visible = activeDocumentTitle != null) {
                    Button(
                        onClick = onOpenPreview,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("hero_open_preview_button")
                    ) {
                        Text("Preview")
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentSourceGrid(
    onPickPdf: () -> Unit,
    onPickImage: () -> Unit,
    onOpenWebPrint: () -> Unit,
    onLoadSamplePdf: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceActionTile(
                title = stringResource(R.string.action_pick_pdf),
                subtitle = "300 DPI PdfRenderer",
                icon = Icons.Default.PictureAsPdf,
                onClick = onPickPdf,
                modifier = Modifier
                    .weight(1f)
                    .testTag("pick_pdf_button")
            )
            SourceActionTile(
                title = stringResource(R.string.action_pick_image),
                subtitle = "JPG / PNG Photo",
                icon = Icons.Default.Image,
                onClick = onPickImage,
                modifier = Modifier
                    .weight(1f)
                    .testTag("pick_image_button")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceActionTile(
                title = stringResource(R.string.action_web_page),
                subtitle = "URL / HTML Capture",
                icon = Icons.Default.Language,
                onClick = onOpenWebPrint,
                modifier = Modifier
                    .weight(1f)
                    .testTag("open_web_print_button")
            )
            SourceActionTile(
                title = stringResource(R.string.action_sample_pdf),
                subtitle = "Signed Stamp Test",
                icon = Icons.Default.Verified,
                onClick = onLoadSamplePdf,
                modifier = Modifier
                    .weight(1f)
                    .testTag("load_sample_pdf_button")
            )
        }
    }
}

@Composable
private fun SourceActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PrinterListCard(
    printer: DiscoveredPrinter,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val icon = when (printer.connectionType) {
        ConnectionType.USB_OTG -> Icons.Default.Usb
        ConnectionType.WIFI_MDNS, ConnectionType.WIFI_RAW -> Icons.Default.Wifi
        ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
    }

    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("printer_card_${printer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = printer.connectionType.label,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = printer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = printer.defaultProtocol.shortTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = printer.details,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            if (printer.connectionType == ConnectionType.USB_OTG && !printer.hasPermission) {
                FilledTonalButton(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("grant_usb_perm_${printer.id}")
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Allow USB")
                }
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected printer",
                    tint = EmeraldReady,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SavedPrinterChip(
    printer: DiscoveredPrinter,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            AssistChip(
                onClick = onSelect,
                label = {
                    Text(
                        text = "${printer.name} (${printer.connectionType.label})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                border = null
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove saved printer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DiscoveryEmptyCard(tab: DiscoveryTab) {
    val (title, body, icon) = when (tab) {
        DiscoveryTab.USB -> Triple(
            stringResource(R.string.usb_empty_title),
            stringResource(R.string.usb_empty_desc),
            Icons.Default.Usb
        )
        DiscoveryTab.WIFI -> Triple(
            stringResource(R.string.wifi_empty_title),
            stringResource(R.string.wifi_empty_desc),
            Icons.Default.Wifi
        )
        DiscoveryTab.BLUETOOTH -> Triple(
            stringResource(R.string.bt_empty_title),
            stringResource(R.string.bt_empty_desc),
            Icons.Default.Bluetooth
        )
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ManualIpPrinterDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int, protocol: PrinterProtocol) -> Unit
) {
    var name by remember { mutableStateOf("Office LaserJet") }
    var host by remember { mutableStateOf("192.168.1.50") }
    var portText by remember { mutableStateOf("9100") }
    var selectedProtocol by remember { mutableStateOf(PrinterProtocol.PCL_RASTER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Network Printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Printer Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_printer_name_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP Address") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(2f)
                            .testTag("manual_printer_ip_input")
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_printer_port_input")
                    )
                }
                Text(
                    text = "Driver Protocol (9100 = Raw Socket, 631 = IPP)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val choices = listOf(
                        PrinterProtocol.PCL_RASTER,
                        PrinterProtocol.PDF_STREAM,
                        PrinterProtocol.POSTSCRIPT,
                        PrinterProtocol.ESC_POS
                    )
                    items(choices) { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                if (proto == PrinterProtocol.PDF_STREAM && portText == "9100") {
                                    portText = "631"
                                } else if (proto != PrinterProtocol.PDF_STREAM && portText == "631") {
                                    portText = "9100"
                                }
                            },
                            label = { Text(proto.shortTag) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 9100
                    onConfirm(name, host, port, selectedProtocol)
                },
                enabled = host.isNotBlank(),
                modifier = Modifier.testTag("confirm_manual_printer_button")
            ) {
                Text("Add & Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
