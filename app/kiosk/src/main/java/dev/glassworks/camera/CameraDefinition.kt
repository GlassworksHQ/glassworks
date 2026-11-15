/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences
import dev.glassworks.frame.Capability
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.SetupStep
import java.text.DateFormat
import java.util.Date

/**
 * The camera pane ([CameraPane]) for the whole process. It [requires] a camera: on a frame without one
 * (the S1) the app wraps it in [dev.glassworks.pane.CantRun], one page that says why, with no on/off,
 * setup card or entity, as every pane that lacks its hardware (#23). [hasCamera] is the frame's
 * `provides` having one; the device shell builds the pane only then. Its settings have no MainActivity
 * extras: any app can send those, and on a tablet another app could turn privacy off or open the
 * stream. The app sets them from adb through its own adb-only activity.
 *
 * With a camera: a card on setup's Panes step, setup's Camera step ([CameraSetupStep]), and the page of
 * #117's approved design. [granted] reads Android's camera permission; [pickers] draws the two use
 * cards (without it, the page offers the use as a list).
 */
class CameraDefinition(
    file: SharedPreferences,
    val hasCamera: Boolean,
    private val granted: () -> Boolean = { false },
    private val pickers: CameraUsePickers? = null,
    /** A time of day as the page shows it. */
    private val time: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
) : PaneDefinition {
    override val id = CameraPane.ID
    override val requires = setOf(Capability.CAMERA)
    val prefs = CameraPrefs(file)

    override val switch = PaneSwitch(
        file, CameraPrefs.KEY_ENABLED, default = false, title = TITLE, detail = "Presence, and snapshots",
        objectId = "camera_pane", entityName = "Camera pane", icon = "mdi:cctv", inSetup = hasCamera, offNote = OFF_NOTE,
    )

    private val step = CameraSetupStep(prefs, on = { switch.on }, turnOff = { switch.on = false }, granted = granted, pickers = pickers)

    override val setupSteps: List<SetupStep> get() = if (hasCamera) listOf(step) else emptyList()

    /**
     * The camera's page: how it is doing, what it may do, the readings, privacy, and the stream with its
     * sign-in and addresses. The core adds the pane's entities as Home Assistant shows them
     * ([SettingsSection.covers] is empty: the page shows none). Without a camera the app shows CantRun's.
     */
    override fun settings(): List<SettingsSection> = settings(null)

    override fun settings(running: Pane?): List<SettingsSection> {
        val pane = running as? CameraReadout
        // Once per page: the address asks the network, and the rows below read [read] every couple of seconds.
        val address = pane?.address()
        fun read() = (pane?.reading() ?: CameraReading(
            prefs.use, prefs.privacy, prefs.onvif && prefs.policy.stillsMayLeave, prefs.signIn != null, running = false,
            problem = null, detecting = false, near = null, nearSince = null, leaving = false, lastStill = null,
            streaming = false, watchers = emptyList(),
        )).copy(address = address)
        val use = pickers?.let { p -> usePickerItem(p) } ?: SettingsItem.Choice(
            "Camera use", { prefs.use.name },
            { listOf(CameraUse.PRESENCE.name to CameraPrefs.USE_PRESENCE, CameraUse.PRESENCE_AND_SNAPSHOTS.name to CameraPrefs.USE_SNAPSHOTS) },
        ) { v, done -> prefs.use = CameraUse.valueOf(v); done(null) }
        return listOf(
            SettingsSection(
                id, TITLE,
                listOf(
                    SettingsItem.Status(
                        text = { CameraPage.status(read()).first },
                        ok = { CameraPage.status(read()).second == CameraPage.Tone.OK },
                        problem = { CameraPage.status(read()).second == CameraPage.Tone.PROBLEM },
                    ),
                    use,
                    SettingsItem.Tiles(3) { CameraPage.tiles(read(), time) },
                    SettingsItem.Group("Privacy", listOf(
                        SettingsItem.Switch("Camera privacy", CameraPage.PRIVACY_DETAIL, { prefs.privacy }) { on, done -> prefs.privacy = on; done(null) },
                    )),
                    SettingsItem.Group("ONVIF stream", listOf(
                        // Its line follows privacy and the use: a different line draws the page again (the
                        // overlay's shape has it), so a card tapped above it updates the switch too.
                        SettingsItem.Switch("Stream over ONVIF and RTSP", CameraPage.streamDetail(prefs.privacy, prefs.use), { prefs.onvif && prefs.policy.stillsMayLeave }) { on, done ->
                            // Refused under privacy and in Presence only, as from Home Assistant (the approved design).
                            val no = CameraPage.streamRefusal(prefs.privacy, prefs.use)
                            if (on && no != null) done(no) else { prefs.onvif = on; done(null) }
                        },
                        // The username; the password only while the row is tapped, on the panel (maintainer, 2026-11-14).
                        SettingsItem.Info({ "Sign-in" }, { CameraPage.signIn(prefs.signIn) }, reveal = { CameraPage.signInShown(prefs.signIn) }),
                        SettingsItem.Info({ "Address" }, { CameraPage.address(read().address) }),
                        SettingsItem.Info({ "Stream" }, { CameraPage.stream(read()) }),
                        SettingsItem.Info({ "ONVIF device" }, { CameraPage.device(read()) }),
                        SettingsItem.Info({ "Watching now" }, { CameraPage.watching(read()) }),
                    )),
                ),
                source = pane,
                covers = emptySet(),
            ),
        )
    }

    /** The two cards, as a native view: taps set the use at once, as Home Assistant's select does. */
    private fun usePickerItem(p: CameraUsePickers) = SettingsItem.Native(USE_KEY) { host ->
        val picker = p.create(host.context)
        picker.onPick = { u -> prefs.use = u; picker.bind(u, enabled = !prefs.privacy) }
        object : SettingsItem.NativeView {
            override val view = picker.view
            override fun refresh() = picker.bind(prefs.use, enabled = !prefs.privacy && granted())
        }
    }

    companion object {
        const val TITLE = "Camera"
        const val USE_KEY = "camera-use"
        const val OFF_NOTE = "The camera pane is off. The camera stays closed, and its entities are unavailable in Home Assistant until you turn it on."
    }
}
