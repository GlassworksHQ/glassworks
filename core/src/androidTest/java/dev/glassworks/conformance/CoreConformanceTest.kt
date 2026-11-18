/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.conformance

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.pane.CantRun
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dev.glassworks.KioskPrefs
import dev.glassworks.MainActivity
import dev.glassworks.device.MdnsAdvertiser
import dev.glassworks.device.PanelDevice
import dev.glassworks.glassworks
import dev.glassworks.glassworksRuntime

/**
 * The core on a real Android, with nothing of the S1 (#23): [ConformanceApp]'s fake frame, fake engine and one
 * pane of its own. CI runs it on emulators at API 23 (the legacy build's floor, #198), 26, 29 and 34
 * (core-emulators.yml).
 */
@RunWith(AndroidJUnit4::class)
class CoreConformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val runtime = app.glassworksRuntime

    @Test fun theAppOwnsTheRuntimeOnItsFrame() {
        assertTrue(app is ConformanceApp)
        assertEquals("conformance", app.glassworks.frame.board)
        assertEquals("Conformance", runtime.build.product)
    }

    /**
     * #198: the core's MainActivity starts, and stands as the frame says, on every Android the core runs on.
     * Ungranted, it opens the system's permission activity over itself, which pauses it (STARTED, not RESUMED)
     * and on the API 34 emulator can crash the emulator (SIGSEGV in qemu).
     */
    @Test fun mainActivityStartsAndStandsAsTheFrameSays() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertEquals("RESUMED", s.state.name)
            s.onActivity { a -> assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, a.requestedOrientation) }
        }
    }

    private fun grant(vararg permissions: String) = permissions.forEach {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} $it"),
        ).use { out -> out.readBytes() }
        assertEquals(it, PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(it))
    }

    @Test fun aPaneTheFrameCantRunIsHidden() {
        val defs = runtime.paneDefinitions(app)
        assertEquals(listOf("speaker-pane", "radio-pane"), defs.map { it.id })
        assertFalse(defs[0] is CantRun)
        assertTrue("no radio on this frame", defs[1] is CantRun)
        assertTrue(runtime.setupSteps(app).none { it.id.startsWith("radio") })
    }

    @Test fun secretsSurviveInAndroidsKeystore() {
        val key = ByteArray(32) { (it * 7).toByte() }
        try {
            assertTrue(KioskPrefs(app).noiseKeyStore.save(key))
            assertArrayEquals("read back by a fresh KioskPrefs", key, KioskPrefs(app).noiseKeyStore.load())
        } finally {
            KioskPrefs(app).noiseKeyStore.save(null)
        }
        assertNull(KioskPrefs(app).noiseKeyStore.load())
    }

    @Test fun theScreenFollowsTheFramesSensors() {
        instrumentation.runOnMainSync {
            runtime.screen.start(app)
            try {
                FakeFrame.presenceSource.onReading!!(true, 0f)
                FakeFrame.lightSource.onLux!!(123f)
                val line = runtime.screen.statusLine()
                assertTrue(line, line.startsWith("presence=near"))
                assertTrue(line, "lux=123" in line)
                FakeFrame.presenceSource.onReading!!(false, 5f)
                assertTrue(runtime.screen.statusLine().startsWith("presence=far"))
            } finally {
                runtime.screen.stop()
            }
        }
        assertNull("stop releases the sensors", FakeFrame.presenceSource.onReading)
    }

    @Test fun theDeviceServesHomeAssistantWithTheFramesPanes() {
        val announced = ArrayList<Pair<DeviceIdentity, Int>>()
        val mdns = object : MdnsAdvertiser {
            override fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) { synchronized(announced) { announced += identity to port } }
            override fun stop() {}
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val exec = Executors.newSingleThreadScheduledExecutor()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = mdns,
            apiPort = 0, apiBindAddress = loopback)   // the server picks a free port and announces it
        try {
            exec.submit { device.startInternal() }.get()
            assertEquals(listOf("speaker-pane"), device.paneList().map { it.id })
            val ids = device.entityList().map { it.objectId }
            assertTrue(ids.toString(), "app_version" in ids && "speaker_state" in ids)
            val (identity, port) = synchronized(announced) { announced.single() }
            assertTrue("announced as its own model", identity.model == "Conformance Tablet (Conformance)")
            assertTrue("announced the port it listens on, got $port", port > 0)
            Socket().use { s ->
                s.connect(InetSocketAddress(loopback, port), 5_000)
                s.soTimeout = 10_000
                // HelloRequest { client_info = "tt" }: the answer starts with a frame indicator (0 plaintext, 1 Noise).
                s.getOutputStream().write(byteArrayOf(0, 4, 1, 0x0a, 2, 't'.code.toByte(), 't'.code.toByte()))
                val first = s.getInputStream().read()
                assertTrue("an ESPHome frame back, got $first", first == 0 || first == 1)
            }
        } finally {
            device.stop()
            exec.shutdownNow()
        }
    }
}
