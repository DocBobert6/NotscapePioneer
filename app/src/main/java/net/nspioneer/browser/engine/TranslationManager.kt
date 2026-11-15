package net.nspioneer.browser.engine

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import net.nspioneer.browser.data.PioneerStore
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.TranslationsController.SessionTranslation

/**
 * Translate (alpha.45): on-device page translation via
 * GeckoView's Bergamot/WASM stack (TranslationsController,
 * verified against the pinned 160 AAR — see
 * TRANSLATE-SPIKE-REPORT.md).
 *
 * Scope, as decided 2026-11-14: into-English only, six
 * languages (Spanish, French, German, Italian, Portuguese,
 * Russian). Models download once on demand from Mozilla's
 * servers, then live in the profile — after that, no page
 * text ever leaves the device. Nothing is bundled in the
 * APK (unsupported, and it would triple it).
 *
 * Download unit, stated plainly: the toolkit downloads
 * per-LANGUAGE packs, not per-pair models — there is no
 * pair-level download API (ModelManagementOptions has
 * LANGUAGE / CACHE / ALL only). So downloading Spanish
 * fetches the Spanish pack (both directions, ~74 MB), and
 * all six together are ~440 MB, not the ~228 MB into-English
 * pair total the spike quoted. The first-use prompt shows
 * the real per-language size from listModelDownloadStates,
 * so the user is never misled at the point of download.
 * The translate call itself never downloads silently:
 * translate() runs with downloadModel=false, and the model
 * is fetched first via manageLanguageModel(DOWNLOAD).
 *
 * No per-site FPP-style exemption problem here: the
 * toolkit itself honours the never-translate site list
 * before offering, and this manager gates on it too.
 * Per-language Always/Offer/Never toolkit settings
 * (setLanguageSettings) are deliberately NOT used:
 * Pioneer's own six-language store setting is the single
 * source of truth for what gets offered.
 */

/** One supported foreign language (English is the hub). */
data class TranslateLanguage(
    val code: String,
    val name: String,
    /** Into-English pair size, MB (spike table, ±30%) —
     *  fallback only, when the live size lookup fails. */
    val intoEnglishMB: Double,
    /** Both-directions pair total, MB (spike table) —
     *  fallback only, for the full language pack. */
    val pairTotalMB: Double,
)

object TranslationLanguages {
    val SUPPORTED = listOf(
        TranslateLanguage("es", "Spanish", 37.0, 73.6),
        TranslateLanguage("fr", "French", 37.2, 73.9),
        TranslateLanguage("de", "German", 37.3, 74.0),
        TranslateLanguage("it", "Italian", 37.1, 73.6),
        TranslateLanguage("pt", "Portuguese", 37.0, 73.3),
        TranslateLanguage("ru", "Russian", 36.9, 72.1),
    )
    val CODES: Set<String> = SUPPORTED.map { it.code }.toSet()

    fun languageFor(code: String): TranslateLanguage? =
        SUPPORTED.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** True when [code] is one of the six (case-insensitive,
     *  region suffix stripped: "es-ES" counts as "es"). */
    fun isOfferable(code: String): Boolean =
        languageFor(normalizeCode(code)) != null

    /** "es-ES" -> "es", "pt-BR" -> "pt", "en" -> "en". */
    fun normalizeCode(code: String): String =
        code.substringBefore("-").substringBefore("_").lowercase()

    /** The stored six-language setting
     *  ("translateLanguages", comma-separated codes):
     *  null/blank means all six; a lone "-" means none
     *  (setLanguageEnabled's sentinel — an empty set must
     *  stay empty, not parse back to all six). Unknown
     *  codes are dropped, duplicates removed — the setting
     *  can never enable a language outside the six. */
    fun parseEnabledLanguages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return CODES
        if (raw.trim() == "-") return emptySet()
        val wanted = raw.split(",").map { normalizeCode(it.trim()) }
            .filter { it.isNotEmpty() }.toSet()
        return wanted.intersect(CODES).ifEmpty { CODES }
    }

    /** "37 MB", "1.5 MB", "750 KB" — decimal MB, as the
     *  spike's table quotes them. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1_000_000 -> "${bytes / 1_000} KB"
        bytes % 1_000_000 == 0L -> "${bytes / 1_000_000} MB"
        bytes < 10_000_000 -> {
            val mb = bytes / 1_000_000.0
            val rounded = (mb * 10).toInt() / 10.0
            if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()} MB"
            else "$rounded MB"
        }
        else -> "${bytes / 1_000_000} MB"
    }
}

/** A pending translate offer for one tab (the shell renders
 *  the offer dialog from this state). [pairSizeBytes] is the
 *  full download for the language pack when the model is not
 *  yet downloaded (live size from listModelDownloadStates,
 *  static pair-total fallback); unused when downloaded. */
data class TranslateOffer(
    val tabId: Int,
    val fromCode: String,
    val fromName: String,
    val host: String,
    val pairSizeBytes: Long,
    val modelDownloaded: Boolean,
)

/** What the selected tab can do about translation right now
 *  (the menu row reads this). */
sealed interface TranslatePageState {
    data object Hidden : TranslatePageState
    data class Offer(val fromCode: String, val fromName: String) : TranslatePageState
    data object Translating : TranslatePageState
    data class Translated(val fromCode: String, val fromName: String) : TranslatePageState
}

/** One language's model state, for the Settings rows. */
data class TranslateModelState(
    val code: String,
    val name: String,
    val downloaded: Boolean,
    /** Full-download size in bytes (0 when unknown). */
    val sizeBytes: Long,
)

enum class TranslateChoice { TRANSLATE, NOT_NOW, NEVER_SITE }

/** The tab the manager acts on: id + session + current URL
 *  (the engine maps its own GeckoTab to this; tests build it
 *  directly). */
data class TranslateTab(val id: Int, val session: GeckoSession, val url: String)

/**
 * Owns the translate feature's state machine. The engine
 * (GeckoEngine) supplies the store, the status sink, a
 * main-thread poster, and the session→tab lookup; this class
 * makes every GeckoView call and every gating decision.
 *
 * The GeckoResult callbacks are kept thin: each one posts its
 * result to a pure-ish `on…` handler ([onSupportedResult],
 * [onOfferDetailsLoaded], [onModelStatesLoaded],
 * [onNeverSitesLoaded]) that the tests call directly.
 */
class TranslationManager(
    private val store: PioneerStore,
    private val status: (String) -> Unit,
    private val postToMain: (Runnable) -> Unit,
    private val tabForSession: (GeckoSession) -> TranslateTab?,
    /** Defaults to the runtime never-translate list;
     *  injectable so tests never touch the static API. */
    private val neverSiteWriter: (Boolean, String) -> Unit = { never, host ->
        runCatching {
            RuntimeTranslation.setNeverTranslateSpecifiedSite(never, host)
        }
    },
) {
    companion object {
        /** Stored six-language setting: comma-separated codes;
         *  absent means all six. */
        const val LANGUAGES_KEY = "translateLanguages"
        const val TARGET_LANGUAGE = "en"
    }

    val supported: State<Boolean?> get() = _supported
    private val _supported = mutableStateOf<Boolean?>(null)
    val offer: State<TranslateOffer?> get() = _offer
    private val _offer = mutableStateOf<TranslateOffer?>(null)
    val pageState: State<TranslatePageState> get() = _pageState
    private val _pageState = mutableStateOf<TranslatePageState>(TranslatePageState.Hidden)
    val modelStates: State<List<TranslateModelState>> get() = _modelStates
    private val _modelStates = mutableStateOf<List<TranslateModelState>>(emptyList())
    val neverSites: State<List<String>> get() = _neverSites
    private val _neverSites = mutableStateOf<List<String>>(emptyList())

    /** tabId -> detected document language code (normalised). */
    private val detectedLang = mutableMapOf<Int, String>()
    /** tabIds whose offer is waiting on detection (the offer
     *  fired before the state change carried the language,
     *  or the engine-support check had not returned yet). */
    private val pendingOffer = mutableSetOf<Int>()
    /** tabId -> last built offer (the menu action reuses it). */
    private val lastOffer = mutableMapOf<Int, TranslateOffer>()
    /** tabId -> from-language code while translated. */
    private val translatedFrom = mutableMapOf<Int, String>()
    private var selectedTab: TranslateTab? = null
    private var supportedChecked = false

    /** The single shared translations delegate, set on every
     *  session by the engine. */
    val delegate = object : SessionTranslation.Delegate {
        override fun onOfferTranslate(session: GeckoSession) {
            handleOffer(session)
        }

        override fun onExpectedTranslate(session: GeckoSession) {
            // The toolkit fires this when a language is set to
            // Always-translate. Pioneer has no Always setting
            // (offer + never only), so there is nothing to do.
        }

        override fun onTranslationStateChange(
            session: GeckoSession,
            state: SessionTranslation.TranslationState?,
        ) {
            // The toolkit can deliver a null state; there is
            // nothing to learn from it.
            if (state == null) return
            handleStateChange(session, state)
        }
    }

    // ---------- device gate ----------

    /** Fire-and-forget, once: the WASM capability check.
     *  Until it returns, [supported] is null and no offer
     *  fires (an offer arriving first waits in
     *  [pendingOffer]). */
    fun checkSupported() {
        if (supportedChecked) return
        supportedChecked = true
        runCatching {
            RuntimeTranslation.isTranslationsEngineSupported().accept(
                { ok -> postToMain { onSupportedResult(ok == true) } },
                { postToMain { onSupportedResult(false) } },
            )
        }.onFailure { postToMain { onSupportedResult(false) } }
    }

    /** Pure handler: record the gate result, then build any
     *  offers that were waiting on it. */
    internal fun onSupportedResult(ok: Boolean) {
        _supported.value = ok
        status("translate: engine ${if (ok) "supported" else "not supported on this device"}")
        if (!ok) return
        pendingOffer.toList().forEach { tabId ->
            val from = detectedLang[tabId] ?: return@forEach
            val tab = selectedTab?.takeIf { it.id == tabId }
                ?: return@forEach
            pendingOffer.remove(tabId)
            loadOfferDetails(tab, from)
        }
    }

    // ---------- offer flow ----------

    private fun handleOffer(session: GeckoSession) {
        val tab = tabForSession(session) ?: return
        val from = detectedLang[tab.id]
        if (from == null) {
            // Detection has not arrived yet (or the state
            // change came after the offer): wait for it.
            pendingOffer.add(tab.id)
            return
        }
        maybeOffer(tab, from)
    }

    private fun handleStateChange(
        session: GeckoSession,
        state: SessionTranslation.TranslationState,
    ) {
        val tab = tabForSession(session) ?: return
        val docLang = state.detectedLanguages?.docLangTag
            ?.takeIf { it.isNotBlank() }
            ?.let { TranslationLanguages.normalizeCode(it) }
        if (docLang != null) {
            detectedLang[tab.id] = docLang
        }
        val err = state.error
        if (err != null) {
            status("translate: failed: $err")
            if (selectedTab?.id == tab.id) {
                _pageState.value = lastOffer[tab.id]?.let {
                    TranslatePageState.Offer(it.fromCode, it.fromName)
                } ?: TranslatePageState.Hidden
            }
            return
        }
        if (state.hasVisibleChange == true) {
            val from = state.requestedTranslationPair?.fromLanguage
                ?.let { TranslationLanguages.normalizeCode(it) }
                ?: detectedLang[tab.id]
            if (from != null) {
                translatedFrom[tab.id] = from
                status("translate: ${from} → en done")
                if (selectedTab?.id == tab.id) {
                    val name = TranslationLanguages.languageFor(from)?.name ?: from
                    _pageState.value = TranslatePageState.Translated(from, name)
                }
            }
            return
        }
        if (pendingOffer.remove(tab.id)) {
            val from = detectedLang[tab.id] ?: return
            maybeOffer(tab, from)
        }
    }

    /** The gating decision (pure, unit-tested): offer only
     *  when the engine is supported, the detected language is
     *  one of the six AND enabled in settings, the host is
     *  not never-translate, and the page is not already
     *  translated. */
    internal fun shouldOffer(
        fromCode: String,
        enabled: Set<String>,
        supported: Boolean?,
        neverSites: List<String>,
        host: String,
        alreadyTranslatedFrom: String?,
    ): Boolean {
        if (supported != true) return false
        if (alreadyTranslatedFrom != null) return false
        if (!TranslationLanguages.isOfferable(fromCode)) return false
        if (TranslationLanguages.normalizeCode(fromCode) !in enabled) return false
        val cleanHost = host.lowercase().trimEnd('.')
        if (neverSites.any {
            val site = it.lowercase().trimEnd('.')
            cleanHost == site || cleanHost.endsWith(".$site")
        }) return false
        return true
    }

    private fun hostOf(tab: TranslateTab): String =
        runCatching { android.net.Uri.parse(tab.url).host }.getOrNull() ?: ""

    private fun maybeOffer(tab: TranslateTab, from: String) {
        if (_supported.value == null) {
            // Gate check still in flight: wait for it.
            pendingOffer.add(tab.id)
            return
        }
        val enabled = TranslationLanguages.parseEnabledLanguages(
            store.setting(LANGUAGES_KEY, "")
        )
        if (!shouldOffer(
                from, enabled, _supported.value, _neverSites.value,
                hostOf(tab), translatedFrom[tab.id],
            )
        ) {
            pendingOffer.remove(tab.id)
            return
        }
        loadOfferDetails(tab, from)
    }

    /** Resolve the model state + download size for the offer,
     *  then build it. Nested (not parallel): the size comes
     *  from the same list, so one call answers both. */
    private fun loadOfferDetails(tab: TranslateTab, from: String) {
        runCatching {
            RuntimeTranslation.listModelDownloadStates().accept(
                { models ->
                    val model = models?.firstOrNull {
                        it.language?.code?.equals(from, ignoreCase = true) == true
                    }
                    val downloaded = model?.isDownloaded == true
                    val size = model?.size?.takeIf { it > 0 }
                        ?: fallbackPackSize(from)
                    postToMain {
                        onOfferDetailsLoaded(tab, from, size, downloaded)
                    }
                },
                {
                    postToMain {
                        onOfferDetailsLoaded(tab, from, fallbackPackSize(from), false)
                    }
                },
            )
        }.onFailure {
            postToMain { onOfferDetailsLoaded(tab, from, fallbackPackSize(from), false) }
        }
    }

    /** Static pair-total fallback (spike table, ±30%) when the
     *  live size lookup fails. */
    internal fun fallbackPackSize(from: String): Long =
        ((TranslationLanguages.languageFor(from)?.pairTotalMB ?: 74.0) * 1_000_000).toLong()

    /** Pure handler: re-validate (the tab may have navigated
     *  away while the lookup ran), then publish the offer. */
    internal fun onOfferDetailsLoaded(
        tab: TranslateTab,
        from: String,
        sizeBytes: Long,
        downloaded: Boolean,
    ) {
        val current = tabForSession(tab.session) ?: return
        if (detectedLang[current.id] != TranslationLanguages.normalizeCode(from)) return
        if (translatedFrom[current.id] != null) return
        val enabled = TranslationLanguages.parseEnabledLanguages(
            store.setting(LANGUAGES_KEY, "")
        )
        if (!shouldOffer(
                from, enabled, _supported.value, _neverSites.value,
                hostOf(current), translatedFrom[current.id],
            )
        ) return
        val lang = TranslationLanguages.languageFor(from) ?: return
        val built = TranslateOffer(
            tabId = current.id,
            fromCode = lang.code,
            fromName = lang.name,
            host = hostOf(current),
            pairSizeBytes = sizeBytes,
            modelDownloaded = downloaded,
        )
        lastOffer[current.id] = built
        pendingOffer.remove(current.id)
        _offer.value = built
        if (selectedTab?.id == current.id) {
            _pageState.value = TranslatePageState.Offer(lang.code, lang.name)
        }
        status("translate: offer ${lang.code} → en on ${built.host}")
    }

    // ---------- answering the offer ----------

    /** The shell's answer to the offer dialog. NOT_NOW only
     *  dismisses the popup — the menu row still offers the
     *  translation. NEVER_SITE persists the host in the
     *  runtime never-translate list and on the session. */
    fun answerOffer(choice: TranslateChoice, session: GeckoSession?) {
        val current = _offer.value ?: return
        when (choice) {
            TranslateChoice.NOT_NOW -> {
                _offer.value = null
            }
            TranslateChoice.NEVER_SITE -> {
                _offer.value = null
                lastOffer.remove(current.tabId)
                detectedLang.remove(current.tabId)
                if (current.host.isNotBlank()) {
                    neverSiteWriter(true, current.host)
                    _neverSites.value = _neverSites.value + current.host
                    status("translate: never on ${current.host}")
                }
                session?.let {
                    runCatching {
                        it.sessionTranslation?.setNeverTranslateSiteSetting(true)
                    }
                }
                if (selectedTab?.id == current.tabId) {
                    _pageState.value = TranslatePageState.Hidden
                }
            }
            TranslateChoice.TRANSLATE -> {
                _offer.value = null
                val tab = selectedTab?.takeIf { it.id == current.tabId }
                    ?: tabForSession(session ?: return)
                    ?: return
                startTranslation(tab, current.fromCode, current.modelDownloaded)
            }
        }
    }

    /** Menu action: translate the selected tab's page, using
     *  the last offer (or the detected language) for the
     *  pair. No-op when nothing is offerable. */
    fun translateCurrentPage() {
        val tab = selectedTab ?: return
        if (translatedFrom[tab.id] != null) return
        val from = lastOffer[tab.id]?.fromCode
            ?: detectedLang[tab.id]
            ?: return
        val enabled = TranslationLanguages.parseEnabledLanguages(
            store.setting(LANGUAGES_KEY, "")
        )
        if (!shouldOffer(
                from, enabled, _supported.value, _neverSites.value,
                hostOf(tab), translatedFrom[tab.id],
            )
        ) return
        val known = lastOffer[tab.id]
        if (known != null && known.fromCode == TranslationLanguages.normalizeCode(from)) {
            startTranslation(tab, known.fromCode, known.modelDownloaded)
        } else {
            loadOfferDetailsForTranslate(tab, from)
        }
    }

    /** Menu path without an offer: resolve the model state
     *  first, then translate (or download-then-translate). */
    private fun loadOfferDetailsForTranslate(tab: TranslateTab, from: String) {
        runCatching {
            RuntimeTranslation.listModelDownloadStates().accept(
                { models ->
                    val downloaded = models?.firstOrNull {
                        it.language?.code?.equals(from, ignoreCase = true) == true
                    }?.isDownloaded == true
                    postToMain {
                        val current = tabForSession(tab.session) ?: return@postToMain
                        if (detectedLang[current.id] !=
                            TranslationLanguages.normalizeCode(from)
                        ) return@postToMain
                        startTranslation(current, from, downloaded)
                    }
                },
                {
                    postToMain {
                        startTranslation(tab, from, false)
                    }
                },
            )
        }.onFailure {
            postToMain { startTranslation(tab, from, false) }
        }
    }

    /** The translate core: download the language pack first
     *  when it is missing (the user already said yes — the
     *  offer dialog stated the size), then translate with
     *  downloadModel=false so translate() itself never
     *  downloads silently. */
    private fun startTranslation(tab: TranslateTab, from: String, downloaded: Boolean) {
        if (selectedTab?.id == tab.id) {
            _pageState.value = TranslatePageState.Translating
        }
        val lang = TranslationLanguages.languageFor(from) ?: return
        if (downloaded) {
            doTranslate(tab, from)
            return
        }
        status(
            "translate: downloading ${lang.name} model " +
                "(${TranslationLanguages.formatSize(fallbackPackSize(from))})…"
        )
        runCatching {
            RuntimeTranslation.manageLanguageModel(
                RuntimeTranslation.ModelManagementOptions.Builder()
                    .languageToManage(lang.code)
                    .operation(RuntimeTranslation.DOWNLOAD)
                    .operationLevel(RuntimeTranslation.LANGUAGE)
                    .build()
            ).accept(
                { postToMain { doTranslate(tab, from) } },
                { e ->
                    postToMain {
                        status("translate: download failed: ${e?.message}")
                        if (selectedTab?.id == tab.id) {
                            _pageState.value = TranslatePageState.Offer(lang.code, lang.name)
                        }
                    }
                },
            )
        }.onFailure { e ->
            status("translate: download failed: ${e.message}")
            if (selectedTab?.id == tab.id) {
                _pageState.value = TranslatePageState.Offer(lang.code, lang.name)
            }
        }
    }

    private fun doTranslate(tab: TranslateTab, from: String) {
        val lang = TranslationLanguages.languageFor(from) ?: return
        runCatching {
            tab.session.sessionTranslation?.translate(
                from,
                TARGET_LANGUAGE,
                SessionTranslation.TranslationOptions.Builder()
                    .downloadModel(false)
                    .build(),
            )?.accept(
                {
                    // The state change (hasVisibleChange) is
                    // the source of truth, but mark it here
                    // too: if it never arrives the menu would
                    // sit on "Translating…" forever. Idempotent.
                    translatedFrom[tab.id] = lang.code
                    if (selectedTab?.id == tab.id) {
                        _pageState.value =
                            TranslatePageState.Translated(lang.code, lang.name)
                    }
                    status("translate: ${lang.code} → en done")
                },
                { e ->
                    status("translate: failed: ${e?.message}")
                    if (selectedTab?.id == tab.id) {
                        _pageState.value =
                            TranslatePageState.Offer(lang.code, lang.name)
                    }
                },
            )
        }.onFailure { e ->
            status("translate: failed: ${e.message}")
            if (selectedTab?.id == tab.id) {
                _pageState.value = TranslatePageState.Offer(lang.code, lang.name)
            }
        }
    }

    /** Menu action: restore the selected tab's page to the
     *  original language. No-op when it is not translated. */
    fun restoreCurrentPage() {
        val tab = selectedTab ?: return
        val from = translatedFrom[tab.id] ?: return
        runCatching {
            tab.session.sessionTranslation?.restoreOriginalPage()?.accept(
                {
                    translatedFrom.remove(tab.id)
                    if (selectedTab?.id == tab.id) {
                        val lang = TranslationLanguages.languageFor(from)
                        _pageState.value = if (lang != null) {
                            TranslatePageState.Offer(lang.code, lang.name)
                        } else {
                            TranslatePageState.Hidden
                        }
                    }
                    status("translate: restored original page")
                },
                { e -> status("translate: restore failed: ${e?.message}") },
            )
        }.onFailure { e -> status("translate: restore failed: ${e.message}") }
    }

    // ---------- selection / lifecycle ----------

    fun onTabSelected(tab: TranslateTab) {
        selectedTab = tab
        refreshPageState(tab.id)
    }

    fun onTabClosed(tabId: Int) {
        detectedLang.remove(tabId)
        pendingOffer.remove(tabId)
        lastOffer.remove(tabId)
        translatedFrom.remove(tabId)
        if (_offer.value?.tabId == tabId) _offer.value = null
        if (selectedTab?.id == tabId) {
            selectedTab = null
            _pageState.value = TranslatePageState.Hidden
        }
    }

    private fun refreshPageState(tabId: Int) {
        val from = translatedFrom[tabId]
        if (from != null) {
            val lang = TranslationLanguages.languageFor(from)
            _pageState.value = if (lang != null) {
                TranslatePageState.Translated(lang.code, lang.name)
            } else {
                TranslatePageState.Hidden
            }
            return
        }
        val offer = lastOffer[tabId]
        if (offer != null) {
            _pageState.value = TranslatePageState.Offer(offer.fromCode, offer.fromName)
            return
        }
        val detected = detectedLang[tabId]
        if (detected != null &&
            TranslationLanguages.isOfferable(detected) &&
            _supported.value == true
        ) {
            val lang = TranslationLanguages.languageFor(detected)
            if (lang != null) {
                _pageState.value = TranslatePageState.Offer(lang.code, lang.name)
                return
            }
        }
        _pageState.value = TranslatePageState.Hidden
    }

    // ---------- settings surfaces ----------

    /** One language on/off in the six-language setting. */
    fun setLanguageEnabled(code: String, enabled: Boolean) {
        val normalized = TranslationLanguages.normalizeCode(code)
        if (normalized !in TranslationLanguages.CODES) return
        val current = TranslationLanguages.parseEnabledLanguages(
            store.setting(LANGUAGES_KEY, "")
        )
        val next = if (enabled) current + normalized else current - normalized
        // The store setting is the single source of truth;
        // an empty set is stored as "" and parses back to
        // all six (parseEnabledLanguages treats blank as
        // "all") — so an explicit empty set is kept as a
        // sentinel instead: a single "-" means "none".
        store.putSetting(
            LANGUAGES_KEY,
            if (next.isEmpty()) "-" else next.sorted().joinToString(","),
        )
    }

    /** Is [code] enabled for offering right now? */
    fun isLanguageEnabled(code: String): Boolean =
        TranslationLanguages.normalizeCode(code) in
            TranslationLanguages.parseEnabledLanguages(store.setting(LANGUAGES_KEY, ""))

    /** Load the per-language model states for the Settings
     *  rows (lazy: only when Settings asks). */
    fun refreshModelStates() {
        runCatching {
            RuntimeTranslation.listModelDownloadStates().accept(
                { models -> postToMain { onModelStatesLoaded(models) } },
                { status("translate: model list failed") },
            )
        }.onFailure { status("translate: model list failed") }
    }

    /** Pure handler: map the toolkit's per-language models
     *  onto the six Settings rows. */
    internal fun onModelStatesLoaded(
        models: List<RuntimeTranslation.LanguageModel>?,
    ) {
        _modelStates.value = TranslationLanguages.SUPPORTED.map { lang ->
            val model = models?.firstOrNull {
                it.language?.code?.equals(lang.code, ignoreCase = true) == true
            }
            TranslateModelState(
                code = lang.code,
                name = lang.name,
                downloaded = model?.isDownloaded == true,
                sizeBytes = model?.size ?: 0L,
            )
        }
    }

    /** Load the never-translate site list for Settings. */
    fun refreshNeverSites() {
        runCatching {
            RuntimeTranslation.getNeverTranslateSiteList().accept(
                { sites ->
                    postToMain {
                        _neverSites.value = sites?.sorted() ?: emptyList()
                    }
                },
                { status("translate: never-translate list failed") },
            )
        }.onFailure { status("translate: never-translate list failed") }
    }

    /** Remove one host from the never-translate list (the
     *  Settings row action), then reload the list. */
    fun removeNeverSite(host: String) {
        val clean = host.lowercase().trimEnd('.')
        if (clean.isBlank()) return
        neverSiteWriter(false, clean)
        _neverSites.value = _neverSites.value.filter {
            it.lowercase().trimEnd('.') != clean
        }
        refreshNeverSites()
    }
}
