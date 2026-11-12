package net.nspioneer.browser.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

/**
 * Cast helpers (Mariner `media/CastHelper.kt`, carried over
 * for the player and the media sheet).
 *
 * Phase 3 boundary (design §10): discovery and the button
 * only. No session is ever launched from here — there is no
 * `loadUrl` and no `SessionManagerListener` in this file.
 * Tapping the rendered Cast button opens the framework's
 * own route chooser; handing a URL to a receiver is Phase 4.
 *
 * Construction guard: Mariner's beta.52/beta.55 crash was
 * `MediaRouteButton` resolving AppCompat colour attributes
 * to #0 under a platform Material theme
 * (ColorUtils "background can not be translucent: #0").
 * Pioneer's theme is platform Material too, so
 * [createMediaRouteButton] returns null instead of crashing
 * when construction throws; callers hide the button then.
 * A dedicated Cast button theme (Mariner's
 * `Theme_Notscape_CastButton`) is still owed — see the
 * Worker C report.
 */
object CastHelper {
    fun mimeFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    fun isRemote(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * Null when the button cannot be constructed under this
     * theme (see the class doc) — callers treat null as
     * "no Cast button" rather than crashing.
     */
    fun createMediaRouteButton(ctx: Context): androidx.mediarouter.app.MediaRouteButton? =
        runCatching { androidx.mediarouter.app.MediaRouteButton(ctx) }.getOrNull()
}

/** True while a remote-playback route (a Cast device) is visible. */
@Composable
fun rememberCastAvailable(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    var available by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val router = MediaRouter.getInstance(context)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refresh(router)
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refresh(router)
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refresh(router)
            }

            fun refresh(router: MediaRouter) {
                available = router.routes.any {
                    it.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                }
            }
        }
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        available = router.routes.any {
            it.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
        }
        onDispose { router.removeCallback(cb) }
    }
    return available
}
