package net.nspioneer.browser

import net.nspioneer.browser.engine.GeckoEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fingerprinting protection (alpha.44): the standard FPP
 * setters, gated on the stored ETP level.
 *
 * The gating is tested through the companion helpers
 * (GeckoEngine itself needs a real GeckoRuntime, which
 * Robolectric cannot create): the level mapping, and the
 * exact setter calls on a mocked GeckoRuntimeSettings.
 * The one behaviour that must never happen — an explicit
 * `false` to the private-browsing setter, which would
 * strip protection private tabs already have by default
 * (geckoview-prefs.js sets
 * privacy.fingerprintingProtection.pbmode true) — is
 * pinned by a `never()` verification on every level.
 *
 * Same shape as PermissionDelegateTest: Robolectric,
 * runtime SDK 35, Mockito 5 (mocks the GeckoView class
 * without calling its constructor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FingerprintingProtectionTest {

    private fun mockedSettings(): GeckoRuntimeSettings =
        mock(GeckoRuntimeSettings::class.java)

    @Test
    fun enabled_strictOn_basicAndOffOff() {
        assertTrue(GeckoEngine.fingerprintingProtectionEnabled("strict"))
        assertFalse(GeckoEngine.fingerprintingProtectionEnabled("basic"))
        assertFalse(GeckoEngine.fingerprintingProtectionEnabled("off"))
    }

    @Test
    fun enabled_unknownLevelFallsBackOn() {
        // Matches setTrackingProtection's ETP mapping, where
        // an unknown level falls back to STRICT — FPP follows
        // the level actually applied.
        assertTrue(GeckoEngine.fingerprintingProtectionEnabled("weird"))
        assertTrue(GeckoEngine.fingerprintingProtectionEnabled(""))
    }

    @Test
    fun apply_strictEnablesBoth() {
        val settings = mockedSettings()
        GeckoEngine.applyFingerprintingProtection(settings, true)
        verify(settings).setFingerprintingProtection(true)
        verify(settings).setFingerprintingProtectionPrivateBrowsing(true)
    }

    @Test
    fun apply_disabledTurnsOffNormalTabsOnly() {
        val settings = mockedSettings()
        GeckoEngine.applyFingerprintingProtection(settings, false)
        verify(settings).setFingerprintingProtection(false)
        // Private tabs are only ever turned ON, never off:
        // the setter must not be called at all here.
        verify(settings, never()).setFingerprintingProtectionPrivateBrowsing(true)
        verify(settings, never()).setFingerprintingProtectionPrivateBrowsing(false)
    }

    @Test
    fun apply_neverDisablesPrivateBrowsing() {
        // The guard, stated as its own test: for every level
        // the engine can apply, the private-browsing setter
        // is never called with false.
        for (enabled in listOf(true, false)) {
            val settings = mockedSettings()
            GeckoEngine.applyFingerprintingProtection(settings, enabled)
            verify(settings, never())
                .setFingerprintingProtectionPrivateBrowsing(false)
        }
    }
}
