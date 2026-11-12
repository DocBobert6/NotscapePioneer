package net.nspioneer.browser.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
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
    inputData: Data,
    runAttemptCount: Int,
    run: suspend (PioneerStore, net.nspioneer.browser.data.DownloadRecord, String?) -> Boolean,
): ListenableWorker.Result {
    val appCtx = context.applicationContext
    val store = PioneerStore.get(appCtx)
    val recId = inputData.getLong("recId", -1L)
    if (recId < 0) return ListenableWorker.Result.failure()
    val rec = store.downloadsSnapshot().firstOrNull { it.id == recId }
        ?: return ListenableWorker.Result.failure()
    val referer = inputData.getString("referer")?.takeIf { it.isNotBlank() }
    // Foreground from the start: WorkManager requires the
    // notification within the foreground window, and a
    // download can run for a long time.
    setForeground(Downloads.foregroundInfo(appCtx, rec, rec.progress))
    val ok = withContext(Dispatchers.IO) { run(store, rec, referer) }
    return when {
        ok -> ListenableWorker.Result.success()
        store.downloadsSnapshot().firstOrNull { it.id == recId }?.status in
            listOf("paused", "cancelled") -> ListenableWorker.Result.success()
        runAttemptCount < MAX_ATTEMPTS -> ListenableWorker.Result.retry()
        else -> ListenableWorker.Result.failure()
    }
}

/** HLS worker: master/media playlist, AES-128 segments,
 *  journaled resume, join to .ts (or .mp4 when the join
 *  sniffs as fMP4). */
class HlsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result = runDownloadWorker(
        applicationContext, inputData, runAttemptCount
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
    override suspend fun doWork(): ListenableWorker.Result = runDownloadWorker(
        applicationContext, inputData, runAttemptCount
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
    override suspend fun doWork(): ListenableWorker.Result = runDownloadWorker(
        applicationContext, inputData, runAttemptCount
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
    override suspend fun doWork(): ListenableWorker.Result = runDownloadWorker(
        applicationContext, inputData, runAttemptCount
    ) { store, rec, referer ->
        Downloads.runMergeJob(applicationContext, store, rec, referer)
    }
}
