/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

/**
 * Changing the panel's Wi-Fi after setup (#660), from its settings or the Manage page, with a way back.
 *
 * A change joins the new network on the provisioner the setup page, Bluetooth and Kiln use ([Radio.join]). It counts
 * once the panel is on the new network and reaches Home Assistant there ([Radio.reaches]) within [budgetMs] (2 minutes
 * from when it was accepted). Everything else ends on the network the panel was on, which stays saved throughout
 * ([Radio.goBack]): a join that fails, a network where Home Assistant can't be reached, the 2 minutes running out, and an
 * app that restarts part way. If even that network doesn't come back, [Radio.armHotspot] lets the setup hotspot open
 * once the panel has been offline for its few minutes, as after any new Wi-Fi details.
 *
 * Before the panel leaves its network the change is saved ([Store]), so an app that restarts part way picks it up
 * ([resume]) and finishes it the same way. The password never is, and no network's name goes in a log line.
 *
 * Plain Kotlin on one thread (the main one on the panel); [Radio]'s answers come back on it. [clock] is monotonic, in ms.
 */
class WifiChange(
    private val radio: Radio,
    private val store: Store,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, run: () -> Unit) -> () -> Unit,
    private val onChange: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** From accepting a change to being on the new network with Home Assistant in reach; past it, the panel goes back. */
    val budgetMs: Long = BUDGET_MS,
    /** Between accepting a change and leaving the network: the answer to the page that sent it gets out first. */
    private val startDelayMs: Long = 2_000L,
    /** How often a joined network is asked again whether Home Assistant answers there. */
    private val checkEveryMs: Long = 5_000L,
    /** After a restart part way: how long the panel gets to show it is on the new network with Home Assistant in reach. */
    private val resumeMs: Long = 60_000L,
) {
    /** A saved network: its [name], and the device's id for it. Logs print the id only. */
    data class Network(val name: String, val id: Int) {
        override fun toString(): String = "Network(id=$id)"
    }

    /** Why this device can't change Wi-Fi from the app ([Radio.block]). */
    enum class Block {
        /** Android lets the app only suggest networks here, and a suggestion never moves the panel off one that works. */
        SUGGESTIONS_ONLY,

        /** The app has no location access, so Android hides which network it is on: no way back could be known. */
        NO_LOCATION,

        /** The panel's Wi-Fi isn't up yet (its service is starting). */
        NOT_READY,
    }

    /** Why a change didn't stay. */
    enum class Why { PASSWORD, NO_CONNECTION, NO_HOME_ASSISTANT, TIMED_OUT, INTERRUPTED }

    /** How a [Radio.join] ended. */
    sealed interface Joined {
        data object Ok : Joined

        data class Failed(val why: Why) : Joined

        /** Another channel sent Wi-Fi details meanwhile (Bluetooth, Kiln, the setup page): that join is the one now. */
        data object Superseded : Joined
    }

    /** What [start] refused, and why. */
    sealed interface Refusal {
        data class Blocked(val block: Block) : Refusal

        /** A change is under way already. */
        data object Busy : Refusal

        /** Not on Wi-Fi now: there would be no network to come back to. */
        data object NotOnWifi : Refusal

        /** The panel is on that network already. */
        data object Same : Refusal

        /** The change couldn't be saved before leaving: without it a restart part way would lose the way back. */
        data object NotSaved : Refusal
    }

    /** The device's Wi-Fi, as a change needs it. Main thread; every `done` is called once, on it. */
    interface Radio {
        /** Why the device can't change networks from the app now, or null when it can. */
        fun block(): Block?

        /** The saved network the panel is on now, or null when it is on none (or can't tell). */
        fun current(): Network?

        /** Whether a network called [name] is saved already: a way back then leaves it saved. */
        fun isSaved(name: String): Boolean

        /** Networks nearby, strongest first, for the person to pick from. */
        fun nearby(): List<String> = emptyList()

        /** Joins [name]. A failed join is back on the network it left ([Joined.Failed]). */
        fun join(name: String, password: String, done: (Joined) -> Unit)

        /** Stops a [join] in progress; its `done` may still come, and is ignored. */
        fun cancel()

        /** From [leave] back to [to], forgetting [leave] when [forget]. True once on [to] again. */
        fun goBack(to: Network, leave: String, forget: Boolean, done: (Boolean) -> Unit)

        /** Whether Home Assistant answers over the network the panel is on now (true while the panel has none to reach). */
        fun reaches(done: (Boolean) -> Unit)

        /** New Wi-Fi details are on their way: the setup hotspot stops, and comes back if the panel then stays offline. */
        fun armHotspot()
    }

    /** A change under way, as saved before the panel leaves its network: never its password. */
    data class Pending(val target: String, val previous: Network, val forget: Boolean) {
        override fun toString(): String = "Pending(previous=$previous, forget=$forget)"
    }

    /** Where [Pending] is kept across a restart. [save] null clears it; false when it couldn't be written. */
    interface Store {
        fun load(): Pending?
        fun save(p: Pending?): Boolean
    }

    sealed interface State {
        /** Nothing under way; [last]: how the last change ended, in this run of the app. */
        data class Idle(val last: Last? = null) : State

        /** Joining [target] from [previous] until [untilMs]. [joined]: on it, asking Home Assistant. */
        data class Joining(val target: String, val previous: Network, val untilMs: Long, val joined: Boolean = false) : State

        /** [target] didn't work ([why]): on the way back to [previous]. */
        data class GoingBack(val target: String, val previous: Network, val why: Why) : State
    }

    sealed interface Last {
        /** On [target] with Home Assistant in reach. */
        data class Joined(val target: String) : Last

        /** [target] didn't work ([why]); back on [previous]. */
        data class Back(val target: String, val previous: String, val why: Why) : Last

        /** [target] didn't work ([why]), and [previous] didn't come back either. */
        data class Stuck(val target: String, val previous: String, val why: Why) : Last
    }

    var state: State = State.Idle()
        private set

    /** The change under way, as saved. */
    private var pending: Pending? = null

    /** Counts changes: an answer or a timer from an earlier one changes nothing. */
    private var run = 0
    private var cancelDeadline: (() -> Unit)? = null
    private var cancelLater: (() -> Unit)? = null

    fun block(): Block? = radio.block()
    fun current(): Network? = radio.current()
    fun nearby(): List<String> = runCatching { radio.nearby() }.getOrDefault(emptyList())

    /**
     * Starts a change to [name] with [password]: null once it is under way (the panel leaves its network after
     * [startDelayMs]), else why not. Nothing changes on a refusal.
     */
    fun start(name: String, password: String): Refusal? {
        radio.block()?.let { return Refusal.Blocked(it) }
        if (state !is State.Idle) return Refusal.Busy
        val prev = radio.current() ?: return Refusal.NotOnWifi
        if (prev.name == name) return Refusal.Same
        val p = Pending(name, prev, forget = !radio.isSaved(name))
        if (!store.save(p)) return Refusal.NotSaved
        val mine = ++run
        pending = p
        state = State.Joining(name, prev, clock() + budgetMs)
        log("Wi-Fi change: accepted (the new network ${if (p.forget) "isn't" else "is"} saved already); back on $prev unless it works within ${budgetMs / 1000} s")
        deadline(mine, budgetMs)
        later(mine, startDelayMs) {
            radio.armHotspot()
            radio.join(name, password) { r -> if (mine == run) joined(mine, r) }
        }
        onChange()
        return null
    }

    /**
     * After the app starts: a change saved by a run that ended part way is finished as it would have been. On the new
     * network with Home Assistant in reach within [resumeMs], it stays; else back to the old one.
     */
    fun resume() {
        if (state !is State.Idle) return
        val p = store.load() ?: return
        val mine = ++run
        pending = p
        state = State.Joining(p.target, p.previous, clock() + resumeMs)
        log("Wi-Fi change: picked up after a restart; back on ${p.previous} unless the new network works within ${resumeMs / 1000} s")
        deadline(mine, resumeMs)
        resumed(mine)
        onChange()
    }

    /** After a restart: wait for the panel to show which network it is on. */
    private fun resumed(mine: Int) {
        if (mine != run) return
        val s = state as? State.Joining ?: return
        val now = radio.current()
        when {
            now?.name == s.target -> { state = s.copy(joined = true); onChange(); check(mine) }
            // Back on the old one already (the join had failed, or never started): forget what it saved, and say so.
            now?.id == s.previous.id -> back(mine, Why.INTERRUPTED)
            else -> later(mine, checkEveryMs) { resumed(mine) }
        }
    }

    private fun joined(mine: Int, r: Joined) {
        val s = state as? State.Joining ?: return
        when (r) {
            Joined.Ok -> {
                log("Wi-Fi change: on the new network; asking Home Assistant")
                state = s.copy(joined = true)
                onChange()
                check(mine)
            }
            is Joined.Failed -> back(mine, r.why)
            Joined.Superseded -> {
                log("Wi-Fi change: other Wi-Fi details came in; that join is the one now")
                finish(mine, null)
            }
        }
    }

    /** On the new network: Home Assistant answers, or ask again until the deadline. */
    private fun check(mine: Int) {
        radio.reaches { ok ->
            if (mine != run || state !is State.Joining) return@reaches
            if (ok) {
                log("Wi-Fi change: Home Assistant answers on the new network; it stays")
                finish(mine, Last.Joined((state as State.Joining).target))
            } else later(mine, checkEveryMs) { check(mine) }
        }
    }

    private fun deadline(mine: Int, ms: Long) {
        cancelDeadline?.invoke()
        cancelDeadline = schedule(ms) {
            cancelDeadline = null
            if (mine != run) return@schedule
            val s = state as? State.Joining ?: return@schedule
            log("Wi-Fi change: ${if (s.joined) "Home Assistant didn't answer on the new network" else "the join didn't finish"} in time")
            if (!s.joined) radio.cancel()
            back(mine, if (s.joined) Why.NO_HOME_ASSISTANT else Why.TIMED_OUT)
        }
    }

    private fun back(mine: Int, why: Why) {
        val s = state as? State.Joining ?: return
        val p = pending ?: return
        cancelLater?.invoke(); cancelLater = null
        cancelDeadline?.invoke(); cancelDeadline = null
        state = State.GoingBack(s.target, s.previous, why)
        log("Wi-Fi change: ${why.name.lowercase()}; going back to ${s.previous}")
        onChange()
        radio.goBack(s.previous, s.target, p.forget) { ok ->
            if (mine != run) return@goBack
            if (ok) {
                log("Wi-Fi change: back on the previous network")
                finish(mine, Last.Back(s.target, s.previous.name, why))
            } else {
                log("Wi-Fi change: the previous network didn't come back; the setup hotspot opens if the panel stays offline")
                radio.armHotspot()
                finish(mine, Last.Stuck(s.target, s.previous.name, why))
            }
        }
    }

    private fun finish(mine: Int, last: Last?) {
        if (mine != run) return
        cancelLater?.invoke(); cancelLater = null
        cancelDeadline?.invoke(); cancelDeadline = null
        pending = null
        if (!store.save(null)) log("Wi-Fi change: couldn't clear the saved change; the next start checks it again")
        state = State.Idle(last)
        onChange()
    }

    private fun later(mine: Int, ms: Long, f: () -> Unit) {
        cancelLater?.invoke()
        cancelLater = schedule(ms) { cancelLater = null; if (mine == run) f() }
    }

    companion object {
        /** A change's 2 minutes: the words on both surfaces say it ([dev.glassworks.settings.WifiSettings]). */
        const val BUDGET_MS = 2 * 60_000L
    }
}
