/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.provider.Settings
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
        // image 3 (2026-11-22, read from its services.jar): a key listed in adb_keys, let in the same way
        assertEquals(UsbDebugging.Heard.LetInByImage, UsbDebugging.heard("I/UsbDebuggingManager(  873): Authorizing an ADB key listed in adb_keys"))
        assertNull(UsbDebugging.heard("I/UsbDebuggingManager(  873): Entering thread"))
        assertNull(UsbDebugging.heard("--------- beginning of system"))
    }

    private val now = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 18, 21, 24, 30) }.timeInMillis
    private fun at(secondsAgo: Int): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(now - secondsAgo * 1000L))

    @Test fun `a computer that asked just before the reader started is still the one waiting`() {
        val lines = listOf(
            "--------- beginning of system",
            "${at(300)} D/UsbDebuggingManager(  873): Received public key: OLD old@host",
            "${at(290)} I/UsbDebuggingManager(  873): Authorizing the Mirror owner's ADB key",
            "${at(10)} D/UsbDebuggingManager(  873): Received public key: QUFB alex@laptop",
        )
        assertEquals(UsbDebugging.Heard.Asked("QUFB alex@laptop"), UsbDebugging.pendingRequest(lines, now, 60_000))
    }

    @Test fun `an old request, or one the image answered, isn't replayed`() {
        val old = listOf("${at(120)} D/UsbDebuggingManager(  873): Received public key: QUFB alex@laptop")
        assertNull("older than the window", UsbDebugging.pendingRequest(old, now, 60_000))
        val owner = listOf(
            "${at(10)} D/UsbDebuggingManager(  873): Received public key: QUFB owner@host",
            "${at(9)} I/UsbDebuggingManager(  873): Authorizing the Mirror owner's ADB key",
        )
        assertNull("the image let it in", UsbDebugging.pendingRequest(owner, now, 60_000))
        assertNull("no time on the line", UsbDebugging.pendingRequest(listOf("D/UsbDebuggingManager(  873): Received public key: QUFB a@b"), now, 60_000))
    }

    @Test fun `the reader passes on the waiting request first, then follows the log`() {
        val heard = ArrayList<UsbDebugging.Heard>()
        val two = CountDownLatch(2)
        val r = UsbDebugging.Requests(onHeard = { synchronized(heard) { heard += it }; two.countDown() }, retryMs = 10_000, clock = { now },
            history = { FakeProcess("${at(5)} D/UsbDebuggingManager(  873): Received public key: QUFB waiting@host\n") },
            logcat = { FakeProcess("D/UsbDebuggingManager(  873): Received public key: QUJD next@host\n") })
        r.start()
        assertTrue(two.await(5, TimeUnit.SECONDS))
        r.stop()
        assertEquals(listOf(UsbDebugging.Heard.Asked("QUFB waiting@host"), UsbDebugging.Heard.Asked("QUJD next@host")), synchronized(heard) { heard.toList() })
    }

    @Test fun `Android's own question coming up on the screen`() {
        assertEquals(UsbDebugging.Heard.QuestionShown, UsbDebugging.heard(
            "11-17 20:47:13.343  1218  3801 I ActivityManager: START u0 {flg=0x10000000 cmp=com.android.systemui/.usb.UsbDebuggingActivity (has extras)} from uid 1000 on display 0"))
        assertEquals(UsbDebugging.Heard.QuestionShown, UsbDebugging.heard(
            "I/ActivityManager(  873): START u0 {flg=0x10000000 cmp=com.android.systemui/.usb.UsbDebuggingSecondaryUserActivity} from uid 1000"))
        assertNull(UsbDebugging.heard("I/ActivityManager(  873): START u0 {act=android.intent.action.MAIN cmp=com.android.settings/.Settings} from uid 2000"))
    }

    @Test fun `Android refusing an approval, with the computer it waits on or none`() {
        val got = "AF:0F:9E:EB:A4:B4:AF:75:6D:1F:86:23:46:4D:36:D2"
        val waiting = "6C:F6:AF:69:38:B4:24:7B:F0:27:6D:5B:F6:A1:ED:7C"
        assertEquals(UsbDebugging.Heard.Refused(got, waiting),
            UsbDebugging.heard("E/UsbDebuggingManager(  873): Fingerprints do not match. Got $got, expected $waiting"))
        assertEquals(UsbDebugging.Heard.Refused(got, null),
            UsbDebugging.heard("E/UsbDebuggingManager(  873): Fingerprints do not match. Got $got, expected null"))
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

    /** Only what stop() clears. adopt() sets following under the same lock as the generation check; the next test covers a stop() before it. */
    @Test fun `following is true while a reader reads, and stop clears it`() {
        val out = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(out)
        val proc = object : Process() {
            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { out.close() }
        }
        val r = UsbDebugging.Requests(onHeard = {}, retryMs = 10_000, logcat = { proc }, history = { FakeProcess("") })
        r.start()
        val until = System.nanoTime() + 5_000_000_000L
        while (!r.following && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(r.following)
        r.stop()
        assertFalse(r.following)
    }

    @Test fun `a stop before the reader adopts its process leaves following false`() {
        lateinit var r: UsbDebugging.Requests
        val stopped = CountDownLatch(1)
        val done = CountDownLatch(1)
        r = UsbDebugging.Requests(onHeard = {}, onError = {}, retryMs = 10, history = { FakeProcess("") }, logcat = {
            r.stop()
            stopped.countDown()
            object : Process() {
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() { done.countDown() }
            }
        })
        r.start()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertTrue("the reader drops the process it didn't adopt", done.await(5, TimeUnit.SECONDS))
        assertFalse(r.following)
    }

    @Test fun `after stop, lines a reader had already read never reach the app`() {
        val heard = ArrayList<UsbDebugging.Heard>()
        val first = CountDownLatch(1)
        val lines = (1..200).joinToString("") { "D/UsbDebuggingManager(  873): Received public key: QUFB n$it@host\n" }
        lateinit var r: UsbDebugging.Requests
        r = UsbDebugging.Requests(onHeard = { synchronized(heard) { heard += it }; r.stop(); first.countDown() }, retryMs = 10, logcat = { FakeProcess(lines) }, history = { FakeProcess("") })
        r.start()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, synchronized(heard) { heard.size })
        assertFalse(r.following)
    }

    @Test fun `each request reaches the app, and the log is followed again when logcat ends`() {
        val heard = ArrayList<UsbDebugging.Heard>()
        val twice = CountDownLatch(2)
        var starts = 0
        val r = UsbDebugging.Requests(onHeard = { synchronized(heard) { heard += it }; twice.countDown() }, retryMs = 10, history = { FakeProcess("") }, logcat = {
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

    @Test fun `an app adb installed answers when the installer opened allowUsbDebugging to a permission it holds`() {
        shadowOf(app).grantPermissions("android.permission.READ_LOGS", "android.permission.WRITE_SECURE_SETTINGS")
        assertFalse("no installer said so: stock checks MANAGE_USB", UsbDebugging.available(app))
        Settings.Global.putString(app.contentResolver, UsbDebugging.ALLOW_PERMISSION_SETTING, "android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(UsbDebugging.available(app))
    }

    @Test fun `the named permission must be held, and READ_LOGS too`() {
        Settings.Global.putString(app.contentResolver, UsbDebugging.ALLOW_PERMISSION_SETTING, "android.permission.WRITE_SECURE_SETTINGS")
        shadowOf(app).grantPermissions("android.permission.READ_LOGS")
        assertFalse("not granted", UsbDebugging.available(app))
        shadowOf(app).denyPermissions("android.permission.READ_LOGS")
        shadowOf(app).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        assertFalse("can't see who asks", UsbDebugging.available(app))
    }

    @Test fun `a setting that doesn't name an Android permission counts for nothing`() {
        shadowOf(app).grantPermissions("android.permission.READ_LOGS", "android.permission.WRITE_SECURE_SETTINGS")
        Settings.Global.putString(app.contentResolver, UsbDebugging.ALLOW_PERMISSION_SETTING, "yes")
        assertFalse(UsbDebugging.available(app))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class UsbDebuggingAndroid10Test {
    @Test fun `Android 10 moved it, so the app doesn't offer it`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("android.permission.MANAGE_USB", "android.permission.READ_LOGS")
        assertFalse(UsbDebugging.available(app))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UsbDebuggingAndroid11Test {
    @Test fun `nor any later Android`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("android.permission.MANAGE_USB", "android.permission.READ_LOGS")
        assertFalse(UsbDebugging.available(app))
    }
}
