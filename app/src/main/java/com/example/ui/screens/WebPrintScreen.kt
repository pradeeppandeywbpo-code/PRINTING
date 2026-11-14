package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.rendering.WebCaptureHelper
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPrintScreen(
    onCapturedToPreview: (url: String, title: String, pageFiles: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("https://example.com") }
    var isLoading by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Web Page") }

    val webView = remember {
        // Required so full scrollable document renders cleanly onto Canvas for 300 DPI printing
        WebView.enableSlowWholeDocumentDraw()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "Web Page")
                }
            }
            loadDataWithBaseURL(
                "https://printforge.local/",
                WebCaptureHelper.SAMPLE_INVOICE_HTML,
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    fun navigateUrl() {
        val raw = urlText.trim()
        if (raw.isEmpty()) return
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
        urlText = normalized
        isLoading = true
        webView.loadUrl(normalized)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text(stringResource(R.string.web_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { navigateUrl() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("web_url_input")
            )
            Button(
                onClick = { navigateUrl() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("web_go_button")
            ) {
                Text(stringResource(R.string.web_load))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    isLoading = false
                    pageTitle = "Sample Invoice"
                    webView.loadDataWithBaseURL(
                        "https://printforge.local/",
                        WebCaptureHelper.SAMPLE_INVOICE_HTML,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("web_sample_invoice_button")
            ) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.web_load_sample_invoice))
            }

            Button(
                onClick = {
                    scope.launch {
                        isCapturing = true
                        val result = WebCaptureHelper.captureWebViewPages(context, webView)
                        isCapturing = false
                        result.onSuccess { files ->
                            onCapturedToPreview(urlText, pageTitle, files)
                        }
                    }
                },
                enabled = !isCapturing,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("web_capture_print_button")
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(18.dp).width(18.dp)
                    )
                } else {
                    Icon(Icons.Default.Print, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.web_capture_print), maxLines = 1)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
