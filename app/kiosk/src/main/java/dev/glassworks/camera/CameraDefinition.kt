/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences
import dev.glassworks.frame.Capability
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsSection

/**
 * The camera pane ([CameraPane]) for the whole process. It [requires] a camera: on a frame without one
 * (the S1) the app wraps it in [dev.glassworks.pane.CantRun], one page that says why, with no on/off,
 * setup card or entity, as every pane that lacks its hardware (#23). [hasCamera] is the frame's
 * `provides` having one; the device shell builds the pane only then. Its settings have no MainActivity
 * extras: any app can send those, and on a tablet another app could turn privacy off or open the
 * stream. The app sets them from adb through its own adb-only activity.
 */
class CameraDefinition(file: SharedPreferences, val hasCamera: Boolean) : PaneDefinition {
    override val id = CameraPane.ID
    override val requires = setOf(Capability.CAMERA)
    val prefs = CameraPrefs(file)

    override val switch = PaneSwitch(
        file, CameraPrefs.KEY_ENABLED, default = false, title = TITLE, detail = "Presence, and snapshots",
        objectId = "camera_pane", entityName = "Camera pane", icon = "mdi:cctv", offNote = OFF_NOTE,
    )

    override val setupChoice: PaneChoice? get() = null

    /** The core's page with the on/off and the entities; the camera's own page comes with its UI (#252). */
    override fun settings(): List<SettingsSection> = emptyList()

    companion object {
        const val TITLE = "Camera"
        const val OFF_NOTE = "The camera pane is off. The camera stays closed, and its entities are unavailable in Home Assistant until you turn it on."
    }
}
