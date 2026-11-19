/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.content.Context
import dev.glassworks.GlassworksApp
import dev.glassworks.KioskPrefs
import dev.glassworks.pane.SettingsItem

/**
 * Every frame's Device page row *Update channel* (#605), next to Silent updates: Stable, the default, or Beta. The
 * panel and the Manage page draw the same row. Its value stays short, so the row keeps its title on a narrow
 * portrait screen (the Mirror's); what each channel means is asked before a change. A lower version is never offered,
 * so both questions say that going back to Stable keeps the version installed until a newer stable one is out.
 */
object UpdateChannelRow {
    const val TITLE = "Update channel"
    /** The row's value on Beta: the one line that says what it costs. */
    const val BETA_LINE = "Beta · may have bugs"
    val TO_BETA = SettingsItem.Confirm(
        "Get beta versions?",
        "Beta gets new versions first. They may have bugs. If you go back to Stable, the panel keeps the version it " +
            "has until a newer stable one is out.",
        "Use Beta",
    )
    val TO_STABLE = SettingsItem.Confirm(
        "Back to Stable?",
        "The panel keeps the version it has. It updates again when a stable version newer than it is out.",
        "Use Stable",
    )

    /** The row on [context]'s app: saved in [KioskPrefs], and the running update check picks again at once. */
    fun item(context: Context): SettingsItem.Choice {
        val app = context.applicationContext
        val prefs by lazy { KioskPrefs(app) }
        return item({ prefs.updateChannel }, { prefs.updateChannel = it }) {
            (app as? GlassworksApp)?.glassworks?.updates?.onChannelChanged()
        }
    }

    /** The row reading [get] and saving with [set], then telling the update check ([changed]). */
    fun item(get: () -> UpdateChannel, set: (UpdateChannel) -> Unit, changed: () -> Unit): SettingsItem.Choice = SettingsItem.Choice(
        TITLE,
        value = { get().id },
        options = { UpdateChannel.entries.map { it.id to it.label } },
        line = { id -> if (UpdateChannel.of(id) == UpdateChannel.BETA) BETA_LINE else UpdateChannel.STABLE.label },
        confirm = { id ->
            when {
                id == get().id -> null
                id == UpdateChannel.BETA.id -> TO_BETA
                id == UpdateChannel.STABLE.id -> TO_STABLE
                else -> null
            }
        },
    ) { id, done ->
        val c = UpdateChannel.entries.firstOrNull { it.id == id } ?: return@Choice done("Not a channel: $id")
        if (c != get()) {
            set(c)
            changed()
        }
        done(null)
    }
}
