package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sidebar both surfaces draw: Panel, Panes with the off ones folded, System in its order. */
class SettingsNavTest {
    private fun section(id: String, title: String) = SettingsSection(id, title, listOf(SettingsItem.Note { "" }))
    private fun pane(id: String, kind: PaneState.Kind) = PaneRow(id, id.uppercase(), PaneState(kind, ""), id)

    private val sections = listOf(section("dashboard", "Home Assistant & dashboard"), section("screen", "Screen"),
        section("voice", "VOICE"), section("radio", "RADIO"), section("music", "MUSIC"), section("log", "Log"), section("labs", "Labs"), section("about", "About"))
    private val panes = listOf(pane("voice", PaneState.Kind.RUNNING), pane("radio", PaneState.Kind.OFF), pane("music", PaneState.Kind.CANT_RUN))

    @Test fun thePanelsSidebar() {
        val n = settingsNav(sections, panes)
        assertEquals(listOf("dashboard", "screen"), n.panel.map { it.id })
        assertEquals("on panes in order, can't run among them", listOf("voice", "music"), n.panes.map { it.id })
        assertEquals("off folds", listOf("radio"), n.off.map { it.id })
        assertEquals("the panel has no Security page", listOf("log", "labs", "about"), n.system.map { it.id })
    }

    @Test fun manageAddsItsSecurityPageInItsPlace() {
        val n = settingsNav(sections, panes, ManagePages.OWN_PAGES)
        assertEquals(listOf("Log", "Security", "Labs", "About"), n.system.map { it.title })
        assertEquals("the rest is the panel's", settingsNav(sections, panes).panel, n.panel)
    }

    @Test fun theSummaryCountsOnAndOff() {
        assertEquals("2 on · 1 off", panesSummary(panes))
        assertEquals("1 on", panesSummary(panes.take(1)))
    }
}
