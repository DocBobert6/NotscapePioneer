package net.nspioneer.browser

import net.nspioneer.browser.media.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha.46: unit tests for the download-quality picker engine
 * (media/Downloads.kt) — the HLS master parsing with separate
 * audio renditions, the pure variant -> choice converters, the
 * row labels, and the start-URL / choice-gate rules.
 *
 * Plain JVM, like DownloadsTest — no Robolectric, no Context.
 * The live fetch + remux path is device-verified only.
 */
class DownloadQualityTest {

    private val base = "https://example.com/master.m3u8"

    private val masterWithAudio = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,URI="audio-en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",URI="audio-es.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
        v1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="audio"
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        v360.m3u8
    """.trimIndent()

    @Test
    fun parseHlsMaster_attachesAudioRendition() {
        val variants = Downloads.parseHlsMaster(masterWithAudio, base)
        assertEquals(3, variants.size)
        // Both variants referencing the audio group carry its URL.
        assertEquals(
            "https://example.com/audio-en.m3u8", variants[0].audioUrl
        )
        assertEquals(
            "https://example.com/audio-en.m3u8", variants[1].audioUrl
        )
        // The variant with no AUDIO attribute has no audio side.
        assertNull(variants[2].audioUrl)
        assertEquals(5000000L, variants[0].bandwidth)
        assertEquals("https://example.com/v1080.m3u8", variants[0].url)
    }

    @Test
    fun parseHlsMaster_prefersDefaultAudioInGroup() {
        // DEFAULT=YES (English) wins over the first-listed
        // rendition when several share a group.
        val variants = Downloads.parseHlsMaster(masterWithAudio, base)
        assertEquals(
            "https://example.com/audio-en.m3u8", variants[0].audioUrl
        )
    }

    @Test
    fun parseHlsMaster_firstRenditionWhenNoDefault() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="One",URI="a1.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Two",URI="a2.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,AUDIO="a"
            v.m3u8
        """.trimIndent()
        val variants = Downloads.parseHlsMaster(master, base)
        assertEquals(1, variants.size)
        assertEquals("https://example.com/a1.m3u8", variants[0].audioUrl)
    }

    @Test
    fun parseHlsMaster_ignoresNonAudioMedia() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="EN",URI="subs.vtt"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="EN",DEFAULT=YES,URI="a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,AUDIO="a"
            v.m3u8
        """.trimIndent()
        val variants = Downloads.parseHlsMaster(master, base)
        assertEquals(1, variants.size)
        assertEquals("https://example.com/a.m3u8", variants[0].audioUrl)
    }

    @Test
    fun parseHlsMaster_withoutAudioUnchanged() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            v720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v360.m3u8
        """.trimIndent()
        val variants = Downloads.parseHlsMaster(master, base)
        assertEquals(2, variants.size)
        assertNull(variants[0].audioUrl)
        assertNull(variants[1].audioUrl)
        assertEquals(1280, variants[0].width)
        assertEquals(720, variants[0].height)
    }

    @Test
    fun parseHlsMaster_unknownAudioGroupIsNull() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,AUDIO="missing"
            v.m3u8
        """.trimIndent()
        val variants = Downloads.parseHlsMaster(master, base)
        assertEquals(1, variants.size)
        assertNull(variants[0].audioUrl)
    }

    @Test
    fun downloadVariantLabel_withAndWithoutAudio() {
        assertEquals(
            "1920x1080 • 5000 kbps",
            Downloads.downloadVariantLabel(1920, 1080, 5000000L, false)
        )
        assertEquals(
            "1920x1080 • 5000 kbps • video + audio",
            Downloads.downloadVariantLabel(1920, 1080, 5000000L, true)
        )
        // No resolution (audio-only bandwidth): just the bitrate.
        assertEquals(
            "128 kbps",
            Downloads.downloadVariantLabel(0, 0, 128000L, false)
        )
    }

    @Test
    fun hlsVariantsToDownload_sortsHighestFirstAndCarriesAudio() {
        val variants = Downloads.parseHlsMaster(masterWithAudio, base)
        val choices = Downloads.hlsVariantsToDownload(variants)
        assertEquals(3, choices.size)
        assertEquals(5000000L, choices[0].bandwidth)
        assertEquals(800000L, choices[2].bandwidth)
        assertTrue(choices[0].hasAudio)
        assertTrue(choices[0].needsMux)
        assertEquals("https://example.com/audio-en.m3u8", choices[0].audioUrl)
        assertEquals(
            "1920x1080 • 5000 kbps • video + audio", choices[0].label
        )
        assertFalse(choices[2].hasAudio)
        assertFalse(choices[2].needsMux)
        assertEquals("640x360 • 800 kbps", choices[2].label)
    }

    @Test
    fun dashRepsToVariants_pairsBestAudio() {
        val reps = listOf(
            Downloads.DashRep(
                5000000L, 1920, 1080, "video/mp4", null, null, null,
                1L, 0L, 1L, emptyList()
            ),
            Downloads.DashRep(
                2000000L, 1280, 720, "video/mp4", null, null, null,
                1L, 0L, 1L, emptyList()
            ),
            Downloads.DashRep(
                128000L, 0, 0, "audio/mp4", null, null, null,
                1L, 0L, 1L, emptyList()
            ),
        )
        val choices = Downloads.dashRepsToVariants(reps)
        // Only video reps are offered; the audio rep is not a row.
        assertEquals(2, choices.size)
        assertEquals(5000000L, choices[0].bandwidth)
        assertTrue(choices[0].hasAudio)
        assertTrue(choices[0].needsMux)
        assertNull(choices[0].url)
        assertEquals(
            "1920x1080 • 5000 kbps • video + audio", choices[0].label
        )
    }

    @Test
    fun dashRepsToVariants_videoOnlyManifestHasNoAudio() {
        val reps = listOf(
            Downloads.DashRep(
                2000000L, 1280, 720, "video/mp4", null, null, null,
                1L, 0L, 1L, emptyList()
            ),
        )
        val choices = Downloads.dashRepsToVariants(reps)
        assertEquals(1, choices.size)
        assertFalse(choices[0].hasAudio)
        assertFalse(choices[0].needsMux)
        assertEquals("1280x720 • 2000 kbps", choices[0].label)
    }

    @Test
    fun downloadUrlForVariant_audioChoiceStartsFromMaster() {
        val master = "https://example.com/master.m3u8"
        val withAudio = Downloads.DownloadVariant(
            label = "1920x1080 • 5000 kbps • video + audio",
            url = "https://example.com/v1080.m3u8", bandwidth = 5000000L,
            audioUrl = "https://example.com/audio-en.m3u8",
            hasAudio = true, needsMux = true
        )
        // The HLS+audio job must start from the master so it
        // can find the audio track.
        assertEquals(master, Downloads.downloadUrlForVariant(master, withAudio))
        val videoOnly = Downloads.DownloadVariant(
            label = "640x360 • 800 kbps",
            url = "https://example.com/v360.m3u8", bandwidth = 800000L
        )
        assertEquals(
            "https://example.com/v360.m3u8",
            Downloads.downloadUrlForVariant(master, videoOnly)
        )
        // DASH choices carry no URL: the manifest is the start.
        val dash = Downloads.DownloadVariant(
            label = "1920x1080 • 5000 kbps", url = null, bandwidth = 5000000L
        )
        assertEquals(master, Downloads.downloadUrlForVariant(master, dash))
        assertEquals(master, Downloads.downloadUrlForVariant(master, null))
    }

    @Test
    fun offersQualityChoice_needsMoreThanOne() {
        assertFalse(Downloads.offersQualityChoice(emptyList()))
        assertFalse(
            Downloads.offersQualityChoice(
                listOf(
                    Downloads.DownloadVariant("one", null, 1000L)
                )
            )
        )
        assertTrue(
            Downloads.offersQualityChoice(
                listOf(
                    Downloads.DownloadVariant("one", null, 1000L),
                    Downloads.DownloadVariant("two", null, 2000L)
                )
            )
        )
    }
}
