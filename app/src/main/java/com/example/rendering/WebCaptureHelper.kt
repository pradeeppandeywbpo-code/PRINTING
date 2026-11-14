package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Captures the full scrollable content of a WebView (using enableSlowWholeDocumentDraw)
 * and slices it into paginated PNG files ready for 300 DPI sheet compositing.
 */
object WebCaptureHelper {

    suspend fun captureWebViewPages(context: Context, webView: WebView): Result<List<String>> {
        val rawBitmap = try {
            val width = webView.width.coerceAtLeast(1080)
            val contentHeightPx = (webView.contentHeight * webView.scale)
                .roundToInt()
                .coerceAtLeast(webView.height.coerceAtLeast(1400))
                .coerceAtMost(16_000)

            val bitmap = Bitmap.createBitmap(width, contentHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            bitmap
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "web_pages").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                val sliceHeight = (rawBitmap.width * 1.414f).roundToInt().coerceAtLeast(400)
                val pageCount = ceil(rawBitmap.height.toFloat() / sliceHeight).toInt().coerceAtLeast(1)
                val paths = mutableListOf<String>()

                for (page in 0 until pageCount) {
                    val top = page * sliceHeight
                    val h = minOf(sliceHeight, rawBitmap.height - top)
                    if (h <= 0) break
                    val slice = Bitmap.createBitmap(rawBitmap, 0, top, rawBitmap.width, h)
                    val file = File(dir, "web_page_${page + 1}.png")
                    FileOutputStream(file).use { out ->
                        slice.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    slice.recycle()
                    paths.add(file.absolutePath)
                }
                rawBitmap.recycle()
                paths
            }
        }
    }

    const val SAMPLE_INVOICE_HTML = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <style>
          body { font-family: sans-serif; color: #0F172A; margin: 28px; background: #FFFFFF; }
          .header { display: flex; justify-content: space-between; border-bottom: 3px solid #0057FF; padding-bottom: 14px; }
          .brand { font-size: 24px; font-weight: 800; color: #0057FF; }
          .meta { font-family: monospace; font-size: 13px; color: #475569; text-align: right; }
          table { width: 100%; border-collapse: collapse; margin-top: 24px; }
          th { background: #0F172A; color: #FFF; text-align: left; padding: 10px; font-size: 13px; }
          td { border-bottom: 1px solid #E2E8F0; padding: 10px; font-size: 14px; }
          .total { margin-top: 22px; text-align: right; font-size: 18px; font-weight: 700; }
          .badge { display: inline-block; margin-top: 24px; padding: 8px 14px; border: 2px solid #059669; color: #059669; font-weight: 700; border-radius: 6px; }
        </style>
        </head>
        <body>
          <div class="header">
            <div class="brand">PRINTFORGE LABS</div>
            <div class="meta">INVOICE #INV-4092<br/>DATE: 2026-11-14</div>
          </div>
          <p><strong>Billed To:</strong> Field Operations &amp; Mobile Dispatch</p>
          <table>
            <tr><th>Item</th><th>Protocol</th><th>Qty</th><th>Amount</th></tr>
            <tr><td>HP LaserJet USB OTG Module</td><td>PCL 5 / Bulk</td><td>1</td><td>$240.00</td></tr>
            <tr><td>80mm Thermal POS Roll Pack</td><td>ESC/POS</td><td>4</td><td>$36.00</td></tr>
            <tr><td>LAN Network mDNS Bridge</td><td>IPP / 9100</td><td>1</td><td>$85.00</td></tr>
          </table>
          <div class="total">Total Due: $361.00</div>
          <div class="badge">PAID · WEB CAPTURE READY</div>
        </body>
        </html>
    """
}
