package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.auth.GoogleAuthHelper
import com.example.auth.authStateFlow
import com.example.ui.DiscoveryTab
import com.example.ui.PrintViewModel
import com.example.ui.screens.JobsScreen
import com.example.ui.screens.PrintPreviewScreen
import com.example.ui.screens.PrintersHubScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.screens.WebPrintScreen
import com.example.ui.theme.PrintForgeTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

private object Routes {
    const val PRINTERS = "printers_hub"
    const val PREVIEW = "print_preview"
    const val WEB = "web_print"
    const val JOBS = "spooler_jobs"
}

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {

    private var pendingUsbAttached by mutableStateOf(false)
    private var pendingDocumentUri by mutableStateOf<Pair<Uri, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureIntent(intent)

        setContent {
            PrintForgeTheme {
                AppNavigation(
                    pendingUsbAttached = pendingUsbAttached,
                    onConsumeUsbAttached = { pendingUsbAttached = false },
                    pendingDocumentUri = pendingDocumentUri,
                    onConsumeDocumentUri = { pendingDocumentUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIntent(intent)
    }

    private fun captureIntent(intent: Intent?) {
        when (intent?.action) {
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                pendingUsbAttached = true
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    val mime = contentResolver.getType(uri).orEmpty()
                    pendingDocumentUri = uri to mime
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    pendingUsbAttached: Boolean,
    onConsumeUsbAttached: () -> Unit,
    pendingDocumentUri: Pair<Uri, String>?,
    onConsumeDocumentUri: () -> Unit
) {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        SignInScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            val viewModel: PrintViewModel = viewModel(
                key = user.uid,
                factory = PrintViewModel.provideFactory(
                    currentUserId = user.uid,
                    userDisplayName = user.displayName,
                    userEmail = user.email
                )
            )
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val credentialManager = remember { CredentialManager.create(context) }

            LaunchedEffect(pendingUsbAttached) {
                if (pendingUsbAttached) {
                    viewModel.selectDiscoveryTab(DiscoveryTab.USB)
                    viewModel.scanUsbPrinters()
                    onConsumeUsbAttached()
                }
            }

            LaunchedEffect(pendingDocumentUri) {
                pendingDocumentUri?.let { (uri, mime) ->
                    if (mime.startsWith("image/")) {
                        viewModel.loadImageUri(uri)
                    } else {
                        viewModel.loadPdfUri(uri)
                    }
                    onConsumeDocumentUri()
                }
            }

            PrintForgeApp(
                viewModel = viewModel,
                onSignOut = {
                    GoogleAuthHelper.signOut(credentialManager, scope)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintForgeApp(
    viewModel: PrintViewModel,
    onSignOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val navTabs = remember {
        listOf(
            NavTab(Routes.PRINTERS, R.string.nav_printers, Icons.Filled.Print, Icons.Outlined.Print),
            NavTab(Routes.PREVIEW, R.string.nav_preview, Icons.Filled.Preview, Icons.Outlined.Preview),
            NavTab(Routes.WEB, R.string.nav_web, Icons.Filled.Language, Icons.Outlined.Language),
            NavTab(Routes.JOBS, R.string.nav_jobs, Icons.Filled.History, Icons.Outlined.History)
        )
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.loadPdfUri(it)
            navigateTo(Routes.PREVIEW)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            viewModel.loadImageUri(it)
            navigateTo(Routes.PREVIEW)
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startBluetoothScan()
        }
    }

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearBanner()
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        navTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail {
                        navTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_${tab.route}")
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.PRINTERS,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(Routes.PRINTERS) {
                        PrintersHubScreen(
                            uiState = uiState,
                            onSelectTab = viewModel::selectDiscoveryTab,
                            onScanUsb = viewModel::scanUsbPrinters,
                            onRequestUsbPermission = viewModel::requestUsbPermission,
                            onToggleWifiScan = viewModel::toggleWifiDiscovery,
                            onAddManualPrinter = viewModel::addManualNetworkPrinter,
                            onScanBluetooth = {
                                if (viewModel.bluetoothManager.hasScanAndConnectPermissions()) {
                                    viewModel.startBluetoothScan()
                                } else {
                                    btPermissionLauncher.launch(
                                        viewModel.bluetoothManager.requiredPermissions()
                                    )
                                }
                            },
                            onSelectPrinter = viewModel::selectPrinter,
                            onRemoveSavedPrinter = viewModel::removeSavedPrinter,
                            onPickPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            onPickImage = {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenWebPrint = { navigateTo(Routes.WEB) },
                            onLoadSamplePdf = {
                                viewModel.loadSampleSignedPdf()
                                navigateTo(Routes.PREVIEW)
                            },
                            onOpenPreview = { navigateTo(Routes.PREVIEW) }
                        )
                    }

                    composable(Routes.PREVIEW) {
                        PrintPreviewScreen(
                            uiState = uiState,
                            onPageChange = viewModel::setPreviewPage,
                            onUpdateSettings = viewModel::updateSettings,
                            onPrintClick = {
                                if (uiState.selectedPrinter == null) {
                                    navigateTo(Routes.PRINTERS)
                                } else {
                                    viewModel.startPrintJob()
                                }
                            },
                            onChooseDocumentClick = { navigateTo(Routes.PRINTERS) },
                            onDismissSpooler = viewModel::dismissSpoolerProgress
                        )
                    }

                    composable(Routes.WEB) {
                        WebPrintScreen(
                            onCapturedToPreview = { url, title, files ->
                                viewModel.loadWebCapture(url, title, files)
                                navigateTo(Routes.PREVIEW)
                            }
                        )
                    }

                    composable(Routes.JOBS) {
                        JobsScreen(
                            jobs = uiState.printJobs,
                            onClearHistory = viewModel::clearJobHistory
                        )
                    }
                }
            }
        }
    }
}
