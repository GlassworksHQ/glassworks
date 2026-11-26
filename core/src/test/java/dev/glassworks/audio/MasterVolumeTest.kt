package dev.glassworks.audio

import android.content.Context
import android.media.AudioManager
import dev.glassworks.KioskPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The master volume: one level on Android's media stream for everything the panel plays, whichever panes are on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterVolumeTest {

    private class FakeStream(override var max: Int = 15, var at: Int = 10, override var fixed: Boolean = false) : MediaStream {
        val writes = ArrayList<Int>()
        override var index: Int
            get() = at
            set(v) { at = v; writes += v }
    }

    private class FakeStore(override var level: Float = -1f, override var muted: Boolean = false, var old: Float? = null) : VolumeStore {
        override var raised = false
        override fun takeOldVolume() = old.also { old = null }
    }

    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val stream = FakeStream()
    private val store = FakeStore()
    private val volume = MasterVolume(stream, store) { ms, action -> timers += ms to action }

    @Test fun aFirstStartReadsAndroidsVolumeAndChangesNothing() {
        assertEquals(10 / 15f, volume.level, 0.001f)
        assertTrue("nothing written", stream.writes.isEmpty())
    }

    @Test fun aMuteSavedBeforeTheFirstStartHolds() {
        store.muted = true
        assertTrue(volume.muted)
        assertEquals(0, stream.at)
        assertEquals("the level unmuting goes back to", 10 / 15f, volume.level, 0.001f)
    }

    @Test fun settingTheLevelMovesTheStream() {
        volume.set(0.5f)
        assertEquals("7.5 rounds to 8 of 15", 8, stream.at)
        assertEquals("the level stays as set while the stream agrees", 0.5f, volume.level, 0.001f)
        volume.set(2f)
        assertEquals(15, stream.at)
        assertEquals(1f, volume.level, 0f)
    }

    @Test fun theVolumeKeysMoveTheLevelAndEveryoneHearsIt() {
        var heard = 0
        volume.start()
        volume.onChange = { heard++ }
        stream.at = 3
        assertEquals(3 / 15f, volume.level, 0.001f)
        assertEquals(1, heard)
        assertEquals("seen once", 3 / 15f, volume.level, 0.001f)
        assertEquals(1, heard)
    }

    @Test fun muteGoesToZeroAndKeepsTheLevel() {
        volume.set(0.6f)
        volume.mute(true)
        assertEquals(0, stream.at)
        assertTrue(volume.muted)
        assertEquals("the level unmuting goes back to", 0.6f, volume.level, 0.001f)
        volume.set(0.4f)
        assertEquals("muted, a new level waits", 0, stream.at)
        volume.mute(false)
        assertEquals(6, stream.at)
    }

    @Test fun turningItUpFromOutsideUnmutes() {
        volume.mute(true)
        stream.at = 2
        assertFalse(volume.muted)
        assertEquals(2 / 15f, volume.level, 0.001f)
    }

    @Test fun stepsAreTheStreamsSteps() {
        volume.step(up = true)
        assertEquals(11, stream.at)
        stream.at = 0
        volume.step(up = false)
        assertEquals(0, stream.at)
        assertEquals(0f, volume.level, 0f)
    }

    @Test fun aCriticalAlertPlaysAtFullEvenMutedAndTheVolumeComesBack() {
        volume.set(0.2f)
        volume.mute(true)
        val end = volume.full(3_000)
        assertEquals(15, stream.at)
        assertTrue("on disk, for a crash mid-alert", store.raised)
        assertEquals("Home Assistant still sees the panel's own level", 0.2f, volume.level, 0.001f)
        assertTrue(volume.muted)
        end()
        assertEquals(0, stream.at)
        assertFalse(store.raised)
        volume.mute(false)
        assertEquals(3, stream.at)
    }

    @Test fun overlappingAlertsShareOneRaiseAndChangesMeanwhileWait() {
        volume.set(0.2f)
        val a = volume.full(3_000)
        val b = volume.full(3_000)
        volume.set(0.4f)
        assertEquals("held at full", 15, stream.at)
        a()
        a()
        assertEquals("one still plays", 15, stream.at)
        b()
        assertEquals("the level set meanwhile", 6, stream.at)
    }

    @Test fun aSoundThatNeverCallsBackEndsAtItsTimeout() {
        volume.set(0.2f)
        volume.full(2_950)
        assertEquals(2_950L, timers.single().first)
        timers.single().second()
        assertEquals(3, stream.at)
    }

    @Test fun aStartAfterACrashMidAlertPutsTheLevelBack() {
        store.level = 0.2f
        store.raised = true
        stream.at = 15
        volume.start()
        assertEquals(3, stream.at)
        assertFalse(store.raised)
    }

    @Test fun theOldSpeakerVolumeKeepsVoiceAsLoudAsItWas() {
        store.old = 0.5f
        stream.at = 12
        volume.start()
        assertEquals("12 of 15 at half the speaker volume", 6, stream.at)
        assertEquals(0.4f, volume.level, 0.001f)
    }

    @Test fun whereTheVolumeIsFixedThePlayersScaleThemselves() {
        stream.fixed = true
        volume.set(0.3f)
        assertTrue("the stream isn't touched", stream.writes.isEmpty())
        assertEquals(0.3f, volume.gain, 0.001f)
        volume.mute(true)
        assertEquals(0f, volume.gain, 0f)
        volume.full(1_000)()
        assertTrue(stream.writes.isEmpty())
    }

    @Test fun theStreamCarriesTheVolumeSoThePlayersPlayAtFull() {
        volume.set(0.3f)
        assertEquals(1f, volume.gain, 0f)
    }

    // --- on Android's own AudioManager and the app's settings file

    @Test fun onAndroidItIsTheMediaStream() {
        val ctx = RuntimeEnvironment.getApplication()
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).setStreamMaxVolume(15)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
        val prefs = KioskPrefs(ctx)
        RuntimeEnvironment.getApplication().getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit().putFloat("speaker_volume", 0.5f).commit()
        val v = MasterVolume.of(ctx)
        v.start()
        assertEquals("the old speaker volume moved over", 6, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("and is gone", null, prefs.takeOldSpeakerVolume())
        v.set(1f)
        assertEquals(15, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(1f, prefs.masterLevel, 0f)
        v.mute(true)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(prefs.speakerMuted)
    }
}
