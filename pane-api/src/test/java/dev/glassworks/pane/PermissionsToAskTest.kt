/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.Manifest
import dev.glassworks.frame.Capability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #461: at start the app asks only for what the panes that are on need and it declares, and nothing without touch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionsToAskTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("permissions-test", 0)
    private val mic = Manifest.permission.RECORD_AUDIO
    private val cam = Manifest.permission.CAMERA
    private val declared = setOf(mic, cam)

    @After fun tearDown() { file.edit().clear().commit() }

    private fun pane(id: String, needs: String, on: Boolean) = object : PaneDefinition {
        override val id = id
        override val requires = setOf(Capability.CAMERA)
        override val switch = PaneSwitch(file, "${id}_enabled", on, id, id, "${id}_pane")
        override val permissions = setOf(needs)
    }

    private val voice = pane("voice", mic, on = true)
    private val camera = pane("camera", cam, on = false)

    @Test fun aPaneThatIsOffAsksForNothing() {
        assertEquals(listOf(mic), listOf(voice, camera).permissionsToAsk(touch = true, declared) { false })
    }

    @Test fun aPaneThatIsOnAsksOnceWithTouch() {
        camera.switch.on = true
        val twice = pane("camera2", cam, on = true)
        assertEquals(listOf(mic, cam), listOf(voice, camera, twice).permissionsToAsk(touch = true, declared) { false })
    }

    /** The lululemon Mirror: nobody could answer Android's dialog, so Kiln's `pm grant` gives what the app gets. */
    @Test fun withoutTouchNothingIsAsked() {
        camera.switch.on = true
        assertEquals(emptyList<String>(), listOf(voice, camera).permissionsToAsk(touch = false, declared) { false })
    }

    @Test fun onlyWhatTheAppDeclaresAndAndroidHasntGranted() {
        camera.switch.on = true
        assertEquals("not declared", listOf(cam), listOf(voice, camera).permissionsToAsk(touch = true, setOf(cam)) { false })
        assertEquals("granted", listOf(cam), listOf(voice, camera).permissionsToAsk(touch = true, declared) { it == mic })
    }

    @Test fun aPaneThatCantRunAsksForNothing() {
        camera.switch.on = true
        assertEquals(listOf(mic), listOf(voice, CantRun(camera)).permissionsToAsk(touch = true, declared) { false })
    }
}
