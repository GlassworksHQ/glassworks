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
            f.readLines().mapIndexedNotNull { i, line ->
                if (LOG_CALL.containsMatchIn(line) && NAME.containsMatchIn(line)) "${RepoFiles.rel(f)}:${i + 1}: ${line.trim()}" else null
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

    private companion object {
        val LOG_CALL = Regex("""\b(Log\.[vdiwe]|[A-Za-z]*Log\.[a-z]|log|event)\(""")

        /** A template that prints a name: `$ssid`, `${o.ssid}`, `$prevSsid`, `${x.passphrase}`, `${o.restored}`. Not `.length`. */
        val NAME = Regex("""\$(\{[\w.]*\b\w*([sS]sid|SSID|passphrase|restored)}|\w*([sS]sid|SSID|passphrase|restored)\b)""")
    }
}
