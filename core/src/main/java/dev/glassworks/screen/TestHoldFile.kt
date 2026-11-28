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

        /** [host] (a URI's: IPv6 in brackets) is an IP address literal on the local network. Never resolves a name. */
        private fun localAddress(host: String): Boolean {
            val ipv4 = IPV4.matches(host)
            val ipv6 = host.startsWith("[") && host.endsWith("]") && IPV6.matches(host.substring(1, host.length - 1))
            if (!ipv4 && !ipv6) return false
            val a = runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull() ?: return false
            return HaSignIn.isLocalAddress(a)
        }

        private val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")
        private val IPV6 = Regex("""[0-9a-fA-F:.]+""")
    }
}
