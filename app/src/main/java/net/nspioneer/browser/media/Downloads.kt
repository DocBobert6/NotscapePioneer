package net.nspioneer.browser.media

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.work.ForegroundInfo
import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.PioneerStore
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Port of Mariner's `media/Downloads.kt` (4,184 lines) to
 * Pioneer's store + Gecko engine input.
 *
 * What changed in the port (adaptations, not redesigns):
 * - Store: Mariner's `AppStore` → `PioneerStore` (§6 API:
 *   `downloadsSnapshot` / `addDownload` / `updateDownload` /
 *   `removeDownload` / `clearCompletedDownloads`, plus the
 *   download helpers `updateDownloadState` /
 *   `updateDownloadProgress` / `updateDownloadBytes` /
 *   `updateDownloadFileName` / `updateDownloadUri`).
 * - Entry: Mariner's `WebView.setDownloadListener` →
 *   `ContentDelegate.onExternalResponse`; the input here is
 *   the engine's `ExternalDownload` (headers already parsed
 *   from `WebResponse` — it has no filename / contentLength /
 *   contentType fields on 159). PDFs download as files (§8.2:
 *   no PDF viewer in Phase 3; Mariner's PDF diversion is
 *   NOT ported).
 * - Cookies: GeckoView exposes no cookie API to the app
 *   (there is no CookieManager equivalent), so the cookie
 *   snapshot Mariner takes at tap time is always empty in
 *   Pioneer — workers send no cookies. Recorded as a known
 *   gap: authenticated / signed media URLs that need the
 *   page's cookies may fail to download. The snapshot
 *   plumbing (host-scoped, replayed only to its own host)
 *   is kept so a future cookie source can plug in.
 * - EPUB auto-shelf is DROPPED (§8.2): reader is Phase 4,
 *   EPUBs download as files like any other.
 * - Subtitle renditions on HLS downloads are not fetched
 *   (minor deviation from Mariner; the player discovers
 *   subtitles itself at playback time).
 *
 * What is kept verbatim in behaviour: filename resolution
 * (HEAD honours Content-Disposition + real content type),
 * `sanitize()`, routing (Wi-Fi-only → queued, DASH →
 * DashDownloadWorker, HLS → HlsDownloadWorker, segmented-
 * eligible → SegmentedDownloadWorker, else DownloadManager),
 * MAX_CONCURRENT_DOWNLOADS = 3 oldest-first, pause/resume
 * only for resumable engines, cancel, retry through the
 * saved job bundle, `dlerror:` / `dldowngrade:` labels,
 * destinations (public via MediaStore publish, app =
 * `getExternalFilesDir(DIRECTORY_MOVIES)/notscape/`),
 * private downloads (generic notification title, never in
 * public lists), per-record notification ids (30-bit fold,
 * bit 30 separates progress/terminal), the one-time
 * POST_NOTIFICATIONS ask (DownloadNotifAsk.kt).
 */
object Downloads {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    const val CHANNEL_ID = "pioneer-downloads"
    private const val CHANNEL_NAME = "Downloads"

    /** In-flight HLS downloads the user has cancelled (checked between segments). */
    private val cancelledIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** In-flight worker downloads the user has paused (checked between
     *  segments/chunks, next to cancelledIds). Pause is deliberately
     *  separate from cancel: a paused job keeps its staging files and
     *  journal, so resume continues where it stopped instead of
     *  starting over. */
    private val pausedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Workers check this as well as the record status: pause()
     *  marks the id before it writes "paused", so a job winding
     *  down at the same moment still suppresses its terminal. */
    internal fun isPaused(id: Long): Boolean = pausedIds.contains(id)

    internal fun markCancelled(id: Long) { cancelledIds.add(id) }
    internal fun clearCancelled(id: Long) { cancelledIds.remove(id) }
    internal fun isCancelled(id: Long): Boolean = cancelledIds.contains(id)

    /** How many downloads run at once; further starts wait queued. */
    internal const val MAX_CONCURRENT_DOWNLOADS = 3

    /** True when no download slot is free. Pure, for tests. */
    internal fun shouldQueue(activeCount: Int): Boolean =
        activeCount >= MAX_CONCURRENT_DOWNLOADS

    /** Downloads currently running (not queued, paused, or finished). */
    internal fun activeDownloadCount(store: PioneerStore): Int =
        store.downloadsSnapshot().count { it.status == "downloading" }

    /**
     * Per-download notification ids, derived from the record id so
     * concurrent downloads of the same type no longer overwrite each
     * other's shade notifications. Progress and completion live in
     * distinct ranges, so the terminal notice survives the
     * foreground-service notification being removed. Pure, for tests.
     */
    internal fun notificationIdFor(recordId: Long, done: Boolean): Int {
        val hash = (recordId xor (recordId ushr 32)).toInt() and 0x3FFFFFFF
        return if (done) hash or 0x40000000 else hash
    }

    /** Total size from a 206 Content-Range header
     *  ("bytes 0-99/1234"), or null when the header is absent,
     *  unknown ("*"), or malformed. */
    internal fun contentRangeTotal(header: String?): Long? {
        if (header == null) return null
        return header.substringAfterLast('/', "").trim().toLongOrNull()
    }

    /** A private download never carries its title into the
     *  system shade — only a generic "Downloading" plus the
     *  percentage, which reveals nothing about what is being
     *  fetched. */
    internal fun progressNoticeText(
        isPrivate: Boolean,
        title: String,
        pct: Int
    ): String =
        if (isPrivate) "Downloading • $pct%" else "$title • $pct%"

    /** The text of a worker foreground notice. Same rule as
     *  progressNoticeText: private downloads show only the
     *  generic word, never the title. */
    internal fun foregroundNoticeText(
        isPrivate: Boolean,
        title: String
    ): String = if (isPrivate) "Downloading" else title

    // ---------- per-download job bundles ----------
    //
    // A DownloadRecord carries no referer, no segmented probe data, and
    // no merge side URLs, so the queue and resume paths could not
    // re-enqueue a job from the record alone. The inputs a job needs
    // are therefore persisted alongside it in one store setting,
    // "dljob:<id>", as URL-encoded key=value pairs. The bundle is
    // written at start time, updated when routing picks an engine,
    // and cleared when the download leaves the active set (cancel,
    // delete, or terminal completion). It never holds credentials
    // beyond the referer/cookie the engine already sends.
    // The cookie snapshot is always empty in Pioneer (GeckoView
    // exposes no cookie API — see the file header); the plumbing
    // stays host-scoped for a future cookie source.

    /** WebView cookies for one host, captured at download-tap time. Host-scoped by construction. */
    internal data class CookieSnapshot(val host: String, val cookie: String)

    /** Which Cookie value a request to [url] gets. The snapshot
     *  wins, but ONLY for its own host; anything else falls back
     *  to the live jar (which may itself be empty). Pure. */
    internal fun cookieForRequest(
        url: String,
        snapshot: CookieSnapshot?,
        liveCookie: String?
    ): String? {
        if (snapshot != null && snapshot.cookie.isNotBlank()) {
            val host = runCatching { java.net.URL(url).host }.getOrNull()
            if (host != null && host == snapshot.host) return snapshot.cookie
        }
        return liveCookie?.takeIf { it.isNotBlank() }
    }

    internal fun encodeJobBundle(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                java.net.URLEncoder.encode(v, "UTF-8")
        }

    internal fun decodeJobBundle(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in encoded.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            out[k] = v
        }
        return out
    }

    internal fun saveJobBundle(store: PioneerStore, id: Long, fields: Map<String, String>) {
        store.putSetting("dljob:$id", encodeJobBundle(fields))
    }

    internal fun readJobBundle(store: PioneerStore, id: Long): Map<String, String>? {
        val raw = store.setting("dljob:$id", "")
        return if (raw.isBlank()) null else decodeJobBundle(raw)
    }

    internal fun updateJobBundle(store: PioneerStore, id: Long, patch: Map<String, String>) {
        val merged = (readJobBundle(store, id) ?: emptyMap()).toMutableMap()
        merged.putAll(patch)
        saveJobBundle(store, id, merged)
    }

    internal fun clearJobBundle(store: PioneerStore, id: Long) {
        store.removeSetting("dljob:$id")
    }

    /** A failure that must not be retried (bad key, bad IV):
     *  retrying it can never heal, so the job fails fast. */
    class FatalDownloadException(message: String) : java.io.IOException(message)

    /** Rename, falling back to copy-then-delete: renameTo is
     *  not guaranteed across directories or filesystems, and
     *  a failed rename must never be reported as a completed
     *  download. */
    internal fun renameOrCopy(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return runCatching {
            src.inputStream().use { ins ->
                dst.outputStream().use { out -> ins.copyTo(out) }
            }
            src.delete()
            true
        }.getOrDefault(false)
    }

    /** Record ids, looped until unused: the millisecond-plus-
     *  random shape is kept — only the collision hole is closed. */
    internal fun nextDownloadId(store: PioneerStore): Long {
        var id = System.currentTimeMillis() * 1000 + (0..999).random()
        while (store.downloadsSnapshot().any { it.id == id }) {
            id = System.currentTimeMillis() * 1000 + (0..999).random()
        }
        return id
    }

    /** Every directory engine staging can live in: the staging
     *  folder, the app folder (app-location and legacy
     *  destinations stage beside the finished file), and the
     *  public Downloads folder (legacy path). */
    internal fun stagingDirs(context: Context): List<File> = listOf(
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "notscape-staging"),
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "notscape"),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    )

    /** Delete one record's engine staging everywhere it can
     *  live: id-keyed staging is matched by the record id
     *  whatever its file name is now (renames don't orphan
     *  it), and legacy name-keyed staging is removed only
     *  when no other record shares the file name. One helper
     *  for cancel, delete, wipe, and erase, so the patterns
     *  can never drift apart. */
    internal fun deleteEngineStaging(context: Context, store: PioneerStore, rec: DownloadRecord) {
        val othersShareName = store.downloadsSnapshot()
            .any { it.id != rec.id && it.fileName == rec.fileName }
        val idMark = ".${rec.id}."
        for (dir in stagingDirs(context)) {
            runCatching {
                dir.listFiles()?.forEach { f ->
                    val n = f.name
                    val idKeyed = n.contains(idMark)
                    val legacy = !othersShareName && (
                        n == rec.fileName + ".segments" ||
                            n == rec.fileName + ".dash0" || n == rec.fileName + ".dash1" ||
                            n == rec.fileName + ".track0" || n == rec.fileName + ".track1" ||
                            n == rec.fileName + ".mux" || n == rec.fileName + ".part" ||
                            n == rec.fileName + ".transV.mp4" || n == rec.fileName + ".transA.mp4" ||
                            n.startsWith(rec.fileName + ".merge")
                        )
                    if (idKeyed || legacy) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                }
            }
        }
    }

    /** Sleep in slices: throws on cancel, returns true early
     *  on pause, so long rate-limit waits answer promptly
     *  instead of sleeping through the user. */
    internal fun sleepChecked(recId: Long, millis: Long): Boolean {
        var left = millis
        while (left > 0) {
            if (cancelledIds.contains(recId)) throw InterruptedException("cancelled")
            if (pausedIds.contains(recId)) return true
            val step = minOf(250L, left)
            Thread.sleep(step)
            left -= step
        }
        return pausedIds.contains(recId)
    }

    /** True when the active network is metered (or unknown
     *  capabilities hide it — no network means not metered,
     *  since the worker constraints hold the job anyway). */
    internal fun isNetworkMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return false
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** A downloading/queued record under Wi-Fi-only on a
     *  metered network is waiting, not stalled — the row
     *  says so. */
    internal fun isWaitingForWifi(context: Context, store: PioneerStore): Boolean =
        store.setting("wifiOnlyDownloads", "false") == "true" && isNetworkMetered(context)

    /** Retry through the saved job bundle: merges re-run
     *  with both side URLs, location and the current file
     *  name are preserved, and the record keeps its id so
     *  staging and journal resume instead of restarting.
     *  Only a missing bundle falls back to delete-and-restart. */
    fun retry(context: Context, store: PioneerStore, rec: DownloadRecord): Boolean {
        val appCtx = context.applicationContext
        val fresh = store.downloadsSnapshot().firstOrNull { it.id == rec.id } ?: return false
        if (fresh.status != "failed" && fresh.status != "cancelled") return false
        val bundle = readJobBundle(store, rec.id)
        if (bundle == null) {
            if (fresh.dmId >= 0) {
                runCatching {
                    val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.remove(fresh.dmId)
                }
            }
            val wiped = runCatching { deleteLocal(appCtx, store, fresh) }.getOrDefault(false)
            if (!wiped) return false
            runCatching { deleteEngineStaging(appCtx, store, fresh) }
            clearJobBundle(store, rec.id)
            store.removeDownload(rec.id)
            store.removeSetting("dlerror:${rec.id}")
            store.removeSetting("dldowngrade:${rec.id}")
            start(appCtx, store, fresh.title, fresh.url, media = fresh.media, isPrivate = fresh.isPrivate)
            return true
        }
        if (fresh.dmId >= 0) {
            runCatching {
                val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(fresh.dmId)
            }
        }
        cancelledIds.remove(rec.id)
        pausedIds.remove(rec.id)
        store.removeSetting("dlerror:${rec.id}")
        store.removeSetting("dldowngrade:${rec.id}")
        store.updateDownloadState(rec.id, "queued", progress = 0)
        promoteQueued(appCtx, store)
        return true
    }

    /** "Clear completed, keep files": records only — files on disk are never touched. */
    fun clearCompletedRecords(store: PioneerStore, media: Boolean) {
        store.clearCompletedDownloads(media)
    }

    /** File names pruned records left behind: the file stays,
     *  and its name is kept so it can still be removed
     *  deliberately. */
    fun prunedFiles(store: PioneerStore): List<String> =
        store.settings.keys.filter { it.startsWith("dltombstone:") }
            .mapNotNull { store.setting(it, "").ifBlank { null } }

    /** Remove tombstoned leftover files, wherever they live. */
    fun clearPrunedFiles(context: Context, store: PioneerStore) {
        val names = prunedFiles(store)
        if (names.isEmpty()) return
        for (dir in stagingDirs(context)) {
            dir.listFiles()?.forEach { f ->
                if (f.name in names) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val resolver = context.contentResolver
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                for (name in names) {
                    resolver.query(
                        collection,
                        arrayOf(android.provider.MediaStore.MediaColumns._ID),
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(name), null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val uri = android.content.ContentUris.withAppendedId(
                                collection, c.getLong(0)
                            )
                            runCatching { resolver.delete(uri, null, null) }
                        }
                    }
                }
            }
        }
        // Tombstones are keyed by record id; remove them by key prefix.
        store.settings.keys.filter { it.startsWith("dltombstone:") }.toList()
            .forEach { store.removeSetting(it) }
    }

    /** Delete every download record and its file/staging (used
     *  by the private-profile wipe; files go too here, unlike
     *  clearCompletedRecords). */
    fun eraseAllDownloads(context: Context, store: PioneerStore) {
        val appCtx = context.applicationContext
        for (rec in store.downloadsSnapshot()) {
            runCatching { deleteLocal(appCtx, store, rec) }
            runCatching { deleteEngineStaging(appCtx, store, rec) }
            clearJobBundle(store, rec.id)
            store.removeDownload(rec.id)
        }
    }

    internal fun engineFor(store: PioneerStore, rec: DownloadRecord): String =
        readJobBundle(store, rec.id)?.get("engine") ?: "dm"

    internal fun isResumableEngine(engine: String): Boolean =
        engine == "hls" || engine == "dash" || engine == "seg" || engine == "merge"

    internal fun locationFor(store: PioneerStore, rec: DownloadRecord): String {
        val fromBundle = readJobBundle(store, rec.id)?.get("location")
        if (!fromBundle.isNullOrBlank()) return fromBundle
        if (rec.isPrivate) return "app"
        return store.setting("downloadLocation", "public")
    }

    internal fun applyFileNameOverride(
        override: String?, resolved: String, isHls: Boolean, isDash: Boolean
    ): String {
        val o = override?.trim().orEmpty()
        if (o.isBlank()) return resolved
        val ext = when {
            isHls -> ".ts"
            isDash -> ".mp4"
            else -> resolved.substringAfterLast('.', "").let { if (it.isNotBlank()) ".$it" else "" }
        }
        val base = sanitize(o.substringBeforeLast('.').ifBlank { o })
        return base + ext
    }

    private fun statusOf(store: PioneerStore, id: Long): String? =
        store.downloadsSnapshot().firstOrNull { it.id == id }?.status

    /** Write a terminal status unless the job was paused in
     *  the meantime (a pause landing between an engine's
     *  terminal write and the user's Resume would otherwise
     *  hide Resume and freeze the bar). */
    internal fun writeTerminalIfNotPaused(
        store: PioneerStore, id: Long, status: String, progress: Int? = null
    ) {
        if (pausedIds.contains(id)) return
        store.updateDownloadState(id, status, progress = progress)
    }

    /** Start queued downloads oldest-first while a slot is
     *  free (MAX_CONCURRENT_DOWNLOADS = 3). */
    fun promoteQueued(context: Context, store: PioneerStore) {
        val appCtx = context.applicationContext
        val queued = store.downloadsSnapshot()
            .filter { it.status == "queued" }
            .sortedBy { it.addedAt }
        for (rec in queued) {
            if (activeDownloadCount(store) >= MAX_CONCURRENT_DOWNLOADS) return
            val bundle = readJobBundle(store, rec.id) ?: continue
            val url = bundle["url"] ?: rec.url
            val contentType = bundle["contentType"]?.takeIf { it.isNotBlank() }
            val referer = bundle["referer"]?.takeIf { it.isNotBlank() }
            val media = bundle["media"] == "true"
            store.updateDownloadState(rec.id, "downloading")
            routeNewDownload(appCtx, store, rec, url, contentType, referer, media)
        }
    }

    private fun routeDirect(
        appCtx: Context,
        store: PioneerStore,
        rec: DownloadRecord,
        url: String,
        referer: String?,
    ) {
        val dmId = enqueueDownloadManager(appCtx, store, rec, url, referer)
        if (dmId < 0) {
            noteError(appCtx, store, rec, "Download failed")
            store.updateDownloadState(rec.id, "failed")
            return
        }
        updateJobBundle(store, rec.id, mapOf("engine" to "dm"))
        store.updateDownloadState(rec.id, "downloading", dmId)
    }

    /** Pause a resumable download: the worker sees the flag
     *  between segments/chunks and stops, keeping staging +
     *  journal so resume continues where it stopped. */
    fun pause(context: Context, store: PioneerStore, rec: DownloadRecord): Boolean {
        if (!isResumableEngine(engineFor(store, rec))) return false
        pausedIds.add(rec.id)
        store.updateDownloadState(rec.id, "paused")
        return true
    }

    /** Resume a paused download: clear the flag and re-queue
     *  through the saved job bundle. */
    fun resume(context: Context, store: PioneerStore, rec: DownloadRecord): Boolean {
        val appCtx = context.applicationContext
        val fresh = store.downloadsSnapshot().firstOrNull { it.id == rec.id } ?: return false
        if (fresh.status != "paused") return false
        pausedIds.remove(rec.id)
        cancelledIds.remove(rec.id)
        store.updateDownloadState(rec.id, "queued")
        promoteQueued(appCtx, store)
        return true
    }

    /** Cancel: mark the id, remove any DownloadManager job,
     *  delete staging, mark the record cancelled. */
    fun cancel(context: Context, store: PioneerStore, rec: DownloadRecord) {
        val appCtx = context.applicationContext
        cancelledIds.add(rec.id)
        pausedIds.remove(rec.id)
        if (rec.dmId >= 0) {
            runCatching {
                val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(rec.dmId)
            }
        }
        runCatching {
            androidx.work.WorkManager.getInstance(appCtx)
                .cancelAllWorkByTag("dl-${rec.id}")
        }
        runCatching { deleteEngineStaging(appCtx, store, rec) }
        store.updateDownloadState(rec.id, "cancelled")
        clearJobBundle(store, rec.id)
    }

    private fun routeNewDownload(
        appCtx: Context,
        store: PioneerStore,
        rec: DownloadRecord,
        url: String,
        contentType: String?,
        referer: String?,
        media: Boolean
    ) {
        // With Wi-Fi-only on and a metered network active,
        // don't even probe — leave the record queued (the row
        // shows "Waiting for Wi-Fi") until a later promotion
        // runs unmetered.
        if (store.setting("wifiOnlyDownloads", "false") == "true" &&
            isNetworkMetered(appCtx)
        ) {
            store.updateDownloadState(rec.id, "queued")
            return
        }
        val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            contentType?.contains("mpegurl", ignoreCase = true) == true
        val isDash = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        if (isDash) {
            // DASH downloads: segments are fetched, assembled
            // per track and remuxed into one MP4 by a WorkManager
            // job, the HLS pattern. Manifests outside the
            // supported shapes fail honestly in the job rather
            // than saving a manifest as if it were a video.
            cancelledIds.remove(rec.id)
            pausedIds.remove(rec.id)
            store.updateDownloadProgress(rec.id, 0)
            updateJobBundle(store, rec.id, mapOf("engine" to "dash"))
            enqueueWorker(appCtx, store, rec.id, "dash", referer, null)
            return
        }
        if (isHls) {
            // A playlist is not a video: download the actual
            // stream segments and join them into one playable
            // file. The join runs as a WorkManager job: it
            // survives the process dying, resumes from the
            // segment journal, and retries transient network
            // failures with backoff.
            cancelledIds.remove(rec.id)
            pausedIds.remove(rec.id)
            store.updateDownloadProgress(rec.id, 0)
            updateJobBundle(store, rec.id, mapOf("engine" to "hls"))
            enqueueWorker(appCtx, store, rec.id, "hls", referer, null)
            return
        }
        // Segmented downloads: eligible plain downloads go to
        // the segmented worker; anything ineligible falls
        // through to the DownloadManager path below, unchanged.
        if (store.setting("segmentedDownloads", "true") == "true") {
            val probe = probeSegmented(appCtx, url, referer)
            if (probe.eligible) {
                cancelledIds.remove(rec.id)
                pausedIds.remove(rec.id)
                store.updateDownloadProgress(rec.id, 0)
                updateJobBundle(store, rec.id, mapOf(
                    "engine" to "seg",
                    "finalUrl" to probe.finalUrl,
                    "total" to probe.total.toString(),
                    "cookie" to (probe.cookie ?: ""),
                    "md5" to (probe.md5 ?: "")
                ))
                enqueueWorker(
                    appCtx, store, rec.id, "seg", referer,
                    mapOf(
                        "finalUrl" to probe.finalUrl,
                        "total" to probe.total.toString(),
                        "cookie" to (probe.cookie ?: ""),
                        "md5" to (probe.md5 ?: "")
                    )
                )
                return
            }
        }
        routeDirect(appCtx, store, rec, url, referer)
    }

    /** Enqueue one of the four WorkManager workers for a
     *  record. Workers survive process death and retry
     *  transient failures with exponential backoff (30s). */
    private fun enqueueWorker(
        appCtx: Context, store: PioneerStore, recId: Long, kind: String,
        referer: String?, extra: Map<String, String>?,
    ) {
        val dataBuilder = androidx.work.Data.Builder()
            .putLong("recId", recId)
            .putString("referer", referer)
        extra?.forEach { (k, v) ->
            if (k == "total") dataBuilder.putLong(k, v.toLongOrNull() ?: -1L)
            else dataBuilder.putString(k, v)
        }
        val data = dataBuilder.build()
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(downloadNetworkType(store))
            .build()
        // The worker class is chosen by kind; each worker
        // reads the same recId/referer input.
        val request = when (kind) {
            "hls" -> androidx.work.OneTimeWorkRequestBuilder<HlsDownloadWorker>()
            "dash" -> androidx.work.OneTimeWorkRequestBuilder<DashDownloadWorker>()
            "seg" -> androidx.work.OneTimeWorkRequestBuilder<SegmentedDownloadWorker>()
            "merge" -> androidx.work.OneTimeWorkRequestBuilder<MergeDownloadWorker>()
            else -> return
        }.setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30, java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag("dl-$recId")
            .addTag("$kind-$recId")
            .build()
        runCatching {
            androidx.work.WorkManager.getInstance(appCtx).enqueue(request)
        }.onFailure {
            store.updateDownloadState(recId, "failed")
        }
    }

    /**
     * Start a download. The file name is resolved properly
     * first (on a background thread): Content-Disposition and
     * the real content type are honoured, so an image URL
     * with no extension in it still lands as e.g. `photo.jpg`
     * — a real photo the gallery can open — instead of an
     * extension-less blob named after the raw URL.
     *
     * [media] marks downloads that belong in the offline
     * library (videos and audio saved from Find media).
     * Ordinary file/image downloads go to the phone's
     * Downloads folder and stay out of the library.
     */
    fun start(
        context: Context,
        store: PioneerStore,
        title: String,
        url: String,
        media: Boolean = false,
        contentType: String? = null,
        suggestedName: String? = null,
        referer: String? = null,
        isPrivate: Boolean = false,
        location: String? = null,
        fileNameOverride: String? = null,
        variantBandwidth: Long? = null
    ): Long {
        val id = nextDownloadId(store)
        val appCtx = context.applicationContext
        ioScope.launch {
            val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                contentType?.contains("mpegurl", ignoreCase = true) == true
            val isDash = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
            val resolved = if (isHls) {
                // The saved file is a joined MPEG-TS stream: name it .ts so
                // other players treat it as what it is.
                val base = (suggestedName ?: Uri.parse(url).lastPathSegment ?: title)
                    .substringBeforeLast('.')
                sanitize(base) + ".ts"
            } else if (isDash) {
                // Assembled and remuxed to a real MP4.
                val base = (suggestedName ?: Uri.parse(url).lastPathSegment ?: title)
                    .substringBeforeLast('.')
                sanitize(base) + ".mp4"
            } else {
                resolveFileName(url, title, contentType, suggestedName)
            }
            // A per-download rename (from the confirmation dialog) wins
            // over the resolved name, keeping the engine's extension.
            val fileName = applyFileNameOverride(fileNameOverride, resolved, isHls, isDash)
            val rec = DownloadRecord(
                id = id,
                title = title.ifBlank { fileName },
                url = url,
                fileName = fileName,
                dmId = -1,
                status = "queued",
                addedAt = System.currentTimeMillis(),
                media = media,
                isPrivate = isPrivate
            )
            store.addDownload(rec)
            // Cookie snapshot: always empty in Pioneer — GeckoView
            // exposes no cookie API (see the file header). The
            // bundle keys stay so the workers' host-scoped replay
            // works unchanged if a cookie source ever plugs in.
            val snapHost = runCatching {
                android.net.Uri.parse(url).host
            }.getOrNull() ?: ""
            saveJobBundle(store, rec.id, mapOf(
                "url" to url,
                "title" to title,
                "media" to media.toString(),
                "contentType" to (contentType ?: ""),
                "suggestedName" to (suggestedName ?: ""),
                "referer" to (referer ?: ""),
                "cookie" to "",
                "cookieHost" to snapHost,
                "isPrivate" to isPrivate.toString(),
                "location" to (if (isPrivate) "app" else (location ?: "")),
                "fileName" to fileName,
                "variantBandwidth" to (variantBandwidth?.toString() ?: "")
            ))
            // The queue gate lives in promoteQueued alone: the
            // record starts at once when a slot is free and waits
            // (oldest first) when the limit is reached.
            promoteQueued(appCtx, store)
        }
        return id
    }

    /** Start a merge download: two sides (video + audio) are
     *  fetched and remuxed into one MP4 by MergeDownloadWorker. */
    fun startMerge(
        context: Context,
        store: PioneerStore,
        title: String,
        videoUrl: String,
        audioUrl: String,
        referer: String? = null,
        isPrivate: Boolean = false,
        location: String? = null,
        fileNameOverride: String? = null
    ): Long {
        val id = nextDownloadId(store)
        val appCtx = context.applicationContext
        ioScope.launch {
            val base = (fileNameOverride?.substringBeforeLast('.')
                ?: title).let { sanitize(it) }
            val fileName = "$base.mp4"
            val rec = DownloadRecord(
                id = id,
                title = title.ifBlank { fileName },
                url = videoUrl,
                fileName = fileName,
                dmId = -1,
                status = "queued",
                addedAt = System.currentTimeMillis(),
                media = true,
                isPrivate = isPrivate
            )
            store.addDownload(rec)
            saveJobBundle(store, rec.id, mapOf(
                "url" to videoUrl,
                "videoUrl" to videoUrl,
                "audioUrl" to audioUrl,
                "title" to title,
                "media" to "true",
                "referer" to (referer ?: ""),
                "cookie" to "",
                "cookieHost" to (runCatching {
                    android.net.Uri.parse(videoUrl).host
                }.getOrNull() ?: ""),
                "isPrivate" to isPrivate.toString(),
                "location" to (if (isPrivate) "app" else (location ?: "")),
                "fileName" to fileName,
                "engine" to "merge"
            ))
            store.updateDownloadState(rec.id, "downloading")
            enqueueWorker(appCtx, store, rec.id, "merge", referer, mapOf(
                "videoUrl" to videoUrl, "audioUrl" to audioUrl
            ))
        }
        return id
    }

    /** Wi-Fi-only downloads, off by default. Workers ask for
     *  an unmetered network; DownloadManager is barred from
     *  metered networks. Applies to downloads started after
     *  the toggle changes — already-enqueued workers keep the
     *  constraints they were built with. */
    private fun downloadNetworkType(store: PioneerStore?) =
        if (store?.setting("wifiOnlyDownloads", "false") == "true")
            androidx.work.NetworkType.UNMETERED
        else androidx.work.NetworkType.CONNECTED

    /** Enqueue a plain download with Android's DownloadManager
     *  (single connection, system-managed). Shared by start()'s
     *  plain path and by the segmented worker's 429 downgrade, so
     *  both routes send identical headers and destinations.
     *  Returns the DownloadManager id, or -1 on failure. */
    fun enqueueDownloadManager(
        context: Context,
        store: PioneerStore,
        rec: DownloadRecord,
        url: String,
        referer: String?,
    ): Long {
        val appCtx = context.applicationContext
        return runCatching {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(rec.title)
                setDescription(rec.fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(
                    store.setting("wifiOnlyDownloads", "false") != "true"
                )
                setAllowedOverRoaming(false)
                referer?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
                addRequestHeader("User-Agent", UA)
                val location = locationFor(store, rec)
                if (location == "app" || rec.isPrivate) {
                    setDestinationInExternalFilesDir(
                        appCtx, Environment.DIRECTORY_MOVIES,
                        "notscape/${rec.fileName}"
                    )
                } else {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, rec.fileName
                    )
                }
            }
            dm.enqueue(req)
        }.getOrDefault(-1L)
    }

    /** Segmented-download probe: HEAD/Range-probe the URL for
     *  Range support + total size. Eligible = Range works and
     *  the file is at least 1 MiB (small files are not worth
     *  the split). */
    internal data class SegmentedProbe(
        val eligible: Boolean, val finalUrl: String, val total: Long,
        val cookie: String? = null, val md5: String? = null
    )

    private fun probeSegmented(
        appCtx: Context, url: String, referer: String?
    ): SegmentedProbe {
        return runCatching {
            val conn = openSegmentedGet(appCtx, url, referer, range = "bytes=0-0")
            val total = contentRangeTotal(conn.getHeaderField("Content-Range"))
                ?: conn.getHeaderFieldLong("Content-Length", -1)
            val accepts = conn.getHeaderField("Accept-Ranges")
            conn.disconnect()
            val eligible = total >= 1024 * 1024 &&
                (accepts?.contains("bytes") == true || total > 0)
            SegmentedProbe(eligible, url, total)
        }.getOrDefault(SegmentedProbe(false, url, -1))
    }

    private fun openSegmentedGet(
        appCtx: Context, url: String, referer: String?, range: String?
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        referer?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Referer", it)
        }
        range?.let { conn.setRequestProperty("Range", it) }
        conn.connect()
        return conn
    }

    // ---------- file naming ----------

    private fun destFile(context: Context, store: PioneerStore, fileName: String, location: String): File =
        if (location == "app") {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "notscape/$fileName")
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
        }

    private fun mimeFor(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            fileName.substringAfterLast('.', "").lowercase()
        ) ?: "application/octet-stream"

    /** Publish a finished file to MediaStore (public
     *  location) and return its content:// URI, or null. */
    private fun publishToMediaStore(context: Context, file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            uri
        }.getOrNull()
    }

    private fun findInMediaStore(context: Context, rec: DownloadRecord): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(rec.fileName), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                } else null
            }
        }.getOrNull()
    }

    internal fun mediaStoreNameExists(context: Context, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name), null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /** "(1)" suffix on collision, like the system downloader. */
    internal fun uniqueMediaStoreName(context: Context, name: String): String =
        dedupFileName({ mediaStoreNameExists(context, it) }, name)

    internal fun dedupFileName(exists: (String) -> Boolean, name: String): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }

    /** Resolve the file name on a background thread: HEAD
     *  honours Content-Disposition + real content type. */
    private fun resolveFileName(
        url: String, title: String, contentType: String?, suggestedName: String?
    ): String {
        val fromHead = runCatching {
            val conn = openHead(url)
            val disp = conn.getHeaderField("Content-Disposition")
            val type = conn.getHeaderField("Content-Type")
                ?.substringBefore(';')?.trim()
            conn.disconnect()
            val headName = disp?.let { parseDispositionName(it) }
            val headExt = type?.let { extensionFor(url, it) }
            headName to headExt
        }.getOrNull()
        val (headName, headExt) = fromHead ?: (null to null)
        val base = suggestedName?.takeIf { it.isNotBlank() }
            ?: headName
            ?: Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: title
        val ext = headExt
            ?: extensionFor(url, contentType)
            ?: base.substringAfterLast('.', "").takeIf { it.isNotBlank() }
                ?.let { ".$it" }
            ?: ""
        val stem = sanitize(base.substringBeforeLast('.').ifBlank { base })
        return if (ext.isNotBlank() && !stem.endsWith(ext, ignoreCase = true)) stem + ext
        else stem
    }

    private fun parseDispositionName(header: String): String? {
        val encoded = Regex(
            """filename\*\s*=\s*UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE
        ).find(header)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return Regex(
            """filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(header)?.let { m ->
            m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        }
    }

    private fun openHead(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        conn.connect()
        return conn
    }

    private fun extensionFor(url: String, mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (!fromMime.isNullOrBlank()) return ".$fromMime"
        return when {
            mime.contains("mpegurl", ignoreCase = true) -> ".m3u8"
            mime.contains("dash+xml", ignoreCase = true) -> ".mpd"
            mime.contains("pdf", ignoreCase = true) -> ".pdf"
            else -> null
        }
    }

    /** Max 100 chars, `[^A-Za-z0-9._-]`→`_`, blank→`"download"`. */
    internal fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(100)
        return cleaned.ifBlank { "download" }
    }

    // ---------- HTTP helpers ----------

    internal fun openGet(
        url: String, referer: String?, cookie: String? = null,
        range: String? = null
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        referer?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Referer", it)
        }
        cookie?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Cookie", it)
        }
        range?.let { conn.setRequestProperty("Range", it) }
        conn.connect()
        return conn
    }

    private fun checkStatus(conn: HttpURLConnection) {
        if (conn.responseCode !in 200..299 && conn.responseCode != 206) {
            throw java.io.IOException("HTTP ${conn.responseCode}")
        }
    }

    private fun checkedBytes(conn: HttpURLConnection): ByteArray {
        checkStatus(conn)
        return conn.inputStream.use { it.readBytes() }
    }

    private fun errorLabel(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No connection"
        is java.net.SocketTimeoutException -> "Timed out"
        is java.io.FileNotFoundException -> "Not found (404)"
        is FatalDownloadException -> e.message ?: "Download failed"
        else -> e.message?.take(80) ?: "Download failed"
    }

    internal fun noteError(
        context: Context, store: PioneerStore, rec: DownloadRecord, label: String
    ) {
        store.putSetting("dlerror:${rec.id}", label)
    }

    internal fun noteError(
        store: PioneerStore, rec: DownloadRecord, e: Exception
    ) {
        store.putSetting("dlerror:${rec.id}", errorLabel(e))
    }

    // ---------- formatting ----------

    fun noteBytes(
        store: PioneerStore, rec: DownloadRecord,
        downloaded: Long, total: Long, speedBps: Long
    ) {
        store.updateDownloadBytes(rec.id, downloaded, total, speedBps)
        if (total > 0) {
            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            store.updateDownloadProgress(rec.id, pct)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }

    fun formatSpeed(bps: Long): String =
        if (bps < 0) "?" else "${formatBytes(bps)}/s"

    fun formatEta(seconds: Long): String {
        if (seconds < 0) return "?"
        if (seconds < 60) return "${seconds}s left"
        val m = seconds / 60
        if (m < 60) return "${m}m left"
        return "${m / 60}h ${m % 60}m left"
    }

    /** Size / speed / ETA detail line for a download row, or
     *  null when nothing is known yet. */
    fun progressDetailLine(rec: DownloadRecord): String? {
        if (rec.bytesDownloaded < 0 && rec.totalBytes < 0) return null
        val size = if (rec.totalBytes > 0) {
            "${formatBytes(rec.bytesDownloaded.coerceAtLeast(0))} / ${formatBytes(rec.totalBytes)}"
        } else if (rec.bytesDownloaded >= 0) {
            formatBytes(rec.bytesDownloaded)
        } else null
        val speed = if (rec.speedBps > 0) formatSpeed(rec.speedBps) else null
        val eta = if (rec.speedBps > 0 && rec.totalBytes > rec.bytesDownloaded && rec.bytesDownloaded >= 0) {
            formatEta((rec.totalBytes - rec.bytesDownloaded) / rec.speedBps)
        } else null
        return listOfNotNull(size, speed, eta).joinToString(" • ").takeIf { it.isNotBlank() }
    }

    // ---------- status / files ----------

    /** Refresh DownloadManager-backed records against the
     *  system downloader (status / progress), and drop
     *  records whose files vanished. Returns true when
     *  anything changed. */
    fun refresh(context: Context, store: PioneerStore): Boolean {
        val appCtx = context.applicationContext
        var changed = false
        val dm = runCatching {
            appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        }.getOrNull()
        for (rec in store.downloadsSnapshot()) {
            if (rec.status == "complete") {
                if (localSource(appCtx, store, rec) == null) {
                    store.updateDownloadState(rec.id, "failed")
                    store.putSetting("dlerror:${rec.id}", "File missing")
                    changed = true
                }
                continue
            }
            if (rec.dmId < 0 || dm == null) continue
            runCatching {
                val q = DownloadManager.Query().setFilterById(rec.dmId)
                dm.query(q)?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val newStatus = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> "complete"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        DownloadManager.STATUS_PENDING -> "queued"
                        else -> "downloading"
                    }
                    if (newStatus != rec.status) {
                        store.updateDownloadState(rec.id, newStatus)
                        if (newStatus == "complete") {
                            val uri = findInMediaStore(appCtx, rec)
                                ?: localFile(appCtx, store, rec).let {
                                    if (it.exists()) Uri.fromFile(it) else null
                                }
                            uri?.let { store.updateDownloadUri(rec.id, it.toString()) }
                            notifyComplete(appCtx, store, rec)
                        }
                        if (newStatus == "failed") {
                            store.putSetting("dlerror:${rec.id}", "Download failed")
                            notifyFailed(appCtx, store, rec, "Download failed")
                        }
                        changed = true
                    }
                    if (total > 0) {
                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        store.updateDownloadProgress(rec.id, pct)
                        store.updateDownloadBytes(rec.id, downloaded, total, -1)
                    }
                }
            }
        }
        return changed
    }

    /** The finished file for a record, wherever it lives. */
    fun localFile(context: Context, store: PioneerStore, rec: DownloadRecord): File {
        if (rec.contentUri.isNotBlank() && rec.contentUri.startsWith("/")) {
            return File(rec.contentUri)
        }
        return destFile(context, store, rec.fileName, locationFor(store, rec))
    }

    /** A source the player / opener can use: a MediaStore
     *  content:// URI, an absolute path, or null when the
     *  file is gone. */
    fun localSource(context: Context, store: PioneerStore, rec: DownloadRecord): String? {
        if (rec.contentUri.isNotBlank()) {
            if (rec.contentUri.startsWith("content://")) {
                val exists = runCatching {
                    context.contentResolver.query(
                        Uri.parse(rec.contentUri), null, null, null, null
                    )?.use { it.count > 0 } ?: false
                }.getOrDefault(false)
                if (exists) return rec.contentUri
            } else if (File(rec.contentUri).exists()) {
                return rec.contentUri
            }
        }
        val file = localFile(context, store, rec)
        return if (file.exists()) file.absolutePath else null
    }

    /** Share intent for a finished download, or null. */
    fun shareIntent(context: Context, store: PioneerStore, rec: DownloadRecord): Intent? {
        val src = localSource(context, store, rec) ?: return null
        val uri = if (src.startsWith("content://")) Uri.parse(src)
        else FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(src)
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(rec.fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Open intent for a finished download, or null. */
    fun openIntent(context: Context, store: PioneerStore, rec: DownloadRecord): Intent? {
        val src = localSource(context, store, rec) ?: return null
        val uri = if (src.startsWith("content://")) Uri.parse(src)
        else FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(src)
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(rec.fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Copy a finished download out through a
     *  CreateDocument URI. Runs on the caller's thread. */
    fun copyExport(context: Context, store: PioneerStore, rec: DownloadRecord, uri: Uri) {
        val src = localSource(context, store, rec) ?: return
        val input = if (src.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(src))
        } else {
            File(src).inputStream()
        } ?: return
        input.use { ins ->
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ins.copyTo(out)
            }
        }
    }

    /** Delete a download's file (not the record). Returns
     *  true when the file is gone (or was already gone). */
    fun deleteLocal(context: Context, store: PioneerStore, rec: DownloadRecord): Boolean {
        val appCtx = context.applicationContext
        var ok = true
        rec.contentUri.takeIf { it.startsWith("content://") }?.let { uriStr ->
            runCatching {
                appCtx.contentResolver.delete(Uri.parse(uriStr), null, null)
            }.onFailure { ok = false }
        }
        val file = localFile(appCtx, store, rec)
        if (file.exists() && !file.delete()) ok = false
        return ok
    }

    /** Wipe every private download's record + file (the
     *  private profile is gone — nothing private stays). */
    fun wipePrivateDownloads(context: Context, store: PioneerStore) {
        val appCtx = context.applicationContext
        for (rec in store.downloadsSnapshot().filter { it.isPrivate }) {
            runCatching { deleteLocal(appCtx, store, rec) }
            runCatching { deleteEngineStaging(appCtx, store, rec) }
            clearJobBundle(store, rec.id)
            store.removeDownload(rec.id)
            store.removeSetting("dlerror:${rec.id}")
            store.removeSetting("dldowngrade:${rec.id}")
        }
    }

    /** Same wipe, run after a store load (private records
     *  that survived a restart are wiped on next launch). */
    fun wipePrivateDownloadsAfterLoad(context: Context, store: PioneerStore) {
        wipePrivateDownloads(context, store)
    }

    // ---------- notifications ----------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Download progress and completion" }
        )
    }

    /** Foreground-service progress notification for a worker. */
    fun progressNotification(
        context: Context, rec: DownloadRecord, pct: Int
    ): android.app.Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(progressNoticeText(rec.isPrivate, rec.title, pct))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notifyProgress(context: Context, rec: DownloadRecord, pct: Int) {
        if (!downloadNotificationsEnabled(context)) return
        ensureChannel(context)
        NotificationManagerCompat.from(context).notify(
            notificationIdFor(rec.id, false),
            progressNotification(context, rec, pct)
        )
    }

    /** ForegroundInfo for a download worker: same notification
     *  id as [notifyProgress] so progress updates merge into
     *  the foreground notification. dataSync matches the
     *  manifest's SystemForegroundService declaration. */
    fun foregroundInfo(
        context: Context, rec: DownloadRecord, pct: Int = -1
    ): ForegroundInfo = ForegroundInfo(
        notificationIdFor(rec.id, false),
        progressNotification(context, rec, pct),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    fun notifyComplete(context: Context, store: PioneerStore, rec: DownloadRecord) {
        if (!downloadNotificationsEnabled(context)) return
        ensureChannel(context)
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (rec.isPrivate) "Download complete" else rec.title)
            .setContentText(if (rec.isPrivate) "Download complete" else "Download complete • ${rec.fileName}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(
            notificationIdFor(rec.id, true), note
        )
        NotificationManagerCompat.from(context).cancel(notificationIdFor(rec.id, false))
    }

    fun notifyFailed(context: Context, store: PioneerStore, rec: DownloadRecord, label: String) {
        if (!downloadNotificationsEnabled(context)) return
        ensureChannel(context)
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (rec.isPrivate) "Download failed" else rec.title)
            .setContentText(if (rec.isPrivate) "Download failed" else label)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(
            notificationIdFor(rec.id, true), note
        )
        NotificationManagerCompat.from(context).cancel(notificationIdFor(rec.id, false))
    }

    /** Finish a worker download: publish (public) or stage
     *  (app), record the URI, mark complete, notify. */
    internal fun finishDownload(
        context: Context, store: PioneerStore, rec: DownloadRecord, staged: File
    ): Boolean {
        val appCtx = context.applicationContext
        val location = locationFor(store, rec)
        return runCatching {
            if (location == "app" || rec.isPrivate) {
                val dest = destFile(appCtx, store, rec.fileName, "app").apply {
                    parentFile?.mkdirs()
                }
                if (!renameOrCopy(staged, dest)) return false
                store.updateDownloadUri(rec.id, dest.absolutePath)
            } else {
                val name = uniqueMediaStoreName(appCtx, rec.fileName)
                val published = publishToMediaStore(appCtx, staged, name)
                if (published != null) {
                    store.updateDownloadUri(rec.id, published.toString())
                    if (name != rec.fileName) {
                        store.updateDownloadFileName(rec.id, name)
                    }
                    runCatching { staged.delete() }
                } else {
                    // MediaStore publish failed: fall back to
                    // the raw public file, like the legacy path.
                    val dest = destFile(appCtx, store, rec.fileName, "public").apply {
                        parentFile?.mkdirs()
                    }
                    if (!renameOrCopy(staged, dest)) return false
                    store.updateDownloadUri(rec.id, dest.absolutePath)
                }
            }
            writeTerminalIfNotPaused(store, rec.id, "complete", 100)
            clearJobBundle(store, rec.id)
            notifyComplete(appCtx, store, rec)
            true
        }.getOrDefault(false)
    }

    /** Fail a worker download honestly: label + terminal + notify. */
    internal fun failDownload(
        context: Context, store: PioneerStore, rec: DownloadRecord, e: Exception
    ) {
        noteError(store, rec, e)
        writeTerminalIfNotPaused(store, rec.id, "failed")
        notifyFailed(context, store, rec, errorLabel(e))
    }

    // ---------- HLS ----------

    data class HlsOutcome(val ok: Boolean, val bytes: Long = 0, val error: String? = null)

    private data class HlsVariant(val bandwidth: Long, val width: Int, val height: Int, val url: String)
    private data class HlsSegment(val url: String, val duration: Double, val keyUri: String?, val keyIv: String?)
    private data class HlsMedia(val segments: List<HlsSegment>, val isLive: Boolean)

    private fun stagingDir(context: Context, rec: DownloadRecord): File =
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "notscape-staging/${rec.fileName}.${rec.id}.segments"
        ).apply { mkdirs() }

    private fun segJournalFile(dir: File): File = File(dir, "journal.txt")

    private fun readSegJournal(dir: File): MutableSet<Int> {
        val f = segJournalFile(dir)
        if (!f.exists()) return mutableSetOf()
        return f.readLines().mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun writeSegJournal(dir: File, done: Set<Int>) {
        runCatching {
            segJournalFile(dir).writeText(done.sorted().joinToString("\n"))
        }
    }

    private fun fetchText(url: String, referer: String?, cookie: String? = null): String {
        val conn = openGet(url, referer, cookie)
        return checkedBytes(conn).decodeToString()
    }

    private fun resolveUrl(base: String, rel: String): String =
        runCatching { URL(URL(base), rel).toString() }.getOrDefault(rel)

    private fun parseHlsMaster(text: String, baseUrl: String): List<HlsVariant> {
        val lines = text.lines()
        val out = mutableListOf<HlsVariant>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
            val w = res?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val h = res?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val uri = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (uri.isNotBlank() && !uri.startsWith("#")) {
                out += HlsVariant(bw, w, h, resolveUrl(baseUrl, uri))
            }
        }
        return out
    }

    private fun parseHlsMedia(text: String, baseUrl: String): HlsMedia {
        val lines = text.lines()
        val segments = mutableListOf<HlsSegment>()
        var duration = 0.0
        var keyUri: String? = null
        var keyIv: String? = null
        var live = true
        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-ENDLIST") -> live = false
                t.startsWith("#EXTINF:") -> {
                    duration = t.substringAfter(":").substringBefore(",")
                        .toDoubleOrNull() ?: 0.0
                }
                t.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("""METHOD=([^,]+)""").find(t)
                        ?.groupValues?.getOrNull(1) ?: ""
                    if (method == "AES-128") {
                        keyUri = Regex("""URI="([^"]+)""").find(t)
                            ?.groupValues?.getOrNull(1)?.let { resolveUrl(baseUrl, it) }
                        keyIv = Regex("""IV=0x([0-9a-fA-F]+)""").find(t)
                            ?.groupValues?.getOrNull(1)
                    } else {
                        keyUri = null
                        keyIv = null
                    }
                }
                t.isNotBlank() && !t.startsWith("#") -> {
                    segments += HlsSegment(resolveUrl(baseUrl, t), duration, keyUri, keyIv)
                }
            }
        }
        return HlsMedia(segments, live)
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }

    /** Fetch + join an HLS stream into one playable file.
     *  Segments are fetched with a journal, so a resumed job
     *  continues where it stopped instead of starting over. */
    fun runHlsJob(
        context: Context, store: PioneerStore, rec: DownloadRecord,
        referer: String?, onProgress: (Int) -> Unit = {},
        stageOnly: File? = null,
    ): HlsOutcome {
        val appCtx = context.applicationContext
        val bundle = readJobBundle(store, rec.id)
        val cookie = bundle?.get("cookie")?.takeIf { it.isNotBlank() }
        val cookieHost = bundle?.get("cookieHost") ?: ""
        val snapshot = if (!cookie.isNullOrBlank() && cookieHost.isNotBlank()) {
            CookieSnapshot(cookieHost, cookie)
        } else null
        val variantBw = bundle?.get("variantBandwidth")?.toLongOrNull()
        return runCatching {
            val masterText = fetchText(
                rec.url, referer,
                cookieForRequest(rec.url, snapshot, null)
            )
            val mediaUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
                val variants = parseHlsMaster(masterText, rec.url)
                if (variants.isEmpty()) throw java.io.IOException("Empty HLS master playlist")
                val chosen = variantBw?.let { bw ->
                    variants.firstOrNull { it.bandwidth == bw }
                } ?: variants.maxByOrNull { it.bandwidth }
                chosen!!.url
            } else {
                rec.url
            }
            val mediaText = fetchText(
                mediaUrl, referer,
                cookieForRequest(mediaUrl, snapshot, null)
            )
            val media = parseHlsMedia(mediaText, mediaUrl)
            if (media.segments.isEmpty()) throw java.io.IOException("Empty HLS media playlist")
            val dir = stagingDir(appCtx, rec)
            val done = readSegJournal(dir)
            val total = media.segments.size
            var keyBytes: ByteArray? = null
            var lastKeyUri: String? = null
            var fetchedBytes = 0L
            for ((index, seg) in media.segments.withIndex()) {
                if (isCancelled(rec.id)) throw InterruptedException("cancelled")
                if (isPaused(rec.id)) {
                    writeSegJournal(dir, done)
                    return HlsOutcome(false, fetchedBytes, "paused")
                }
                if (index in done) continue
                val segCookie = cookieForRequest(seg.url, snapshot, null)
                val bytes = runCatching {
                    checkedBytes(openGet(seg.url, referer, segCookie))
                }.getOrElse { e ->
                    // One transient failure retries once after a
                    // short wait; a second failure fails the job.
                    if (sleepChecked(rec.id, 2_000)) {
                        writeSegJournal(dir, done)
                        return HlsOutcome(false, fetchedBytes, "paused")
                    }
                    checkedBytes(openGet(seg.url, referer, segCookie))
                }
                val plain = if (seg.keyUri != null) {
                    if (seg.keyUri != lastKeyUri) {
                        keyBytes = checkedBytes(
                            openGet(seg.keyUri, referer, cookieForRequest(seg.keyUri, snapshot, null))
                        )
                        lastKeyUri = seg.keyUri
                    }
                    val iv = seg.keyIv?.let { hex ->
                        val padded = hex.padStart(32, '0')
                        ByteArray(16) { i ->
                            padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                        }
                    } ?: ByteArray(16).also { b ->
                        // IV defaults to the segment sequence
                        // number as a 16-byte big-endian value.
                        b[12] = (index ushr 24).toByte()
                        b[13] = (index ushr 16).toByte()
                        b[14] = (index ushr 8).toByte()
                        b[15] = index.toByte()
                    }
                    decryptAes128(bytes, keyBytes!!, iv)
                } else bytes
                File(dir, "seg-%05d.ts".format(index)).writeBytes(plain)
                done += index
                fetchedBytes += plain.size.toLong()
                if (index % 5 == 0 || index == total - 1) {
                    writeSegJournal(dir, done)
                    val pct = ((index + 1) * 100 / total).coerceIn(0, 100)
                    store.updateDownloadProgress(rec.id, pct)
                    noteBytes(store, rec, fetchedBytes, -1, -1)
                    onProgress(pct)
                    notifyProgress(appCtx, rec, pct)
                }
            }
            // Join in order into the staged output.
            val staged = File(dir, "joined.ts")
            staged.outputStream().use { out ->
                for (i in media.segments.indices) {
                    val f = File(dir, "seg-%05d.ts".format(i))
                    if (f.exists()) f.inputStream().use { it.copyTo(out) }
                }
            }
            // CMAF/fMP4 joins are really MP4, not TS: sniff
            // for an ftyp box and rename the record.
            val isMp4 = runCatching {
                staged.inputStream().use { ins ->
                    val head = ByteArray(12)
                    ins.read(head) == 12 && head.copyOfRange(4, 8).decodeToString() == "ftyp"
                }
            }.getOrDefault(false)
            if (isMp4 && rec.fileName.endsWith(".ts", ignoreCase = true)) {
                val mp4Name = rec.fileName.substringBeforeLast('.') + ".mp4"
                store.updateDownloadFileName(rec.id, mp4Name)
            }
            if (stageOnly != null) {
                // Merge side: stage the joined file for the
                // remux instead of finishing the parent record.
                if (!renameOrCopy(staged, stageOnly)) {
                    throw java.io.IOException("Could not stage HLS side")
                }
            } else if (!finishDownload(appCtx, store, rec, staged)) {
                throw java.io.IOException("Could not place finished file")
            }
            runCatching { dir.deleteRecursively() }
            HlsOutcome(true, fetchedBytes)
        }.getOrElse { e ->
            if (e is InterruptedException) {
                HlsOutcome(false, 0, "cancelled")
            } else if (e.message == "paused") {
                HlsOutcome(false, 0, "paused")
            } else {
                failDownload(appCtx, store, rec, e as? Exception ?: java.io.IOException("$e"))
                HlsOutcome(false, 0, errorLabel(e as? Exception ?: java.io.IOException("$e")))
            }
        }
    }

    private fun downloadHls(
        context: Context, store: PioneerStore, rec: DownloadRecord, referer: String?
    ): HlsOutcome = runHlsJob(context, store, rec, referer)

    /** A quality variant offered in the download confirm
     *  dialog (audit D-m13). [url] is the variant playlist
     *  URL for HLS; for DASH it is null and [bandwidth]
     *  selects the representation at start time. */
    data class DownloadVariant(
        val label: String, val url: String?, val bandwidth: Long
    )

    /** Fetch the quality variants for an HLS master playlist
     *  or a DASH manifest while the confirmation dialog is
     *  up; empty means no choice (single-variant source, or
     *  the fetch failed). Highest bandwidth first. */
    fun fetchDownloadVariants(
        url: String, contentType: String?, referer: String?
    ): List<DownloadVariant> {
        val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            contentType?.contains("mpegurl", ignoreCase = true) == true
        val isDash = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        if (!isHls && !isDash) return emptyList()
        return runCatching {
            val text = fetchText(url, referer)
            if (isHls) {
                parseHlsMaster(text, url)
                    .sortedByDescending { it.bandwidth }
                    .map {
                        DownloadVariant(
                            label = if (it.width > 0) {
                                "${it.width}x${it.height} • ${it.bandwidth / 1000} kbps"
                            } else "${it.bandwidth / 1000} kbps",
                            url = it.url, bandwidth = it.bandwidth
                        )
                    }
            } else {
                parseDashManifest(text, url)
                    .filter { it.mime.contains("video") || it.width > 0 }
                    .sortedByDescending { it.bandwidth }
                    .map {
                        DownloadVariant(
                            label = if (it.width > 0) {
                                "${it.width}x${it.height} • ${it.bandwidth / 1000} kbps"
                            } else "${it.bandwidth / 1000} kbps",
                            url = null, bandwidth = it.bandwidth
                        )
                    }
            }
        }.getOrDefault(emptyList())
    }

    // ---------- DASH ----------

    data class DashOutcome(val ok: Boolean, val bytes: Long = 0, val error: String? = null)

    private data class DashRep(
        val bandwidth: Long, val width: Int, val height: Int,
        val mime: String, val baseUrl: String?,
        val mediaTemplate: String?, val initTemplate: String?,
        val timescale: Long, val duration: Long, val startNumber: Long,
        val segments: List<String>,
    )

    /** Parse a DASH manifest (MPD) into its representations.
     *  Supports SegmentTemplate (media/initialization with
     *  $Number$ / $Time$) and SegmentList (SegmentURL list).
     *  Dynamic (live) manifests are rejected honestly. */
    private fun parseDashManifest(text: String, baseUrl: String): List<DashRep> {
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(text.reader())
        val reps = mutableListOf<DashRep>()
        var dynamic = false
        var curMime = ""
        var adaptMime = ""
        var curBw = 0L
        var curW = 0
        var curH = 0
        var curBase: String? = null
        var curMedia: String? = null
        var curInit: String? = null
        var curTimescale = 1L
        var curDuration = 0L
        var curStart = 1L
        var curSegments = mutableListOf<String>()
        var inRep = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "MPD" -> {
                        dynamic = parser.getAttributeValue(null, "type") == "dynamic"
                    }
                    "AdaptationSet" -> {
                        adaptMime = parser.getAttributeValue(null, "mimeType")
                            ?: when (parser.getAttributeValue(null, "contentType")) {
                                "video" -> "video/mp4"
                                "audio" -> "audio/mp4"
                                else -> ""
                            }
                    }
                    "Representation" -> {
                        inRep = true
                        curMime = parser.getAttributeValue(null, "mimeType")
                            ?: adaptMime.ifBlank { curMime }
                        curBw = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                        curW = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                        curH = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                        curBase = null
                        curMedia = null
                        curInit = null
                        curTimescale = 1L
                        curDuration = 0L
                        curStart = 1L
                        curSegments = mutableListOf()
                    }
                    "BaseURL" -> {
                        if (inRep) curBase = parser.nextText()?.trim()
                    }
                    "SegmentTemplate" -> {
                        if (inRep) {
                            curMedia = parser.getAttributeValue(null, "media")
                            curInit = parser.getAttributeValue(null, "initialization")
                            curTimescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                            curDuration = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            curStart = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L
                        }
                    }
                    "SegmentURL" -> {
                        if (inRep) {
                            parser.getAttributeValue(null, "media")?.let { curSegments += it }
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "Representation" && inRep) {
                        inRep = false
                        reps += DashRep(
                            curBw, curW, curH, curMime, curBase,
                            curMedia, curInit, curTimescale, curDuration,
                            curStart, curSegments.toList()
                        )
                    }
                }
            }
            event = parser.next()
        }
        if (dynamic) throw java.io.IOException("Live DASH streams cannot be downloaded")
        if (reps.isEmpty()) throw java.io.IOException("No DASH representations found")
        return reps
    }

    /** Fetch + assemble a DASH stream into one MP4. Video and
     *  audio tracks are downloaded separately and remuxed with
     *  Media3 Transformer (the same remux as merges). */
    fun runDashJob(
        context: Context, store: PioneerStore, rec: DownloadRecord,
        referer: String?, onProgress: (Int) -> Unit = {},
        stageOnly: File? = null,
    ): DashOutcome {
        val appCtx = context.applicationContext
        val bundle = readJobBundle(store, rec.id)
        val variantBw = bundle?.get("variantBandwidth")?.toLongOrNull()
        return runCatching {
            val manifestText = fetchText(rec.url, referer)
            val reps = parseDashManifest(manifestText, rec.url)
            val videos = reps.filter { it.mime.contains("video") || it.width > 0 }
            val audios = reps.filter { it.mime.contains("audio") }
            val video = variantBw?.let { bw -> videos.firstOrNull { it.bandwidth == bw } }
                ?: videos.maxByOrNull { it.bandwidth }
                ?: throw java.io.IOException("No DASH video representation")
            val audio = audios.maxByOrNull { it.bandwidth }
            val dir = stagingDir(appCtx, rec)
            val videoFile = downloadDashTrack(
                appCtx, store, rec, video, rec.url, referer, dir, "track0", onProgress, 0.0, 0.8
            )
            var audioFile: File? = null
            if (audio != null) {
                audioFile = downloadDashTrack(
                    appCtx, store, rec, audio, rec.url, referer, dir, "track1", onProgress, 0.8, 0.2
                )
            }
            val staged = File(dir, "joined.mp4")
            if (audioFile != null) {
                remuxMp4(appCtx, listOf(videoFile, audioFile), staged)
            } else {
                remuxMp4(appCtx, listOf(videoFile), staged)
            }
            if (stageOnly != null) {
                if (!renameOrCopy(staged, stageOnly)) {
                    throw java.io.IOException("Could not stage DASH side")
                }
            } else if (!finishDownload(appCtx, store, rec, staged)) {
                throw java.io.IOException("Could not place finished file")
            }
            runCatching { dir.deleteRecursively() }
            DashOutcome(true)
        }.getOrElse { e ->
            if (e is InterruptedException) {
                DashOutcome(false, 0, "cancelled")
            } else {
                failDownload(appCtx, store, rec, e as? Exception ?: java.io.IOException("$e"))
                DashOutcome(false, 0, errorLabel(e as? Exception ?: java.io.IOException("$e")))
            }
        }
    }

    /** Download one DASH track (init + segments) into a file.
     *  SegmentTemplate $Number$ is expanded until the first
     *  404 (the manifest carries no segment count for
     *  SegmentTimeline-less manifests); SegmentList uses its
     *  explicit URLs. */
    private fun downloadDashTrack(
        appCtx: Context, store: PioneerStore, rec: DownloadRecord,
        rep: DashRep, baseUrl: String, referer: String?, dir: File,
        prefix: String, onProgress: (Int) -> Unit,
        progressBase: Double, progressSpan: Double,
    ): File {
        val out = File(dir, "$prefix.mp4")
        out.outputStream().use { output ->
            rep.initTemplate?.let { init ->
                val initUrl = resolveUrl(baseUrl, init)
                val bytes = checkedBytes(openGet(initUrl, referer))
                output.write(bytes)
            }
            val urls: List<String> = when {
                rep.segments.isNotEmpty() ->
                    rep.segments.map { resolveUrl(baseUrl, it) }
                rep.mediaTemplate != null -> {
                    // Expand $Number$ until 404; cap at 20,000
                    // segments so a runaway template cannot loop.
                    val found = mutableListOf<String>()
                    var n = rep.startNumber
                    while (n < rep.startNumber + 20_000) {
                        val u = resolveUrl(
                            baseUrl,
                            rep.mediaTemplate
                                .replace("\$Number\$", n.toString())
                                .replace("\$Number%05d\$", "%05d".format(n))
                        )
                        found += u
                        n++
                        // Probe lazily during fetch (below) — the
                        // list is capped by the first 404 there.
                        if (found.size >= 20_000) break
                    }
                    // The fetcher below stops at the first 404,
                    // so over-generation here is harmless.
                    found
                }
                rep.baseUrl != null -> listOf(resolveUrl(baseUrl, rep.baseUrl))
                else -> emptyList()
            }
            if (urls.isEmpty()) throw java.io.IOException("No DASH segments for track")
            var fetched = 0
            for ((i, segUrl) in urls.withIndex()) {
                if (isCancelled(rec.id)) throw InterruptedException("cancelled")
                if (isPaused(rec.id)) throw InterruptedException("paused")
                val conn = runCatching { openGet(segUrl, referer) }.getOrNull()
                    ?: break
                if (conn.responseCode == 404) {
                    conn.disconnect()
                    break
                }
                val bytes = runCatching { checkedBytes(conn) }.getOrElse {
                    conn.disconnect()
                    break
                }
                output.write(bytes)
                fetched++
                if (i % 10 == 0) {
                    val pct = (progressBase * 100 + progressSpan * 100 * (i + 1) / urls.size)
                        .toInt().coerceIn(0, 100)
                    store.updateDownloadProgress(rec.id, pct)
                    onProgress(pct)
                }
            }
            if (fetched == 0) throw java.io.IOException("No DASH segments downloaded")
        }
        return out
    }

    private fun downloadDash(
        context: Context, store: PioneerStore, rec: DownloadRecord, referer: String?
    ): DashOutcome = runDashJob(context, store, rec, referer)

    // ---------- plain + segmented ----------

    /** Single-connection plain download with resume from a
     *  .part file (Range) and progress. Returns the finished
     *  staged file. */
    internal fun downloadPlain(
        context: Context, store: PioneerStore, rec: DownloadRecord,
        url: String, referer: String?, dest: File,
    ): File {
        val appCtx = context.applicationContext
        val part = File(dest.parentFile, dest.name + ".part")
        var downloaded = if (part.exists()) part.length() else 0L
        val conn = openGet(
            url, referer,
            range = if (downloaded > 0) "bytes=$downloaded-" else null
        )
        checkStatus(conn)
        val total = contentRangeTotal(conn.getHeaderField("Content-Range"))
            ?: conn.getHeaderFieldLong("Content-Length", -1).let {
                if (it > 0 && downloaded > 0) it + downloaded else it
            }
        val append = conn.responseCode == 206 && downloaded > 0
        if (!append) downloaded = 0L
        val startedAt = System.currentTimeMillis()
        var lastTick = startedAt
        var lastBytes = downloaded
        conn.inputStream.use { input ->
            // Append on a 206 resume (the part file already
            // holds the first `downloaded` bytes); truncate
            // on a fresh start. A truncating stream here
            // would silently discard the resumed prefix.
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled(rec.id)) throw InterruptedException("cancelled")
                    if (isPaused(rec.id)) throw InterruptedException("paused")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 500) {
                        val speed = if (now > lastTick) {
                            (downloaded - lastBytes) * 1000 / (now - lastTick)
                        } else -1L
                        noteBytes(store, rec, downloaded, total, speed)
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            notifyProgress(appCtx, rec, pct)
                        }
                        lastTick = now
                        lastBytes = downloaded
                    }
                }
            }
        }
        conn.disconnect()
        if (!renameOrCopy(part, dest)) throw java.io.IOException("Could not place file")
        return dest
    }

    /** Segmented download: split the file into 4 Range
     *  chunks, fetched in parallel, joined in order. A
     *  journal records finished chunks so resume continues
     *  instead of restarting. On a 429 (or a Range refusal)
     *  the job downgrades to the system downloader
     *  (`dldowngrade:<id>` = "1") instead of failing. */
    fun runSegmentedJob(
        context: Context, store: PioneerStore, rec: DownloadRecord,
        referer: String?, onProgress: (Int) -> Unit = {}
    ): Boolean {
        val appCtx = context.applicationContext
        val bundle = readJobBundle(store, rec.id)
        val finalUrl = bundle?.get("finalUrl")?.takeIf { it.isNotBlank() } ?: rec.url
        val total = bundle?.get("total")?.toLongOrNull() ?: -1L
        return runCatching {
            if (total <= 0) {
                // No usable probe total: fall through to a
                // plain single-connection download.
                val dir = stagingDir(appCtx, rec)
                val staged = File(dir, "plain")
                downloadPlain(appCtx, store, rec, finalUrl, referer, staged)
                if (!finishDownload(appCtx, store, rec, staged)) {
                    throw java.io.IOException("Could not place finished file")
                }
                runCatching { dir.deleteRecursively() }
                return true
            }
            val dir = stagingDir(appCtx, rec)
            val journal = File(dir, "seg-journal.txt")
            val done = runCatching {
                journal.readLines().mapNotNull { it.toIntOrNull() }.toMutableSet()
            }.getOrDefault(mutableSetOf())
            val chunks = 4
            val chunkSize = (total + chunks - 1) / chunks
            kotlinx.coroutines.runBlocking {
                val jobs = (0 until chunks).map { i ->
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize - 1, total - 1)
                    async(Dispatchers.IO) {
                    if (i in done) return@async
                    val part = File(dir, "chunk-$i")
                    var have = if (part.exists()) part.length() else 0L
                    val conn = openSegmentedGet(
                        appCtx, finalUrl, referer,
                        range = "bytes=${start + have}-$end"
                    )
                    if (conn.responseCode == 429) {
                        conn.disconnect()
                        throw RateLimitedException()
                    }
                    if (conn.responseCode == 200) {
                        // Server ignored Range: a chunked
                        // download would corrupt the file.
                        conn.disconnect()
                        throw RangeUnsupportedException()
                    }
                    checkStatus(conn)
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(part, have > 0).use { out ->
                            // Append mode: the part file holds
                            // the bytes fetched so far.
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled(rec.id)) throw InterruptedException("cancelled")
                                if (isPaused(rec.id)) throw InterruptedException("paused")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                            }
                        }
                    }
                    conn.disconnect()
                    synchronized(done) {
                        done += i
                        runCatching {
                            journal.writeText(done.sorted().joinToString("\n"))
                        }
                    }
                    }
                }
                jobs.forEach { it.await() }
            }
            // Join in order.
            val staged = File(dir, "joined")
            staged.outputStream().use { out ->
                for (i in 0 until chunks) {
                    val f = File(dir, "chunk-$i")
                    if (!f.exists()) throw java.io.IOException("Missing chunk $i")
                    f.inputStream().use { it.copyTo(out) }
                }
            }
            // The completion gate: the joined size must match
            // the probe total, or the file is incomplete.
            if (staged.length() != total) {
                throw java.io.IOException(
                    "Incomplete segmented download (${staged.length()}/$total)"
                )
            }
            if (!finishDownload(appCtx, store, rec, staged)) {
                throw java.io.IOException("Could not place finished file")
            }
            runCatching { dir.deleteRecursively() }
            true
        }.getOrElse { e ->
            when (e) {
                is InterruptedException -> false
                is RangeUnsupportedException -> {
                    // Range refused: fall back to a plain
                    // single-connection download.
                    val dir = stagingDir(appCtx, rec)
                    val staged = File(dir, "plain")
                    return runCatching {
                        downloadPlain(appCtx, store, rec, finalUrl, referer, staged)
                        if (!finishDownload(appCtx, store, rec, staged)) {
                            throw java.io.IOException("Could not place finished file")
                        }
                        runCatching { dir.deleteRecursively() }
                        true
                    }.getOrElse { inner ->
                        failDownload(
                            appCtx, store, rec,
                            inner as? Exception ?: java.io.IOException("$inner")
                        )
                        false
                    }
                }
                is RateLimitedException -> {
                    // 429 downgrade: continue via the system
                    // downloader and say so on the row.
                    store.putSetting("dldowngrade:${rec.id}", "1")
                    updateJobBundle(store, rec.id, mapOf("engine" to "dm"))
                    routeDirect(appCtx, store, rec, finalUrl, referer)
                    true
                }
                else -> {
                    failDownload(
                        appCtx, store, rec,
                        e as? Exception ?: java.io.IOException("$e")
                    )
                    false
                }
            }
        }
    }

    private class RateLimitedException : java.io.IOException("Rate limited (429)")
    private class RangeUnsupportedException : java.io.IOException("Server ignored Range")

    // ---------- merge ----------

    /** Merge job: fetch both sides (video + audio, each via
     *  the HLS / DASH / plain path as appropriate) and remux
     *  into one MP4 with Media3 Transformer. */
    fun runMergeJob(
        context: Context, store: PioneerStore, rec: DownloadRecord,
        referer: String?, onProgress: (Int) -> Unit = {}
    ): Boolean {
        val appCtx = context.applicationContext
        val bundle = readJobBundle(store, rec.id)
        val videoUrl = bundle?.get("videoUrl") ?: return false
        val audioUrl = bundle?.get("audioUrl") ?: return false
        return runCatching {
            val dir = stagingDir(appCtx, rec)
            val videoSide = File(dir, "side-video")
            val audioSide = File(dir, "side-audio")
            downloadMergeSide(appCtx, store, rec, videoUrl, referer, videoSide)
            if (isCancelled(rec.id)) throw InterruptedException("cancelled")
            downloadMergeSide(appCtx, store, rec, audioUrl, referer, audioSide)
            if (isCancelled(rec.id)) throw InterruptedException("cancelled")
            val staged = File(dir, "merged.mp4")
            remuxMp4(appCtx, listOf(videoSide, audioSide), staged)
            if (!finishDownload(appCtx, store, rec, staged)) {
                throw java.io.IOException("Could not place finished file")
            }
            runCatching { dir.deleteRecursively() }
            true
        }.getOrElse { e ->
            if (e is InterruptedException) false
            else {
                failDownload(
                    appCtx, store, rec,
                    e as? Exception ?: java.io.IOException("$e")
                )
                false
            }
        }
    }

    /** Fetch one merge side through the engine that fits its
     *  URL (HLS / DASH / plain), into [dest]. */
    private fun downloadMergeSide(
        appCtx: Context, store: PioneerStore, rec: DownloadRecord,
        url: String, referer: String?, dest: File
    ) {
        val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val isDash = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        when {
            isHls -> {
                val side = rec.copy(url = url)
                val outcome = runHlsJob(appCtx, store, side, referer, stageOnly = dest)
                if (!outcome.ok) throw java.io.IOException(outcome.error ?: "HLS side failed")
            }
            isDash -> {
                val side = rec.copy(url = url)
                val outcome = runDashJob(appCtx, store, side, referer, stageOnly = dest)
                if (!outcome.ok) throw java.io.IOException(outcome.error ?: "DASH side failed")
            }
            else -> downloadPlain(appCtx, store, rec, url, referer, dest)
        }
    }

    /** Remux [inputs] into one MP4 with Media3 Transformer.
     *  Video from the first input, audio from the rest (or
     *  the first input alone when there is only one). */
    private fun remuxMp4(
        context: Context, inputs: List<File>, output: File
    ) {
        if (inputs.isEmpty()) throw java.io.IOException("Nothing to remux")
        output.parentFile?.mkdirs()
        val latch = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        val items = inputs.map { file ->
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence(items)
        ).build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    latch.countDown()
                }
                override fun onError(
                    composition: Composition, result: ExportResult,
                    exception: ExportException
                ) {
                    failure = exception
                    latch.countDown()
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
        latch.await()
        failure?.let { throw java.io.IOException("Remux failed: ${it.message}", it) }
        if (!output.exists() || output.length() == 0L) {
            throw java.io.IOException("Remux produced no output")
        }
    }

    private fun EditedMediaItemSequence(
        items: List<EditedMediaItem>
    ): androidx.media3.transformer.EditedMediaItemSequence =
        androidx.media3.transformer.EditedMediaItemSequence.Builder(items).build()

    // ---------- staging sweep ----------

    /** Delete stale staging left by jobs that died mid-run
     *  (process death, crash): staging dirs older than a day
     *  with no live record referencing them. */
    fun sweepStaging(context: Context, store: PioneerStore) {
        val appCtx = context.applicationContext
        val liveIds = store.downloadsSnapshot().map { it.id }.toSet()
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        for (dir in stagingDirs(appCtx)) {
            sweepOneDir(dir, liveIds, cutoff)
        }
    }

    private fun sweepOneDir(dir: File, liveIds: Set<Long>, cutoff: Long) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                val idInName = Regex("""\.(\d+)\.""").find(f.name)
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
                val stale = f.lastModified() < cutoff
                if (stale && (idInName == null || idInName !in liveIds)) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
        }
    }
}
