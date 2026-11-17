/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Input
import dev.glassworks.pane.PaneDefinition

/**
 * What this device has for the Screen settings (#471): the one answer the panel's settings and the Manage
 * page read, as [dev.glassworks.pane.CantRun] is for panes. A row shows only when the device can back it.
 */
data class ScreenHardware(val presence: Presence, val light: Boolean, val touch: Boolean) {

    /** Who tells the screen that someone is near. */
    sealed interface Presence {
        /** The frame's own sensor. [ranged]: the person can set how far it sees ([PresenceSource.ranged]). */
        data class Sensor(val ranged: Boolean) : Presence

        /** A pane on a device without a sensor ([PaneDefinition.givesPresence]): its section [id] and [title], and whether it's [on]. */
        data class Pane(val id: String, val title: String, val on: Boolean) : Presence

        data object None : Presence
    }

    /** Someone near can wake the screen now. */
    val sensesPresence: Boolean get() = presence is Presence.Sensor || (presence as? Presence.Pane)?.on == true

    companion object {
        /**
         * [frame] on the device of [context], with its [panes] as the app has them (a pane the frame can't run is
         * already [dev.glassworks.pane.CantRun], and gives nothing). The frame's own sensor comes first, as the
         * camera pane's plan has it.
         */
        fun of(frame: Frame, panes: List<PaneDefinition>, context: Context): ScreenHardware {
            val own = frame.presence?.takeIf { it.builtIn || it.available(context) }
            val presence = own?.let { Presence.Sensor(it.ranged) }
                ?: panes.firstOrNull { it.givesPresence }?.let { d -> Presence.Pane(d.id, d.switch?.title ?: d.id, d.switch?.on ?: true) }
                ?: Presence.None
            return ScreenHardware(presence, light = frame.light?.available(context) == true, touch = frame.input == Input.TOUCH)
        }
    }
}
