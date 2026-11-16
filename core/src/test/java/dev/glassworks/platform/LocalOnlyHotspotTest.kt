/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.Manifest
import android.app.Application
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The local-only hotspot gives back every reservation Android hands it that it doesn't keep: on stop, from a start
 * stop() ended since, and one whose network it can't read. Against a fake of Android's call ([LocalOnlyAp]).
 */
@RunWith(RobolectricTestRunner::class)
class LocalOnlyHotspotTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())
    private val net = HotspotNetwork("Example-Setup", "abcdefghjkmn")

    /** A reservation that counts its closes; [network] answers [read]. */
    private class Res(val read: () -> HotspotNetwork?) : LocalOnlyAp.Reservation {
        var closes = 0
        override fun network() = read()
        override fun close() { closes++ }
    }

    /** Android's call: keeps the listener of each start. */
    private class FakeAp : LocalOnlyAp {
        val listeners = mutableListOf<LocalOnlyAp.Listener>()
        override fun start(listener: LocalOnlyAp.Listener, handler: Handler): Boolean { listeners += listener; return true }
    }

    private val ap = FakeAp()
    private val events = mutableListOf<Hotspot.Event>()
    private val hotspot = LocalOnlyHotspot(app, handler, PlatformApi(33), ap)

    @Before fun allowed() = shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)

    @Test
    fun `stop gives the reservation back`() {
        hotspot.start { events += it }
        val r = Res { net }
        ap.listeners.single().onStarted(r)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(net)), events)
        hotspot.stop()
        assertEquals(1, r.closes)
        hotspot.stop()
        assertEquals("a second stop closes nothing more", 1, r.closes)
    }

    @Test
    fun `a reservation from a start that was stopped since goes back at once, and says nothing`() {
        hotspot.start { events += it }
        val stale = ap.listeners.single()
        hotspot.stop()
        val r = Res { net }
        stale.onStarted(r)
        assertEquals(1, r.closes)
        assertEquals(emptyList<Hotspot.Event>(), events)
    }

    @Test
    fun `a start while a request is still out waits for it - one request, and its reservation is the new start's`() {
        hotspot.start { events += it }
        hotspot.stop()
        val later = mutableListOf<Hotspot.Event>()
        hotspot.start { later += it }
        assertEquals("Android refuses a second pending request, so none is made", 1, ap.listeners.size)
        val r = Res { net }
        ap.listeners.single().onStarted(r)
        assertEquals(0, r.closes)
        assertEquals(emptyList<Hotspot.Event>(), events)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(net)), later)
    }

    @Test
    fun `a network it can't read fails the start and closes the reservation`() {
        for (read in listOf<() -> HotspotNetwork?>({ null }, { throw IllegalStateException("no config") })) {
            events.clear()
            hotspot.start { events += it }
            val r = Res(read)
            ap.listeners.last().onStarted(r)
            assertEquals(1, r.closes)
            assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.FAILED)), events)
        }
    }

    @Test
    fun `Android's own stop and failures reach the caller, as words for the screen`() {
        hotspot.start { events += it }
        ap.listeners.last().onStarted(Res { net })
        ap.listeners.last().onStopped()
        assertEquals(Hotspot.Event.Stopped, events.last())
        for ((reason, why) in listOf(
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL to HotspotFailure.NO_CHANNEL,
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE to HotspotFailure.TETHERING_ON,
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED to HotspotFailure.NOT_ALLOWED,
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC to HotspotFailure.FAILED,
        )) {
            hotspot.start { events += it }
            ap.listeners.last().onFailed(reason)
            assertEquals(Hotspot.Event.Failed(why), events.last())
        }
    }

    @Test
    fun `a device without Wi-Fi is a clear refusal`() {
        val none = LocalOnlyHotspot(app, handler, PlatformApi(33), object : LocalOnlyAp {
            override fun start(listener: LocalOnlyAp.Listener, handler: Handler) = false
        })
        none.start { events += it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.single() == Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED))
    }
}
