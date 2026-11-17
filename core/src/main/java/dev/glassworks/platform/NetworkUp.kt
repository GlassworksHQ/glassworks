/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the panel is on a network now: Wi-Fi or Ethernet, with or without internet. The panel's own setup
 * hotspot doesn't count: Android lists it as no network. Rule 2 of the setup hotspot (#197), and the setup
 * channel's `online`.
 */
object NetworkUp {
    fun now(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // allNetworks: the active network can be another one while Wi-Fi is up too
        (listOfNotNull(cm.activeNetwork) + cm.allNetworks).any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    }.getOrDefault(false)
}
