/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsSection

/**
 * The settings sidebar, the same on the panel's menu and on the Manage page: Overview, then Panel (the
 * core's own sections), Panes (each with its state; the ones turned off fold under "Off · N"), then
 * System (Log, Labs, About). Only its foot differs: Done on the panel, who is signed in on the Manage page.
 */
data class SettingsNav(val panel: List<Item>, val panes: List<Item>, val off: List<Item>, val system: List<Item>) {
    /** A row: a section's id and title, and a pane's state (null for the core's own). */
    data class Item(val id: String, val title: String, val state: PaneState? = null)

    companion object {
        /** The Overview's id, never a section's. */
        const val OVERVIEW = "overview"
        /** The pages under System, in their order: sections of the core's, or pages of a surface's own. */
        val SYSTEM = listOf("log", "security", "labs", "about")
    }
}

/**
 * The sidebar from the panel's [sections] and its [panes] (each from its section, [paneRow]). [systemPages]:
 * System pages a surface has besides the sections (the Manage page's Security), titled by id.
 */
fun settingsNav(sections: List<SettingsSection>, panes: List<PaneRow>, systemPages: Map<String, String> = emptyMap()): SettingsNav {
    val paneIds = panes.mapNotNull { it.sectionId }.toSet()
    val core = sections.filter { it.id !in paneIds }
    val (off, on) = panes.partition { it.state.kind == PaneState.Kind.OFF }
    fun item(p: PaneRow) = SettingsNav.Item(p.sectionId ?: p.id, p.title, p.state)
    return SettingsNav(
        panel = core.filter { it.id !in SettingsNav.SYSTEM }.map { SettingsNav.Item(it.id, it.title) },
        panes = on.map(::item),
        off = off.map(::item),
        system = SettingsNav.SYSTEM.mapNotNull { id -> core.firstOrNull { it.id == id }?.let { SettingsNav.Item(id, it.title) } ?: systemPages[id]?.let { SettingsNav.Item(id, it) } },
    )
}

/** "5 on · 1 off", over the Overview's panes. */
fun panesSummary(panes: List<PaneRow>): String {
    val off = panes.count { it.state.kind == PaneState.Kind.OFF }
    return if (off == 0) "${panes.size} on" else "${panes.size - off} on · $off off"
}

/** The Overview's buttons, in their order, with what each says once started. Both surfaces offer these; Reboot is the Manage page's, behind the panel's code. */
enum class OverviewAction(val key: String, val label: String, val started: String, val run: (PanelActions) -> String?) {
    RELOAD("reload", "Reload dashboard", "Reloading the dashboard.", PanelActions::reloadDashboard),
    RESTART("restart", "Restart app", "The app is restarting. The panel shows the dashboard again in a few seconds.", PanelActions::restartApp),
    UPDATE("update", "Check for updates", "Checking for updates. Home Assistant's update card shows what it finds.", PanelActions::checkForUpdates),
}
