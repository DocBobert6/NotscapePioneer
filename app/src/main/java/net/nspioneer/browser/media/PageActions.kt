package net.nspioneer.browser.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import net.nspioneer.browser.data.PioneerStore
import java.io.File

/** Menu page actions (alpha.10, Mariner's rows, Gecko-shaped).
 *
 * Screenshot and Save-as-PDF both start from one
 * GeckoView.capturePixels() bitmap of the visible page —
 * GeckoView has no WebView.draw and no print adapter, so:
 * - Screenshot is the same as Mariner's (PageExtras):
 *   the bitmap straight into Pictures/Notscape as PNG.
 * - Save as PDF is an honest fallback, NOT Mariner's
 *   paginated text PDF (that needs
 *   createPrintDocumentAdapter, which does not exist in
 *   GeckoView): one PdfDocument page holding the viewport
 *   image, saved to Downloads/Notscape. It prints what you
 *   see; it is not a reflowed document.
 *
 * Save offline is an HTML snapshot, not Mariner's .mht
 * (WebView.saveWebArchive does not exist in GeckoView and
 * Pioneer has no JS-eval path): the probe content script
 * posts document.documentElement.outerHTML (see probe.js
 * `page-html-request`), the shell writes it to
 * filesDir/offline/<ts>.html and records an OfflinePage.
 * Like Mariner's .mht, the snapshot cannot be rendered in
 * a tab — the Offline library row re-opens the original URL.
 */
object PageActions {

    /** Mariner PageExtras.saveScreenshot, Bitmap-shaped:
     *  true when the PNG landed in Pictures/Notscape. */
    fun saveScreenshotBitmap(context: Context, bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val name = "notscape-" + System.currentTimeMillis() + ".png"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Notscape"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: error("MediaStore stream unavailable")
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

    /** Viewport-image PDF (see the header note): one page,
     *  A4-wide (595pt), height following the bitmap's aspect,
     *  capped at 2000pt so a tall phone viewport does not make
     *  an absurd page. True when the file landed in
     *  Downloads/Notscape. */
    fun saveViewportPdf(context: Context, bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val name = "notscape-" + System.currentTimeMillis() + ".pdf"
        return runCatching {
            val pageW = 595
            val pageH = ((bitmap.height.toLong() * pageW) / bitmap.width)
                .toInt().coerceIn(200, 2000)
            val doc = PdfDocument()
            try {
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                )
                page.canvas.drawColor(Color.WHITE)
                val scaled = Bitmap.createScaledBitmap(bitmap, pageW, pageH, true)
                page.canvas.drawBitmap(scaled, 0f, 0f, null)
                if (scaled !== bitmap) scaled.recycle()
                doc.finishPage(page)
                writePdf(context, doc, name)
            } finally {
                doc.close()
            }
            true
        }.getOrDefault(false)
    }

    private fun writePdf(context: Context, doc: PdfDocument, name: String) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Notscape"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("MediaStore insert failed")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    doc.writeTo(out)
                } ?: error("MediaStore stream unavailable")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "Notscape"
                ).apply { mkdirs() }
                File(dir, name).outputStream().use { out -> doc.writeTo(out) }
            }
    }

    /** Write the probe's HTML snapshot to filesDir/offline and
     *  record it. True when the record landed. */
    fun saveOfflinePage(
        context: Context,
        store: PioneerStore,
        title: String,
        url: String,
        html: String
    ): Boolean {
        if (url.isBlank() || html.isBlank()) return false
        return runCatching {
            val dir = File(context.filesDir, "offline").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}.html")
            file.writeText(html)
            store.addOfflinePage(title, url, file.absolutePath)
            true
        }.getOrDefault(false)
    }

    /** A QR result is a web address only when it is http(s)
     *  or a bare domain (which gains https://). Anything else
     *  (wifi:, tel:, mailto:, javascript:, plain text) is not
     *  a page to open. */
    fun sanitizeQrUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        if (text.contains(" ") || text.contains(":")) return null
        val host = text.removePrefix("www.")
        if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) return null
        return "https://$text"
    }
}
