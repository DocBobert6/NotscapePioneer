package net.nspioneer.browser.engine

import android.app.Activity
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Fullscreen container behaviour (PHASE3-DESIGN.md §8.6),
 * called from GeckoEngine.onFullScreen.
 *
 * Unlike WebView's onShowCustomView there is no custom-view
 * object to add/remove: the page's fullscreen element stays
 * inside the GeckoView, so the app only changes the chrome
 * around it — hide the system bars on enter, restore them
 * on exit. The browser chrome itself is hidden by the shell
 * (it reads EngineTab.isFullscreen); the GeckoView already
 * fills its container, so no view re-parenting is needed.
 * The container is kept MATCH_PARENT by the shell layout;
 * [ensureMatchParent] is the explicit guard for a container
 * whose params were ever changed elsewhere.
 */
object Fullscreen {

    /** Hide the system bars for fullscreen video. */
    fun enter(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Restore the system bars when fullscreen ends. */
    fun exit(activity: Activity) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /** Guard: the fullscreen container must be MATCH_PARENT
     *  in both dimensions while a page is fullscreen. */
    fun ensureMatchParent(view: android.view.View) {
        val params = view.layoutParams ?: return
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.height != ViewGroup.LayoutParams.MATCH_PARENT
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = params
        }
    }
}
