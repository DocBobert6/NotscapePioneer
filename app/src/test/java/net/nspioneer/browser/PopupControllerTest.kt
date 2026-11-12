package net.nspioneer.browser

import net.nspioneer.browser.engine.PopupAnswerEffect
import net.nspioneer.browser.engine.PopupChoice
import net.nspioneer.browser.engine.PopupVerdict
import net.nspioneer.browser.engine.addPopupCapture
import net.nspioneer.browser.engine.answerEffect
import net.nspioneer.browser.engine.decidePopup
import net.nspioneer.browser.engine.isCrossSiteLink
import net.nspioneer.browser.engine.isGestureBlocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.AllowOrDeny

/**
 * Scorecard row K (logic half): unit tests for the popup
 * controller's pure decision + capture rules (rebuild items
 * 1 + 5, alpha.13; four-choice prompt, alpha.14). These run
 * on the plain JVM, like FilterEngineTest — no Robolectric.
 * The Gecko wiring (session → tab mapping, onNewSession
 * bring-up, held-request completion) is device-verified via
 * the Copy-status discriminator lines (PHASE3-DESIGN.md §8.9).
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

    @Test fun decide_strictOn_default_asks() {
        // Alpha.14: an unlisted site's popup is HELD for the
        // four-choice prompt, never answered by default.
        assertEquals(
            PopupVerdict.ASK,
            decidePopup(strict = true, allowPopups = false, blockPopups = false)
        )
    }

    @Test fun decide_strictOn_blacklisted_deniesAndCaptures() {
        // Alpha.14: a blacklisted site's popup is denied
        // without asking and its URL is captured for the
        // notice — no more silent blacklist.
        assertEquals(
            PopupVerdict.DENY_CAPTURE,
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

    // ---- four-choice prompt (alpha.14): answer effects ----

    @Test fun answer_allowOnce() {
        assertEquals(
            PopupAnswerEffect(AllowOrDeny.ALLOW, true, false, null, null),
            answerEffect(PopupChoice.ALLOW_ONCE)
        )
    }

    @Test fun answer_denyOnce() {
        assertEquals(
            PopupAnswerEffect(AllowOrDeny.DENY, false, true, null, null),
            answerEffect(PopupChoice.DENY_ONCE)
        )
    }

    @Test fun answer_trust() {
        assertEquals(
            PopupAnswerEffect(AllowOrDeny.ALLOW, true, false, true, false),
            answerEffect(PopupChoice.TRUST)
        )
    }

    @Test fun answer_dontTrust() {
        assertEquals(
            PopupAnswerEffect(AllowOrDeny.DENY, false, true, false, true),
            answerEffect(PopupChoice.DONT_TRUST)
        )
    }

    // ---- gesture links (alpha.14): blacklist check ----

    @Test fun gesture_blacklisted_isBlocked() {
        assertTrue(isGestureBlocked(strict = true, allowPopups = false, blockPopups = true))
    }

    @Test fun gesture_unlisted_isNotBlocked() {
        assertFalse(isGestureBlocked(strict = true, allowPopups = false, blockPopups = false))
    }

    @Test fun gesture_whitelisted_isNotBlocked() {
        assertFalse(isGestureBlocked(strict = true, allowPopups = true, blockPopups = false))
    }

    @Test fun gesture_strictOff_isNotBlockedEvenWhenBlacklisted() {
        assertFalse(isGestureBlocked(strict = false, allowPopups = false, blockPopups = true))
    }

    // ---- cross-site tapped links (alpha.20) ----

    @Test fun crossSite_differentHosts_isCrossSite() {
        // The Full Show tap: watch-wrestling.eu -> techradan.com.
        assertTrue(isCrossSiteLink("watch-wrestling.eu", "techradan.com"))
    }

    @Test fun crossSite_sameHost_isNotCrossSite() {
        assertFalse(isCrossSiteLink("watch-wrestling.eu", "watch-wrestling.eu"))
    }

    @Test fun crossSite_sameHostDifferentCase_isNotCrossSite() {
        assertFalse(isCrossSiteLink("Watch-Wrestling.eu", "watch-wrestling.EU"))
    }

    @Test fun crossSite_subdomain_isCrossSite() {
        // Exact-host rule, same as the popunder guard:
        // a subdomain is a different host.
        assertTrue(isCrossSiteLink("example.com", "www.example.com"))
    }

    @Test fun crossSite_blankSource_isNotCrossSite() {
        // A link from the start page has no source host.
        assertFalse(isCrossSiteLink("", "techradan.com"))
    }

    @Test fun crossSite_blankTarget_isNotCrossSite() {
        assertFalse(isCrossSiteLink("watch-wrestling.eu", ""))
    }
}
