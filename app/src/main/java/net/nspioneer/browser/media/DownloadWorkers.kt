package net.nspioneer.browser.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.PioneerStore

/**
 * WorkManager workers for the download engines in
 * [Downloads]. One file; the four workers share the
 * staging / journal helpers that live in Downloads.
 *
 * Input data (see `Downloads.enqueueWorker`): `recId`
 * (Long) and `referer` (String). Everything else the job
 * needs — final URL, total, cookie, md5, video/audio
 * URLs — lives in the job bundle in the store
 * (`dljob:<id>`), written by `Downloads.start`.
 *
 * Each worker runs as a foreground service
 * (dataSync, `Downloads.foregroundInfo`) so a long
 * download survives backgrounding; progress is posted
 * through `Downloads.notifyProgress` by the engines.
 *
 * Retry policy: a transient failure retries up to the
 * backoff the request was enqueued with (30s
 * exponential, see `enqueueWorker`); a paused or
 * cancelled job is not a failure — it returns success
 * and leaves the record in its paused/cancelled state.
 */

private const val MAX_ATTEMPTS = 3

private suspend fun CoroutineWorker.runDownloadWorker(
    context: Context,
    params: WorkerParameters,
    run: suspend (PioneerStore, net.nspioneer.browser.data.DownloadRecord, String?) -> Boolean,
): Result {
    val appCtx = context.applicationContext
    val store = PioneerStore.get(appCtx)
    val recId = params.inputData.getLong("recId", -1L)
    if (recId < 0) return Result.failure()
    val rec = store.downloadsSnapshot().firstOrNull { it.id == recId }
        ?: return Result.failure()
    val referer = params.inputData.getString("referer")?.takeIf { it.isNotBlank() }
    // Foreground from the start: WorkManager requires the
    // notification within the foreground window, and a
    // download can run for a long time.
    setForeground(Downloads.foregroundInfo(appCtx, rec, rec.progress))
    val ok = withContext(Dispatchers.IO) { run(store, rec, referer) }
    return when {
        ok -> Result.success()
        store.downloadsSnapshot().firstOrNull { it.id == recId }?.status in
            listOf("paused", "cancelled") -> Result.success()
        params.runAttemptCount < MAX_ATTEMPTS -> Result.retry()
        else -> Result.failure()
    }
}

/** HLS worker: master/media playlist, AES-128 segments,
 *  journaled resume, join to .ts (or .mp4 when the join
 *  sniffs as fMP4). */
class HlsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runDownloadWorker(
        applicationContext, params
    ) { store, rec, referer ->
        Downloads.runHlsJob(applicationContext, store, rec, referer).ok
    }
}

/** DASH worker: MPD parse, best video + best audio
 *  tracks, init + segments, Media3 Transformer remux
 *  to one MP4. */
class DashDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runDownloadWorker(
        applicationContext, params
    ) { store, rec, referer ->
        Downloads.runDashJob(applicationContext, store, rec, referer).ok
    }
}

/** Segmented worker: 4-way Range chunks with a journal,
 *  joined in order; 429 downgrades to the system
 *  downloader, Range refusal falls back to a plain
 *  single-connection download. */
class SegmentedDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runDownloadWorker(
        applicationContext, params
    ) { store, rec, referer ->
        Downloads.runSegmentedJob(applicationContext, store, rec, referer)
    }
}

/** Merge worker: fetch the video + audio sides (each via
 *  its own engine) and remux into one MP4. */
class MergeDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runDownloadWorker(
        applicationContext, params
    ) { store, rec, referer ->
        Downloads.runMergeJob(applicationContext, store, rec, referer)
    }
}
