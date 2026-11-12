package net.nspioneer.browser.media

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nspioneer.browser.R
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.ui.NotscapeTheme
import net.nspioneer.browser.ui.frostBar
import java.io.File
import kotlin.math.abs

/**
 * Exact port of Mariner's PlayerActivity (2,351 lines) — layout,
 * controls, gestures, floating pill, queue dialog, lock cover
 * (design §8.5). Same package path (`media/`), same intent
 * extras (`queue_urls` / `queue_titles` / `start_pip`), same
 * `launch` / `launchQueue` signatures.
 *
 * Pioneer adaptations (each reported in the Worker C report):
 *  1. No PlaybackService / MediaController in Phase 3 — the
 *     player is a local ExoPlayer built directly (Mariner's
 *     6s-fallback path, promoted to the only path). No
 *     background playback service exists in this phase.
 *  2. Resume positions live in a small private JSON file
 *     (`filesDir/player-positions.json`) instead of the store:
 *     PioneerStore has no playback-position API in Phase 3.
 *  3. HLS/DASH variant parsing is inline here (minimal
 *     `parseHlsMasterVariants` / `parseDashVariants`) — the
 *     Mariner `HlsPlaylist` / `DashManifest` files are owned
 *     by no Phase 3 worker.
 *  4. No cookies are attached to playback or to the quality
 *     probe: Gecko cookies are not reachable through
 *     WebKit CookieManager, and Pioneer has no WebView.
 *     Referer + the browser user-agent still ride along.
 *  5. Subtitle fonts: platform typefaces only — Pioneer
 *     ships no bundled fonts, so the opensans/inter/custom
 *     faces fall back to the default.
 *  6. Cast: the button renders exactly as in Mariner, but no
 *     session is ever launched (Phase 4, design §10) — no
 *     SessionManagerListener, no `CastHelper.loadUrl`.
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_QUEUE_URLS = "queue_urls"
        private const val EXTRA_QUEUE_TITLES = "queue_titles"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_PRIVATE = "private"
        private const val EXTRA_START_PIP = "start_pip"

        fun launch(context: Context, url: String, title: String, referer: String? = null, privateSession: Boolean = false, startPip: Boolean = false) {
            val uri = if (url.startsWith("/")) Uri.fromFile(File(url)).toString() else url
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, uri)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_REFERER, referer)
                    .putExtra(EXTRA_PRIVATE, privateSession)
                    .putExtra(EXTRA_START_PIP, startPip)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun launchQueue(context: Context, urls: List<String>, titles: List<String>, index: Int, referer: String? = null) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_QUEUE_URLS, ArrayList(urls))
                    .putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(titles))
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_REFERER, referer)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setSystemBarsHidden(this, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // True fullscreen (0.0.987, Robert): no status or
        // navigation bars over video, ever; an edge swipe
        // brings them back briefly.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSystemBarsHidden(this, true)
        // Unconditional: a launch without a Referer must
        // clear the previous page's, or it leaks onto
        // every later playback in this process.
        PlayerNet.referer = intent.getStringExtra(EXTRA_REFERER)
        val queueUrls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS)
        val queueTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
        val urls: List<String>
        val titles: List<String>
        val startIndex: Int
        if (!queueUrls.isNullOrEmpty()) {
            urls = queueUrls
            titles = queueTitles ?: queueUrls.map { it.substringAfterLast('/') }
            startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        } else {
            val u = intent.getStringExtra(EXTRA_URL) ?: intent.dataString ?: ""
            urls = listOf(u)
            titles = listOf(intent.getStringExtra(EXTRA_TITLE) ?: u.substringAfterLast('/'))
            startIndex = 0
        }
        val store = PioneerStore.get(this)
        val privateSession = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val startInPip = intent.getBooleanExtra(EXTRA_START_PIP, false)
        setContent {
            NotscapeTheme(store) {
                PlayerScreen(
                    store = store,
                    urls = urls,
                    titles = titles,
                    startIndex = startIndex,
                    privateSession = privateSession,
                    startInPip = startInPip,
                    onBack = { finish() },
                    onStopPlayback = { finish() },
                    onEnterPip = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching {
                                enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build()
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Hides or shows the system bars (Mariner MainActivity helper, local copy). */
internal fun setSystemBarsHidden(activity: android.app.Activity, hidden: Boolean) {
    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (hidden) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Shared player construction: a browser user-agent and the page's
 * Referer on every media request (Mariner `PlayerNet`, carried
 * over). Many CDNs refuse playback (403) without them, which
 * looks like a broken stream. [referer] is set when playback is
 * launched from a web page.
 *
 * Pioneer difference: no cookies. Gecko cookies are not
 * reachable through WebKit CookieManager (Pioneer has no
 * WebView), so cookie-gated streams that played in-page may
 * still 403 here — the quality probe has the same limit.
 */
internal object PlayerNet {

    @Volatile
    var referer: String? = null

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var playerCache: SimpleCache? = null

    private fun cacheFor(context: Context): SimpleCache {
        playerCache?.let { return it }
        synchronized(this) {
            playerCache?.let { return it }
            val cache = SimpleCache(
                java.io.File(context.cacheDir, "player"),
                LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024)
            )
            playerCache = cache
            return cache
        }
    }

    /**
     * True for HLS (.m3u8) and DASH (.mpd) playlist / manifest
     * URLs — the requests that must never be served from the
     * player cache. A stale cached playlist freezes the live
     * window: the player runs out of segments it knows about
     * and reports end-of-stream on a stream that is still live.
     */
    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", ignoreCase = true) ||
            path.endsWith(".mpd", ignoreCase = true)
    }

    private class PlaylistBypassDataSource(
        private val cached: DataSource,
        private val direct: DataSource
    ) : DataSource {
        private var active: DataSource? = null
        private var activeUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds = if (isPlaylistUrl(dataSpec.uri.toString())) direct else cached
            active = ds
            activeUri = dataSpec.uri
            return ds.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = activeUri

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.responseHeaders ?: emptyMap()

        override fun close() {
            active?.close()
            active = null
        }
    }

    fun buildPlayer(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cacheFor(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http))
        val upstream = DataSource.Factory {
            PlaylistBypassDataSource(
                cacheFactory.createDataSource(),
                DefaultDataSource.Factory(context, http).createDataSource()
            )
        }
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val ref = referer
            val scheme = spec.uri.scheme ?: ""
            if (scheme != "https" || ref == null) {
                spec
            } else {
                var headers = spec.httpRequestHeaders
                if (!headers.containsKey("Referer")) {
                    headers = HashMap(headers).apply { put("Referer", ref) }
                    spec.buildUpon().setHttpRequestHeaders(headers).build()
                } else {
                    spec
                }
            }
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .build()
    }
}

/**
 * Resume positions (Mariner `AppStore.playbackPosition` /
 * `setPlaybackPosition` / `clearPlaybackPosition`, carried over
 * as a private file — PioneerStore has no playback-position API
 * in Phase 3). One JSON object in `filesDir`: url → position
 * + title, capped at 100 entries, oldest dropped first.
 * A private session leaves no trace: nothing is written.
 */
internal object PlaybackPositions {
    private const val FILE_NAME = "player-positions.json"
    private const val MAX_ENTRIES = 100
    private val lock = Any()
    private var cache: LinkedHashMap<String, Long>? = null

    private fun load(context: Context): LinkedHashMap<String, Long> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Long>()
        runCatching {
            val text = File(context.filesDir, FILE_NAME).readText()
            val obj = org.json.JSONObject(text)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optLong(k, 0L)
            }
        }
        cache = map
        return map
    }

    private fun save(context: Context, map: LinkedHashMap<String, Long>) {
        runCatching {
            val obj = org.json.JSONObject()
            for ((k, v) in map) obj.put(k, v)
            File(context.filesDir, FILE_NAME).writeText(obj.toString())
        }
    }

    fun get(context: Context, url: String?): Long {
        if (url.isNullOrBlank()) return 0L
        synchronized(lock) { return load(context)[url] ?: 0L }
    }

    fun put(context: Context, url: String, positionMs: Long) {
        if (positionMs <= 0) return
        synchronized(lock) {
            val map = load(context)
            map.remove(url)
            map[url] = positionMs
            while (map.size > MAX_ENTRIES) {
                map.remove(map.keys.first())
            }
            save(context, map)
        }
    }

    fun clear(context: Context, url: String) {
        synchronized(lock) {
            val map = load(context)
            if (map.remove(url) != null) save(context, map)
        }
    }
}

/**
 * Minimal HLS master parsing for the quality row only
 * (Mariner `HlsPlaylist.parseMaster`, reduced to the
 * label + bandwidth the player needs). Variants are the
 * #EXT-X-STREAM-INF lines: BANDWIDTH + RESOLUTION=WxH.
 */
internal fun parseHlsMasterVariants(text: String): List<ParsedQuality> {
    if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()
    val out = mutableListOf<ParsedQuality>()
    for (line in text.lines()) {
        val t = line.trim()
        if (!t.startsWith("#EXT-X-STREAM-INF")) continue
        val bw = Regex("""BANDWIDTH=(\d+)""").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(t)
        val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (bw <= 0) continue
        out.add(
            ParsedQuality(
                label = when {
                    h > 0 -> "${h}p"
                    bw > 0 -> String.format(
                        java.util.Locale.US, "%.1f Mbps",
                        bw / 1_000_000.0
                    )
                    else -> "Variant"
                },
                bandwidth = bw
            )
        )
    }
    return out.sortedByDescending { it.bandwidth }.distinctBy { it.label }
}

/**
 * Minimal DASH parsing for the quality row only (Mariner
 * `DashManifest.parse`). A Representation counts as video
 * when it — or its AdaptationSet — declares a video mime
 * type, or when no mime type is declared and it carries
 * dimensions.
 */
internal fun parseDashVariants(text: String): List<ParsedQuality> {
    if (!text.contains("<MPD")) return emptyList()
    val token = Regex("""<(AdaptationSet|Representation)\b[^>]*>""")
    val attr = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    var setMime = ""
    val out = mutableListOf<ParsedQuality>()
    for (m in token.findAll(text)) {
        val tag = m.value
        val a = attr.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        if (tag.startsWith("<AdaptationSet")) {
            setMime = a["mimeType"] ?: ""
        } else {
            val mime = a["mimeType"] ?: setMime
            val w = a["width"]?.toIntOrNull() ?: 0
            val h = a["height"]?.toIntOrNull() ?: 0
            val bw = a["bandwidth"]?.toLongOrNull() ?: 0L
            val isVideo = mime.startsWith("video") ||
                (mime.isEmpty() && (w > 0 || h > 0))
            if (isVideo && bw > 0) {
                out.add(
                    ParsedQuality(
                        label = when {
                            h > 0 -> "${h}p"
                            bw > 0 -> String.format(
                                java.util.Locale.US, "%.1f Mbps",
                                bw / 1_000_000.0
                            )
                            else -> "Variant"
                        },
                        bandwidth = bw
                    )
                )
            }
        }
    }
    return out.sortedByDescending { it.bandwidth }.distinctBy { it.label }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    store: PioneerStore,
    urls: List<String>,
    titles: List<String>,
    startIndex: Int,
    privateSession: Boolean = false,
    startInPip: Boolean = false,
    onBack: () -> Unit,
    onStopPlayback: () -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    val activePlayer: Player? = exoPlayer
    // Gesture coroutines (pointerInput blocks) run for
    // the composition that created them and never see
    // a later activePlayer: they must read this live
    // state instead of the snapshot above, or every
    // gesture that needs a player stays dead after
    // the player is built asynchronously.
    val activePlayerState = rememberUpdatedState(activePlayer)

    fun buildItems(): List<MediaItem> = urls.mapIndexed { i, u ->
        MediaItem.Builder()
            .setUri(u)
            .setMediaId(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i) ?: "").build())
            .build()
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    // Buffering (0.0.9995): set by the player listener below;
    // drives the play button's cycling fill and the stall watchdog.
    var isBuffering by remember { mutableStateOf(false) }
    var stallRecoveries by remember { mutableIntStateOf(0) }
    // Beta.67 (1b): live items that spuriously hit ENDED get
    // re-prepared at the live edge, at most this many times
    // per item — a genuinely finished (or dead) stream then
    // ends honestly instead of looping forever.
    var liveEndRecoveries by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentIndex by remember { mutableIntStateOf(startIndex) }
    // Beta.58 (7): the in-player queue sheet.
    var showQueue by remember { mutableStateOf(false) }
    // Audit P-m19: a user-loaded .srt/.vtt sidecar for the
    // current item. It is a track like any other once the
    // player rebuilds, so the user picks it in the row below.
    var sidecarSubs by remember {
        mutableStateOf<List<MediaItem.SubtitleConfiguration>>(emptyList())
    }
    // Beta.58 (6): true when sidecarSubs came from the
    // sibling discovery below, so a manual pick is never
    // cleared by it and discovery re-runs per item.
    var sidecarAuto by remember { mutableStateOf(false) }
    fun buildItemsFor(forIndex: Int): List<MediaItem> = urls.mapIndexed { i, u ->
        MediaItem.Builder()
            .setUri(u)
            .setMediaId(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i) ?: "").build())
            .setSubtitleConfigurations(
                if (i == forIndex) sidecarSubs else emptyList()
            )
            .build()
    }
    var listenMode by remember { mutableStateOf(false) }
    // Video scaling (Phase C item 6): resize mode + pinch zoom/pan.
    // The PlayerView rides a TextureView (res/layout/player_view.xml)
    // so the graphicsLayer transform below really moves the picture.
    var scaleMode by remember {
        mutableStateOf(
            runCatching {
                ScaleMode.valueOf(store.setting("playerScale", "Fit"))
            }.getOrDefault(ScaleMode.Fit)
        )
    }
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var showScaleRow by remember { mutableStateOf(false) }
    var transformActive by remember { mutableStateOf(false) }
    fun baseScale() = if (scaleMode == ScaleMode.Crop) 1.25f else 1f
    fun effScale() = baseScale() * pinchScale
    fun resetZoom() {
        pinchScale = 1f
        panX = 0f
        panY = 0f
    }
    fun applyScaleMode(m: ScaleMode) {
        scaleMode = m
        store.putSetting("playerScale", m.name)
        resetZoom()
        playerViewRef?.resizeMode = when (m) {
            ScaleMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ScaleMode.Fill, ScaleMode.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }
    LaunchedEffect(currentIndex) { resetZoom() }
    // Beta.58 (6): a downloaded video's subtitle file
    // sitting beside it is picked up automatically —
    // <video>.vtt / .srt / .ttml, or <video>.sub<N> —
    // so a download's captions play without a manual
    // pick. Only plain file paths have siblings; a
    // MediaStore content:// URI has none. A manual pick
    // always wins, and discovery re-runs per item.
    LaunchedEffect(currentIndex) {
        if (sidecarAuto) sidecarSubs = emptyList()
        val u = urls.getOrNull(currentIndex) ?: return@LaunchedEffect
        val path = when {
            u.startsWith("file://") -> u.removePrefix("file://")
            u.startsWith("/") -> u
            else -> return@LaunchedEffect
        }
        val base = path.substringBeforeLast('.', path)
        val found = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO
        ) {
            val candidates = listOf(
                "$base.vtt", "$base.srt", "$base.ttml",
                "$base.sub1.vtt", "$base.sub2.vtt", "$base.sub3.vtt",
                "$base.sub1.ttml", "$base.sub2.ttml", "$base.sub3.ttml"
            )
            candidates.firstOrNull { java.io.File(it).isFile }
        } ?: return@LaunchedEffect
        val mime = when (found.substringAfterLast('.', "")) {
            "vtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ttml" -> MimeTypes.APPLICATION_TTML
            else -> return@LaunchedEffect
        }
        sidecarSubs = listOf(
            MediaItem.SubtitleConfiguration.Builder(
                android.net.Uri.fromFile(java.io.File(found))
            ).setMimeType(mime).setLanguage("und").build()
        )
        sidecarAuto = true
    }
    var speed by remember {
        mutableFloatStateOf(store.setting("playerSpeed", "1.0").toFloatOrNull() ?: 1.0f)
    }
    // Brightness + volume sliders (beta.18): explicit
    // controls in the overlay, not gestures. Volume
    // is Media3 player.volume — app-local, it never
    // touches device volume. Brightness is the
    // window's screenBrightness, which overrides
    // device auto-brightness only while this player
    // window lives; leaving the player restores
    // system behaviour on its own.
    var playerVolume by remember {
        mutableFloatStateOf(
            store.setting("playerVolume", "1.0").toFloatOrNull()
                ?.coerceIn(0f, 1f) ?: 1f
        )
    }
    var brightnessSetting by remember {
        mutableStateOf(store.setting("playerBrightness", "system"))
    }
    fun applyBrightness() {
        val activity = context as? android.app.Activity ?: return
        val lp = activity.window.attributes
        lp.screenBrightness = brightnessSetting.toFloatOrNull()
            ?.coerceIn(0.05f, 1f) ?: -1f
        activity.window.attributes = lp
    }
    LaunchedEffect(Unit) { applyBrightness() }
    // Skip interval in seconds (0.0.9997, Robert's ladder on the
    // Video player settings page): one value drives both buttons.
    var skipSec by remember {
        mutableIntStateOf(store.setting("playerSkipSec", "10").toIntOrNull() ?: 10)
    }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var gestureLabel by remember { mutableStateOf("") }
    // Audio / subtitle track selection (Phase D player completion).
    var audioTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var selectedAudioId by remember { mutableStateOf<String?>(null) }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var subtitlesOff by remember { mutableStateOf(false) }
    var showAudioRow by remember { mutableStateOf(false) }
    var showTextRow by remember { mutableStateOf(false) }
    // Audit P-m20: when locked, the transport cluster hides
    // and only the unlock affordance shows.
    var controlsLocked by remember { mutableStateOf(false) }
    val castAvailable = rememberCastAvailable()
    // The Cast button, built once when routes are visible.
    // Null when it cannot be built under this theme
    // (see CastHelper) — the options capsule hides it then.
    val castButton = remember(castAvailable) {
        if (castAvailable) CastHelper.createMediaRouteButton(context) else null
    }
    val subtitlePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts
            .OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            } ?: return@rememberLauncherForActivityResult
            val head = bytes.take(256).toByteArray().decodeToString()
                .lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            val isVtt = head.trim().startsWith("WEBVTT")
            val dir = java.io.File(context.filesDir, "subs").apply { mkdirs() }
            val file = java.io.File(
                dir, "sidecar-${System.currentTimeMillis()}" +
                    if (isVtt) ".vtt" else ".srt"
            )
            file.writeBytes(bytes)
            sidecarSubs = listOf(
                MediaItem.SubtitleConfiguration.Builder(
                    android.net.Uri.fromFile(file)
                )
                    .setMimeType(
                        if (isVtt) MimeTypes.TEXT_VTT
                        else MimeTypes.APPLICATION_SUBRIP
                    )
                    .setLanguage("und")
                    .build()
            )
            sidecarAuto = false
        }.onFailure {
            Toast.makeText(
                context, "Could not load that subtitle file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // Video quality (0.0.960): the renditions of adaptive streams,
    // highest first. selectedVideoId == null is Auto (the player's
    // own adaptive choice); a manual pick is a track override, the
    // same machinery as audio/subtitle selection.
    var videoTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    var showQualityRow by remember { mutableStateOf(false) }
    // Parsed HLS qualities (0.0.967, Robert: no selector on
    // eligible videos): when the player exposes fewer than two
    // renditions for an HLS master, the master's own variant
    // list drives the selector instead, and a pick is applied
    // as an exact bitrate pin on the track selector.
    var parsedQualities by remember { mutableStateOf<List<ParsedQuality>>(emptyList()) }
    var selectedParsedBw by remember { mutableStateOf<Long?>(null) }
    fun refreshTracks(p: Player) {
        val groups = p.currentTracks.groups
        val audios = mutableListOf<TrackChoice>()
        val texts = mutableListOf<TrackChoice>()
        val videos = mutableListOf<Pair<TrackChoice, Int>>()
        var selA: String? = null
        var selT: String? = null
        groups.forEachIndexed { gi, g ->
            if (g.type == C.TRACK_TYPE_VIDEO) {
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val label = when {
                        f.height > 0 -> "${f.height}p"
                        f.width > 0 -> "${f.width}x${f.height}"
                        else -> "Video ${videos.size + 1}"
                    }
                    videos.add(TrackChoice(gi, ti, label, "$gi:$ti") to f.height)
                }
                return@forEachIndexed
            }
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) {
                return@forEachIndexed
            }
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val fallback = if (g.type == C.TRACK_TYPE_AUDIO) {
                    "Audio ${audios.size + 1}"
                } else {
                    "Subtitles ${texts.size + 1}"
                }
                val label = f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.takeIf { it.isNotBlank() && it != "und" }
                        ?.let { runCatching { java.util.Locale.forLanguageTag(it).displayLanguage }.getOrNull() }
                    ?: fallback
                val choice = TrackChoice(gi, ti, label, "$gi:$ti")
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    audios.add(choice)
                    if (g.isTrackSelected(ti)) selA = choice.id
                } else {
                    texts.add(choice)
                    if (g.isTrackSelected(ti)) selT = choice.id
                }
            }
        }
        audioTracks = audios
        textTracks = texts
        videoTracks = videos.sortedByDescending { it.second }.map { it.first }
        selectedAudioId = selA
        selectedTextId = selT
        subtitlesOff = texts.isNotEmpty() && selT == null
    }
    fun selectAudio(choice: TrackChoice) {
        val p = activePlayer ?: return
        val group = p.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            .build()
        selectedAudioId = choice.id
    }
    fun selectText(choice: TrackChoice?) {
        val p = activePlayer ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            subtitlesOff = true
            selectedTextId = null
        } else {
            val group = p.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            subtitlesOff = false
            selectedTextId = choice.id
        }
        p.trackSelectionParameters = builder.build()
    }
    fun selectVideo(choice: TrackChoice?) {
        val p = activePlayer ?: return
        val builder = p.trackSelectionParameters.buildUpon()
            .setMinVideoBitrate(0)
            .setMaxVideoBitrate(Int.MAX_VALUE)
        if (choice == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            selectedVideoId = null
        } else {
            val group = p.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            selectedVideoId = choice.id
        }
        selectedParsedBw = null
        p.trackSelectionParameters = builder.build()
    }
    fun selectParsedQuality(bandwidth: Long?) {
        val p = activePlayer ?: return
        val builder = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (bandwidth == null) {
            builder.setMinVideoBitrate(0)
                .setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            builder.setMinVideoBitrate(bandwidth.toInt())
                .setMaxVideoBitrate(bandwidth.toInt())
        }
        selectedVideoId = null
        selectedParsedBw = bandwidth
        p.trackSelectionParameters = builder.build()
    }
    // A new video starts on Auto: last item's manual quality
    // override (and its row) never follows the queue. (Declared
    // here, after the quality state it touches exists.)
    LaunchedEffect(currentIndex) {
        selectedVideoId = null
        selectedParsedBw = null
        showQualityRow = false
        activePlayer?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMinVideoBitrate(0)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .build()
        }
    }
    // Parse the current item's manifest for its variant
    // list (the parsed-qualities fallback above). The
    // address is probed by its content rather than its
    // name, and the fetch carries the player's own
    // browser identity and the page's Referer. Media
    // playlists and non-manifest items leave the list
    // empty. No cookies: Gecko cookies are not reachable
    // through WebKit CookieManager (see PlayerNet).
    LaunchedEffect(currentIndex) {
        parsedQualities = emptyList()
        val u = urls.getOrNull(currentIndex)
            ?: return@LaunchedEffect
        if (!u.startsWith("http", ignoreCase = true)) {
            return@LaunchedEffect
        }
        val text = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO
        ) {
            runCatching {
                val conn = java.net.URL(u).openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty(
                    "User-Agent", PlayerNet.USER_AGENT
                )
                if (u.startsWith("https://")) {
                    PlayerNet.referer?.takeIf { it.isNotBlank() }?.let {
                        conn.setRequestProperty("Referer", it)
                    }
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    var line = reader.readLine()
                    if (line == null) {
                        null
                    } else {
                        sb.append(line)
                        val first = line.trim()
                            .trimStart('\uFEFF')
                        if (first.startsWith("#EXTM3U")) {
                            sb.append('\n')
                                .append(reader.readText())
                            sb.toString()
                        } else {
                            var budget = 200
                            while (budget > 0 &&
                                sb.length < 300_000
                            ) {
                                line = reader.readLine() ?: break
                                sb.append('\n').append(line)
                                budget--
                                if (sb.contains("<MPD")) break
                            }
                            if (sb.contains("<MPD")) {
                                sb.toString()
                            } else {
                                null
                            }
                        }
                    }
                }
            }.getOrNull()
        }
        if (text != null && text.contains("#EXT-X-STREAM-INF")) {
            parsedQualities = parseHlsMasterVariants(text)
        } else if (text != null && text.contains("<MPD")) {
            parsedQualities = parseDashVariants(text)
        }
    }
    // Hold-to-scan (iOS-style): holding a side of the video scans
    // through it in 3-second steps until release.
    val scanScope = androidx.compose.runtime.rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun startScan(dir: Int) {
        scanJob?.cancel()
        scanJob = scanScope.launch {
            while (true) {
                val p = activePlayerState.value ?: break
                val dur = if (p.duration == C.TIME_UNSET) 0L else p.duration.coerceAtLeast(0)
                p.seekTo((p.currentPosition + dir * 3_000L).coerceIn(0L, dur))
                gestureLabel = if (dir > 0) "Fast-forward »" else "« Rewind"
                delay(220)
            }
        }
    }
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (gestureLabel == "Fast-forward »" || gestureLabel == "« Rewind") {
            gestureLabel = ""
        }
    }
    var seekingPreview by remember { mutableStateOf<Long?>(null) }
    // Slider scrub (beta.18): the fraction the
    // thumb is being dragged to. While it is set,
    // the row shows the scrub target instead of
    // the live position, and nothing seeks until
    // the drag ends — the old code seeked on every
    // drag tick, so the player rebuffered under
    // the thumb and the thumb fought the live
    // position.
    var sliderScrub by remember { mutableStateOf<Float?>(null) }
    // Controls overlay: shown on tap/interaction, auto-hides while
    // playing so it never sits over the video.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastControlInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var playerError by remember { mutableStateOf<String?>(null) }
    fun pokeControls() {
        controlsVisible = true
        lastControlInteraction = System.currentTimeMillis()
    }
    LaunchedEffect(controlsVisible, isPlaying, listenMode, lastControlInteraction) {
        if (controlsVisible && isPlaying && !listenMode) {
            delay(3_500)
            controlsVisible = false
        }
    }

    // Local ExoPlayer only: Pioneer has no PlaybackService /
    // MediaController in Phase 3 (see the file doc). The
    // local player is built at once and starts the queue;
    // there is no service handover and no 6s fallback.
    LaunchedEffect(Unit) {
        val idx = startIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        val resume = PlaybackPositions.get(context, urls.getOrNull(idx))
        val exo = PlayerNet.buildPlayer(context.applicationContext).apply {
            setPlaybackSpeed(speed)
            volume = playerVolume
            setMediaItems(buildItemsFor(idx), idx, resume)
            prepare()
            play()
        }
        exoPlayer = exo
        playerViewRef?.player = exo
    }

    DisposableEffect(activePlayer) {
        val c = activePlayer
        if (c == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentIndex = c.currentMediaItemIndex
                    liveEndRecoveries = 0
                    refreshTracks(c)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    refreshTracks(c)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playerError = "This media couldn't be played — the link may not be a playable stream."
                }

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        stallRecoveries = 0
                        refreshTracks(c)
                    }
                    if (state == Player.STATE_ENDED) {
                        urls.getOrNull(c.currentMediaItemIndex)?.let { PlaybackPositions.clear(context, it) }
                        // Beta.67 (1b): a live item must never
                        // accept ENDED. With the playlist no
                        // longer cached (see PlayerNet), a live
                        // window keeps advancing; if ENDED still
                        // fires spuriously, re-prepare at the
                        // live edge instead of letting the
                        // player close. On-demand items are
                        // untouched — ENDED still ends them.
                        if (c.isCurrentMediaItemLive && liveEndRecoveries < 3) {
                            liveEndRecoveries++
                            c.seekToDefaultPosition()
                            c.prepare()
                            c.play()
                        }
                    }
                }
            }
            c.addListener(listener)
            onDispose {
                // Save resume point before leaving. A private
                // session leaves no trace (0.0.960): nothing is
                // written, so nothing can surface in Continue.
                val u = urls.getOrNull(c.currentMediaItemIndex)
                if (shouldSavePlaybackPosition(privateSession) && u != null && c.playbackState != Player.STATE_ENDED) {
                    PlaybackPositions.put(
                        context, u, c.currentPosition.coerceAtLeast(0)
                    )
                }
                c.removeListener(listener)
            }
        }
    }

    // Stall watchdog (0.0.9995): a healthy buffer resolves in
    // seconds. If the player is still buffering after 8 s with
    // play intent, rebuild the load at the current position -
    // exactly what closing and reopening the player did by hand
    // (Robert's seek-stall report) - at most twice per item; a
    // stream that still will not load then fails honestly
    // instead of sitting in silence.
    LaunchedEffect(activePlayer, isBuffering) {
        if (!isBuffering) return@LaunchedEffect
        val c = activePlayer ?: return@LaunchedEffect
        delay(8_000)
        if (c.playbackState == Player.STATE_BUFFERING &&
            c.playWhenReady && stallRecoveries < 2
        ) {
            stallRecoveries++
            val idx = c.currentMediaItemIndex
            val pos = c.currentPosition.coerceAtLeast(0)
            c.stop()
            c.setMediaItems(buildItemsFor(idx), idx, pos)
            c.prepare()
            c.play()
        }
    }

    // Audit P-m1: save the position when playback pauses and
    // when the player goes away, not just on the 30s tick.
    fun savePositionNow(c: androidx.media3.common.Player) {
        val u = urls.getOrNull(c.currentMediaItemIndex) ?: return
        if (c.currentPosition <= 0) return
        if (shouldSavePlaybackPosition(privateSession) &&
            c.playbackState != androidx.media3.common.Player.STATE_ENDED
        ) {
            PlaybackPositions.put(context, u, c.currentPosition.coerceAtLeast(0))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.let { savePositionNow(it) }
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    LaunchedEffect(activePlayer) {
        val c = activePlayer ?: return@LaunchedEffect
        var ticks = 0
        while (true) {
            positionMs = c.currentPosition.coerceAtLeast(0)
            durationMs = if (c.duration == androidx.media3.common.C.TIME_UNSET) 0 else c.duration.coerceAtLeast(0)
            ticks++
            // Audit P-m1: 1s ticker, position saved every 30s
            // (was 500ms / 5s) — the bar doesn't need more.
            if (ticks % 30 == 0) {
                val u = urls.getOrNull(c.currentMediaItemIndex)
                if (shouldSavePlaybackPosition(privateSession) && u != null && c.playbackState != Player.STATE_ENDED) {
                    PlaybackPositions.put(context, u, c.currentPosition.coerceAtLeast(0))
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(isPlaying) {
        playerViewRef?.keepScreenOn = isPlaying
        if (!isPlaying) {
            activePlayer?.let { savePositionNow(it) }
        }
    }

    LaunchedEffect(sidecarSubs) {
        if (sidecarSubs.isEmpty()) return@LaunchedEffect
        val p = activePlayerState.value ?: return@LaunchedEffect
        val pos = p.currentPosition
        val idx = p.currentMediaItemIndex
        p.setMediaItems(buildItemsFor(idx), idx, pos)
        p.prepare()
        p.play()
    }
    // Beta.58 (7): the in-player queue — the old app's
    // bottom-sheet queue as a plain dialog. Tap jumps
    // to that item; the player rebuilds from
    // onMediaItemTransition like any other jump.
    if (showQueue) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Queue") },
            text = {
                LazyColumn {
                    items(urls.size) { i ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    showQueue = false
                                    activePlayer?.seekTo(i, 0L)
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                titles.getOrNull(i).orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (i == currentIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueue = false }) {
                    Text("Close")
                }
            }
        )
    }
    LaunchedEffect(playerViewRef) {
        if (playerViewRef != null) applyScaleMode(scaleMode)
    }

    LaunchedEffect(gestureLabel) {
        if (gestureLabel.isNotEmpty()) {
            delay(900)
            gestureLabel = ""
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            activePlayer?.pause()
            sleepMinutes = 0
        }
    }

    val player: Player? = activePlayer

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!listenMode) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        this.player = activePlayer
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        playerViewRef = this
                        applySubtitleStyle(ctx, this)
                    }
                },
                update = { pv ->
                    if (pv.player != activePlayer) pv.player = activePlayer
                    applySubtitleStyle(pv.context, pv)
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val eff = effScale()
                    scaleX = eff
                    scaleY = eff
                    translationX = panX
                    translationY = panY
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    titles.getOrNull(currentIndex) ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                Text("Listen mode — keeps playing with the screen off", color = Color.White.copy(alpha = 0.7f))
            }
        }

        // Gesture layer
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            // Hold-to-scan lives in the SAME tap
                            // detector (Phase G repair): as a separate
                            // pointerInput it swallowed plain taps, so
                            // the controls only ever answered a slide.
                            // Arm after 480ms — a tap or double-tap
                            // releases long before that — then scan
                            // until the press ends.
                            val job = scanScope.launch {
                                delay(480)
                                startScan(if (offset.x < size.width / 2) -1 else 1)
                            }
                            tryAwaitRelease()
                            job.cancel()
                            stopScan()
                        },
                        onDoubleTap = { offset ->
                            val p = activePlayerState.value ?: return@detectTapGestures
                            val w = size.width
                            if (effScale() > 1.01f && offset.x > w / 3 && offset.x < 2 * w / 3) {
                                // Zoomed: a centre double-tap resets the
                                // zoom (side double-taps still seek).
                                resetZoom()
                                gestureLabel = "Zoom reset"
                            } else if (offset.x < w / 2) {
                                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                                gestureLabel = "-10s"
                            } else {
                                p.seekTo(p.currentPosition + 10_000)
                                gestureLabel = "+10s"
                            }
                        },
                        onTap = {
                            if (controlsVisible) controlsVisible = false else pokeControls()
                        }
                    )
                }
                .pointerInput(Unit) {
                    var totalDx = 0f
                    var totalDy = 0f
                    var startX = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            totalDx = 0f
                            totalDy = 0f
                            startX = offset.x
                        },
                        onDrag = { change, drag ->
                            if (transformActive) {
                                // A pinch owns this gesture.
                                change.consume()
                                return@detectDragGestures
                            }
                            if (effScale() > 1.01f) {
                                // Zoomed in: a one-finger drag pans the
                                // picture (seek/brightness/volume return
                                // at 1x).
                                val eff = effScale()
                                val maxX = (size.width * (eff - 1f) / 2f).coerceAtLeast(0f)
                                val maxY = (size.height * (eff - 1f) / 2f).coerceAtLeast(0f)
                                panX = (panX + drag.x).coerceIn(-maxX, maxX)
                                panY = (panY + drag.y).coerceIn(-maxY, maxY)
                                change.consume()
                                return@detectDragGestures
                            }
                            val p = activePlayerState.value
                            totalDx += drag.x
                            totalDy += drag.y
                            if (abs(totalDy) > abs(totalDx) && abs(totalDy) > 24f) {
                                val fraction = -totalDy / size.height
                                if (startX < size.width / 2) {
                                    val activity = context as? android.app.Activity
                                    val lp = activity?.window?.attributes
                                    if (lp != null) {
                                        val cur = if (lp.screenBrightness >= 0) lp.screenBrightness else 0.5f
                                        lp.screenBrightness = (cur + fraction * 0.25f).coerceIn(0.05f, 1.0f)
                                        activity.window.attributes = lp
                                        gestureLabel = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                                    }
                                } else {
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val target = (cur + fraction * max).toInt().coerceIn(0, max)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                    gestureLabel = "Volume ${(target * 100) / max.coerceAtLeast(1)}%"
                                }
                                totalDy = 0f
                            } else if (abs(totalDx) > 60f && p != null) {
                                val seekBy = (totalDx / size.width * 120_000).toLong()
                                val dur = if (p.duration == androidx.media3.common.C.TIME_UNSET) 0 else p.duration.coerceAtLeast(0)
                                seekingPreview = (p.currentPosition + seekBy).coerceIn(0, dur)
                                gestureLabel = "Seek ${formatTime(seekingPreview ?: 0)}"
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            seekingPreview?.let { activePlayerState.value?.seekTo(it) }
                            seekingPreview = null
                            gestureLabel = ""
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Pinch detection: this Compose version's
                    // detectTransformGestures has no start/end callbacks,
                    // so "a pinch is happening" is tracked by counting
                    // pressed pointers directly.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            transformActive = event.changes.count { it.pressed } >= 2
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!listenMode) {
                            pinchScale = (pinchScale * zoom).coerceIn(1f, 3f)
                            val eff = effScale()
                            val maxX = (size.width * (eff - 1f) / 2f).coerceAtLeast(0f)
                            val maxY = (size.height * (eff - 1f) / 2f).coerceAtLeast(0f)
                            panX = (panX + pan.x).coerceIn(-maxX, maxX)
                            panY = (panY + pan.y).coerceIn(-maxY, maxY)
                            pokeControls()
                        }
                    }
                }
        )

        if (gestureLabel.isNotEmpty()) {
            val isLevel = gestureLabel.startsWith("Volume") || gestureLabel.startsWith("Brightness")
            if (isLevel) {
                // A slim level pill at the top-right, like the reference
                // player's volume control.
                val pct = gestureLabel.filter { it.isDigit() }.toIntOrNull() ?: 0
                Row(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(16.dp)
                        .playerCapsule()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (gestureLabel.startsWith("Volume")) Icons.Filled.VolumeUp else Icons.Filled.WbSunny,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.width(72.dp).height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    Text(
                        gestureLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(12.dp)
                    )
                }
            }
        }

        // Connecting / error states sit in the centre, above the gesture
        // layer and never underneath the transport cluster.
        if (playerError != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.ErrorOutline, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Couldn't play this media",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    playerError ?: "",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.Button(onClick = onBack) { Text("Back") }
            }
        } else if (player == null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text("Connecting…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // ---- iOS 26 player layout (per the supplied reference): one
        // frosted capsule of actions at the top-left, separate frosted
        // transport circles in the centre, and at the bottom a title
        // block + options capsule over a slim scrubber. Controls fade
        // together and auto-hide while playing (3.5s). Scrims/frost
        // stand in for blur, which cannot capture a SurfaceView.
        val ready = player != null && playerError == null
        // Beta.63 #45: a launch that asked for PiP (from the
        // detected-media sheet) enters it as soon as the
        // player is ready. One shot, never on error; if the
        // enter call fails silently the in-player button
        // remains.
        var pipRequested by remember { mutableStateOf(false) }
        LaunchedEffect(ready, startInPip) {
            if (startInPip && ready && !pipRequested) {
                pipRequested = true
                onEnterPip()
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible && playerError == null && !controlsLocked,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp)
                    .playerCapsule()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close player", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onEnterPip, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture in picture", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = {
                        activePlayer?.stop()
                        onStopPlayback()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop playback", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
                }
            }
        }

        // ---- Centre transport: separate frosted circles.
        if (ready) {
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (urls.size > 1) {
                        FrostCircle(size = 48.dp, onClick = {
                            pokeControls()
                            activePlayer?.seekToPreviousMediaItem()
                        }) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    if (isBuffering) {
                        // Gone while buffering (0.0.9997): a seek
                        // into a stall cannot help; the spacer
                        // keeps the play button centred.
                        Spacer(Modifier.size(56.dp))
                    } else {
                        FrostCircle(size = 56.dp, onClick = {
                            pokeControls()
                            activePlayer?.let {
                                it.seekTo(
                                    (it.currentPosition - skipSec * 1000L)
                                        .coerceAtLeast(0)
                                )
                            }
                        }) {
                            SkipBackIcon(skipSec)
                        }
                    }
                    FrostCircle(size = 76.dp, onClick = {
                        pokeControls()
                        if (activePlayer?.isPlaying == true) activePlayer?.pause()
                        else activePlayer?.play()
                    }) {
                        // Robert's buffering treatment (0.0.9995):
                        // while buffering, the button fills and
                        // empties in a steady cycle - the address
                        // bar's loading fill, looping, because a
                        // buffer has no honest progress to show.
                        Box(
                            modifier = Modifier.size(76.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isBuffering) {
                                val cycle = rememberInfiniteTransition(
                                    label = "bufferFill"
                                )
                                val fillFrac = cycle.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1600),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "bufferFillFrac"
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxHeight()
                                        .fillMaxWidth(fillFrac.value)
                                        .background(
                                            MaterialTheme.colorScheme.primary
                                                .copy(alpha = 0.35f)
                                        )
                                )
                            }
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                    if (isBuffering) {
                        Spacer(Modifier.size(56.dp))
                    } else {
                        FrostCircle(size = 56.dp, onClick = {
                            pokeControls()
                            activePlayer?.let {
                                it.seekTo(it.currentPosition + skipSec * 1000L)
                            }
                        }) {
                            SkipForwardIcon(skipSec)
                        }
                    }
                    if (urls.size > 1) {
                        FrostCircle(size = 48.dp, onClick = {
                            pokeControls()
                            activePlayer?.seekToNextMediaItem()
                        }) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- Bottom: title + options capsule, then the slim scrubber.
        if (ready) {
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 12.dp)
                ) {
                    if (showScaleRow && !listenMode) {
                        // Scaling modes as a capsule pill row in the same
                        // material, right-aligned over the options capsule.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Row(
                                modifier = Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ScaleMode.values().forEach { m ->
                                    TextButton(
                                        onClick = {
                                            pokeControls()
                                            applyScaleMode(m)
                                        }
                                    ) {
                                        Text(
                                            m.name,
                                            color = if (scaleMode == m) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showQualityRow &&
                        (videoTracks.size > 1 || parsedQualities.size > 1)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Row(
                                modifier = Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        pokeControls()
                                        if (videoTracks.size > 1) {
                                            selectVideo(null)
                                        } else {
                                            selectParsedQuality(null)
                                        }
                                    }
                                ) {
                                    Text(
                                        "Auto",
                                        color = if (selectedVideoId == null &&
                                            selectedParsedBw == null
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                if (videoTracks.size > 1) {
                                    videoTracks.forEach { choice ->
                                        TextButton(
                                            onClick = {
                                                pokeControls()
                                                selectVideo(choice)
                                            }
                                        ) {
                                            Text(
                                                choice.label,
                                                color = if (selectedVideoId == choice.id) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                } else {
                                    parsedQualities.forEach { q ->
                                        TextButton(
                                            onClick = {
                                                pokeControls()
                                                selectParsedQuality(q.bandwidth)
                                            }
                                        ) {
                                            Text(
                                                q.label,
                                                color = if (selectedParsedBw == q.bandwidth) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showAudioRow && audioTracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Row(
                                modifier = Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                audioTracks.forEach { choice ->
                                    TextButton(
                                        onClick = {
                                            pokeControls()
                                            selectAudio(choice)
                                        }
                                    ) {
                                        Text(
                                            choice.label,
                                            color = if (selectedAudioId == choice.id) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showTextRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Row(
                                modifier = Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        pokeControls()
                                        subtitlePicker.launch(arrayOf("*/*"))
                                    }
                                ) {
                                    Text(
                                        "Load subtitles…",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        pokeControls()
                                        selectText(null)
                                    }
                                ) {
                                    Text(
                                        "Off",
                                        color = if (subtitlesOff) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                textTracks.forEach { choice ->
                                    TextButton(
                                        onClick = {
                                            pokeControls()
                                            selectText(choice)
                                        }
                                    ) {
                                        Text(
                                            choice.label,
                                            color = if (!subtitlesOff && selectedTextId == choice.id) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            val host = remember(currentIndex) {
                                runCatching {
                                    android.net.Uri.parse(urls.getOrNull(currentIndex) ?: "").host ?: ""
                                }.getOrDefault("")
                            }
                            if (host.isNotBlank()) {
                                Text(
                                    host,
                                    color = Color.White.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                titles.getOrNull(currentIndex) ?: "",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.playerCapsule()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    pokeControls()
                                    listenMode = !listenMode
                                    if (listenMode) {
                                        resetZoom()
                                        showScaleRow = false
                                    }
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Headphones,
                                    contentDescription = if (listenMode) "Show video" else "Listen mode",
                                    tint = if (listenMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (audioTracks.size > 1) {
                                IconButton(
                                    onClick = {
                                        pokeControls()
                                        showAudioRow = !showAudioRow
                                        if (showAudioRow) {
                                            showTextRow = false
                                            showScaleRow = false
                                            showQualityRow = false
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Audiotrack,
                                        contentDescription = "Audio track",
                                        tint = if (showAudioRow) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if ((videoTracks.size > 1 || parsedQualities.size > 1) &&
                                !listenMode
                            ) {
                                IconButton(
                                    onClick = {
                                        pokeControls()
                                        showQualityRow = !showQualityRow
                                        if (showQualityRow) {
                                            showAudioRow = false
                                            showTextRow = false
                                            showScaleRow = false
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.HighQuality,
                                        contentDescription = "Video quality",
                                        tint = if (showQualityRow || selectedVideoId != null ||
                                            selectedParsedBw != null
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (urls.size > 1) {
                                IconButton(
                                    onClick = {
                                        pokeControls()
                                        showQueue = true
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = "Queue",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (textTracks.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        pokeControls()
                                        showTextRow = !showTextRow
                                        if (showTextRow) {
                                            showAudioRow = false
                                            showScaleRow = false
                                            showQualityRow = false
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Subtitles,
                                        contentDescription = "Subtitles",
                                        tint = if (showTextRow || !subtitlesOff) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    controlsLocked = true
                                    playerViewRef?.useController = false
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Lock controls",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // Cast button, rendered exactly as in
                            // Mariner. Phase 3: present, but no
                            // session is ever launched (Phase 4) —
                            // the button only opens the framework's
                            // own route chooser, and nothing in
                            // this file hands a URL to a receiver.
                            if (castButton != null) {
                                AndroidView(
                                    factory = { castButton },
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                            if (!listenMode) {
                                IconButton(
                                    onClick = {
                                        pokeControls()
                                        showScaleRow = !showScaleRow
                                        if (showScaleRow) {
                                            showAudioRow = false
                                            showTextRow = false
                                            showQualityRow = false
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.AspectRatio,
                                        contentDescription = "Video scaling",
                                        tint = if (showScaleRow || scaleMode != ScaleMode.Fit) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    pokeControls()
                                    val steps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                                    val next = steps[(steps.indexOf(speed) + 1) % steps.size]
                                    speed = next
                                    store.putSetting("playerSpeed", next.toString())
                                    activePlayer?.setPlaybackSpeed(next)
                                }
                            ) {
                                Text(
                                    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            TextButton(
                                onClick = {
                                    pokeControls()
                                    val steps = listOf(0, 10, 20, 30, 60)
                                    sleepMinutes = steps[(steps.indexOf(sleepMinutes) + 1) % steps.size]
                                }
                            ) {
                                Text(
                                    if (sleepMinutes == 0) "Sleep" else "${sleepMinutes}m",
                                    color = if (sleepMinutes == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Portrait: the scrubber and both times ride in
                    // one floating pill, inset from the edges (the
                    // iOS portrait player arrangement).
                    val isPortrait = androidx.compose.ui.platform.LocalConfiguration.current
                        .orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                    // On the portrait pill the scrubber wears the
                    // capsule's ink; in landscape it floats on the
                    // scrim and stays white.
                    val scrubColor = if (isPortrait) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.White
                    }
                    val scrubDim = if (isPortrait) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    } else {
                        Color.White.copy(alpha = 0.3f)
                    }
                    Row(
                        modifier = if (isPortrait) {
                            Modifier.fillMaxWidth().playerCapsule()
                                .padding(horizontal = 14.dp, vertical = 2.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val shownMs = sliderScrub?.let { frac ->
                            if (durationMs > 0) (frac * durationMs).toLong() else positionMs
                        } ?: positionMs
                        Text(formatTime(shownMs), color = scrubColor, style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = sliderScrub ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = { frac ->
                                pokeControls()
                                sliderScrub = frac
                            },
                            onValueChangeFinished = {
                                sliderScrub?.let { frac ->
                                    if (durationMs > 0) activePlayer?.seekTo((frac * durationMs).toLong())
                                }
                                sliderScrub = null
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = scrubColor,
                                activeTrackColor = scrubColor,
                                inactiveTrackColor = scrubDim
                            ),
                            thumb = {
                                Box(
                                    modifier = Modifier.size(11.dp)
                                        .clip(CircleShape)
                                        .background(scrubColor)
                                )
                            },
                            track = {
                                val frac = if (durationMs > 0) {
                                    (shownMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                } else 0f
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(scrubDim)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(frac).fillMaxHeight()
                                            .clip(RoundedCornerShape(50))
                                            .background(scrubColor)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        Text(
                            "−" + formatTime((durationMs - shownMs).coerceAtLeast(0)),
                            color = scrubColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Brightness + volume sliders (beta.18):
                    // same pill treatment as the scrubber.
                    Row(
                        modifier = if (isPortrait) {
                            Modifier.fillMaxWidth().playerCapsule()
                                .padding(horizontal = 14.dp, vertical = 2.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Brightness",
                            color = scrubColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = brightnessSetting.toFloatOrNull()
                                ?.coerceIn(0.05f, 1f) ?: 0.5f,
                            onValueChange = { v ->
                                pokeControls()
                                brightnessSetting = v.toString()
                                store.putSetting(
                                    "playerBrightness", v.toString()
                                )
                                applyBrightness()
                            },
                            valueRange = 0.05f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = scrubColor,
                                activeTrackColor = scrubColor,
                                inactiveTrackColor = scrubDim
                            ),
                            modifier = Modifier.weight(1f)
                                .padding(horizontal = 10.dp)
                        )
                        TextButton(
                            onClick = {
                                pokeControls()
                                brightnessSetting = "system"
                                store.putSetting(
                                    "playerBrightness", "system"
                                )
                                applyBrightness()
                            }
                        ) {
                            Text(
                                if (brightnessSetting == "system") {
                                    "System ✓"
                                } else {
                                    "System"
                                },
                                color = scrubColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        modifier = if (isPortrait) {
                            Modifier.fillMaxWidth().playerCapsule()
                                .padding(horizontal = 14.dp, vertical = 2.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Volume",
                            color = scrubColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = playerVolume,
                            onValueChange = { v ->
                                pokeControls()
                                playerVolume = v
                                store.putSetting(
                                    "playerVolume", v.toString()
                                )
                                activePlayer?.volume = v
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = scrubColor,
                                activeTrackColor = scrubColor,
                                inactiveTrackColor = scrubDim
                            ),
                            modifier = Modifier.weight(1f)
                                .padding(horizontal = 10.dp)
                        )
                        Text(
                            "${(playerVolume * 100).toInt()}%",
                            color = scrubColor,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }
        }
        // Audit P-m20: the lock cover swallows every gesture
        // except the unlock button itself.
        if (controlsLocked) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            controlsLocked = false
                            playerViewRef?.useController = true
                        }
                    ) {
                        Icon(
                            Icons.Filled.LockOpen,
                            contentDescription = "Unlock controls",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Text(
                        "Tap to unlock",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * The player's one capsule material over video. Since 0.0.952 it
 * IS the app's solid-frost recipe (the Phase C kit's frostBar):
 * same shapes in the same places, wearing the app's palette —
 * surface gradient, ink hairline, soft shadow. Every player
 * capsule, pill and circle shares this chain.
 */
@Composable
private fun Modifier.playerCapsule(shape: Shape = RoundedCornerShape(50)): Modifier =
    this.frostBar(shape, elevation = 4.dp)

/**
 * Player scaling modes (Phase C item 6): Fit, Stretch and Fill map
 * straight onto the Media3 resize modes; Crop is Fill (cover) with an
 * extra fixed 1.25x zoom on top, for a stronger centre crop.
 */
private enum class ScaleMode { Fit, Stretch, Fill, Crop }

/** One selectable audio/subtitle track in the player's track rows. */
/** A quality parsed from an HLS master playlist (0.0.967). */
private data class ParsedQuality(
    val label: String,
    val bandwidth: Long
)

private data class TrackChoice(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val id: String
)

/** A frosted circular transport button (scrim + hairline, AVKit-style). */
@Composable
private fun FrostCircle(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(size)
            .playerCapsule(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Skip-button faces (0.0.9997): the baked glyph where
 * Material ships one (5/10/30), the plain curved arrow with
 * the seconds beneath it everywhere else on Robert's ladder. */
@Composable
private fun SkipBackIcon(sec: Int) {
    val tint = MaterialTheme.colorScheme.onSurface
    when (sec) {
        5 -> Icon(
            Icons.Filled.Replay5, "Back 5 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        10 -> Icon(
            Icons.Filled.Replay10, "Back 10 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        30 -> Icon(
            Icons.Filled.Replay30, "Back 30 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Undo, "Back $sec seconds",
                tint = tint, modifier = Modifier.size(20.dp)
            )
            Text(
                sec.toString(), color = tint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SkipForwardIcon(sec: Int) {
    val tint = MaterialTheme.colorScheme.onSurface
    when (sec) {
        5 -> Icon(
            Icons.Filled.Forward5, "Forward 5 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        10 -> Icon(
            Icons.Filled.Forward10, "Forward 10 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        30 -> Icon(
            Icons.Filled.Forward30, "Forward 30 seconds",
            tint = tint, modifier = Modifier.size(28.dp)
        )
        else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Redo, "Forward $sec seconds",
                tint = tint, modifier = Modifier.size(20.dp)
            )
            Text(
                sec.toString(), color = tint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** A private session leaves no trace: no resume point is
 * written, so nothing of it can surface in Continue.
 * Top-level so unit tests can pin it down. */
internal fun shouldSavePlaybackPosition(privateSession: Boolean): Boolean = !privateSession

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Robert's subtitle styling (0.0.991), from the Video
 * player settings page. Applies only when he has set at
 * least one subtitle option - until then the player's
 * default (the system caption style) stands untouched.
 * Pioneer ships no bundled fonts, so the opensans/inter/
 * custom faces fall back to the platform default; serif,
 * mono and the system sans still apply.
 */
internal fun applySubtitleStyle(context: Context, view: PlayerView) {
    val store = PioneerStore.get(context)
    val touched = listOf(
        "subFont", "subSize", "subColor", "subBg", "subEdge"
    ).any { store.setting(it, "") != "" }
    if (!touched) return
    val sv = view.subtitleView ?: return
    val fg = when (store.setting("subColor", "white")) {
        "yellow" -> 0xFFFFFF00.toInt()
        "cyan" -> 0xFF00FFFF.toInt()
        "green" -> 0xFF00FF00.toInt()
        "magenta" -> 0xFFFF00FF.toInt()
        "red" -> 0xFFFF0000.toInt()
        "blue" -> 0xFF0000FF.toInt()
        "black" -> 0xFF000000.toInt()
        else -> 0xFFFFFFFF.toInt()
    }
    val bg = when (store.setting("subBg", "none")) {
        "black50" -> 0x80000000.toInt()
        "black75" -> 0xBF000000.toInt()
        "black" -> 0xFF000000.toInt()
        "white75" -> 0xBFFFFFFF.toInt()
        else -> 0
    }
    val edge = when (store.setting("subEdge", "outline")) {
        "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        "none" -> CaptionStyleCompat.EDGE_TYPE_NONE
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    val face = when (store.setting("subFont", "default")) {
        "roboto" -> android.graphics.Typeface.create(
            "sans", android.graphics.Typeface.NORMAL
        )
        "serif" -> android.graphics.Typeface.SERIF
        "mono" -> android.graphics.Typeface.MONOSPACE
        else -> null
    }
    sv.setStyle(
        CaptionStyleCompat(
            fg, bg, 0, edge, 0xFF000000.toInt(), face
        )
    )
    val pct = store.setting("subSize", "100")
        .toIntOrNull() ?: 100
    sv.setFractionalTextSize(0.0533f * pct / 100f)
}
