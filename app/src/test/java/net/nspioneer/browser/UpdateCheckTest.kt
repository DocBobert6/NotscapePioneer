package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.UpdateCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Opt-in update check (alpha.43): the pure pieces — remote
 * version parsing, the newer-than comparison, the stored
 * last-check round-trip, and the 24-hour staleness rule.
 *
 * The live fetch itself is not tested here: CI is offline by
 * design, and the fetch is one HttpURLConnection GET whose
 * result is exactly what [UpdateCheck.evaluate] judges.
 * Runs under Robolectric like DeAmpTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckTest {

    private lateinit var store: PioneerStore

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        store.removeSetting(UpdateCheck.SETTING_LAST)
        store.removeSetting(UpdateCheck.SETTING_ENABLED)
    }

    // -- parseRemoteVersion --

    @Test
    fun parse_validGradleText_returnsVersion() {
        val text = """
            android {
                defaultConfig {
                    applicationId = "net.nspioneer.browser"
                    versionCode = 43
                    versionName = "0.1.0-alpha.43"
                }
            }
        """.trimIndent()
        assertEquals(
            UpdateCheck.RemoteVersion("0.1.0-alpha.43", 43),
            UpdateCheck.parseRemoteVersion(text)
        )
    }

    @Test
    fun parse_realFileShape_ignoresComments() {
        // The real file carries the pin comment and other
        // assignments; only the defaultConfig pair counts.
        val text = """
            // GeckoView pin: 160.0.20261104181552 — latest STABLE release
            val geckoviewVersion = "160.0.20261104181552"
            android {
                defaultConfig {
                    versionCode = 44
                    versionName = "0.1.0-alpha.44"
                }
            }
        """.trimIndent()
        assertEquals(
            UpdateCheck.RemoteVersion("0.1.0-alpha.44", 44),
            UpdateCheck.parseRemoteVersion(text)
        )
    }

    @Test
    fun parse_missingVersionCode_returnsNull() {
        assertNull(
            UpdateCheck.parseRemoteVersion(
                "versionName = \"0.1.0-alpha.43\""
            )
        )
    }

    @Test
    fun parse_missingVersionName_returnsNull() {
        assertNull(UpdateCheck.parseRemoteVersion("versionCode = 43"))
    }

    @Test
    fun parse_emptyText_returnsNull() {
        assertNull(UpdateCheck.parseRemoteVersion(""))
    }

    // -- evaluate --

    @Test
    fun evaluate_newerRemote_isAvailable() {
        assertEquals(
            UpdateCheck.Result.Available(
                UpdateCheck.RemoteVersion("0.1.0-alpha.44", 44)
            ),
            UpdateCheck.evaluate(
                UpdateCheck.RemoteVersion("0.1.0-alpha.44", 44), 43
            )
        )
    }

    @Test
    fun evaluate_sameCode_isUpToDate() {
        assertEquals(
            UpdateCheck.Result.UpToDate(
                UpdateCheck.RemoteVersion("0.1.0-alpha.43", 43)
            ),
            UpdateCheck.evaluate(
                UpdateCheck.RemoteVersion("0.1.0-alpha.43", 43), 43
            )
        )
    }

    @Test
    fun evaluate_olderRemote_isUpToDate() {
        assertEquals(
            UpdateCheck.Result.UpToDate(
                UpdateCheck.RemoteVersion("0.1.0-alpha.42", 42)
            ),
            UpdateCheck.evaluate(
                UpdateCheck.RemoteVersion("0.1.0-alpha.42", 42), 43
            )
        )
    }

    @Test
    fun evaluate_nullRemote_isFailed() {
        assertEquals(
            UpdateCheck.Result.Failed,
            UpdateCheck.evaluate(null, 43)
        )
    }

    // -- last-check round-trip --

    @Test
    fun lastCheck_roundTrip_throughStore() {
        val remote = UpdateCheck.RemoteVersion("0.1.0-alpha.44", 44)
        UpdateCheck.saveLastCheck(store, remote, 1_700_000_000_000L)
        assertEquals(
            UpdateCheck.LastCheck(remote, 1_700_000_000_000L),
            UpdateCheck.loadLastCheck(store)
        )
    }

    @Test
    fun parseLastCheck_malformed_returnsNull() {
        assertNull(UpdateCheck.parseLastCheck(null))
        assertNull(UpdateCheck.parseLastCheck(""))
        assertNull(UpdateCheck.parseLastCheck("44|0.1.0-alpha.44"))
        assertNull(UpdateCheck.parseLastCheck("x|0.1.0-alpha.44|1"))
        assertNull(UpdateCheck.parseLastCheck("44||1"))
        assertNull(UpdateCheck.parseLastCheck("44|0.1.0-alpha.44|x"))
    }

    // -- staleness --

    @Test
    fun isStale_noStoredCheck_isTrue() {
        assertTrue(UpdateCheck.isStale(null, 1_700_000_000_000L))
        assertTrue(UpdateCheck.isStale("", 1_700_000_000_000L))
    }

    @Test
    fun isStale_freshCheck_isFalse() {
        val now = 1_700_000_000_000L
        val raw = "44|0.1.0-alpha.44|${now - 60 * 60 * 1000}"
        assertFalse(UpdateCheck.isStale(raw, now))
    }

    @Test
    fun isStale_dayOldCheck_isTrue() {
        val now = 1_700_000_000_000L
        val raw = "44|0.1.0-alpha.44|${now - 25 * 60 * 60 * 1000}"
        assertTrue(UpdateCheck.isStale(raw, now))
    }

    @Test
    fun isStale_exactlyAtInterval_isTrue() {
        val now = 1_700_000_000_000L
        val raw = "44|0.1.0-alpha.44|${now - UpdateCheck.CHECK_INTERVAL_MS}"
        assertTrue(UpdateCheck.isStale(raw, now))
    }
}
