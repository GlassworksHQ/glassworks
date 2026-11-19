/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.dock

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection

/**
 * The Device page's live dock row: *Docked · On its dock*, or *Not docked*. A status, not a setting. Only where Home
 * Assistant has the Docked sensor ([Docked.hasEntity]), so a frame that never docks, or hasn't yet, has no row.
 * Every surface reads it again while the page is open.
 */
object DockRow {
    const val DOCKED = "Docked"
    const val ON_ITS_DOCK = "On its dock"
    const val NOT_DOCKED = "Not docked"

    /** The row, reading [docked] each time it's drawn. */
    fun item(docked: () -> Boolean?): SettingsItem.Info = SettingsItem.Info(
        title = { if (docked() == true) DOCKED else NOT_DOCKED },
        detail = { if (docked() == true) ON_ITS_DOCK else "" },
    )

    /** [device] (the frame's Device page) with the row at its end, while [docked] isn't null; else [device] as it is. */
    fun withRow(device: SettingsSection, docked: () -> Boolean?): SettingsSection =
        if (docked() == null) device
        else SettingsSection(device.id, device.title, device.items + item(docked), device.heading, device.master,
            device.status, device.unavailable, device.source, device.covers)

    /** Whether [items] has the row: a page that does changes by itself, so the Manage page reloads it. */
    fun shownIn(items: List<SettingsItem>): Boolean =
        items.any { it is SettingsItem.Info && runCatching { it.title() }.getOrNull().let { t -> t == DOCKED || t == NOT_DOCKED } }
}
