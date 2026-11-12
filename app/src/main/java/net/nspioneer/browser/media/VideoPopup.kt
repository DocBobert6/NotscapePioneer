package net.nspioneer.browser.media

import net.nspioneer.browser.data.PioneerStore

/**
 * The pop-up player, native side (design §8.3).
 *
 * The watcher JS lives in the probe content script (probe.js,
 * document_idle in Pioneer — the same adaptation as cosmetics —
 * where Mariner ran it at document start). It finds the largest
 * playing <video>, parks one 40px ▶ button at its top-right
 * corner, and reports taps as native messages
 * (`video-popup-tap` / `video-popup-longpress`, design §7)
 * instead of Mariner's `notscapeVideoPopup.onVideoTap` /
 * `onVideoLongPress` JS bridge, which GeckoView has no
 * equivalent for.
 *
 * Anti-spoof (non-negotiable, verbatim from Mariner): a tap is
 * honoured only when the URL matches a media entry the app's
 * own detectors saw — the page can never make the app open an
 * arbitrary URL.
 *
 * Wiring note for the coordinator: the `video-popup-tap` /
 * `video-popup-longpress` native messages arrive at
 * Extensions.kt's probe message delegate, which is owned by no
 * Phase 3 worker. That delegate must route them to
 * `EngineTab.onVideoPopupTap` / `onVideoPopupLongPress`
 * (design §5) and answer `video-popup-query` with the
 * per-site opt-out (`{type:"video-popup-off", off: bool}`).
 * The exact routing is listed in the Worker C report.
 */
object VideoPopup {

    /** Per-site opt-out setting key for [isOff]. */
    fun offKey(host: String): String = "videoPopupOff:$host"

    fun isOff(store: PioneerStore, host: String): Boolean =
        host.isNotBlank() &&
            store.setting(offKey(host), "false") == "true"

    /**
     * The anti-spoof gate: the tapped URL must be a video
     * the detectors saw — never a page/embed kind, never
     * audio, never blob:, never an unknown URL.
     */
    fun allowCheck(
        url: String,
        known: List<DetectedMedia>
    ): DetectedMedia? {
        if (url.startsWith("blob:")) return null
        if (!url.startsWith("http://") &&
            !url.startsWith("https://")
        ) return null
        return known.firstOrNull {
            it.url == url && it.kind == "video"
        }
    }

    /**
     * Handles one watcher report. Tap → [onPlay] with the
     * allow-checked entry (the shell launches
     * `PlayerActivity.launch`); long-press → [onOpenSheet]
     * (the shell runs `runMediaScan()` and opens the media
     * sheet). An entry that fails [allowCheck] is dropped
     * silently — the page never learns why.
     */
    fun handle(
        url: String,
        longPress: Boolean,
        known: List<DetectedMedia>,
        onPlay: (DetectedMedia) -> Unit,
        onOpenSheet: () -> Unit
    ) {
        val entry = allowCheck(url, known) ?: return
        if (longPress) {
            onOpenSheet()
            return
        }
        onPlay(entry)
    }
}
