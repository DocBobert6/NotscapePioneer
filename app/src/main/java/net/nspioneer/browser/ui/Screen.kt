package net.nspioneer.browser.ui

/** The app's top-level screens. Browser is the shell; the rest are
 * sub-screens MainActivity navigates to. Library screens open a URL
 * by returning to Browser and loading it in the active tab. */
enum class PioneerScreen {
    Browser, Bookmarks, History, Favorites, Settings, Licences, Diagnostics
}
