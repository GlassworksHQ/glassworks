/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No log line prints a Wi-Fi network's name or key (AGENTS.md rule 2, #460): no log call in any module's sources puts
 * an `ssid`, a hotspot `passphrase` or the network a failed join `restored` into its text. The S1's logs printed the
 * name the person picked (`installer Wi-Fi: joining '…'`). The behaviour tests (WifiProvisionerLogTest, the Improv
 * session's, SetupChannelTest's and SetupHotspotTest's) read real log lines; this keeps new ones from adding it back.
 */
class NoNetworkNameInLogsTest {
    @Test
    fun noLogCallInterpolatesANetworkName() {
        val found = RepoFiles.sources(atLeast = 300) { it.extension == "kt" }.flatMap { f ->
            val lines = f.readLines()
            lines.indices.mapNotNull { i ->
                val call = call(lines, i) ?: return@mapNotNull null
                if (NAME.containsMatchIn(call)) "${RepoFiles.rel(f)}:${i + 1}: ${call.trim()}" else null
            }
        }
        assertEquals(found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun theScanCatchesTheLinesTheS1Printed() {
        val printed = listOf(
            """Log.i(TAG, "installer Wi-Fi: joining '${'$'}ssid'")""",
            """Log.i(TAG, "connect '${'$'}ssid' (${'$'}{password.length} char password); ${'$'}{status()}")""",
            """Log.i(TAG, "connected to '${'$'}{o.ssid}' via ${'$'}{o.method}")""",
            """log("hotspot ${'$'}{network.passphrase}")""",
            """Log.w(TAG, "failed. Back on ${'$'}{o.restored}")""",
        )
        assertTrue(printed.all { LOG_CALL.containsMatchIn(it) && NAME.containsMatchIn(it) })
        assertTrue("a length is fine", !NAME.containsMatchIn("""Log.i(TAG, "connect (${'$'}{ssid.length} char name)")"""))
    }

    @Test
    fun `a call wrapped over several lines is read whole`() {
        val wrapped = listOf("""Log.i(""", """    TAG,""", """    "joining '${'$'}ssid'",""", """)""", """val after = ssid""")
        assertEquals("""Log.i( TAG, "joining '${'$'}ssid'", )""", call(wrapped, 0)!!.replace(Regex("""\s+"""), " ").trim())
        assertTrue(NAME.containsMatchIn(call(wrapped, 0)!!))
    }

    private companion object {
        /**
         * The log call that starts on line [i] of [lines], up to its closing parenthesis (at most [MAX_LINES] lines), or null
         * when none starts there. Parentheses inside strings count too: good enough for log lines, which balance.
         */
        fun call(lines: List<String>, i: Int): String? {
            val m = LOG_CALL.find(lines[i]) ?: return null
            val text = StringBuilder()
            var depth = 0
            for (j in i until minOf(lines.size, i + MAX_LINES)) {
                val part = if (j == i) lines[j].substring(m.range.first) else lines[j]
                text.append(part).append(' ')
                depth += part.count { it == '(' } - part.count { it == ')' }
                if (depth <= 0) break
            }
            return text.toString()
        }

        const val MAX_LINES = 8

        val LOG_CALL = Regex("""\b(Log\.[vdiwe]|[A-Za-z]*Log\.[a-z]|log|event)\(""")

        /** A template that prints a name: `$ssid`, `${o.ssid}`, `$prevSsid`, `${x.passphrase}`, `${o.restored}`. Not `.length`. */
        val NAME = Regex("""\$(\{[\w.]*\b\w*([sS]sid|SSID|passphrase|restored)}|\w*([sS]sid|SSID|passphrase|restored)\b)""")
    }
}
