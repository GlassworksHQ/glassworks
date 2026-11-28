package dev.glassworks.discovery

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import dev.glassworks.auth.HttpPoster
import dev.glassworks.esphome.android.EspHomeMdns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NsdManager's constructor waits for its channel to Android's NsdService (211-228 ms of the S1's cold start, on the
 * main thread, in KioskService.onCreate). Neither of its users asks Android for it on the thread that makes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsdOffMainTest {
    /** Records the thread of every NSD_SERVICE lookup, and answers none (the users cope with no mDNS). */
    private class Recording(base: Context) : ContextWrapper(base) {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val asked = CountDownLatch(1)
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name != Context.NSD_SERVICE) return super.getSystemService(name)
            threads += Thread.currentThread().name
            asked.countDown()
            return null
        }
    }

    private val noHttp = object : HttpPoster {
        override fun postForm(url: String, body: String) = -1 to ""
        override fun get(url: String, maxBytes: Int) = -1 to ""
    }

    @Test
    fun homeAssistantDiscoveryGetsNsdManagerOnItsOwnThread() {
        val ctx = Recording(RuntimeEnvironment.getApplication())
        assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
        val d = HaDiscovery(ctx, noHttp) { _, _ -> }
        assertEquals("making it asks for nothing", emptyList<String>(), ctx.threads.toList())
        d.start()
        assertTrue("the browse asks for it", ctx.asked.await(5, TimeUnit.SECONDS))
        d.stop()
        assertEquals(listOf("ha-discovery"), ctx.threads.toList())
    }

    @Test
    fun theEspHomeAdvertiserAsksForNothingUntilItStarts() {
        val ctx = Recording(RuntimeEnvironment.getApplication())
        val mdns = EspHomeMdns(ctx, board = "test")
        mdns.stop() // not advertising: nothing to ask for
        assertEquals(emptyList<String>(), ctx.threads.toList())
    }
}
