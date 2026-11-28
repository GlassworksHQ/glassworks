/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.esphome.BinarySensorEntity
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.EntityCategory
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import dev.glassworks.esphome.SwitchEntity
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraPaneTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = app.getSharedPreferences("camera-test", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadScheduledExecutor()

    @After fun tearDown() { exec.shutdownNow(); file.edit().clear().commit() }

    private class Camera : FrameSource {
        var sink: FrameSink? = null
        var opens = 0
        var closes = 0
        override fun open(fps: Float, sink: FrameSink): AutoCloseable {
            opens++; this.sink = sink
            return AutoCloseable { closes++; this.sink = null }
        }
    }

    private class Stream : StreamOutput {
        var running: Pair<String, String>? = null
        var starts = 0
        val motion = mutableListOf<Boolean>()
        var refuse = false
        var fit: StreamFit = StreamFit.Size(1280, 720)
        override fun fit() = fit
        override fun start(signIn: Pair<String, String>): Boolean {
            if (refuse) return false
            starts++; running = signIn; return true
        }
        override fun stop() { running = null }
        override fun setMotion(active: Boolean) { motion += active }
    }

    private inner class Rig(permitted: Boolean = true, ownSensor: Boolean = false, facts: CameraFacts? = null) {
        val prefs = CameraPrefs(file)
        val camera = Camera()
        val stream = Stream()
        val screen = mutableListOf<Pair<Boolean, Float>>()
        var allowed = permitted
        var publishes = 0
        var releases = 0
        var now = 0L
        val pane = CameraPane(
            PaneContext(
                app, prefs = { file },
                DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
                publishStates = { publishes++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
                build = AppBuild("1.0.0", 10000, debug = false),
            ),
            prefs, camera, { byteArrayOf(1) }, stream,
            FrameEgress({ now }, { _, _ -> }) {},
            permitted = { allowed }, ownSensor = { ownSensor }, toScreen = { n, r -> screen += n to r }, exec = exec,
            release = { releases++ },
            facts = facts,
            presenceFactory = { source, onMotion, onProblem ->
                CameraPresence(source, { prefs.sensitivity }, clock = { now }, post = { it.run() }, after = { _, _ -> },
                    onProblem = onProblem, onMotion = onMotion)
            },
        )

        fun changed(key: String) = pane.onEvent(PanelEvent.SettingChanged(key))

        /** Someone walks in: a seed frame, then four with a person-sized change. */
        fun someoneWalksIn() {
            repeat(5) { i ->
                now += 200
                camera.sink!!.frame(LumaFrame(96, 54, 96, ByteArray(96 * 54) { p -> if (i > 0 && p % 96 in (i * 8) until (i * 8 + 16)) 120 else 60 }))
            }
        }
    }

    @Test fun presenceOnlyWatchesForPeopleAndFeedsTheScreen() {
        val r = Rig()
        r.pane.start()
        assertEquals(1, r.camera.opens)
        assertNull(r.stream.running)
        r.someoneWalksIn()
        assertEquals(true, r.screen.last().first)
        val sensor = r.pane.entities().filterIsInstance<BinarySensorEntity>().single()
        assertEquals("camera_presence", sensor.objectId)
        assertEquals(true, ProtoMessage(sensor.state().toByteArray()).bool(2))
    }

    @Test fun privacyClosesTheCameraAndTellsTheScreenNobodyIsThere() {
        val r = Rig()
        r.pane.start()
        r.someoneWalksIn()
        r.prefs.privacy = true
        r.changed(CameraPrefs.KEY_PRIVACY)
        assertEquals(1, r.camera.closes)
        assertEquals(false to -1f, r.screen.last())
        r.prefs.privacy = false
        r.changed(CameraPrefs.KEY_PRIVACY)
        assertEquals(2, r.camera.opens)
    }

    @Test fun aDeviceWithItsOwnSensorDoesntOpenTheCameraForPresence() {
        val r = Rig(ownSensor = true)
        r.pane.start()
        assertEquals(0, r.camera.opens)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun withoutAndroidsPermissionNothingOpensAndThePaneSaysWhy() {
        val r = Rig(permitted = false)
        r.pane.start()
        assertEquals(0, r.camera.opens)
        assertEquals(CameraPane.NOT_ALLOWED, r.pane.problem)
        r.allowed = true
        r.pane.reconcile() // the periodic check: someone allowed it meanwhile
        assertEquals(1, r.camera.opens)
        assertNull(r.pane.problem)
    }

    @Test fun theStreamRunsOnlyWithItsSwitchAndSnapshotsAndAlwaysWithASignIn() {
        val r = Rig()
        r.pane.start()
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertNull("refused in Presence only", r.stream.running)
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.changed(CameraPrefs.KEY_USE)
        // Never without a sign-in, and the panel never makes one nobody could read (maintainer, 2026-11-15).
        assertNull("waits for the person's sign-in", r.stream.running)
        assertNull(r.prefs.signIn)
        assertEquals(CameraPage.NO_SIGN_IN, CameraPage.status(r.pane.reading()).first)
        r.prefs.signIn = "viewer" to "s3cret"; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertEquals("starts with the sign-in the person set", "viewer" to "s3cret", r.stream.running)
        r.someoneWalksIn()
        assertEquals("motion reaches the ONVIF events", listOf(true), r.stream.motion)
        r.prefs.use = CameraUse.PRESENCE; r.changed(CameraPrefs.KEY_USE)
        assertNull(r.stream.running)
    }

    @Test fun aStreamThatCantOpenItsPortsSaysSo() {
        val r = Rig()
        r.stream.refuse = true
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        assertEquals(CameraPane.STREAM_FAILED, r.pane.problem)
    }

    /** #479: on a camera that can't feed the encoder, nothing opens, the switch springs back, and the page says why. */
    @Test fun aCameraThatCantStreamKeepsTheStreamOffAndSaysWhy() {
        val r = Rig()
        val why = "The video encoder can't take the camera's 640 × 480 picture"
        r.stream.fit = StreamFit.Refused(why)
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        assertEquals(0, r.stream.starts)
        assertFalse(r.pane.plan.stream)
        assertTrue("presence goes on", r.pane.plan.detect)
        assertEquals("$why · the stream stays off" to CameraPage.Tone.PROBLEM, CameraPage.status(r.pane.reading()))
        val onvif = r.pane.entities().filterIsInstance<SwitchEntity>().single { it.objectId == "onvif_stream" }
        assertEquals("Home Assistant sees it off", false, ProtoMessage(onvif.state().toByteArray()).bool(2))
        onvif.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertFalse("springs back", r.prefs.onvif)
        assertEquals(0, r.stream.starts)
    }

    /** Verifier F8 on #485: the page's switch refuses as Home Assistant's does, and says why. */
    @Test fun thePagesStreamSwitchIsRefusedOnACameraThatCantStream() {
        val r = Rig()
        val why = "The video encoder can't take the camera's 640 × 480 picture"
        r.stream.fit = StreamFit.Refused(why)
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        r.pane.start()
        val switch = CameraDefinition(file).settings(r.pane).single().items.filterIsInstance<SettingsItem.Group>()
            .single { it.title == "ONVIF stream" }.items.filterIsInstance<SettingsItem.Switch>().single()
        assertEquals("$why.", switch.detail)
        var answer: String? = "not answered"
        switch.save(true) { answer = it }
        assertEquals("$why.", answer)
        assertFalse(r.prefs.onvif)
        assertFalse(switch.value())
        assertEquals(0, r.stream.starts)
    }

    @Test fun theOnvifSwitchSpringsBackInPresenceOnly() {
        val r = Rig()
        val onvif = r.pane.entities().filterIsInstance<SwitchEntity>().single { it.objectId == "onvif_stream" }
        onvif.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertFalse(r.prefs.onvif)
        assertEquals(false, ProtoMessage(onvif.state().toByteArray()).bool(2))
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        onvif.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertTrue(r.prefs.onvif)
        assertTrue("only an encrypted connection may open a port", onvif.authOnly)
    }

    @Test fun theEntitiesAreTheApprovedOnes() {
        val e = Rig().pane.entities().associateBy { it.objectId }
        assertEquals(listOf("camera", "camera_presence", "camera_privacy", "camera_use", "onvif_stream", "camera_sensitivity"), e.keys.toList())
        assertEquals(EntityCategory.NONE, e.getValue("camera_privacy").category)
        assertFalse(e.getValue("camera_privacy").disabledByDefault)
        assertTrue("only an encrypted connection may turn privacy off (#847)", e.getValue("camera_privacy").authOnly)
        assertTrue(e.getValue("camera_use").disabledByDefault)
        // Camera use turns snapshots on: like privacy and the stream, only Home Assistant with the key may change it.
        assertTrue("only an encrypted connection may turn snapshots on", e.getValue("camera_use").authOnly)
        assertEquals(EntityCategory.CONFIG, e.getValue("onvif_stream").category)
        assertTrue(e.getValue("camera").authOnly)
    }

    @Test fun stopClosesEverything() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        r.pane.stop()
        assertEquals(1, r.camera.closes)
        assertNull(r.stream.running)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun aCheckThatWasAlreadyWaitingWhenThePaneStoppedDoesNothing() {
        val r = Rig()
        r.pane.start()
        r.pane.stop()
        r.pane.reconcile() // the periodic check, run after stop() released the pane
        assertEquals(1, r.camera.opens)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun privacyAndStopLetTheCameraCloseAtOnce() {
        val r = Rig()
        r.pane.start()
        assertEquals(0, r.releases)
        r.prefs.privacy = true; r.changed(CameraPrefs.KEY_PRIVACY)
        assertTrue("a still's linger is dropped too", r.releases >= 1)
        val before = r.releases
        r.pane.stop()
        assertTrue(r.releases > before)
    }

    @Test fun noStillWhileThePaneIsOff() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        assertTrue("before start: judged as privacy", r.pane.stillPolicy().privacy)
        r.pane.start()
        assertFalse(r.pane.stillPolicy().privacy)
        assertTrue(r.pane.stillPolicy().stillsMayLeave)
        r.pane.stop()
        assertFalse("stopped: no still leaves", r.pane.stillPolicy().stillsMayLeave)
        r.pane.start(); r.allowed = false
        assertFalse("Android took the camera back: no still", r.pane.stillPolicy().stillsMayLeave)
    }

    @Test fun aSettingOfAnotherPaneChangesNothing() {
        val r = Rig()
        r.pane.start()
        r.changed("speaker_volume")
        assertEquals(1, r.camera.opens)
    }

    @Test fun theOnvifMotionGoesOffWhenPresenceClears() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        r.prefs.onvif = true
        r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        r.someoneWalksIn()
        val presence = r.pane.entities().filterIsInstance<BinarySensorEntity>().single()
        assertEquals(true, ProtoMessage(presence.state().toByteArray()).bool(2))
        // Nobody moves for longer than presence waits (8 s): still frames, as an empty room gives.
        repeat(50) {
            r.now += 200
            r.camera.sink!!.frame(LumaFrame(96, 54, 96, ByteArray(96 * 54) { 60 }))
        }
        assertEquals("camera_presence clears", false, ProtoMessage(presence.state().toByteArray()).bool(2))
        assertEquals("the ONVIF motion follows it, on then off", listOf(true, false), r.stream.motion)
    }

    @Test fun clearingItClosesTheStreamUntilANewOneIsSet() {
        val r = Rig()
        r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertEquals("viewer" to "s3cret", r.stream.running)
        r.prefs.signIn = null; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertNull("none made in its place", r.prefs.signIn)
        assertNull(r.stream.running)
        r.prefs.signIn = "viewer" to "n3w-s3cret"; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertEquals("viewer" to "n3w-s3cret", r.stream.running)
    }

    @Test fun inPresenceOnlyNoSignInIsMadeAndNoStreamRuns() {
        val r = Rig()
        r.pane.start()
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertNull(r.prefs.signIn)
        assertNull(r.stream.running)
    }

    @Test fun theSignInIsNeverLogged() {
        org.robolectric.shadows.ShadowLog.clear()
        val r = Rig()
        val (user, password) = "made-up-user" to "made-up-pass-1234"
        r.pane.start()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        r.prefs.signIn = user to password; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertEquals(user to password, r.stream.running)
        val logs = org.robolectric.shadows.ShadowLog.getLogs().map { it.msg.orEmpty() }
        assertTrue(logs.toString(), logs.none { password in it || user in it })
        assertTrue("no entity carries it", r.pane.entities().none { e -> e.name.contains(password) || e.objectId.contains(password) })
    }

    @Test fun homeAssistantSeesPresencePlusCameraWhileTheStoredValueStays() {
        // Renamed on 2026-11-14 ("snapshots" read as leaving the stream out); panels keep their stored choice.
        val r = Rig()
        val select = r.pane.entities().filterIsInstance<dev.glassworks.esphome.SelectEntity>().single { it.objectId == "camera_use" }
        fun choose(option: String) = select.command(ProtoMessage(ProtoWriter().fixed32(1, select.key, true).string(2, option).toByteArray()))
        choose("Presence + camera")
        assertEquals(CameraUse.PRESENCE_AND_SNAPSHOTS, r.prefs.use)
        assertEquals("what Home Assistant shows", "Presence + camera", select.shownState())
        assertEquals("stored as before, so panels keep their choice", "Presence + snapshots", file.getString(CameraPrefs.KEY_USE, null))
        choose("Presence only")
        assertEquals(CameraUse.PRESENCE, r.prefs.use)
        choose("Presence + snapshots")
        assertEquals("the old name is no option any more", CameraUse.PRESENCE, r.prefs.use)
        file.edit().putString(CameraPrefs.KEY_USE, "Presence + snapshots").commit()
        assertEquals("a panel's stored choice still reads", CameraUse.PRESENCE_AND_SNAPSHOTS, r.prefs.use)
    }

    @Test fun applyNeverStartsTheStreamWithoutASignIn() {
        // The plan already asks for one (CameraPlan), and reconcile makes one; this pins apply's own guard.
        val r = Rig()
        r.pane.start()
        r.pane.apply(CameraPlan(detect = true, feedScreen = true, stream = true), signIn = null)
        assertNull(r.stream.running)
        assertEquals(0, r.stream.starts)
    }

    @Test fun theCameraTileSaysItsOwnRateWhileStreamingWithoutA15FpsRange() {
        // CodeRabbit on #252: the camera's own rate (null) became 0, and the tile said "Not in use" mid-stream.
        val own = object : CameraFacts {
            override val lens = CameraFacts.Lens.FRONT
            override fun frameRate(): Int? = null
        }
        val r = Rig(facts = own)
        r.pane.start()
        assertNull(r.pane.reading().frameRate)
        val streaming = r.pane.reading().copy(watchers = listOf("192.0.2.20"), streaming = true)
        assertEquals("Its own frame rate", CameraPage.tiles(streaming) { "14:31" }[1].detail)
        assertEquals("no facts at all: nothing asked", 0, Rig().pane.reading().frameRate)
    }
}
