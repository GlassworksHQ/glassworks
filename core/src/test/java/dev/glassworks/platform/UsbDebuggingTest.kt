/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UsbDebuggingHeardTest {
    @Test fun `a computer asking, in the log's brief and threadtime formats`() {
        assertEquals(UsbDebugging.Heard.Asked("QUFB alex@laptop"),
            UsbDebugging.heard("D/UsbDebuggingManager(  873): Received public key: QUFB alex@laptop"))
        assertEquals(UsbDebugging.Heard.Asked("QUFB alex@laptop"),
            UsbDebugging.heard("11-17 09:41:07.123   873  1002 D UsbDebuggingManager: Received public key: QUFB alex@laptop"))
    }

    @Test fun `the image letting its owner in, and lines about neither`() {
        assertEquals(UsbDebugging.Heard.LetInByImage, UsbDebugging.heard("I/UsbDebuggingManager(  873): Authorizing the Mirror owner's ADB key"))
        assertNull(UsbDebugging.heard("E/UsbDebuggingManager(  873): Fingerprints do not match. Got AA, expected BB"))
        assertNull(UsbDebugging.heard("--------- beginning of system"))
    }

    private class FakeProcess(text: String) : Process() {
        private val input = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() {}
    }

    @Test fun `each request reaches the app, and the log is followed again when logcat ends`() {
        val heard = ArrayList<UsbDebugging.Heard>()
        val twice = CountDownLatch(2)
        var starts = 0
        val r = UsbDebugging.Requests(onHeard = { synchronized(heard) { heard += it }; twice.countDown() }, retryMs = 10, logcat = {
            starts++
            FakeProcess("--------- beginning of system\nD/UsbDebuggingManager(  873): Received public key: QUFB alex@laptop\n")
        })
        r.start()
        assertTrue(twice.await(5, TimeUnit.SECONDS))
        r.stop()
        assertTrue(starts >= 2)
        assertEquals(UsbDebugging.Heard.Asked("QUFB alex@laptop"), synchronized(heard) { heard.first() })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class UsbDebuggingAvailableTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `only an app holding MANAGE_USB and READ_LOGS answers`() {
        assertFalse(UsbDebugging.available(app))
        shadowOf(app).grantPermissions("android.permission.MANAGE_USB")
        assertFalse("can't see who asks", UsbDebugging.available(app))
        shadowOf(app).grantPermissions("android.permission.READ_LOGS")
        assertTrue(UsbDebugging.available(app))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UsbDebuggingAndroid11Test {
    @Test fun `Android 11 moved it, so the app doesn't offer it`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("android.permission.MANAGE_USB", "android.permission.READ_LOGS")
        assertFalse(UsbDebugging.available(app))
    }
}
