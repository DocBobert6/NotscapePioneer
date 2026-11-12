package net.nspioneer.browser

import net.nspioneer.browser.media.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * DASH SegmentTimeline (alpha.10, Mariner DashManifest.kt:174-197
 * + 330-355): the manifest parser must expand <S> elements into
 * exact start times (t defaults to the running cursor, d must be
 * > 0, r defaults to 0 and must be >= 0, each S adds r+1 times,
 * capped at 10,000) and discard the whole timeline on any
 * invalid S — falling back to expand-$Number$-until-404.
 *
 * Runs under Robolectric like DownloadsStoreTest: the parser uses
 * XmlPullParserFactory, which throws on the plain-JVM android.jar
 * stubs. Pinned runtime SDK 35, as in DownloadsStoreTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashManifestTest {

    private fun mpd(video: String, audio: String = ""): String = """
        <MPD type="static" mediaPresentationDuration="PT8S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v1" bandwidth="1000000" width="1280" height="720">
                $video
              </Representation>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4">
              <Representation id="a1" bandwidth="128000">
                $audio
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private fun templateWith(timeline: String) =
        "<SegmentTemplate media=\"seg-${'$'}Number${'$'}-${'$'}Time${'$'}.m4s\" " +
            "initialization=\"init.mp4\" timescale=\"1000\" startNumber=\"1\">" +
            timeline + "</SegmentTemplate>"

    @Test
    fun segmentTimelineParsedWithRepeatAndCursor() {
        val timeline = "<SegmentTimeline>" +
            "<S t=\"0\" d=\"2000\" r=\"2\"/>" +
            "<S d=\"2000\"/>" +
            "</SegmentTimeline>"
        val reps = Downloads.parseDashManifest(
            mpd(templateWith(timeline)), "https://example.com/manifest.mpd"
        )
        val video = reps.first { it.mime.contains("video") }
        // First S: t=0, d=2000, r=2 -> 0, 2000, 4000. Second S:
        // no t, so the cursor (6000) continues -> 6000.
        assertEquals(listOf(0L, 2000L, 4000L, 6000L), video.timeline)
        assertEquals(1L, video.startNumber)
        assertEquals("seg-\$Number\$-\$Time\$.m4s", video.mediaTemplate)
        assertEquals("init.mp4", video.initTemplate)
        assertEquals(1000L, video.timescale)
    }

    @Test
    fun segmentTimelineInvalidDurationDiscardsWholeTimeline() {
        val timeline = "<SegmentTimeline>" +
            "<S t=\"0\" d=\"2000\" r=\"2\"/>" +
            "<S d=\"0\"/>" +
            "</SegmentTimeline>"
        val reps = Downloads.parseDashManifest(
            mpd(templateWith(timeline)), "https://example.com/manifest.mpd"
        )
        val video = reps.first { it.mime.contains("video") }
        assertTrue(video.timeline.isEmpty())
    }

    @Test
    fun segmentTimelineNegativeRepeatDiscardsWholeTimeline() {
        val timeline = "<SegmentTimeline>" +
            "<S t=\"0\" d=\"2000\" r=\"-1\"/>" +
            "</SegmentTimeline>"
        val reps = Downloads.parseDashManifest(
            mpd(templateWith(timeline)), "https://example.com/manifest.mpd"
        )
        val video = reps.first { it.mime.contains("video") }
        assertTrue(video.timeline.isEmpty())
    }

    @Test
    fun templateWithoutTimelineKeepsEmptyTimeline() {
        val reps = Downloads.parseDashManifest(
            mpd(
                "<SegmentTemplate media=\"seg-${'$'}Number${'$'}.m4s\" " +
                    "initialization=\"init.mp4\" timescale=\"1000\" startNumber=\"3\"/>"
            ),
            "https://example.com/manifest.mpd"
        )
        val video = reps.first { it.mime.contains("video") }
        assertTrue(video.timeline.isEmpty())
        assertEquals(3L, video.startNumber)
    }

    @Test
    fun segmentListStillParsed() {
        val list = "<SegmentList>" +
            "<SegmentURL media=\"a0.m4s\"/>" +
            "<SegmentURL media=\"a1.m4s\"/>" +
            "</SegmentList>"
        val reps = Downloads.parseDashManifest(
            mpd(templateWith("<SegmentTimeline><S t=\"0\" d=\"2000\"/></SegmentTimeline>"), list),
            "https://example.com/manifest.mpd"
        )
        val audio = reps.first { it.mime.contains("audio") }
        assertEquals(listOf("a0.m4s", "a1.m4s"), audio.segments)
        assertTrue(audio.timeline.isEmpty())
    }

    @Test(expected = IOException::class)
    fun dynamicManifestRejected() {
        val text = "<MPD type=\"dynamic\">" +
            "<Period><AdaptationSet mimeType=\"video/mp4\">" +
            "<Representation id=\"v1\" bandwidth=\"1000000\" width=\"1280\" height=\"720\">" +
            "<SegmentTemplate media=\"seg.m4s\"/>" +
            "</Representation></AdaptationSet></Period></MPD>"
        Downloads.parseDashManifest(text, "https://example.com/manifest.mpd")
    }
}
