/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import android.content.SharedPreferences

/** The notifications pane's settings, in the file the frame gives its namespace (`notifications`). */
class NotificationsPrefs(private val sp: SharedPreferences) {
    /**
     * The pane's on/off (#67). On by default (maintainer, 2026-11-20): idle it only waits for a notify
     * call, and it shows something only when Home Assistant sends one. Keep it the same as the switch's
     * default in [NotificationsDefinition].
     */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** Do not disturb: holds every notification but critical in the list, silent. Kept across restarts. */
    var doNotDisturb: Boolean
        get() = sp.getBoolean(KEY_DND, false)
        // commit(), not apply(): the app can exit right after (#185).
        set(v) { sp.edit().putBoolean(KEY_DND, v).commit() }

    companion object {
        /** Panels keep these keys: never rename them. */
        const val KEY_ENABLED = "notifications_enabled"
        const val KEY_DND = "notifications_dnd"

        /**
         * On until turned off. Only a panel with no stored value follows this: setup's Panes step stores
         * every card's value, so a panel set up while the default was off keeps its off (no migration).
         */
        const val DEFAULT_ENABLED = true
    }
}
