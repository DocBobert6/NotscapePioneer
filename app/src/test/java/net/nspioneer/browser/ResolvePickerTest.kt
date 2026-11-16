package net.nspioneer.browser

import net.nspioneer.browser.media.DetectedMedia
import net.nspioneer.browser.media.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha.58 resolve-to-one: the picker must show one row per
 * video when a master manifest is present — the media
 * playlists it references are hidden — while a media
 * playlist with no master stays listed (orphan case).
 *
 * Plain JVM, like DownloadQualityTest — the reference
 * extraction and the filter are pure. The live fetch
 * (fetchReferencedPlaylists) is device-verified only.
 */
class ResolvePickerTest {

    private val base = "https://example.com/hls/master.m3u8"

    private val master = """
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

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:6.0,
        seg0.ts
        #EXTINF:6.0,
        seg1.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private fun media(url: String, label: String = "stream (HLS)") =
        DetectedMedia(url, "video", label, source = "net")

    @Test
    fun referencedMediaPlaylists_masterListsVariantsAndAllAudio() {
        val refs = Downloads.referencedMediaPlaylists(master, base)
        assertEquals(
            setOf(
                "https://example.com/hls/v1080.m3u8",
                "https://example.com/hls/v720.m3u8",
                "https://example.com/hls/v360.m3u8",
                "https://example.com/hls/audio-en.m3u8",
                "https://example.com/hls/audio-es.m3u8"
            ),
            refs
        )
    }

    @Test
    fun referencedMediaPlaylists_mediaPlaylistReferencesNothing() {
        assertTrue(
            Downloads.referencedMediaPlaylists(
                mediaPlaylist, "https://example.com/hls/v720.m3u8"
            ).isEmpty()
        )
    }

    @Test
    fun referencedMediaPlaylists_resolvesRelativeUris() {
        val refs = Downloads.referencedMediaPlaylists(
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"../audio/a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000\n" +
                "sub/v.m3u8\n",
            "https://example.com/hls/master.m3u8"
        )
        assertEquals(
            setOf(
                "https://example.com/audio/a.m3u8",
                "https://example.com/hls/sub/v.m3u8"
            ),
            refs
        )
    }

    @Test
    fun referencedMediaPlaylists_garbageIsEmpty() {
        assertTrue(Downloads.referencedMediaPlaylists("", base).isEmpty())
        assertTrue(
            Downloads.referencedMediaPlaylists("<html>not a playlist</html>", base).isEmpty()
        )
    }

    @Test
    fun resolvePickerItems_hidesReferencedMediaPlaylists() {
        val items = listOf(
            media("https://example.com/hls/master.m3u8"),
            media("https://example.com/hls/v720.m3u8"),
            media("https://example.com/hls/audio-en.m3u8"),
            DetectedMedia("https://example.com/clip.mp4", "video", "", source = "net")
        )
        val resolved = Downloads.resolvePickerItems(
            items,
            setOf(
                "https://example.com/hls/v720.m3u8",
                "https://example.com/hls/audio-en.m3u8"
            )
        )
        assertEquals(
            listOf(
                "https://example.com/hls/master.m3u8",
                "https://example.com/clip.mp4"
            ),
            resolved.map { it.url }
        )
    }

    @Test
    fun resolvePickerItems_orphanMediaPlaylistStays() {
        // No master present: the referenced set is empty and
        // the only media playlist is the only way to
        // download that stream.
        val orphan = listOf(media("https://example.com/hls/lonely.m3u8"))
        assertEquals(orphan, Downloads.resolvePickerItems(orphan, emptySet()))
        // A master elsewhere that references something
        // else does not hide it either.
        assertEquals(
            orphan,
            Downloads.resolvePickerItems(
                orphan, setOf("https://example.com/hls/other.m3u8")
            )
        )
    }

    @Test
    fun resolvePickerItems_neverHidesNonManifest() {
        // Defensive: even if a direct file's URL somehow
        // lands in the referenced set, only manifest rows
        // are ever hidden.
        val items = listOf(
            DetectedMedia("https://example.com/clip.mp4", "video", "", source = "net")
        )
        assertEquals(
            items,
            Downloads.resolvePickerItems(
                items, setOf("https://example.com/clip.mp4")
            )
        )
    }
}
