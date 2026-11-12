package net.nspioneer.browser

import net.nspioneer.browser.engine.PopupVerdict
import net.nspioneer.browser.engine.addPopupCapture
import net.nspioneer.browser.engine.decidePopup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scorecard row K (logic half): unit tests for the popup
 * controller's pure decision + capture rules (rebuild items
 * 1 + 5, alpha.13). These run on the plain JVM, like
 * FilterEngineTest — no Robolectric. The Gecko wiring
 * (session → tab mapping, onNewSession bring-up) is
 * device-verified via the Copy-status discriminator lines
 * (PHASE3-DESIGN.md §8.9).
 */
class PopupControllerTest {

    @Test fun decide_strictOff_allows() {
        assertEquals(
            PopupVerdict.ALLOW,
            decidePopup(strict = false, allowPopups = false, blockPopups = false)
        )
    }

    @Test fun decide_strictOn_whitelisted_allows() {
        assertEquals(
            PopupVerdict.ALLOW,
            decidePopup(strict = true, allowPopups = true, blockPopups = false)
        )
    }

    @Test fun decide_strictOn_default_deniesAndCaptures() {
        assertEquals(
            PopupVerdict.DENY_CAPTURE,
            decidePopup(strict = true, allowPopups = false, blockPopups = false)
        )
    }

    @Test fun decide_strictOn_blacklisted_deniesSilently() {
        assertEquals(
            PopupVerdict.DENY_SILENT,
            decidePopup(strict = true, allowPopups = false, blockPopups = true)
        )
    }

    @Test fun decide_allowBeatsBlock() {
        // A site cannot be both whitelisted and blacklisted
        // through the UI, but the decision must prefer the
        // explicit allow if it ever happens.
        assertEquals(
            PopupVerdict.ALLOW,
            decidePopup(strict = true, allowPopups = true, blockPopups = true)
        )
    }

    @Test fun capture_appendsInOrder() {
        assertEquals(
            listOf("https://a.example/", "https://b.example/"),
            addPopupCapture(listOf("https://a.example/"), "https://b.example/")
        )
    }

    @Test fun capture_dedupes() {
        val one = listOf("https://a.example/")
        assertEquals(one, addPopupCapture(one, "https://a.example/"))
    }

    @Test fun capture_ignoresBlankAndAboutBlank() {
        val one = listOf("https://a.example/")
        assertEquals(one, addPopupCapture(one, ""))
        assertEquals(one, addPopupCapture(one, "about:blank"))
    }

    @Test fun capture_capsAtFive() {
        val five = (1..5).map { "https://$it.example/" }
        assertEquals(five, addPopupCapture(five, "https://6.example/"))
    }
}
