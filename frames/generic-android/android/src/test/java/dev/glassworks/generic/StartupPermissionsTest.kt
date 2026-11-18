/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import dev.glassworks.camera.CameraPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInputDevice
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.glassworks
import dev.glassworks.assist.VoicePrefs

/**
 * #461 and #463: the real MainActivity under GenericApp asks Android at start only for what the panes that are on need,
 * and never on a screen without touch (the lululemon Mirror), where nobody could answer: Kiln grants there. A device
 * with a microphone, a speaker and a camera; nothing granted yet. Voice is on by default, the camera off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = GenericApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class StartupPermissionsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun aTabletWithACamera() {
        val pm = shadowOf(app.packageManager)
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_CAMERA_ANY)) pm.setSystemFeature(f, true)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }

    private fun cameraOn() = app.glassworks.paneFile(app, "camera").edit().putBoolean(CameraPrefs.KEY_ENABLED, true).commit()

    private fun asked(block: (GeckoPinHarness) -> Unit = {}): List<String>? = GeckoPinHarness().use { h ->
        block(h)
        shadowOf(h.activity).lastRequestedPermission?.requestedPermissions?.toList()
    }

    @Test fun theCameraPaneOffAsksForTheMicrophoneOnly() {
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked())
    }

    @Test fun theCameraPaneOnAsksForBothOnce() {
        cameraOn()
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), asked())
    }

    /** CodeRabbit on #463: Voice off at start, then on from Home Assistant: the next setup tick asks, once. */
    @Test fun aPaneTurnedOnLaterAsksOnce() {
        app.glassworks.paneFile(app, "assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        GeckoPinHarness().use { h ->
            assertNull("Voice off: nothing at start", shadowOf(h.activity).lastRequestedPermission)
            app.glassworks.paneFile(app, "assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, true).commit()
            h.advance(2_500)
            val asked = shadowOf(h.activity).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked?.requestedPermissions?.toList())
            refuse(h, Manifest.permission.RECORD_AUDIO)
            h.advance(6_000)
            assertSame("not asked again in this run", asked, shadowOf(h.activity).lastRequestedPermission)
        }
    }

    /** Verifier on #463: turned on while another screen is in front (Android's settings), it asks once the panel is back. */
    @Test fun aStoppedActivityAsksWhenItIsBack() {
        app.glassworks.paneFile(app, "assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        GeckoPinHarness().use { h ->
            h.controller.pause().stop()
            app.glassworks.paneFile(app, "assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, true).commit()
            h.advance(6_000)
            assertNull("not over another screen", shadowOf(h.activity).lastRequestedPermission)
            h.controller.start().resume()
            h.idle()
            assertEquals(listOf(Manifest.permission.RECORD_AUDIO), shadowOf(h.activity).lastRequestedPermission?.requestedPermissions?.toList())
        }
    }

    /** Android cancels a request when another is open (an empty answer): that isn't an answer, so it asks again. */
    @Test fun aCancelledAskIsAskedAgain() = GeckoPinHarness().use { h ->
        val first = shadowOf(h.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), first?.requestedPermissions?.toList())
        h.advance(2_500)
        assertSame("one request at a time", first, shadowOf(h.activity).lastRequestedPermission)
        h.permissionDialogAnswered()
        h.activity.onRequestPermissionsResult(1, emptyArray(), IntArray(0))
        h.advance(2_500)
        val again = shadowOf(h.activity).lastRequestedPermission
        assertNotSame(first, again)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), again?.requestedPermissions?.toList())
    }

    /** The person says no: Android's dialog closes with a refusal. */
    private fun refuse(h: GeckoPinHarness, permission: String) {
        h.permissionDialogAnswered()
        h.activity.onRequestPermissionsResult(1, arrayOf(permission), intArrayOf(PackageManager.PERMISSION_DENIED))
    }

    @Test fun withoutTouchAndroidIsNeverAsked() {
        cameraOn()
        shadowOf(app.getSystemService(InputManager::class.java)).addInputDevice(ShadowInputDevice.makeInputDeviceNamed("gpio-keys"))
        assertNull("no dialog nobody could answer", asked())
    }
}
