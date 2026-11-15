package dev.glassworks.sendspin

import android.app.Application
import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [Compat]'s network callback on both sides of API 24. On API 23 the platform has no
 * `registerDefaultNetworkCallback`, so a call to it would throw NoSuchMethodError there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompatTest {
    private val cm = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
    private val cb = object : ConnectivityManager.NetworkCallback() {}

    @Config(sdk = [23])
    @Test fun belowApi24ItRegistersANetworkRequest() {
        assertFalse(Compat.hasDefaultNetworkCallback)
        assertFalse(Compat.hasEndpointIdentification)
        Compat.registerNetworkCallback(cm, cb)
        assertEquals(setOf(cb), shadowOf(cm).networkCallbacks)
    }

    @Config(sdk = [29])
    @Test fun fromApi24ItRegistersTheDefaultNetworkCallback() {
        assertTrue(Compat.hasDefaultNetworkCallback)
        assertTrue(Compat.hasEndpointIdentification)
        Compat.registerNetworkCallback(cm, cb)
        assertEquals(setOf(cb), shadowOf(cm).networkCallbacks)
    }
}
