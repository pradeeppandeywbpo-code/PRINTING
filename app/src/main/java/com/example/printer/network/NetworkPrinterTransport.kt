package com.example.printer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Handles network print job transmission via:
 * 1. Raw TCP Socket (JetDirect Port 9100) for PCL, PostScript, ESC/POS, or raw PDF streams.
 * 2. IPP (Internet Printing Protocol / RFC 8011) HTTP POST to port 631 (/ipp/print).
 */
class NetworkPrinterTransport {

    companion object {
        private const val SOCKET_TIMEOUT_MS = 12_000
        private const val CHUNK_SIZE = 16 * 1024
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sendViaRawSocket(
        host: String,
        port: Int,
        payload: ByteArray,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val output = socket.getOutputStream()
                var offset = 0
                val total = payload.size
                while (offset < total) {
                    val count = minOf(CHUNK_SIZE, total - offset)
                    output.write(payload, offset, count)
                    offset += count
                    onProgress(offset.toLong(), total.toLong())
                }
                output.flush()
                offset.toLong()
            }
        }
    }

    /**
     * Sends a Print-Job (0x0002) IPP 2.0 request over HTTP to http://<host>:<port>/ipp/print.
     */
    suspend fun sendViaIpp(
        host: String,
        port: Int,
        jobName: String,
        documentFormat: String,
        payload: ByteArray,
        useTls: Boolean = false,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val scheme = if (useTls) "https" else "http"
            val endpointUrl = "$scheme://$host:$port/ipp/print"
            val printerUri = "ipp://$host:$port/ipp/print"

            val ippFrame = buildIppPrintJobPacket(
                printerUri = printerUri,
                jobName = jobName,
                documentFormat = documentFormat,
                documentBytes = payload
            )

            val request = Request.Builder()
                .url(endpointUrl)
                .post(ippFrame.toRequestBody("application/ipp".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                onProgress(ippFrame.size.toLong(), ippFrame.size.toLong())
                if (!response.isSuccessful) {
                    throw IOException("IPP server returned HTTP ${response.code} ${response.message}")
                }
                val body = response.body?.bytes()
                if (body != null && body.size >= 4) {
                    val statusCode = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
                    if (statusCode >= 0x0400) {
                        throw IOException("IPP Print-Job rejected with status 0x${statusCode.toString(16)}")
                    }
                }
            }
            payload.size.toLong()
        }
    }

    /**
     * Encodes an RFC 8011 IPP Print-Job (0x0002) request packet.
     */
    fun buildIppPrintJobPacket(
        printerUri: String,
        jobName: String,
        documentFormat: String,
        documentBytes: ByteArray
    ): ByteArray {
        val baos = ByteArrayOutputStream(256 + documentBytes.size)
        DataOutputStream(baos).use { out ->
            // IPP Version 2.0
            out.writeByte(0x02)
            out.writeByte(0x00)
            // Operation ID: Print-Job (0x0002)
            out.writeShort(0x0002)
            // Request ID
            out.writeInt(1)
            // Operation attributes tag (0x01)
            out.writeByte(0x01)

            writeIppAttribute(out, tag = 0x47, name = "attributes-charset", value = "utf-8")
            writeIppAttribute(out, tag = 0x48, name = "attributes-natural-language", value = "en-us")
            writeIppAttribute(out, tag = 0x45, name = "printer-uri", value = printerUri)
            writeIppAttribute(out, tag = 0x42, name = "requesting-user-name", value = "PrintForge")
            writeIppAttribute(out, tag = 0x42, name = "job-name", value = jobName.take(64))
            writeIppAttribute(out, tag = 0x49, name = "document-format", value = documentFormat)

            // End of attributes tag (0x03)
            out.writeByte(0x03)
            out.write(documentBytes)
            out.flush()
        }
        return baos.toByteArray()
    }

    private fun writeIppAttribute(out: DataOutputStream, tag: Int, name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(valueBytes.size)
        out.write(valueBytes)
    }
}
