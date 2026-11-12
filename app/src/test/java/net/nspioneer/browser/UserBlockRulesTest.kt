package net.nspioneer.browser

import net.nspioneer.browser.engine.UserBlockRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scorecard row A (Kotlin half), user-rules side: the user's own
 * net / cosmetic rules, stored tab-separated under `userBlockNet`
 * / `userBlockCosmetic`. Mirrors Mariner's `UserBlockRulesTest`
 * — same cases, same expectations. A network rule never blocks
 * a main-frame load (that is the engine's job, not the rule's);
 * a cosmetic rule applies to its exact host only.
 */
class UserBlockRulesTest {

    @Test
    fun netHostRuleMatchesHostAndSubdomainsOnly() {
        val rule = UserBlockRules.NetRule.Host("ads.example.com")
        assertTrue(UserBlockRules.netMatches(rule, "https", "ads.example.com", "/x"))
        assertTrue(UserBlockRules.netMatches(rule, "https", "sub.ads.example.com", "/x"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "notads.example.com", "/x"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "ads.example.org", "/x"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "", "/x"))
    }

    @Test
    fun netUrlRuleMatchesExactAddressWithoutQueryOrFragment() {
        val rule = UserBlockRules.NetRule.Url("https://exact.example/path")
        assertTrue(UserBlockRules.netMatches(rule, "https", "exact.example", "/path"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "exact.example", "/other"))
        assertFalse(UserBlockRules.netMatches(rule, "http", "exact.example", "/path"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "sub.exact.example", "/path"))
    }

    @Test
    fun urlKeyLowercasesAndDropsQuery() {
        assertEquals(
            "https://exact.example/path",
            UserBlockRules.urlKey("HTTPS", "Exact.Example", "/Path")
        )
    }

    @Test
    fun parseNetRoundTrips() {
        val raw = "net\tHOST\tads.example.com\nnet\tURL\thttps://exact.example/path\n"
        val rules = UserBlockRules.parseNet(raw)
        assertEquals(2, rules.size)
        assertEquals(UserBlockRules.NetRule.Host("ads.example.com"), rules[0])
        assertEquals(UserBlockRules.NetRule.Url("https://exact.example/path"), rules[1])
        assertEquals(raw.trim(), UserBlockRules.serializeNet(rules))
    }

    @Test
    fun parseNetIgnoresMalformedLines() {
        val raw = "net\tHOST\t\nnet\tBOGUS\tx.example\ncos\ta\tb\n\nnet\tHOST\tok.example"
        val rules = UserBlockRules.parseNet(raw)
        assertEquals(listOf(UserBlockRules.NetRule.Host("ok.example")), rules)
    }

    @Test
    fun cosmeticRulesApplyToExactHostOnly() {
        val rules = listOf(
            UserBlockRules.CosmeticRule("example.com", ".ad-banner"),
            UserBlockRules.CosmeticRule("other.example", ".sponsored")
        )
        assertEquals(
            listOf(".ad-banner"),
            UserBlockRules.selectorsForHost(rules, "example.com")
        )
        assertEquals(
            emptyList<String>(),
            UserBlockRules.selectorsForHost(rules, "sub.example.com")
        )
        assertEquals(
            listOf(".sponsored"),
            UserBlockRules.selectorsForHost(rules, "OTHER.EXAMPLE")
        )
    }

    @Test
    fun parseCosmeticRejectsUnsafeSelectors() {
        val raw = "cos\texample.com\t.ad-banner\n" +
            "cos\texample.com\t.bad{color:red}\n" +
            "cos\texample.com\t.bad;attack\n" +
            "cos\texample.com\t.bad<script>\n" +
            "cos\t\t.no-host\n"
        val rules = UserBlockRules.parseCosmetic(raw)
        assertEquals(
            listOf(UserBlockRules.CosmeticRule("example.com", ".ad-banner")),
            rules
        )
    }

    @Test
    fun parseCosmeticRoundTrips() {
        val rules = listOf(UserBlockRules.CosmeticRule("example.com", ".ad-banner"))
        val raw = UserBlockRules.serializeCosmetic(rules)
        assertEquals("cos\texample.com\t.ad-banner", raw)
        assertEquals(rules, UserBlockRules.parseCosmetic(raw))
    }

    @Test
    fun isSafeSelectorGate() {
        assertTrue(UserBlockRules.isSafeSelector(".ad-banner"))
        assertTrue(UserBlockRules.isSafeSelector("div > .sponsored-box"))
        assertFalse(UserBlockRules.isSafeSelector(".x{color:red}"))
        assertFalse(UserBlockRules.isSafeSelector(".x;color:red"))
        assertFalse(UserBlockRules.isSafeSelector(".x<script>"))
        assertFalse(UserBlockRules.isSafeSelector(""))
        assertFalse(UserBlockRules.isSafeSelector("   "))
    }

    @Test
    fun netRuleJsonRoundTrips() {
        val host = UserBlockRules.NetRule.Host("ads.example.com")
        val url = UserBlockRules.NetRule.Url("https://exact.example/path")
        assertEquals(host, UserBlockRules.fromJson(UserBlockRules.toJson(host)))
        assertEquals(url, UserBlockRules.fromJson(UserBlockRules.toJson(url)))
        assertEquals(null, UserBlockRules.fromJson(UserBlockRules.NetRuleJson("bogus", "x")))
    }
}
