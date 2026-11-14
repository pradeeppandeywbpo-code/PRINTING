package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.SpoolerProgress
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.EmeraldReady

@Composable
fun SpoolerProgressDialog(
    progress: SpoolerProgress,
    onDismiss: () -> Unit
) {
    if (progress is SpoolerProgress.Idle) return

    AlertDialog(
        onDismissRequest = {
            if (progress is SpoolerProgress.Completed || progress is SpoolerProgress.Failed) {
                onDismiss()
            }
        },
        icon = {
            when (progress) {
                is SpoolerProgress.Completed -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = EmeraldReady,
                    modifier = Modifier.size(36.dp)
                )
                is SpoolerProgress.Failed -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = CrimsonError,
                    modifier = Modifier.size(36.dp)
                )
                else -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        },
        title = {
            Text(
                when (progress) {
                    is SpoolerProgress.Rendering -> "Rendering ${progress.dpi} DPI Bitmap"
                    is SpoolerProgress.Encoding -> "Encoding ${progress.protocolName} Stream"
                    is SpoolerProgress.Transmitting -> "Sending via ${progress.transportLabel}"
                    is SpoolerProgress.Completed -> "Print Job Sent"
                    is SpoolerProgress.Failed -> "Spooler Failed"
                    SpoolerProgress.Idle -> ""
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (progress) {
                    is SpoolerProgress.Rendering -> {
                        Text("Flattening signatures & layers on page ${progress.currentPage} of ${progress.totalPages}…")
                        LinearProgressIndicator(
                            progress = { progress.currentPage.toFloat() / progress.totalPages.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is SpoolerProgress.Encoding -> {
                        Text("Converting page ${progress.page} of ${progress.totalPages} to ${progress.protocolName}…")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is SpoolerProgress.Transmitting -> {
                        val kbSent = progress.bytesSent / 1024
                        val kbTotal = (progress.totalBytes / 1024).coerceAtLeast(1)
                        Text("Transferred $kbSent KB / $kbTotal KB")
                        LinearProgressIndicator(
                            progress = {
                                (progress.bytesSent.toFloat() / progress.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is SpoolerProgress.Completed -> {
                        Text("${progress.jobTitle} (${progress.bytesSent / 1024} KB) spooled via ${progress.protocolUsed}.")
                    }
                    is SpoolerProgress.Failed -> {
                        Text(progress.reason, color = CrimsonError)
                    }
                    SpoolerProgress.Idle -> {}
                }
            }
        },
        confirmButton = {
            if (progress is SpoolerProgress.Completed || progress is SpoolerProgress.Failed) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("spooler_done_button")
                ) {
                    Text(stringResource(R.string.spooler_done))
                }
            }
        }
    )
}
