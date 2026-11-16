package net.nspioneer.browser

import net.nspioneer.browser.media.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alpha.58 resolve-to-one, DASH branch: an MPD has no
 * sub-playlists, so a normal manifest references nothing;
 * only a Representation BaseURL that itself ends in .mpd
 * (a chained MPD) is reported.
 *
 * Runs under Robolectric like DashManifestTest: the MPD
 * parser uses XmlPullParserFactory, which throws on the
 * plain-JVM android.jar stubs. Pinned runtime SDK 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResolvePickerDashTest {

    private fun mpd(baseUrl: String) = """
        <MPD type="static" mediaPresentationDuration="PT8S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v1" bandwidth="1000000" width="1280" height="720">
                <BaseURL>$baseUrl</BaseURL>
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4" timescale="1000" startNumber="1" />
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun referencedMediaPlaylists_normalMpdReferencesNothing() {
        assertTrue(
            Downloads.referencedMediaPlaylists(
                mpd("video.mp4"), "https://example.com/manifest.mpd"
            ).isEmpty()
        )
    }

    @Test
    fun referencedMediaPlaylists_chainedMpdBaseUrl() {
        assertEquals(
            setOf("https://example.com/other.mpd"),
            Downloads.referencedMediaPlaylists(
                mpd("other.mpd"), "https://example.com/manifest.mpd"
            )
        )
    }
}
