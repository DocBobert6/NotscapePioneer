package net.nspioneer.browser

import net.nspioneer.browser.data.Bookmark
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.ui.sortFavourites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Alpha.29 — Favorites Sort + Select: the pure sort orders
 * ([sortFavourites]) and the bulk-remove store helpers
 * ([PioneerStore.removeFavourites] /
 * [PioneerStore.restoreFavourites]).
 *
 * Plain JVM unit tests cannot build a PioneerStore: its
 * constructor reads `context.filesDir` and builds a
 * `Handler(Looper.getMainLooper())`, both of which throw on the
 * android.jar stubs. So this class runs under Robolectric
 * (pinned runtime SDK 35 — 4.14.1's highest — the app targets
 * 36, but nothing under test touches an API past 35).
 *
 * The store is a process singleton; each test starts from an
 * empty favourites list so state can never leak across tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavouritesTest {

    private lateinit var store: PioneerStore

    private fun fav(id: Long, title: String, createdAt: Long): Bookmark =
        Bookmark(id, title, "https://example.com/$id", createdAt)

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.favourites.clear()
    }

    @After
    fun tearDown() {
        store.favourites.clear()
    }

    @Test
    fun sortNewestFirst() {
        val list = listOf(fav(1, "a", 1000), fav(2, "b", 3000), fav(3, "c", 2000))
        assertEquals(
            listOf(2L, 3L, 1L),
            sortFavourites(list, "newest").map { it.id }
        )
    }

    @Test
    fun sortOldestFirst() {
        val list = listOf(fav(1, "a", 1000), fav(2, "b", 3000), fav(3, "c", 2000))
        assertEquals(
            listOf(1L, 3L, 2L),
            sortFavourites(list, "oldest").map { it.id }
        )
    }

    @Test
    fun sortTitleCaseInsensitive() {
        val list = listOf(
            fav(1, "banana", 1000),
            fav(2, "Apple", 2000),
            fav(3, "cherry", 3000)
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            sortFavourites(list, "title").map { it.id }
        )
    }

    @Test
    fun sortUnknownKeyDefaultsToNewest() {
        val list = listOf(fav(1, "a", 1000), fav(2, "b", 3000))
        assertEquals(
            listOf(2L, 1L),
            sortFavourites(list, "bogus").map { it.id }
        )
    }

    @Test
    fun removeFavouritesSubset() {
        store.favourites.addAll(
            listOf(fav(1, "a", 1000), fav(2, "b", 2000), fav(3, "c", 3000))
        )
        val removed = store.removeFavourites(setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), removed.map { it.id })
        assertEquals(listOf(2L), store.favourites.map { it.id })
    }

    @Test
    fun removeFavouritesEmptyAndUnknown() {
        store.favourites.addAll(listOf(fav(1, "a", 1000), fav(2, "b", 2000)))
        assertTrue(store.removeFavourites(emptySet()).isEmpty())
        assertTrue(store.removeFavourites(setOf(99L)).isEmpty())
        assertEquals(listOf(1L, 2L), store.favourites.map { it.id })
    }

    @Test
    fun restoreFavouritesKeepsIdsAndSortsNewestFirst() {
        store.favourites.addAll(
            listOf(fav(1, "a", 1000), fav(2, "b", 2000), fav(3, "c", 3000))
        )
        val removed = store.removeFavourites(setOf(2L, 3L))
        assertEquals(listOf(2L), store.favourites.map { it.id })
        store.restoreFavourites(removed)
        assertEquals(listOf(3L, 2L, 1L), store.favourites.map { it.id })
    }

    @Test
    fun restoreFavouritesDoesNotDuplicate() {
        store.favourites.addAll(listOf(fav(1, "a", 1000), fav(2, "b", 2000)))
        val removed = store.removeFavourites(setOf(2L))
        store.restoreFavourites(removed)
        store.restoreFavourites(removed)
        assertEquals(listOf(2L, 1L), store.favourites.map { it.id })
    }
}
