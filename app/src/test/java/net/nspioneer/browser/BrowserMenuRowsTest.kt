package net.nspioneer.browser

import net.nspioneer.browser.ui.BrowserMenuRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha.25 regression tests for the menu grouping: the menu
 * renders one labelled card per run of consecutive rows of
 * one section, and the LazyColumn keys are derived from the
 * run's first row id. Keying by the section label alone
 * collided whenever a section appeared in two runs and the
 * menu threw IllegalArgumentException on open (three crashes
 * on 2026-11-12, all "card:Page actions" already used).
 * These run on the plain JVM, like PopupControllerTest — the
 * grouping is pure, no Compose needed.
 */
class BrowserMenuRowsTest {

    /** The LazyColumn keys a group would get, exactly as
     * BrowserMenuRowsContent derives them. */
    private fun keysFor(ids: List<String>): List<String> =
        BrowserMenuRows.groupedSections(ids).flatMap { (_, group) ->
            listOf("label:${group.first()}", "card:${group.first()}")
        }

    @Test fun defaultOrder_pageSectionAppearsInTwoRuns() {
        // In the default order installUBlock sits between the
        // LIBRARY and SETTINGS blocks yet sectionOf maps it to
        // PAGE — so PAGE appears in two runs. That is the exact
        // shape that crashed Robert's menu while uBlock was not
        // yet reported installed.
        val groups = BrowserMenuRows.groupedSections(BrowserMenuRows.DEFAULT_ORDER)
        val pageRuns = groups.count { it.first == BrowserMenuRows.MenuSection.PAGE }
        assertEquals(2, pageRuns)
        // The lone installUBlock run is a PAGE group of one.
        assertTrue(
            groups.any {
                it.first == BrowserMenuRows.MenuSection.PAGE &&
                    it.second == listOf("installUBlock")
            }
        )
    }

    @Test fun defaultOrder_keysAreUnique() {
        val keys = keysFor(BrowserMenuRows.DEFAULT_ORDER)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun interleavedCustomOrder_keysAreUnique() {
        // A LIBRARY row moved into the PAGE block, and a PAGE
        // row moved into the LIBRARY block: every section that
        // can split, splits. The Customize editor's Move up/down
        // buttons are unconstrained, so any such order is
        // reachable and must not crash the menu.
        val order = listOf(
            "newTab", "newPrivateTab",
            "reload", "bookmarks", "sharePage",
            "history", "copyPageUrl", "favoritesManager",
            "downloads", "savePdf", "offlineLibrary",
            "installUBlock", "copyStatus", "diagnostics",
            "settings", "licences"
        )
        // Sanity: the test order really does interleave.
        val groups = BrowserMenuRows.groupedSections(order)
        assertTrue(groups.count { it.first == BrowserMenuRows.MenuSection.PAGE } >= 2)
        assertTrue(groups.count { it.first == BrowserMenuRows.MenuSection.LIBRARY } >= 2)
        val keys = keysFor(order)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun moved_canSplitASection() {
        // Moving one LIBRARY row up into the PAGE block splits
        // LIBRARY into two runs; moved() itself is the editor's
        // primitive, so pin the shape it produces.
        val order = BrowserMenuRows.moved(
            BrowserMenuRows.DEFAULT_ORDER, "bookmarks", -3
        )
        val groups = BrowserMenuRows.groupedSections(order)
        assertTrue(groups.count { it.first == BrowserMenuRows.MenuSection.LIBRARY } >= 2)
        assertEquals(keysFor(order).size, keysFor(order).toSet().size)
    }

    @Test fun grouping_groupsConsecutiveRowsOnly() {
        val groups = BrowserMenuRows.groupedSections(
            listOf("newTab", "newPrivateTab", "reload", "sharePage", "bookmarks")
        )
        assertEquals(
            listOf(
                BrowserMenuRows.MenuSection.TABS to listOf("newTab", "newPrivateTab"),
                BrowserMenuRows.MenuSection.PAGE to listOf("reload", "sharePage"),
                BrowserMenuRows.MenuSection.LIBRARY to listOf("bookmarks")
            ),
            groups
        )
    }
}
