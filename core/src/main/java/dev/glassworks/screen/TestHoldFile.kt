/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import dev.glassworks.manage.HaSignIn
import java.io.File
import java.net.InetAddress
import java.net.URI

/**
 * Where a [ScreenPolicy.testHold] outlives the app's process (#240): a test restarts the app, and the
 * new process must not sleep before the test can send the hold again. One line, `<end> <boot>`, and the
 * test's dashboard after them while it has one ([ScreenPolicy.testDashboard]): the end on
 * `elapsedRealtime` in ms, and the boot it was set in (Android's boot count). It isn't a setting: it sits
 * in `noBackupFilesDir`, outside SharedPreferences and backups.
 */
internal class TestHoldFile(private val file: File) {
    data class Saved(val end: Long, val dashboard: String?)

    /**
     * The hold, if it is from this boot, not over, and no further than [maxMs] ahead (a boot count
     * Android doesn't have, -1, can't tell boots apart; a reboot resets elapsedRealtime, so an old end
     * would look far ahead). Anything else is deleted.
     */
    fun read(now: Long, boot: Long, maxMs: Long): Saved? {
        val parts = runCatching { file.readText().trim().split(" ") }.getOrNull().orEmpty()
        val end = parts.getOrNull(0)?.toLongOrNull()
        val ok = parts.size in 2..3 && end != null && parts[1].toLongOrNull() == boot && end > now && end - now <= maxMs
        if (!ok) {
            clear()
            return null
        }
        return Saved(end!!, parts.getOrNull(2)?.takeIf(::isTestDashboard))
    }

    fun write(end: Long, boot: Long, dashboard: String? = null) {
        runCatching { file.writeText(listOfNotNull("$end", "$boot", dashboard).joinToString(" ")) }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        /**
         * A test's dashboard is on this device or the local network: http(s) on a loopback address, where Clarity
         * serves its fake Home Assistant through `adb reverse`, or on a local network IP address (private, link-local
         * or unique local, as [HaSignIn.isLocalAddress] reads one), where Clarity serves it on a device whose
         * `adb reverse` doesn't work (the lululemon Mirror's Android 6 over network adb, `run.sh --ha-transport lan`).
         * An address, never a name, so nothing is resolved; nothing on the internet, so the extra can't point a panel
         * at another site. It lasts only while a test holds the screen ([ScreenPolicy.setTestDashboard]).
         */
        fun isTestDashboard(url: String): Boolean {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            if (u.scheme !in setOf("http", "https") || ' ' in url) return false
            val host = u.host ?: return false
            return host in setOf("127.0.0.1", "localhost", "[::1]") || localAddress(host)
        }

        /**
         * [host] (a URI's: IPv6 in brackets) is an IP address literal on the local network. Never resolves a name.
         * Dotted parts take no leading zero: Java reads `010.0.0.1` as decimal (10.0.0.1, private), but Gecko parses a
         * URL's host by the WHATWG rules, as octal (8.0.0.1, public), so such a literal is refused rather than read.
         */
        private fun localAddress(host: String): Boolean {
            val literal = when {
                IPV4.matches(host) -> host
                host.startsWith("[") && host.endsWith("]") -> host.substring(1, host.length - 1).takeIf(::ipv6Literal)
                else -> null
            } ?: return false
            val a = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            return HaSignIn.isLocalAddress(a)
        }

        /** An IPv6 literal, its embedded IPv4 part (`::ffff:10.0.0.1`), if any, under [IPV4]'s rule too. */
        private fun ipv6Literal(s: String): Boolean =
            IPV6.matches(s) && ('.' !in s || IPV4.matches(s.substringAfterLast(':')))

        /** One part of a dotted IPv4 address, 0 to 255, with no leading zero. */
        private const val OCTET = """(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""
        private val IPV4 = Regex("""$OCTET(\.$OCTET){3}""")
        private val IPV6 = Regex("""[0-9a-fA-F:.]+""")
    }
}
