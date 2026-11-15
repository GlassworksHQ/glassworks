package dev.glassworks.sendspin

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android version differences this library handles, in one file. The library stays standalone, so
 * this is its own copy and does not use the app's `dev.glassworks.platform`. `@ChecksSdkIntAtLeast` lets
 * lint (NewApi) treat each check as a version guard.
 */
internal object Compat {
    /** `ConnectivityManager.registerDefaultNetworkCallback` exists (Android 7). */
    @get:ChecksSdkIntAtLeast(api = 24)
    val hasDefaultNetworkCallback: Boolean get() = Build.VERSION.SDK_INT >= 24

    /** `SSLParameters.setEndpointIdentificationAlgorithm` exists (Android 7). Below it, the default hostname verifier checks. */
    @get:ChecksSdkIntAtLeast(api = 24)
    val hasEndpointIdentification: Boolean get() = Build.VERSION.SDK_INT >= 24

    /**
     * Calls [cb] when a network becomes available: the default network from Android 7, any network with
     * Internet below it.
     */
    fun registerNetworkCallback(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback) {
        if (hasDefaultNetworkCallback) {
            cm.registerDefaultNetworkCallback(cb)
        } else {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        }
    }
}
