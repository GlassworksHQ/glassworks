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

    @Test fun aLinkToASectionWithNoItemsIsLeftOut() {
        val device = section("device", SettingsItem.Info({ "Model" }, { "x" }), SettingsItem.Link("Network", "", section = "network"))
        val out = settingsOrder(mapOf(SettingsPlace.DEVICE to device, SettingsPlace.NETWORK to SettingsSection("network", "Network", emptyList())), emptyList())
        assertEquals(listOf("device"), out.map { it.id })
        assertEquals(1, out.single().items.size)
    }

    @Test fun aLinkToASectionThatPruningEmptiesIsLeftOutToo() {
        // CodeRabbit on #303: "screen" holds only a link to a missing "radio", so it goes, and the Device page's link to it.
        val screen = section("screen", SettingsItem.Link("Radio", "", section = "radio"))
        val device = section("device", SettingsItem.Info({ "Model" }, { "x" }), SettingsItem.Group("More", listOf(SettingsItem.Link("Screen", "", section = "screen"))))
        val out = settingsOrder(mapOf(SettingsPlace.SCREEN to screen, SettingsPlace.DEVICE to device), emptyList())
        assertEquals(listOf("device"), out.map { it.id })
        assertEquals("the group that held only that link goes too", listOf("Model"), out.single().items.map { (it as SettingsItem.Info).title() })
    }
}
