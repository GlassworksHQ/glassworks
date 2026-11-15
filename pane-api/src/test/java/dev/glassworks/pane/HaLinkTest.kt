package dev.glassworks.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #261: an "In Home Assistant" list says when Home Assistant doesn't have the panel, instead of "Unknown" on each row. */
class HaLinkTest {
    private var link = HaLink.CONNECTED
    private val rows = listOf(SettingsItem.StateRow("Camera", UNKNOWN), SettingsItem.StateRow("Camera firmware", "1.2", offByDefault = true))

    private fun page(vararg items: SettingsItem) = SettingsSection("camera", "Camera", items.toList(), master = SettingsItem.Switch("Camera", "", { true }) { _, d -> d(null) }, covers = setOf("camera_still"))

    private fun shown(s: SettingsSection) = s.items.filterIsInstance<SettingsItem.States>().single().shown()

    private val linked get() = withHaLink(listOf(page(SettingsItem.Note { "own" }, SettingsItem.States(IN_HOME_ASSISTANT) { rows }))) { link }.single()

    @Test
    fun theLinkComesFromTheServerAndWhetherHomeAssistantEverAddedThePanel() {
        assertEquals(HaLink.NOT_ADDED, HaLink.of(connected = false, added = false))
        assertEquals(HaLink.NOT_CONNECTED, HaLink.of(connected = false, added = true))
        assertEquals(HaLink.CONNECTED, HaLink.of(connected = true, added = true))
        // A connection proves it was added, even before the flag is saved.
        assertEquals(HaLink.CONNECTED, HaLink.of(connected = true, added = false))
    }

    @Test
    fun neverAddedSaysSoOnceWithNoRows() {
        link = HaLink.NOT_ADDED
        val s = shown(linked)
        assertEquals(HA_NOT_ADDED, s.note)
        assertEquals(emptyList<SettingsItem.StateRow>(), s.rows)
        assertFalse(s.dimmed)
    }

    @Test
    fun addedButNotConnectedSaysSoAndDimsTheLastKnownStates() {
        link = HaLink.NOT_CONNECTED
        val s = shown(linked)
        assertEquals(HA_NOT_CONNECTED, s.note)
        assertEquals(rows.map { it.name to it.value }, s.rows.map { it.name to it.value })
        assertTrue(s.dimmed)
    }

    @Test
    fun connectedIsAsBefore() {
        val s = shown(linked)
        assertNull(s.note)
        assertEquals(rows, s.rows)
        assertFalse(s.dimmed)
    }

    @Test
    fun theListFollowsTheLinkWhileThePageIsOpen() {
        val page = linked
        link = HaLink.NOT_ADDED
        assertEquals(HA_NOT_ADDED, shown(page).note)
        link = HaLink.CONNECTED
        assertNull(shown(page).note)
    }

    @Test
    fun theRestOfThePageIsKept() {
        val other = SettingsItem.States("Readings") { rows }
        val before = page(SettingsItem.Note { "own" }, other)
        assertSame("a page without the list is the same page", before, withHaLink(listOf(before)) { HaLink.NOT_ADDED }.single())
        link = HaLink.NOT_ADDED
        val after = linked
        assertEquals(listOf("camera", "Camera", "Camera"), listOf(after.id, after.title, after.master?.title))
        assertEquals(setOf("camera_still"), after.covers)
        assertTrue(after.items.first() is SettingsItem.Note)
    }
}
