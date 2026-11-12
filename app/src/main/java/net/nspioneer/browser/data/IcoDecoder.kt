package net.nspioneer.browser.data

/**
 * Batch 3: minimal ICO decoding for fetched favicons.
 *
 * GeckoView supplies no favicon path (Bug 1770989, still open),
 * so Pioneer fetches `https://<host>/favicon.ico` the way
 * Mariner's `loadEngineFavicon` does — but most servers answer
 * with a real ICO container, which `BitmapFactory` cannot decode
 * (it returns null). This file decodes the container itself.
 *
 * Pure Kotlin, no Android classes: the byte-level work is
 * unit-testable on the JVM ([IcoDecoderTest]); the caller turns
 * the decoded pixels into a Bitmap.
 *
 * Supported: ICO (type 1) with PNG-embedded entries (handed back
 * raw for `BitmapFactory`) and uncompressed 32bpp / 24bpp BMP
 * entries. The largest entry wins; anything else returns null
 * and the caller falls back to Mariner's generic icon.
 */

/** One decoded BMP entry: row-major ARGB pixels, top row first. */
data class DecodedIco(val width: Int, val height: Int, val pixels: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedIco) return false
        return width == other.width && height == other.height &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

private fun u16(bytes: ByteArray, off: Int): Int =
    (bytes[off].toInt() and 0xFF) or
        ((bytes[off + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, off: Int): Long =
    (bytes[off].toLong() and 0xFF) or
        ((bytes[off + 1].toLong() and 0xFF) shl 8) or
        ((bytes[off + 2].toLong() and 0xFF) shl 16) or
        ((bytes[off + 3].toLong() and 0xFF) shl 24)

/** The raw image bytes of the largest entry in an ICO container,
 *  or null when [bytes] is not an ICO file. A PNG-embedded entry
 *  comes back as its PNG bytes (signature check is the caller's). */
fun extractLargestIcoEntry(bytes: ByteArray): ByteArray? {
    if (bytes.size < 6) return null
    if (u16(bytes, 0) != 0 || u16(bytes, 2) != 1) return null
    val count = u16(bytes, 4)
    if (count <= 0 || bytes.size < 6 + count * 16) return null
    var best: ByteArray? = null
    var bestArea = -1
    for (i in 0 until count) {
        val e = 6 + i * 16
        val w = bytes[e].toInt() and 0xFF
        val h = bytes[e + 1].toInt() and 0xFF
        val size = u32(bytes, e + 8).toInt()
        val offset = u32(bytes, e + 12).toInt()
        if (size <= 0 || offset < 0 || offset + size > bytes.size) continue
        val area = (if (w == 0) 256 else w) * (if (h == 0) 256 else h)
        if (area > bestArea) {
            bestArea = area
            best = bytes.copyOfRange(offset, offset + size)
        }
    }
    return best
}

/** Decode one BMP entry (a BITMAPINFOHEADER + pixel rows + AND
 *  mask) into ARGB pixels. Returns null for PNG data, compressed
 *  entries, or bit depths other than 32/24. */
fun parseIcoBmp(entry: ByteArray): DecodedIco? {
    if (entry.size < 40) return null
    // PNG-embedded entries are not BMP: let BitmapFactory have them.
    if (entry.size >= 8 &&
        entry[0] == 0x89.toByte() && entry[1] == 0x50.toByte() &&
        entry[2] == 0x4E.toByte() && entry[3] == 0x47.toByte()
    ) return null
    val headerSize = u32(entry, 0).toInt()
    if (headerSize < 40 || entry.size < headerSize) return null
    val width = u32(entry, 4).toInt()
    val rawHeight = u32(entry, 8).toInt()
    // ICO stores the height doubled (image + AND mask), and a
    // negative height means top-down rows.
    val topDown = rawHeight < 0
    val height = kotlin.math.abs(rawHeight) / 2
    if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null
    val bitCount = u16(entry, 14)
    if (bitCount != 32 && bitCount != 24) return null
    if (u32(entry, 16) != 0L) return null // BI_RGB only
    val bytesPerPixel = bitCount / 8
    // Each row is padded to a 4-byte boundary.
    val rowBytes = ((width * bitCount + 31) / 32) * 4
    val pixelsStart = headerSize
    if (entry.size < pixelsStart + rowBytes * height) return null
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val srcRow = if (topDown) y else height - 1 - y
        val rowOff = pixelsStart + srcRow * rowBytes
        for (x in 0 until width) {
            val off = rowOff + x * bytesPerPixel
            val b = entry[off].toInt() and 0xFF
            val g = entry[off + 1].toInt() and 0xFF
            val r = entry[off + 2].toInt() and 0xFF
            val a = if (bitCount == 32) entry[off + 3].toInt() and 0xFF else 0xFF
            // Many 32bpp icons store a zero alpha channel with
            // opaque pixels; treat an all-zero alpha plane as opaque.
            pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    if (bitCount == 32) {
        var anyAlpha = false
        for (p in pixels) if ((p ushr 24) != 0) {
            anyAlpha = true
            break
        }
        if (!anyAlpha) {
            for (i in pixels.indices) pixels[i] = pixels[i] or -0x1000000
        }
    }
    return DecodedIco(width, height, pixels)
}
