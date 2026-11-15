package net.nspioneer.browser

import net.nspioneer.browser.media.LOCAL_VIDEO_EXTENSIONS
import net.nspioneer.browser.media.LOCAL_VIDEO_MIME_TYPES
import net.nspioneer.browser.media.isVideoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Import and Open (alpha.56): the player gains an explicit
 * entry that opens the system picker for a local video file
 * and plays it in the same player (OpenDocument, video/ only —
 * the same contract as the subtitle picker).
 *
 * What the harness can express without a real picker:
 *  - the picker MIME contract ([LOCAL_VIDEO_MIME_TYPES]),
 *  - the video/non-video decision ([isVideoFile]) the pick
 *    callback applies before anything is handed to the
 *    player.
 * What it cannot: driving the system picker itself. The
 * cancel path is the launcher's null return — the same
 * one-line guard the subtitle picker uses — verified by
 * inspection, not by a test; a cancelled pick changes
 * nothing and cannot crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerLocalVideoTest {

    @Test
    fun pickerOffersVideoOnly() {
        assertEquals(listOf("video/*"), LOCAL_VIDEO_MIME_TYPES.toList())
    }

    @Test
    fun videoMimeAccepted() {
        assertTrue(isVideoFile("video/mp4", "movie.mp4"))
        assertTrue(isVideoFile("video/x-matroska", "movie.mkv"))
        assertTrue(isVideoFile("video/webm", null))
        // Parameters and case never change the answer.
        assertTrue(isVideoFile("video/mp4; codecs=\"avc1\"", "movie.mp4"))
        assertTrue(isVideoFile("Video/MP4", "movie.mp4"))
    }

    @Test
    fun nonVideoMimeDeclined() {
        assertFalse(isVideoFile("image/jpeg", "photo.jpg"))
        assertFalse(isVideoFile("audio/mpeg", "song.mp3"))
        assertFalse(isVideoFile("application/pdf", "doc.pdf"))
        assertFalse(isVideoFile("text/plain", "notes.txt"))
        // A video-looking name does not save a non-video MIME.
        assertFalse(isVideoFile("image/jpeg", "movie.mp4"))
    }

    @Test
    fun noMimeFallsBackToExtension() {
        assertTrue(isVideoFile(null, "movie.mp4"))
        assertTrue(isVideoFile(null, "clip.WEBM"))
        assertTrue(isVideoFile(null, "film.mkv"))
        assertTrue(isVideoFile(null, "old.avi"))
        assertFalse(isVideoFile(null, "photo.jpg"))
        assertFalse(isVideoFile(null, "song.mp3"))
        assertFalse(isVideoFile(null, "subs.srt"))
        // Unknown extension with no MIME is declined plainly
        // rather than handed to the player to fail on.
        assertFalse(isVideoFile(null, "weird.xyz"))
        assertFalse(isVideoFile(null, null))
        // An empty MIME string is "no MIME type" — the
        // extension decides, exactly as for a null MIME.
        assertTrue(isVideoFile("", "movie.mp4"))
    }

    @Test
    fun knownVideoExtensionsCovered() {
        for (ext in listOf("mp4", "webm", "mkv", "mov", "avi", "m4v")) {
            assertTrue(ext, LOCAL_VIDEO_EXTENSIONS.contains(ext))
        }
    }
}
