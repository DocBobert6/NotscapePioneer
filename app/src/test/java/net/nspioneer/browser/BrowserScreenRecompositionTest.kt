package net.nspioneer.browser.ui

import net.nspioneer.browser.data.Bookmark
import net.nspioneer.browser.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-A1: the pure decisions behind the browser screen's
 * recomposition scoping — scroll-hide thresholds, the
 * loading-progress range, and the address-suggestion lookup.
 * The leaves that read `tab.scrollY`, `tab.progress`, and
 * `store.revision` are Compose code and cannot run on the JVM;
 * these functions are the behaviour they encode.
 */
class BrowserScreenRecompositionTest {

    // ---- scrollHideDecision ----

    @Test
    fun scrollHideDownPastThresholdHides() {
        assertEquals(true, scrollHideDecision(100, 0))
        assertEquals(true, scrollHideDecision(81, 0))
        assertEquals(true, scrollHideDecision(87, 80))
    }

    @Test
    fun scrollHideAtOrBelowEightyKeeps() {
        assertNull(scrollHideDecision(80, 0))
        assertNull(scrollHideDecision(50, 0))
    }

    @Test
    fun scrollHideSmallDownMoveKeeps() {
        assertNull(scrollHideDecision(90, 88))
        assertNull(scrollHideDecision(85, 84))
    }

    @Test
    fun scrollHideUpShows() {
        assertEquals(false, scrollHideDecision(50, 60))
        assertEquals(false, scrollHideDecision(53, 60))
        assertEquals(false, scrollHideDecision(0, 10))
    }

    @Test
    fun scrollHideSmallUpMoveKeeps() {
        assertNull(scrollHideDecision(54, 60))
        assertNull(scrollHideDecision(50, 50))
    }

    // ---- isLoadingProgress ----

    @Test
    fun isLoadingProgressRange() {
        assertFalse(isLoadingProgress(0))
        assertTrue(isLoadingProgress(1))
        assertTrue(isLoadingProgress(50))
        assertTrue(isLoadingProgress(99))
        assertFalse(isLoadingProgress(100))
    }

    // ---- matchSuggestions ----

    private fun bookmark(title: String, url: String) =
        Bookmark(id = 1, title = title, url = url, createdAt = 0L)

    private fun history(title: String, url: String) =
        HistoryEntry(id = 1, title = title, url = url, visitedAt = 0L)

    @Test
    fun matchSuggestionsBlankQueryIsEmpty() {
        assertTrue(matchSuggestions(listOf(bookmark("A", "https://a.example")), emptyList(), "").isEmpty())
        assertTrue(matchSuggestions(listOf(bookmark("A", "https://a.example")), emptyList(), "   ").isEmpty())
    }

    @Test
    fun matchSuggestionsBookmarksFirst() {
        val out = matchSuggestions(
            bookmarks = listOf(bookmark("Kotlin docs", "https://kotlinlang.org")),
            history = listOf(history("Kotlin blog", "https://blog.example/kotlin")),
            query = "kotlin"
        )
        assertEquals(2, out.size)
        assertEquals(Triple("Kotlin docs", "https://kotlinlang.org", true), out[0])
        assertEquals(Triple("Kotlin blog", "https://blog.example/kotlin", false), out[1])
    }

    @Test
    fun matchSuggestionsDedupesByUrlBookmarkWins() {
        val out = matchSuggestions(
            bookmarks = listOf(bookmark("Same", "https://same.example")),
            history = listOf(history("Same page", "https://same.example")),
            query = "same"
        )
        assertEquals(1, out.size)
        assertEquals(Triple("Same", "https://same.example", true), out[0])
    }

    @Test
    fun matchSuggestionsBlankTitleFallsBackToUrl() {
        val out = matchSuggestions(
            bookmarks = listOf(bookmark("", "https://plain.example/x")),
            history = emptyList(),
            query = "plain"
        )
        assertEquals(listOf(Triple("https://plain.example/x", "https://plain.example/x", true)), out)
    }

    @Test
    fun matchSuggestionsCapsAtSix() {
        val bookmarks = (1..8).map { bookmark("Match $it", "https://m$it.example") }
        val out = matchSuggestions(bookmarks, emptyList(), "match")
        assertEquals(6, out.size)
        assertEquals("https://m1.example", out.first().second)
        assertEquals("https://m6.example", out.last().second)
    }

    @Test
    fun matchSuggestionsMatchesUrlNotJustTitle() {
        val out = matchSuggestions(
            bookmarks = listOf(bookmark("Unrelated", "https://needle.example/page")),
            history = emptyList(),
            query = "needle"
        )
        assertEquals(1, out.size)
        assertEquals("https://needle.example/page", out[0].second)
    }
}
