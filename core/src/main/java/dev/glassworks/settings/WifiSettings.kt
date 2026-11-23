/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import dev.glassworks.net.WifiChange
import dev.glassworks.net.WifiChange.Block
import dev.glassworks.net.WifiChange.Last
import dev.glassworks.net.WifiChange.Refusal
import dev.glassworks.net.WifiChange.State
import dev.glassworks.net.WifiChange.Why
import dev.glassworks.pane.SettingsItem

/**
 * The Network page's Wi-Fi row (#660), the same on the panel's menu and the Manage page: the network the panel is on, a
 * change under way, or how the last one ended, and a sheet to pick another. Where the device can't change networks from
 * the app ([WifiChange.Block]), a line says so and why, and nothing is offered. The words live here once.
 */
object WifiSettings {
    const val TITLE = "Wi-Fi"
    const val LABEL = "Change"

    /** The sheet's line, on both surfaces. */
    val HINT = "The panel keeps its current network saved. If it can't join this one or reach Home Assistant there, " +
        "it goes back within ${minutes(WifiChange.BUDGET_MS)}."

    /** On the Manage page after a change is accepted: the page loses the panel while it switches. */
    val SWITCHING = "The panel is switching Wi-Fi networks, so this page may stop responding. Once it's on the new " +
        "network, open it again with Visit on its device page in Home Assistant. If that network doesn't work, the panel " +
        "comes back here within ${minutes(WifiChange.BUDGET_MS)}."

    /** The row for [change], or the line that says why the device can't change Wi-Fi here. Null without one at all. */
    fun item(change: WifiChange?, connected: () -> Boolean): SettingsItem? {
        change ?: return null
        val block = runCatching { change.block() }.getOrNull()
        if (block == Block.SUGGESTIONS_ONLY || block == Block.NO_LOCATION) {
            return SettingsItem.Info(title = { TITLE }, detail = { blocked(block, if (connected()) "Connected" else "Not connected") })
        }
        return SettingsItem.Wifi(
            title = TITLE,
            detail = { detail(change.state, change.current()?.name, connected()) },
            hint = HINT,
            label = LABEL,
            problem = { val s = change.state; s is State.Idle && (s.last is Last.Back || s.last is Last.Stuck) },
            nearby = { change.nearby() },
            open = { change.state is State.Idle },
        ) { network, password, done ->
            done(change.start(network, password)?.let(::refusal))
        }
    }

    /** The row's line for [s]: [current] is the network the panel is on now, when it can tell. */
    fun detail(s: State, current: String?, connected: Boolean): String = when (s) {
        is State.Joining -> joining(s.target, s.previous.name)
        is State.GoingBack -> "${s.target} didn't work: ${why(s.why)}. Going back to ${s.previous.name}"
        is State.Idle -> when (val l = s.last) {
            is Last.Back -> "Back on ${l.previous}. ${l.target} didn't work: ${why(l.why)}"
            is Last.Stuck -> "${l.target} didn't work, and ${l.previous} didn't come back. If the panel stays offline, it opens its setup hotspot"
            is Last.Joined, null -> current ?: if (connected) "Connected" else "Not connected"
        }
    }

    /** While a change is under way: where it goes, and the way back. */
    fun joining(target: String, previous: String) =
        "Joining $target. If it doesn't work, the panel goes back to $previous within ${minutes(WifiChange.BUDGET_MS)}"

    fun why(w: Why): String = when (w) {
        Why.PASSWORD -> "the password was wrong"
        Why.NO_CONNECTION -> "the panel couldn't connect"
        Why.NO_HOME_ASSISTANT -> "Home Assistant didn't answer there"
        Why.TIMED_OUT -> "it took too long"
        Why.INTERRUPTED -> "the app restarted during the change"
    }

    fun refusal(r: Refusal): String = when (r) {
        is Refusal.Blocked -> when (r.block) {
            Block.NOT_READY -> "The panel's Wi-Fi isn't ready yet. Try again in a moment."
            else -> blocked(r.block, null) + "."
        }
        Refusal.Busy -> dev.glassworks.pane.SettingsChange.WIFI_BUSY
        Refusal.NotOnWifi -> "The panel isn't on Wi-Fi now, so it would have no network to go back to. Set up Wi-Fi from the panel's setup instead."
        Refusal.Same -> "The panel is already on that network."
        Refusal.NotSaved -> "The panel couldn't save the change, so it didn't start. Try again."
    }

    /** Why this device can't change Wi-Fi from the app, after what it is on ([now]), if given. */
    private fun blocked(b: Block, now: String?): String {
        val why = when (b) {
            Block.SUGGESTIONS_ONLY -> "Android only lets this app suggest networks here, and a suggestion can't move the panel off one that works"
            Block.NO_LOCATION -> "the app has no location access, so Android hides which network the panel is on"
            Block.NOT_READY -> "the panel's Wi-Fi isn't ready yet"
        }
        val line = "Change it in Android's settings: $why"
        return if (now == null) line else "$now. $line"
    }

    private fun minutes(ms: Long): String = (ms / 60_000).let { if (it == 1L) "1 minute" else "$it minutes" }
}
