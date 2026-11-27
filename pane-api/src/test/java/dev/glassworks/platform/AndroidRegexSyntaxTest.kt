/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fails on a regex literal that the JVM compiles and Android doesn't. Android's `java.util.regex` is ICU's, which
 * rejects a `}` that closes nothing (`^\{\s*}$`), and so does any class initializer holding one, at the app's start.
 * The JVM reads it as a literal `}`, so Robolectric, which runs the JVM's regex, never sees the error. Escape it: `\}`.
 *
 * A source scan over every module's `Regex("…")`, `Regex("""…""")`, `"…".toRegex()` and `Pattern.compile("…")` with a
 * literal pattern. A pattern built at run time isn't seen.
 */
class AndroidRegexSyntaxTest {
    @Test
    fun `no regex literal has a brace that Android rejects`() {
        var seen = 0
        val found = RepoFiles.code(atLeast = 100).flatMap { f ->
            val src = f.readText()
            LITERAL.findAll(src).mapNotNull { m ->
                val raw = m.groups[1] ?: m.groups[3]
                val text = raw?.value ?: (m.groups[2] ?: m.groups[4])!!.value
                if (INTERPOLATED.containsMatchIn(text)) return@mapNotNull null
                seen++
                val pattern = if (raw != null) text else unescape(text)
                if (strayBrace(pattern) < 0) null else "${RepoFiles.rel(f)}:${src.substring(0, m.range.first).count { it == '\n' } + 1}: $pattern"
            }.toList()
        }
        assertTrue("the scan saw only $seen regex literals", seen >= 100)
        assertTrue("a '}' (or '{') ICU rejects; escape it as \\} (\\{):\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the check tells a stray brace from a quantifier, a class and a property`() {
        assertEquals(6, strayBrace("""^\{\s*}\s*$"""))
        assertEquals(1, strayBrace("""a{b"""))
        assertEquals(-1, strayBrace("""^\{\s*\}\s*$"""))
        assertEquals(-1, strayBrace("""a{2}b{1,}c{0,3}"""))
        assertEquals(-1, strayBrace("""[^\s\[\]{},]+[}{]"""))
        assertEquals(-1, strayBrace("""\p{M}+\P{L}\x{41}"""))
    }

    private companion object {
        /** A literal pattern: Regex("""…"""), Regex("…"), "…".toRegex() or Pattern.compile("…"). */
        val LITERAL = Regex(
            """(?:Regex|Pattern\.compile)\(\s*(?:"{3}(.*?)"{3}|"((?:[^"\\\n]|\\.)*)")|""" +
                """(?:"{3}(.*?)"{3}|"((?:[^"\\\n]|\\.)*)")\.toRegex\(""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** A string template, `${…}` or `$name`: its pattern is only known at run time. An escaped `\$` isn't one. */
        val INTERPOLATED = Regex("""(?<!\\)\$[{A-Za-z_]""")

        /** A Kotlin "…" literal's text: `\\` is `\`, `\"` is `"`. */
        fun unescape(s: String): String = Regex("""\\(.)""").replace(s) { m ->
            when (val c = m.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c }
        }

        /** Where [p] has a brace that is neither escaped, in a class, part of `{n}`, `{n,}`, `{n,m}`, nor `\p{…}`; or -1. */
        fun strayBrace(p: String): Int {
            var i = 0
            var depth = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> {
                        i += if (i + 2 < p.length && p[i + 1] in "pPxN" && p[i + 2] == '{') p.indexOf('}', i).let { if (it < 0) p.length else it - i + 1 } else 2
                        continue
                    }
                    depth > 0 -> if (c == '[') depth++ else if (c == ']') depth--
                    c == '[' -> {
                        depth = 1
                        if (p.getOrNull(i + 1) == '^') i++
                        if (p.getOrNull(i + 1) == ']') i++
                    }
                    c == '{' -> {
                        val q = QUANTIFIER.matchAt(p, i) ?: return i
                        i += q.value.length
                        continue
                    }
                    c == '}' -> return i
                }
                i++
            }
            return -1
        }

        val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")
    }
}
