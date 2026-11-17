/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import java.net.NetworkInterface

/**
 * The names the panel's web port answers to ([ManageServer.hostAllowed]): its IP addresses now, `localhost`,
 * its ESPHome name ([nodeName]) and its Android host name, as `.local`. The same list as the S1's Manage page
 * builds, which can move here once #364 has landed.
 */
object PanelHosts {
    fun now(nodeName: String?, warn: (String, Throwable) -> Unit = { _, _ -> }): Set<String> {
        val out = hashSetOf("localhost")
        nodeName?.let { out += "$it.local" }
        runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) for (a in i.inetAddresses.toList()) {
                a.hostAddress?.let { out += it.substringBefore('%').lowercase() }
            }
        }.onFailure { warn("can't list the panel's addresses", it) }
        hostname().lowercase().takeIf { it.isNotEmpty() }?.let { out += it; out += "$it.local" }
        return out
    }

    /**
     * [compute], kept for [ttlMs]: the panel's names are listed at most that often, not on every request. A new
     * address (another network) shows within [ttlMs].
     */
    fun cached(ttlMs: Long = 5_000, monoMs: () -> Long = { System.nanoTime() / 1_000_000 }, compute: () -> Set<String>): () -> Set<String> {
        val lock = Any()
        var at = 0L
        var hosts: Set<String>? = null
        return {
            synchronized(lock) {
                val now = monoMs()
                hosts?.takeIf { now - at < ttlMs } ?: compute().also { hosts = it; at = now }
            }
        }
    }

    private fun hostname(): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, "net.hostname", "") as String
    }.getOrDefault("")
}
