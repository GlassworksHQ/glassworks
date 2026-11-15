package dev.glassworks.sendspin

/**
 * Base64 (RFC 4648), as `java.util.Base64`'s basic and URL encoders and decoders do it, which Android has
 * only from API 26. Base64Test checks it against `java.util.Base64` on random and malformed input.
 */
internal object Base64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray, url: Boolean = false, pad: Boolean = true): String {
        val alphabet = if (url) URL else STD
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63]).append(alphabet[n shr 6 and 63]).append(alphabet[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].toInt() and 0xFF
                sb.append(alphabet[n shr 2]).append(alphabet[n shl 4 and 63])
                if (pad) sb.append("==")
            }
            2 -> {
                val n = (data[i].toInt() and 0xFF shl 8) or (data[i + 1].toInt() and 0xFF)
                sb.append(alphabet[n shr 10]).append(alphabet[n shr 4 and 63]).append(alphabet[n shl 2 and 63])
                if (pad) sb.append('=')
            }
        }
        return sb.toString()
    }

    /**
     * Decodes [s]; padding is optional, as in `java.util.Base64`. Throws [IllegalArgumentException] for a
     * character outside the alphabet, padding in the wrong place, anything after it, or a lone last character.
     */
    fun decode(s: String, url: Boolean = false): ByteArray {
        val alphabet = if (url) URL else STD
        val out = java.io.ByteArrayOutputStream(s.length / 4 * 3 + 2)
        var acc = 0
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=') {
                if (n < 2 || (n == 2 && (i + 1 >= s.length || s[i + 1] != '='))) throw IllegalArgumentException("Base64: misplaced padding at $i")
                i += if (n == 2) 2 else 1
                if (i < s.length) throw IllegalArgumentException("Base64: input after the padding")
                break
            }
            val v = alphabet.indexOf(c)
            if (v < 0) throw IllegalArgumentException("Base64: illegal character at $i")
            acc = acc shl 6 or v
            if (++n == 4) {
                out.write(acc shr 16); out.write(acc shr 8); out.write(acc)
                acc = 0
                n = 0
            }
            i++
        }
        when (n) {
            1 -> throw IllegalArgumentException("Base64: a lone last character")
            2 -> out.write(acc shr 4)
            3 -> { out.write(acc shr 10); out.write(acc shr 2) }
        }
        return out.toByteArray()
    }
}
