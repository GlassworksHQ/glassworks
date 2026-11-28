/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.fonts

import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Font files as Android's `Typeface` loads them: TrueType and OpenType (sfnt) pass through, and WOFF 1.0 unwraps into
 * the sfnt it wraps (W3C WOFF 1.0, section 4–5: a header, a table directory, each table zlib-compressed or stored).
 * WOFF2 returns null: it needs Brotli and the `glyf`/`loca` transforms.
 */
object Sfnt {
    /** The kind of font file [bytes] is, from its first four bytes. */
    enum class Kind { SFNT, WOFF, WOFF2, UNKNOWN }

    fun kind(bytes: ByteArray): Kind {
        if (bytes.size < 4) return Kind.UNKNOWN
        return when (ByteBuffer.wrap(bytes, 0, 4).int) {
            0x00010000, TAG_OTTO, TAG_TRUE, TAG_TTCF -> Kind.SFNT
            TAG_WOFF -> Kind.WOFF
            TAG_WOF2 -> Kind.WOFF2
            else -> Kind.UNKNOWN
        }
    }

    /** [bytes] as an sfnt Android can load, or null when it isn't one (WOFF2, a broken file, an HTML error page). */
    fun toSfnt(bytes: ByteArray, maxBytes: Int = Int.MAX_VALUE): ByteArray? = when (kind(bytes)) {
        Kind.SFNT -> bytes
        Kind.WOFF -> runCatching { unwrapWoff(bytes, maxBytes) }.getOrNull()
        else -> null
    }

    /** WOFF 1.0 to sfnt. Throws on a malformed file; refuses one that would unwrap past [maxBytes]. */
    fun unwrapWoff(woff: ByteArray, maxBytes: Int = Int.MAX_VALUE): ByteArray {
        val src = ByteBuffer.wrap(woff)
        require(woff.size >= WOFF_HEADER && src.getInt(0) == TAG_WOFF) { "not a WOFF file" }
        val flavor = src.getInt(4)
        val numTables = src.getShort(12).toInt() and 0xFFFF
        val totalSfntSize = src.getInt(16).toLong() and 0xFFFFFFFFL
        require(numTables in 1..MAX_TABLES) { "bad table count" }
        require(totalSfntSize in 12L..maxBytes.toLong()) { "sfnt too large" }
        require(WOFF_HEADER + numTables * WOFF_ENTRY <= woff.size) { "truncated directory" }

        class Entry(val tag: Int, val offset: Int, val compLength: Int, val origLength: Int, val checksum: Int)
        val entries = (0 until numTables).map { i ->
            val p = WOFF_HEADER + i * WOFF_ENTRY
            val e = Entry(src.getInt(p), src.getInt(p + 4), src.getInt(p + 8), src.getInt(p + 12), src.getInt(p + 16))
            require(e.offset >= 0 && e.compLength >= 0 && e.origLength >= 0) { "bad table entry" }
            // Table data follows the directory (WOFF 1.0 §5): never inside the header or the directory.
            require(e.offset >= WOFF_HEADER + numTables * WOFF_ENTRY) { "table inside the directory" }
            require(e.offset.toLong() + e.compLength <= woff.size) { "table past the end" }
            require(e.compLength <= e.origLength) { "table grows when stored" }
            e
        }
        // Tables may not share bytes: each starts at or after the end of the one before it in the file.
        val inFileOrder = entries.sortedBy { it.offset }
        inFileOrder.zipWithNext().forEach { (a, b) -> require(b.offset.toLong() >= a.offset.toLong() + a.compLength) { "overlapping tables" } }
        // sfnt header + directory (in tag order, as the WOFF directory is), then each table padded to four bytes, in
        // the WOFF file's data order: the font's original layout, which its head.checkSumAdjustment was made for.
        var size = 12L + 16L * numTables
        for (e in entries) size += (e.origLength + 3L) and 3L.inv()
        require(size <= maxBytes) { "sfnt too large" }
        val out = ByteBuffer.allocate(size.toInt())
        var entrySelector = 0
        while (1 shl (entrySelector + 1) <= numTables) entrySelector++
        val searchRange = (1 shl entrySelector) * 16
        out.putInt(flavor)
        out.putShort(numTables.toShort())
        out.putShort(searchRange.toShort())
        out.putShort(entrySelector.toShort())
        out.putShort((numTables * 16 - searchRange).toShort())
        var dataOffset = 12 + 16 * numTables
        val placedAt = HashMap<Entry, Int>()
        for (e in inFileOrder) {
            placedAt[e] = dataOffset
            val table = if (e.compLength < e.origLength) inflate(woff, e.offset, e.compLength, e.origLength)
            else woff.copyOfRange(e.offset, e.offset + e.origLength)
            out.position(dataOffset)
            out.put(table)
            dataOffset += (e.origLength + 3) and 3.inv()
        }
        for ((i, e) in entries.withIndex()) {
            val dir = 12 + 16 * i
            out.putInt(dir, e.tag)
            out.putInt(dir + 4, e.checksum)
            out.putInt(dir + 8, placedAt.getValue(e))
            out.putInt(dir + 12, e.origLength)
        }
        return out.array()
    }

    private fun inflate(src: ByteArray, offset: Int, length: Int, origLength: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(src, offset, length)
            val out = ByteArray(origLength)
            var n = 0
            while (n < origLength) {
                val r = try {
                    inflater.inflate(out, n, origLength - n)
                } catch (e: DataFormatException) {
                    throw IllegalArgumentException("bad zlib data", e)
                }
                if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            require(n == origLength) { "table inflates to ${n}, not $origLength" }
            // A full buffer isn't the stream's end: it must finish here, with no more output behind it.
            if (!inflater.finished()) {
                val extra = try { inflater.inflate(ByteArray(1)) } catch (e: DataFormatException) { throw IllegalArgumentException("bad zlib data", e) }
                require(extra == 0 && inflater.finished()) { "table inflates past $origLength or doesn't end" }
            }
            return out
        } finally {
            inflater.end()
        }
    }

    private const val WOFF_HEADER = 44
    private const val WOFF_ENTRY = 20
    private const val MAX_TABLES = 512
    private const val TAG_OTTO = 0x4F54544F // 'OTTO'
    private const val TAG_TRUE = 0x74727565 // 'true'
    private const val TAG_TTCF = 0x74746366 // 'ttcf'
    private const val TAG_WOFF = 0x774F4646 // 'wOFF'
    private const val TAG_WOF2 = 0x774F4632 // 'wOF2'
}
