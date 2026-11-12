package net.nspioneer.browser

import net.nspioneer.browser.data.DecodedIco
import net.nspioneer.browser.data.extractLargestIcoEntry
import net.nspioneer.browser.data.parseIcoBmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3: the ICO decoder is pure Kotlin (no Android classes),
 * so its byte-level behaviour is pinned here on the JVM. The
 * BitmapFactory paths in [net.nspioneer.browser.data.PioneerStore]
 * cannot run in a unit test and are covered by the device check.
 */
class IcoDecoderTest {

    /** One ICO entry header + BITMAPINFOHEADER, little-endian. */
    private fun icoWithBmpEntry(
        width: Int, height: Int, bitCount: Int, pixelRows: ByteArray
    ): ByteArray {
        val headerSize = 40
        val imageSize = headerSize + pixelRows.size
        val out = ArrayList<Byte>()
        fun u16(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
        }
        fun u32(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
            out.add(((v shr 16) and 0xFF).toByte())
            out.add(((v shr 24) and 0xFF).toByte())
        }
        // ICONDIR: reserved 0, type 1 (ICO), one image.
        u16(0); u16(1); u16(1)
        // ICONDIRENTRY: w, h, colours, reserved, planes, bitcount, size, offset.
        out.add(width.toByte()); out.add(height.toByte())
        out.add(0); out.add(0)
        u16(1); u16(bitCount); u32(imageSize); u32(6 + 16)
        // BITMAPINFOHEADER: size 40, width, doubled height, planes, bitcount.
        u32(40); u32(width); u32(height * 2); u16(1); u16(bitCount)
        u32(0); u32(0); u32(0); u32(0); u32(0); u32(0)
        pixelRows.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun decodes32bppSinglePixel() {
        // Bottom-up 1x1: BGRA red, opaque; 4-byte AND mask row.
        val bytes = icoWithBmpEntry(
            1, 1, 32,
            byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        )
        assertEquals(
            DecodedIco(1, 1, intArrayOf(0xFFFF0000.toInt())),
            parseIcoBmp(extractLargestIcoEntry(bytes)!!)
        )
    }

    @Test
    fun zeroAlphaPlaneMeansOpaque() {
        // 32bpp with an all-zero alpha channel: a common ICO
        // quirk — the pixel must come out opaque, not invisible.
        val bytes = icoWithBmpEntry(
            1, 1, 32,
            byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0, 0, 0, 0)
        )
        assertEquals(
            DecodedIco(1, 1, intArrayOf(0xFFFF0000.toInt())),
            parseIcoBmp(extractLargestIcoEntry(bytes)!!)
        )
    }

    @Test
    fun decodes24bppAndFlipsBottomUpRows() {
        // 2x2, 24bpp: rows are stored bottom-up, each padded to 4 bytes.
        // Stored first (bottom) row: blue, white. Stored second: red, black.
        val rows = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, // blue
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // white
            0x00, 0x00, // pad
            0x00, 0x00, 0xFF.toByte(), // red
            0x00, 0x00, 0x00, // black
            0x00, 0x00, // pad
            0x00, 0x00, 0x00, 0x00, // AND mask row 1
            0x00, 0x00, 0x00, 0x00 // AND mask row 2
        )
        val bytes = icoWithBmpEntry(2, 2, 24, rows)
        assertEquals(
            DecodedIco(
                2, 2, intArrayOf(
                    0xFFFF0000.toInt(), 0xFF000000.toInt(),
                    0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()
                )
            ),
            parseIcoBmp(extractLargestIcoEntry(bytes)!!)
        )
    }

    @Test
    fun pngEmbeddedEntryIsNotBmp() {
        // An entry whose bytes start with the PNG signature is
        // left for BitmapFactory — parseIcoBmp must decline it.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3, 4
        )
        val out = ArrayList<Byte>()
        fun u16(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
        }
        fun u32(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
            out.add(((v shr 16) and 0xFF).toByte())
            out.add(((v shr 24) and 0xFF).toByte())
        }
        u16(0); u16(1); u16(1)
        out.add(16); out.add(16); out.add(0); out.add(0)
        u16(1); u16(32); u32(png.size); u32(6 + 16)
        png.forEach { out.add(it) }
        val bytes = out.toByteArray()
        val entry = extractLargestIcoEntry(bytes)!!
        assertTrue(entry.contentEquals(png))
        assertNull(parseIcoBmp(entry))
    }

    @Test
    fun largestEntryWins() {
        // Two entries: 16x16 (10 bytes of payload) and 32x32
        // (20 bytes). The extractor must return the 32x32 one.
        val out = ArrayList<Byte>()
        fun u16(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
        }
        fun u32(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v shr 8) and 0xFF).toByte())
            out.add(((v shr 16) and 0xFF).toByte())
            out.add(((v shr 24) and 0xFF).toByte())
        }
        u16(0); u16(1); u16(2)
        out.add(16); out.add(16); out.add(0); out.add(0)
        u16(1); u16(32); u32(10); u32(6 + 32)
        out.add(32); out.add(32); out.add(0); out.add(0)
        u16(1); u16(32); u32(20); u32(6 + 32 + 10)
        repeat(10) { out.add(0x11) }
        repeat(20) { out.add(0x22) }
        val entry = extractLargestIcoEntry(out.toByteArray())!!
        assertEquals(20, entry.size)
        assertEquals(0x22.toByte(), entry[0])
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(extractLargestIcoEntry(byteArrayOf()))
        assertNull(extractLargestIcoEntry(byteArrayOf(0, 0, 2, 0, 1, 0)))
        // Truncated: header claims one entry, none present.
        assertNull(extractLargestIcoEntry(byteArrayOf(0, 0, 1, 0, 1, 0)))
        assertNull(parseIcoBmp(byteArrayOf(1, 2, 3)))
    }
}
