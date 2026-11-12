package net.nspioneer.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import net.nspioneer.browser.media.DetectedMedia

/**
 * MediaSession delegate — now-playing signal only (design §8.3).
 *
 * `MediaSession.Delegate.onMetadata` fires only for pages that
 * use the Media Session API (YouTube, Spotify web, etc.); since
 * v83 it is NOT dispatched for plain media elements (API doc
 * §7). This is therefore a supplementary signal, never the
 * detector: it feeds `detectedMedia` with `source="session"`
 * and kind `"page"` where metadata exists. The URL is the
 * page's own URL — a session entry is a now-playing pointer,
 * never a playable stream, and must never be offered to the
 * player as one.
 *
 * Wiring (Worker B, from GeckoEngine): set [onNowPlaying] to
 * the sink that merges the entry into the tab's detected
 * media (via MediaLog, keyed by the tab that owns [session]),
 * then call [register] once per tab session, on the UI thread
 * (`setMediaSessionDelegate` became @UiThread in v154).
 * Re-registering a session replaces its delegate.
 */
object MediaSessionHandler {

    /**
     * Sink for now-playing entries. The handler keeps no tab
     * state of its own — the sink closes over the tab.
     */
    var onNowPlaying: ((session: GeckoSession, media: DetectedMedia) -> Unit)? = null

    fun register(session: GeckoSession) {
        session.setMediaSessionDelegate(delegate)
    }

    private val delegate = object : MediaSession.Delegate {
        override fun onMetadata(
            session: GeckoSession,
            mediaSession: MediaSession,
            meta: MediaSession.Metadata
        ) {
            val title = meta.title?.takeIf { it.isNotBlank() } ?: return
            val label = listOfNotNull(
                title,
                meta.artist?.takeIf { it.isNotBlank() }
            ).joinToString(" — ")
            // No stream URL exists for a session entry: the page
            // URL stands in as the identifier, kind "page" so
            // the sheet offers Open, never Play.
            onNowPlaying?.invoke(
                session,
                DetectedMedia(
                    url = "",
                    kind = "page",
                    label = label,
                    source = "session"
                )
            )
        }

        override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
            // Now-playing state only; nothing to record without metadata.
        }

        override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
            // Session ended; the sink keeps the last entry until the
            // tab's next DOM/net scan replaces it.
        }

        override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {}
        override fun onPause(session: GeckoSession, mediaSession: MediaSession) {}
        override fun onStop(session: GeckoSession, mediaSession: MediaSession) {}
    }
}
