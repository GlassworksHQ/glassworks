package dev.glassworks.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The order every surface uses, and what the assembled model leaves out. */
class SettingsOrderTest {
    private fun section(id: String, vararg items: SettingsItem) = SettingsSection(id, id, items.toList())

    @Test fun aLinkToASectionThatIsntThereIsLeftOut() {
        // #303 verifier: the Device page's radio line on a frame without the radio would open nothing.
        val device = section("device",
            SettingsItem.Info({ "Model" }, { "x" }),
            SettingsItem.Link("Radio mode", "Off", section = "radio"),
            SettingsItem.Group("More", listOf(SettingsItem.Link("Radio again", "", section = "radio"), SettingsItem.Link("Screen", "", section = "screen"))),
        )
        val out = settingsOrder(mapOf(SettingsPlace.SCREEN to section("screen", SettingsItem.Note { "" }), SettingsPlace.DEVICE to device), emptyList())
        val items = out.single { it.id == "device" }.items
        assertEquals(listOf("Model", "More"), items.map { (it as? SettingsItem.Info)?.title?.invoke() ?: (it as SettingsItem.Group).title })
        assertEquals(listOf("Screen"), (items[1] as SettingsItem.Group).items.map { (it as SettingsItem.Link).title })
    }

    @Test fun aLinkWhoseSectionIsThereStaysAndTheSectionIsTheSameObject() {
        val device = section("device", SettingsItem.Link("Radio mode", "Off", section = "radio"))
        val out = settingsOrder(mapOf(SettingsPlace.DEVICE to device), listOf(section("radio", SettingsItem.Note { "" })))
        assertSame(device, out.single { it.id == "device" })
    }
}
