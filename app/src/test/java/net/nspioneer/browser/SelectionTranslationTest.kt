package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.TranslateTab
import net.nspioneer.browser.engine.TranslationManager
import net.nspioneer.browser.engine.TranslationManager.SelectionTranslationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.PageExtractionController
import org.mozilla.geckoview.TranslationsController.Language
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Selection translation (alpha.67, D-6a): the
 * TranslationManager's hidden-session route.
 *
 * What runs here: the entry guards (blank, too long,
 * unsupported device), the language decision (English,
 * unsupported, downloaded, not downloaded), the
 * translate() invocation (downloadModel=false, verified
 * on the mocked synthetic session), the extraction
 * handler, the timeout, and dismissal — all through
 * the injected fakes, so no static
 * RuntimeTranslation call is ever made.
 *
 * What deliberately does NOT run here: the live Gecko
 * loop (detection on the synthetic file: page, the
 * translate() completion, the page extraction) — those
 * go through the Gecko event bus and need a runtime.
 * The device test proves that loop end to end.
 *
 * Same shape as TranslationManagerTest: Robolectric,
 * runtime SDK 35, Mockito 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionTranslationTest {

    private lateinit var store: PioneerStore
    private lateinit var session: GeckoSession
    private lateinit var selSession: GeckoSession
    private lateinit var selTranslation: SessionTranslation
    private lateinit var selExtractor: PageExtractionController.SessionPageExtractor
    private val statusLines = mutableListOf<String>()
    private lateinit var manager: TranslationManager

    private val opened = mutableListOf<GeckoSession>()
    private val closed = mutableListOf<GeckoSession>()
    private val deletedFiles = mutableListOf<String>()
    private var writtenHtml: String? = null
    private val delayed = mutableListOf<Runnable>()
    private var modelsForTest: List<RuntimeTranslation.LanguageModel>? = null

    private lateinit var tab1: TranslateTab

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(TranslationManager.LANGUAGES_KEY, "")
        session = mock(GeckoSession::class.java)
        selSession = mock(GeckoSession::class.java)
        selTranslation = mock(SessionTranslation::class.java)
        `when`(selSession.sessionTranslation).thenReturn(selTranslation)
        selExtractor = mock(PageExtractionController.SessionPageExtractor::class.java)
        `when`(selSession.sessionPageExtractor).thenReturn(selExtractor)
        // Extraction never completes on its own in tests:
        // the callback is registered but never fires, so
        // the tests drive onSelectionExtracted directly
        // (the same pure-handler pattern the page tests
        // use for their GeckoResult handlers).
        `when`(selExtractor.getPageContent(any())).thenReturn(GeckoResult<String>())
        tab1 = TranslateTab(1, session, "https://elpais.com/articulo")
        manager = TranslationManager(
            store = store,
            status = { statusLines += it },
            postToMain = { r -> r.run() },
            tabForSession = { s -> if (s == session) tab1 else null },
            selectionSessionFactory = { selSession },
            openSelectionSession = { opened += it },
            closeSelectionSession = { closed += it },
            writeSelectionFile = { html ->
                writtenHtml = html
                "file:///cache/selection.html"
            },
            deleteSelectionFile = { deletedFiles += it },
            listSelectionModels = { onResult -> onResult(modelsForTest) },
            postDelayedToMain = { r, _ -> delayed += r },
        )
        manager.onSupportedResult(true)
        manager.onTabSelected(tab1)
    }

    @After
    fun tearDown() {
        store.putSetting(TranslationManager.LANGUAGES_KEY, "")
    }

    private fun model(code: String, downloaded: Boolean) =
        RuntimeTranslation.LanguageModel(Language(code, code), downloaded, 70_000_000)

    private fun detectSelection(lang: String) {
        manager.selectionDelegate.onTranslationStateChange(
            selSession,
            SessionTranslation.TranslationState(
                null,
                null,
                SessionTranslation.DetectedLanguages(lang, true, lang),
                true,
                false,
            ),
        )
    }

    private fun visibleChange() {
        manager.selectionDelegate.onTranslationStateChange(
            selSession,
            SessionTranslation.TranslationState(
                SessionTranslation.TranslationPair("es", "en"),
                null,
                SessionTranslation.DetectedLanguages("es", true, "es"),
                true,
                true,
            ),
        )
    }

    // ---------- entry guards ----------

    @Test
    fun blankSelectionDoesNothing() {
        manager.translateSelection("   \n ")
        assertEquals(
            SelectionTranslationState.Hidden,
            manager.selectionResult.value,
        )
        assertTrue(opened.isEmpty())
        assertNull(writtenHtml)
    }

    @Test
    fun tooLongSelectionShowsMessage() {
        manager.translateSelection("x".repeat(5001))
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("too long"))
        assertTrue(opened.isEmpty())
        assertNull(writtenHtml)
    }

    @Test
    fun unsupportedDeviceShowsMessage() {
        manager.onSupportedResult(false)
        manager.translateSelection("Bonjour le monde")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("isn't supported"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun pendingSelectionStartsWhenGateReturns() {
        // A fresh manager whose gate has not returned yet:
        // the selection waits, then starts when the gate
        // answers.
        val waiting = TranslationManager(
            store = store,
            status = { statusLines += it },
            postToMain = { r -> r.run() },
            tabForSession = { s -> if (s == session) tab1 else null },
            selectionSessionFactory = { selSession },
            openSelectionSession = { opened += it },
            closeSelectionSession = { closed += it },
            writeSelectionFile = { html ->
                writtenHtml = html
                "file:///cache/selection.html"
            },
            deleteSelectionFile = { deletedFiles += it },
            listSelectionModels = { onResult -> onResult(modelsForTest) },
            postDelayedToMain = { r, _ -> delayed += r },
        )
        waiting.translateSelection("Bonjour le monde")
        assertEquals(
            SelectionTranslationState.Translating,
            waiting.selectionResult.value,
        )
        assertTrue(opened.isEmpty())
        waiting.onSupportedResult(true)
        assertEquals(listOf(selSession), opened)
        verify(selSession).loadUri("file:///cache/selection.html")
    }

    // ---------- the flow ----------

    @Test
    fun flowStartsHiddenSessionOnFileUrl() {
        manager.translateSelection("Bonjour le monde")
        assertEquals(
            SelectionTranslationState.Translating,
            manager.selectionResult.value,
        )
        assertEquals(listOf(selSession), opened)
        verify(selSession).loadUri("file:///cache/selection.html")
        assertTrue(writtenHtml?.contains("Bonjour le monde") == true)
        // One bounded wait was scheduled (detection).
        assertEquals(1, delayed.size)
    }

    @Test
    fun unsupportedLanguageShowsMessageNoTranslate() {
        manager.translateSelection("Konnichiwa sekai")
        detectSelection("ja")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("isn't available"))
        verify(selTranslation, never()).translate(any(), any(), any())
        // The hidden session is closed on the failure.
        assertEquals(listOf(selSession), closed)
        assertEquals(listOf("file:///cache/selection.html"), deletedFiles)
    }

    @Test
    fun englishSelectionShowsAlreadyEnglish() {
        manager.translateSelection("Hello world")
        detectSelection("en")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("already in English"))
        verify(selTranslation, never()).translate(any(), any(), any())
    }

    @Test
    fun notDownloadedShowsMessageNoTranslateNoDownload() {
        modelsForTest = listOf(model("es", false))
        manager.translateSelection("Hola mundo")
        detectSelection("es")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("isn't downloaded"))
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("Settings"))
        verify(selTranslation, never()).translate(any(), any(), any())
        // No download is ever attempted on this path: the
        // only download call in the manager lives on the
        // page path (startTranslation), which the selection
        // flow never reaches.
        assertFalse(statusLines.any { it.contains("downloading") })
    }

    @Test
    fun downloadedTranslatesWithDownloadModelFalse() {
        modelsForTest = listOf(model("es", true))
        manager.translateSelection("Hola mundo")
        detectSelection("es")
        verify(selTranslation).translate(
            eq("es"),
            eq("en"),
            argThat(
                ArgumentMatcher<SessionTranslation.TranslationOptions> { opts ->
                    opts != null && !opts.downloadModel
                },
            ),
        )
        assertEquals(
            SelectionTranslationState.Translating,
            manager.selectionResult.value,
        )
        // A second bounded wait was scheduled (translation).
        assertEquals(2, delayed.size)
    }

    @Test
    fun visibleChangeExtractsAndPublishes() {
        modelsForTest = listOf(model("es", true))
        manager.translateSelection("Hola mundo")
        detectSelection("es")
        visibleChange()
        verify(selExtractor).getPageContent(any())
        manager.onSelectionExtracted(selSession, "Hello world")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Translated)
        result as SelectionTranslationState.Translated
        assertEquals("es", result.fromCode)
        assertEquals("Spanish", result.fromName)
        assertEquals("Hola mundo", result.original)
        assertEquals("Hello world", result.translated)
        assertTrue(statusLines.any { it == "translate selection: es → en done" })
        // The hidden session is closed once the result lands.
        assertEquals(listOf(selSession), closed)
        assertEquals(listOf("file:///cache/selection.html"), deletedFiles)
    }

    @Test
    fun emptyExtractionShowsMessage() {
        modelsForTest = listOf(model("es", true))
        manager.translateSelection("Hola mundo")
        detectSelection("es")
        visibleChange()
        manager.onSelectionExtracted(selSession, "   ")
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("came back empty"))
    }

    @Test
    fun errorStateShowsMessage() {
        manager.translateSelection("Hola mundo")
        manager.selectionDelegate.onTranslationStateChange(
            selSession,
            SessionTranslation.TranslationState(
                null,
                "engine failed",
                null,
                true,
                false,
            ),
        )
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("failed"))
    }

    @Test
    fun staleSessionCallbacksIgnored() {
        // A state change from any other session is not the
        // selection session's: it must not drive the flow.
        val other = mock(GeckoSession::class.java)
        manager.translateSelection("Hola mundo")
        manager.selectionDelegate.onTranslationStateChange(
            other,
            SessionTranslation.TranslationState(
                null,
                null,
                SessionTranslation.DetectedLanguages("ja", true, "ja"),
                true,
                false,
            ),
        )
        assertEquals(
            SelectionTranslationState.Translating,
            manager.selectionResult.value,
        )
        verify(selTranslation, never()).translate(any(), any(), any())
    }

    @Test
    fun timeoutShowsMessage() {
        manager.translateSelection("Hola mundo")
        assertEquals(1, delayed.size)
        delayed.first().run()
        val result = manager.selectionResult.value
        assertTrue(result is SelectionTranslationState.Unavailable)
        assertTrue((result as SelectionTranslationState.Unavailable).message.contains("timed out"))
    }

    @Test
    fun dismissClearsAndCancels() {
        manager.translateSelection("Hola mundo")
        manager.dismissSelectionResult()
        assertEquals(
            SelectionTranslationState.Hidden,
            manager.selectionResult.value,
        )
        assertEquals(listOf(selSession), closed)
        assertEquals(listOf("file:///cache/selection.html"), deletedFiles)
        // A late timeout from the cancelled run is a no-op.
        delayed.first().run()
        assertEquals(
            SelectionTranslationState.Hidden,
            manager.selectionResult.value,
        )
    }

    @Test
    fun tabSwitchDismissesResult() {
        modelsForTest = listOf(model("es", true))
        manager.translateSelection("Hola mundo")
        detectSelection("es")
        visibleChange()
        manager.onSelectionExtracted(selSession, "Hello world")
        assertTrue(manager.selectionResult.value is SelectionTranslationState.Translated)
        val tab2 = TranslateTab(2, mock(GeckoSession::class.java), "https://example.com/")
        manager.onTabSelected(tab2)
        assertEquals(
            SelectionTranslationState.Hidden,
            manager.selectionResult.value,
        )
    }

    // ---------- the synthetic page ----------

    @Test
    fun selectionPageHtmlEscapesMarkup() {
        val html = manager.selectionPageHtml("<script>alert(1)</script> & <b>bold</b>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt;"))
    }

    @Test
    fun selectionPageHtmlKeepsLineBreaks() {
        val html = manager.selectionPageHtml("first\nsecond")
        assertTrue(html.contains("first<br>second"))
    }
}
