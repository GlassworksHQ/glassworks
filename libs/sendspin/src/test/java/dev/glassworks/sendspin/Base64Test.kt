package dev.glassworks.sendspin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The library's [Base64] gives what `java.util.Base64` gives (the player used it before minSdk 23, #152). */
class Base64Test {
    private val rnd = Random(152)

    @Test fun encodesAsJavaDoes() {
        repeat(2_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 70))
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), Base64.encode(b))
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b), Base64.encode(b, url = true, pad = false))
        }
    }

    @Test fun decodesWhatItEncodes() {
        repeat(2_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 70))
            assertArrayEquals(b, Base64.decode(Base64.encode(b)))
            assertArrayEquals(b, Base64.decode(Base64.encode(b, url = true, pad = false), url = true))
        }
    }

    /** Short strings over the alphabet, padding and a few strangers: the same bytes, or both refuse. */
    @Test fun decodesAndRefusesAsJavaDoes() {
        val chars = "AQgw+/-_=\n .é"
        for (url in listOf(false, true)) {
            val java = if (url) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
            repeat(50_000) {
                val s = String(CharArray(rnd.nextInt(0, 10)) { chars[rnd.nextInt(chars.length)] })
                val want = runCatching { java.decode(s) }
                val got = runCatching { Base64.decode(s, url) }
                assertEquals("'$s' url=$url: refused", want.isFailure, got.isFailure)
                if (want.isSuccess) assertArrayEquals("'$s' url=$url", want.getOrThrow(), got.getOrThrow())
                if (got.isFailure) assertEquals(IllegalArgumentException::class.java, got.exceptionOrNull()!!::class.java)
            }
        }
    }
}
