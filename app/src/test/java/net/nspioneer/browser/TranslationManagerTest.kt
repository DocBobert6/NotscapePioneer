package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.TranslateChoice
import net.nspioneer.browser.engine.TranslatePageState
import net.nspioneer.browser.engine.TranslationLanguages
import net.nspioneer.browser.engine.TranslationManager
import net.nspioneer.browser.engine.TranslateTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController.Language
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Translate (alpha.45): the TranslationManager state machine.
 *
 * What runs here: every pure decision (language parsing,
 * size formatting, the offer gate), the pure GeckoResult
 * handlers (gate result, model states, offer details), the
 * delegate's detection bookkeeping (real TranslationState
 * objects, mocked GeckoSession), and the answer paths that
 * do not touch the static RuntimeTranslation API.
 *
 * What deliberately does NOT run here: the live model
 * download and the translate() call's completion — both go
 * through static RuntimeTranslation calls that dispatch to
 * the Gecko event bus, which returns a GeckoResult that
 * never completes without a runtime. The one exception is
 * the translate() *invocation* itself: with a mocked
 * SessionTranslation the call is verified (including
 * downloadModel=false), while its completion is left to
 * device testing. The same standing limit as the alpha.37/
 * 39/40 builders': per-test names do not appear in the CI
 * console log, so these tests are verified by the green
 * testDebugUnitTest run on the exact commit adding them.
 *
 * Same shape as FingerprintingProtectionTest: Robolectric,
 * runtime SDK 35, Mockito 5 (mocks the GeckoView classes
 * without calling their constructors).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationManagerTest {

    private lateinit var store: PioneerStore
    private lateinit var session: GeckoSession
    private lateinit var sessionTranslation: SessionTranslation
    private lateinit var session2: GeckoSession
    private lateinit var sessionTranslation2: SessionTranslation
    private val statusLines = mutableListOf<String>()
    private val neverWrites = mutableListOf<Pair<Boolean, String>>()
    private lateinit var manager: TranslationManager

    private lateinit var tab1: TranslateTab
    private lateinit var tab2: TranslateTab

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(TranslationManager.LANGUAGES_KEY, "")
        session = mock(GeckoSession::class.java)
        sessionTranslation = mock(SessionTranslation::class.java)
        `when`(session.sessionTranslation).thenReturn(sessionTranslation)
        session2 = mock(GeckoSession::class.java)
        sessionTranslation2 = mock(SessionTranslation::class.java)
        `when`(session2.sessionTranslation).thenReturn(sessionTranslation2)
        tab1 = TranslateTab(1, session, "https://elpais.com/articulo")
        tab2 = TranslateTab(2, session2, "https://example.com/")
        manager = TranslationManager(
            store = store,
            status = { statusLines += it },
            postToMain = { r -> r.run() },
            tabForSession = { s ->
                when (s) {
                    session -> tab1
                    session2 -> tab2
                    else -> null
                }
            },
            neverSiteWriter = { never, host -> neverWrites += never to host },
        )
    }

    @After
    fun tearDown() {
        store.putSetting(TranslationManager.LANGUAGES_KEY, "")
    }

    // ---------- pure helpers ----------

    @Test
    fun parse_defaultIsAllEight() {
        assertEquals(TranslationLanguages.CODES, TranslationLanguages.parseEnabledLanguages(null))
        assertEquals(TranslationLanguages.CODES, TranslationLanguages.parseEnabledLanguages(""))
        assertEquals(TranslationLanguages.CODES, TranslationLanguages.parseEnabledLanguages("   "))
    }

    @Test
    fun parse_subsetAndUnknownDropped() {
        assertEquals(
            setOf("es", "fr"),
            TranslationLanguages.parseEnabledLanguages("es,fr,xx"),
        )
        assertEquals(
            setOf("es", "pt"),
            TranslationLanguages.parseEnabledLanguages("es-ES, PT"),
        )
    }

    @Test
    fun parse_dashMeansNone() {
        // The "-" sentinel setLanguageEnabled writes when
        // all eight are off must parse back to empty — never
        // to all eight.
        assertEquals(emptySet<String>(), TranslationLanguages.parseEnabledLanguages("-"))
    }

    @Test
    fun parse_allUnknownFallsBackToAll() {
        assertEquals(
            TranslationLanguages.CODES,
            TranslationLanguages.parseEnabledLanguages("xx,yy"),
        )
    }

    @Test
    fun formatSize_wholeAndZero() {
        assertEquals("37 MB", TranslationLanguages.formatSize(37_000_000))
        assertEquals("0 MB", TranslationLanguages.formatSize(0))
        assertEquals("0 MB", TranslationLanguages.formatSize(-5))
    }

    @Test
    fun formatSize_fractionalAndKb() {
        assertEquals("1.5 MB", TranslationLanguages.formatSize(1_500_000))
        assertEquals("750 KB", TranslationLanguages.formatSize(750_000))
        assertEquals("74 MB", TranslationLanguages.formatSize(74_300_000))
    }

    @Test
    fun isOfferable_eightOnly() {
        assertTrue(TranslationLanguages.isOfferable("es"))
        assertTrue(TranslationLanguages.isOfferable("es-ES"))
        assertTrue(TranslationLanguages.isOfferable("pt-BR"))
        // alpha.55: Mandarin Chinese and Hindi join the set.
        assertTrue(TranslationLanguages.isOfferable("zh"))
        assertTrue(TranslationLanguages.isOfferable("zh-CN"))
        assertTrue(TranslationLanguages.isOfferable("hi"))
        assertTrue(TranslationLanguages.isOfferable("hi-IN"))
        assertFalse(TranslationLanguages.isOfferable("ja"))
        assertFalse(TranslationLanguages.isOfferable("en"))
        assertFalse(TranslationLanguages.isOfferable(""))
    }

    @Test
    fun normalizeCode_stripsRegion() {
        assertEquals("pt", TranslationLanguages.normalizeCode("pt-BR"))
        assertEquals("es", TranslationLanguages.normalizeCode("es_ES"))
        assertEquals("en", TranslationLanguages.normalizeCode("EN"))
    }

    // ---------- the offer gate ----------

    private fun gate(
        from: String = "es",
        enabled: Set<String> = TranslationLanguages.CODES,
        supported: Boolean? = true,
        never: List<String> = emptyList(),
        host: String = "elpais.com",
        translated: String? = null,
    ) = manager.shouldOffer(from, enabled, supported, never, host, translated)

    @Test
    fun gate_allConditions() {
        assertTrue(gate())
    }

    @Test
    fun gate_unsupportedOrUnknownDevice() {
        assertFalse(gate(supported = false))
        assertFalse(gate(supported = null))
    }

    @Test
    fun gate_disabledLanguage() {
        assertFalse(gate(enabled = TranslationLanguages.CODES - "es"))
    }

    @Test
    fun gate_neverSiteExactAndSubdomain() {
        assertFalse(gate(never = listOf("elpais.com")))
        assertFalse(gate(never = listOf("elpais.com"), host = "www.elpais.com"))
        assertTrue(gate(never = listOf("other.com")))
    }

    @Test
    fun gate_alreadyTranslated() {
        assertFalse(gate(translated = "es"))
    }

    @Test
    fun gate_ninthLanguage() {
        // A language outside the eight is never offered.
        assertFalse(gate(from = "ja"))
        assertFalse(gate(from = "en"))
    }

    @Test
    fun gate_newLanguagesOffer() {
        // alpha.55: Mandarin Chinese and Hindi gate exactly
        // like the original six.
        assertTrue(gate(from = "zh"))
        assertTrue(gate(from = "hi"))
    }

    // ---------- device gate ----------

    @Test
    fun supported_trueRecordsAndReports() {
        manager.onSupportedResult(true)
        assertEquals(true, manager.supported.value)
        assertTrue(statusLines.any { it == "translate: engine supported" })
    }

    @Test
    fun supported_falseRecords() {
        manager.onSupportedResult(false)
        assertEquals(false, manager.supported.value)
        assertTrue(statusLines.any { it == "translate: engine not supported on this device" })
    }

    // ---------- model states ----------

    private fun model(code: String, downloaded: Boolean, size: Long) =
        RuntimeTranslation.LanguageModel(
            Language(code, code),
            downloaded,
            size,
        )

    @Test
    fun modelStates_mapsEightRows() {
        manager.onModelStatesLoaded(
            listOf(
                model("es", true, 73_600_000),
                model("fr", false, 73_900_000),
                model("zh", false, 104_500_000),
                model("hi", true, 44_300_000),
            )
        )
        val states = manager.modelStates.value
        assertEquals(8, states.size)
        val es = states.first { it.code == "es" }
        assertTrue(es.downloaded)
        assertEquals(73_600_000, es.sizeBytes)
        val fr = states.first { it.code == "fr" }
        assertFalse(fr.downloaded)
        assertEquals(73_900_000, fr.sizeBytes)
        val de = states.first { it.code == "de" }
        assertFalse(de.downloaded)
        assertEquals(0, de.sizeBytes)
        // alpha.55: the two new rows map the same way.
        val zh = states.first { it.code == "zh" }
        assertFalse(zh.downloaded)
        assertEquals(104_500_000, zh.sizeBytes)
        val hi = states.first { it.code == "hi" }
        assertTrue(hi.downloaded)
        assertEquals(44_300_000, hi.sizeBytes)
    }

    @Test
    fun supportedSet_hasEightLanguages() {
        // alpha.55: the six shipped in alpha.45 plus
        // Mandarin Chinese and Hindi.
        assertEquals(8, TranslationLanguages.SUPPORTED.size)
        assertEquals(
            setOf("es", "fr", "de", "it", "pt", "ru", "zh", "hi"),
            TranslationLanguages.CODES,
        )
        val zh = TranslationLanguages.languageFor("zh")
        assertEquals("Mandarin Chinese", zh?.name)
        assertEquals(54.6, zh?.intoEnglishMB)
        assertEquals(104.5, zh?.pairTotalMB)
        val hi = TranslationLanguages.languageFor("hi")
        assertEquals("Hindi", hi?.name)
        assertEquals(22.7, hi?.intoEnglishMB)
        assertEquals(44.3, hi?.pairTotalMB)
    }

    @Test
    fun fallbackPackSize_newLanguages() {
        // alpha.55: static fallbacks are the registry pair
        // totals (runonweb MODELS.md, 2026-10-24 mirror).
        assertEquals(104_500_000L, manager.fallbackPackSize("zh"))
        assertEquals(44_300_000L, manager.fallbackPackSize("hi"))
    }

    @Test
    fun modelStates_nullListMeansNoneDownloaded() {
        manager.onModelStatesLoaded(null)
        assertTrue(manager.modelStates.value.all { !it.downloaded })
        assertTrue(manager.modelStates.value.all { it.sizeBytes == 0L })
    }

    // ---------- offer flow ----------

    private fun detect(tabSession: GeckoSession, lang: String) {
        manager.delegate.onTranslationStateChange(
            tabSession,
            SessionTranslation.TranslationState(
                null,
                null,
                SessionTranslation.DetectedLanguages(lang, true, lang),
                true,
                false,
            ),
        )
    }

    /** Detection + the pure offer-details handler, with the
     *  tab selected: the offer appears and the menu state
     *  follows it. */
    private fun buildOffer(from: String = "es", downloaded: Boolean = false) {
        manager.onSupportedResult(true)
        manager.onTabSelected(tab1)
        detect(session, from)
        manager.onOfferDetailsLoaded(tab1, from, 73_600_000, downloaded)
    }

    @Test
    fun offer_buildsAfterDetectionAndDetails() {
        buildOffer()
        val offer = manager.offer.value
        assertEquals(1, offer?.tabId)
        assertEquals("es", offer?.fromCode)
        assertEquals("Spanish", offer?.fromName)
        assertEquals("elpais.com", offer?.host)
        assertEquals(73_600_000L, offer?.pairSizeBytes)
        assertFalse(offer?.modelDownloaded ?: true)
        assertEquals(
            TranslatePageState.Offer("es", "Spanish"),
            manager.pageState.value,
        )
        assertTrue(statusLines.any { it == "translate: offer es → en on elpais.com" })
    }

    @Test
    fun offer_rejectedWhenLanguageDisabled() {
        manager.setLanguageEnabled("es", false)
        buildOffer()
        assertNull(manager.offer.value)
        assertEquals(TranslatePageState.Hidden, manager.pageState.value)
    }

    @Test
    fun offer_rejectedForNinthLanguage() {
        manager.onSupportedResult(true)
        manager.onTabSelected(tab1)
        detect(session, "ja")
        manager.onOfferDetailsLoaded(tab1, "ja", 40_000_000, false)
        assertNull(manager.offer.value)
    }

    @Test
    fun offer_buildsForNewLanguages() {
        // alpha.55: the offer fires for Mandarin Chinese
        // and Hindi exactly as for the original six.
        buildOffer(from = "zh")
        assertEquals("zh", manager.offer.value?.fromCode)
        assertEquals("Mandarin Chinese", manager.offer.value?.fromName)
        assertEquals(
            TranslatePageState.Offer("zh", "Mandarin Chinese"),
            manager.pageState.value,
        )
        manager.answerOffer(TranslateChoice.NOT_NOW, session)
        buildOffer(from = "hi")
        assertEquals("hi", manager.offer.value?.fromCode)
        assertEquals("Hindi", manager.offer.value?.fromName)
        assertEquals(
            TranslatePageState.Offer("hi", "Hindi"),
            manager.pageState.value,
        )
    }

    @Test
    fun offer_staleDetectionRejected() {
        // Detected es, then navigated to a French page before
        // the size lookup returned: the es offer must not
        // land on the French page.
        manager.onSupportedResult(true)
        manager.onTabSelected(tab1)
        detect(session, "es")
        detect(session, "fr")
        manager.onOfferDetailsLoaded(tab1, "es", 73_600_000, false)
        assertNull(manager.offer.value)
    }

    // ---------- answering the offer ----------

    @Test
    fun answer_notNowDismissesPopupOnly() {
        buildOffer()
        manager.answerOffer(TranslateChoice.NOT_NOW, session)
        assertNull(manager.offer.value)
        // The menu row still offers the translation.
        assertEquals(
            TranslatePageState.Offer("es", "Spanish"),
            manager.pageState.value,
        )
        // No never-translate write, no session call.
        assertTrue(neverWrites.isEmpty())
    }

    @Test
    fun answer_neverSitePersistsAndHides() {
        buildOffer()
        manager.answerOffer(TranslateChoice.NEVER_SITE, session)
        assertNull(manager.offer.value)
        assertEquals(listOf(true to "elpais.com"), neverWrites)
        verify(sessionTranslation).setNeverTranslateSiteSetting(true)
        assertEquals(TranslatePageState.Hidden, manager.pageState.value)
        assertTrue(manager.neverSites.value.contains("elpais.com"))
        // A later offer for the same host is now gated off.
        assertFalse(
            manager.shouldOffer(
                "es", TranslationLanguages.CODES, true,
                manager.neverSites.value, "elpais.com", null,
            )
        )
    }

    @Test
    fun answer_translateCallsTranslateWithoutDownload() {
        // Model already downloaded: translate() must run with
        // downloadModel=false — translate() itself never
        // downloads silently.
        buildOffer(downloaded = true)
        manager.answerOffer(TranslateChoice.TRANSLATE, session)
        assertNull(manager.offer.value)
        verify(sessionTranslation).translate(
            eq("es"),
            eq("en"),
            argThat(
                ArgumentMatcher<SessionTranslation.TranslationOptions> { opts ->
                    opts != null && !opts.downloadModel
                },
            ),
        )
    }

    // ---------- selection / lifecycle ----------

    @Test
    fun answer_translateNewLanguageWithoutDownload() {
        // alpha.55: with the model already downloaded, the
        // new languages translate with downloadModel=false
        // — translate() itself never downloads silently,
        // and no download is attempted before the user
        // confirms (there is no download call on the offer
        // path at all; the download only starts after the
        // user answers TRANSLATE on an undownloaded model).
        buildOffer(from = "zh", downloaded = true)
        manager.answerOffer(TranslateChoice.TRANSLATE, session)
        verify(sessionTranslation).translate(
            eq("zh"),
            eq("en"),
            argThat(
                ArgumentMatcher<SessionTranslation.TranslationOptions> { opts ->
                    opts != null && !opts.downloadModel
                },
            ),
        )
        assertFalse(
            statusLines.any { it.contains("downloading Mandarin Chinese model") }
        )
        buildOffer(from = "hi", downloaded = true)
        manager.answerOffer(TranslateChoice.TRANSLATE, session)
        verify(sessionTranslation).translate(
            eq("hi"),
            eq("en"),
            argThat(
                ArgumentMatcher<SessionTranslation.TranslationOptions> { opts ->
                    opts != null && !opts.downloadModel
                },
            ),
        )
        assertFalse(
            statusLines.any { it.contains("downloading Hindi model") }
        )
    }

    @Test
    fun tabClosed_clearsOfferAndState() {
        buildOffer()
        manager.answerOffer(TranslateChoice.NOT_NOW, session)
        assertEquals(
            TranslatePageState.Offer("es", "Spanish"),
            manager.pageState.value,
        )
        manager.onTabClosed(1)
        assertEquals(TranslatePageState.Hidden, manager.pageState.value)
        // The detection is gone too: a late details result
        // for the closed tab builds nothing.
        manager.onOfferDetailsLoaded(tab1, "es", 73_600_000, false)
        assertNull(manager.offer.value)
    }

    @Test
    fun tabSelected_restoresTranslatedState() {
        manager.onSupportedResult(true)
        manager.onTabSelected(tab1)
        detect(session, "es")
        manager.delegate.onTranslationStateChange(
            session,
            SessionTranslation.TranslationState(
                SessionTranslation.TranslationPair("es", "en"),
                null,
                SessionTranslation.DetectedLanguages("es", true, "es"),
                true,
                true,
            ),
        )
        assertEquals(
            TranslatePageState.Translated("es", "Spanish"),
            manager.pageState.value,
        )
        assertTrue(statusLines.any { it == "translate: es → en done" })
        // Switching away and back keeps the translated state.
        manager.onTabSelected(tab2)
        assertEquals(TranslatePageState.Hidden, manager.pageState.value)
        manager.onTabSelected(tab1)
        assertEquals(
            TranslatePageState.Translated("es", "Spanish"),
            manager.pageState.value,
        )
    }

    // ---------- the eight-language setting ----------

    @Test
    fun setLanguageEnabled_togglesAndPersists() {
        manager.setLanguageEnabled("es", false)
        assertFalse(manager.isLanguageEnabled("es"))
        assertTrue(manager.isLanguageEnabled("fr"))
        manager.setLanguageEnabled("es", true)
        assertTrue(manager.isLanguageEnabled("es"))
    }

    @Test
    fun setLanguageEnabled_allOffStoresSentinel() {
        TranslationLanguages.CODES.forEach { code ->
            manager.setLanguageEnabled(code, false)
        }
        assertEquals(
            emptySet<String>(),
            TranslationLanguages.parseEnabledLanguages(
                store.setting(TranslationManager.LANGUAGES_KEY, "")
            ),
        )
        assertFalse(manager.isLanguageEnabled("es"))
        // Re-enabling one language works from the sentinel.
        manager.setLanguageEnabled("fr", true)
        assertTrue(manager.isLanguageEnabled("fr"))
        assertFalse(manager.isLanguageEnabled("es"))
    }

    @Test
    fun setLanguageEnabled_unknownCodeIgnored() {
        manager.setLanguageEnabled("ja", false)
        assertTrue(manager.isLanguageEnabled("es"))
        assertEquals(
            TranslationLanguages.CODES,
            TranslationLanguages.parseEnabledLanguages(
                store.setting(TranslationManager.LANGUAGES_KEY, "")
            ),
        )
    }
}
