package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.settingsOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Sound page: the master volume and its mute, on the panel's menu and the Manage page alike. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundPageTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun core(volume: dev.glassworks.audio.MasterVolume?) = CoreSettings(
        KioskPrefs(app), "1.0.0", status = { "" }, deviceSettings = null,
        licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        hardware = MenuFixture.S1_HARDWARE, volume = volume,
    )

    @Test fun theSliderAndTheSwitchAreTheMasterVolume() {
        val volume = MenuFixture.volume(index = 9)
        val page = core(volume).sections().getValue(SettingsPlace.SOUND)
        assertEquals(CoreSettings.SOUND_TITLE, page.title)
        val slider = page.items.filterIsInstance<SettingsItem.Slider>().single()
        assertEquals(CoreSettings.VOLUME_TITLE, slider.title)
        assertEquals("9 of 15", 60f, slider.value(), 0f)
        assertEquals("60 %", slider.shown(slider.value()))
        var answer: String? = "not yet"
        slider.save(20f) { answer = it }
        assertNull(answer)
        assertEquals(0.2f, volume.level, 0.001f)
        val mute = page.items.filterIsInstance<SettingsItem.Switch>().single()
        assertFalse(mute.value())
        mute.save(true) {}
        assertTrue(volume.muted)
        assertEquals("muted, the slider keeps the level", 20f, slider.value(), 0f)
    }

    @Test fun itSitsAfterScreenAndWithoutAVolumeThereIsNone() {
        val ids = settingsOrder(core(MenuFixture.volume()).sections(), emptyList()).map { it.id }
        assertEquals(ids.indexOf(CoreSettings.SCREEN) + 1, ids.indexOf(CoreSettings.SOUND))
        assertFalse(CoreSettings.SOUND in settingsOrder(core(null).sections(), emptyList()).map { it.id })
    }
}
