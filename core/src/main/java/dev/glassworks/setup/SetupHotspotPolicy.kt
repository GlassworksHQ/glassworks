/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

/**
 * When the panel opens its own Wi-Fi for setup (#197), as decided in #38:
 *
 * 1. Only when no saved Wi-Fi works: no connection [graceMs] (3 min) after the app starts. Or when asked
 *    from the setup screen ([ask]).
 * 2. Never while the panel is connected.
 * 3. It stops once Wi-Fi is configured ([configured]) or after [maxOnMs] (15 min). Then the panel retries
 *    its saved network for another [graceMs], and rule 1 applies again.
 *
 * "None saved" has no shortcut: since Android 10 an ordinary app can't list the saved networks
 * (`getConfiguredNetworks` returns an empty list), so a panel with none waits the same 3 minutes. The
 * setup screen can ask for the hotspot at once.
 *
 * The automatic start is armed from the start of the run until the panel first connects: a panel that has
 * been online since starting doesn't take itself off a router that reboots. Stop on the setup screen, or
 * the end of setup ([cancel]), disarms it too, so a hotspot someone closed doesn't come back by itself.
 * Configuring Wi-Fi arms it again: if the new network fails, the hotspot comes back for another try,
 * which is the only way back on a panel without touch.
 *
 * Plain Kotlin on a monotonic clock in ms ([update]'s `now`). [SetupHotspot] drives it on the panel.
 */
class SetupHotspotPolicy(
    start: Long,
    private val graceMs: Long = 3 * 60_000L,
    private val maxOnMs: Long = 15 * 60_000L,
) {
    sealed interface State {
        /**
         * Off. It starts by itself once the panel has been offline [graceMs] since [since], which moves to the
         * moment a connection drops. Until [holdUntil], a connection doesn't disarm it: right after new Wi-Fi
         * details the panel is often still on the old network, which it leaves to join the new one.
         */
        data class Waiting(val since: Long, val holdUntil: Long? = null) : State

        /** Off, and only [ask] starts it. */
        data object Idle : State

        /**
         * On since [since], until [until]. [asked]: from the setup screen, not by rule 1. [armed]: when it times
         * out, rule 1 counts again ([Waiting]); otherwise it goes [Idle], as it was before someone asked.
         */
        data class On(val since: Long, val until: Long, val asked: Boolean, val armed: Boolean) : State
    }

    /**
     * Why the hotspot last went off: shown on the setup screen, and logged. [ANDROID]: Android closed it by itself (on
     * Android 10, after 10 minutes with nobody on it).
     */
    enum class Stop { CONNECTED, CONFIGURED, TIMED_OUT, ANDROID, CANCELLED, FAILED }

    /** Have the hotspot on when [on]. Call [update] again after [wakeInMs], or on the next change when null. */
    data class Decision(val on: Boolean, val wakeInMs: Long?)

    var state: State = State.Waiting(start)
        private set

    /** Why the hotspot last stopped, or null when it hasn't run yet. */
    var lastStop: Stop? = null
        private set

    /** After any change in [connected], and when the last [Decision.wakeInMs] is up. */
    fun update(connected: Boolean, now: Long): Decision {
        val before = state
        // Offline from now: the 3 minutes count from the drop, not from the last update that saw the link.
        val s = if (!connected && wasConnected && before is State.Waiting) before.copy(since = now) else before
        wasConnected = connected
        state = when {
            connected -> when {
                s is State.Waiting && s.holdUntil != null && now < s.holdUntil -> s
                else -> {
                    if (s is State.On) lastStop = Stop.CONNECTED
                    State.Idle
                }
            }
            s is State.Waiting && now - s.since >= graceMs -> State.On(now, now + maxOnMs, asked = false, armed = true)
            s is State.On && now >= s.until -> {
                lastStop = Stop.TIMED_OUT
                if (s.armed) State.Waiting(now) else State.Idle
            }
            else -> s
        }
        return decision(now, connected)
    }

    /**
     * The setup screen asks for the hotspot. Refused (false) while [connected] (rule 2); the panel is on the
     * network, and the setup page is already there.
     */
    fun ask(connected: Boolean, now: Long): Boolean {
        if (connected) return false
        val s = state
        if (s is State.On) return true
        state = State.On(now, now + maxOnMs, asked = true, armed = s is State.Waiting)
        return true
    }

    /** Stop on the setup screen, or setup finished: off, and no automatic start until Wi-Fi is configured again. */
    fun cancel() {
        if (state is State.On) lastStop = Stop.CANCELLED
        state = State.Idle
    }

    /**
     * Wi-Fi details arrived (the setup page, Bluetooth, USB): off, so the panel can join, and armed again. A
     * connection in the next [graceMs] (the old network, before the panel leaves it) keeps it armed: if the new
     * network then fails, the hotspot comes back [graceMs] after the panel was last connected. A connection that
     * outlasts the hold disarms it as usual: a join that starts only after [graceMs] and fails gets no hotspot until
     * the next start or an [ask] (docs/manage.md says so).
     */
    fun configured(now: Long) {
        if (state is State.On) lastStop = Stop.CONFIGURED
        state = State.Waiting(now, holdUntil = now + graceMs)
    }

    /**
     * Android closed the hotspot by itself (S1, Android 10: SoftApManager's 10-minute timeout with no station). As
     * after [maxOnMs]: the saved network gets [graceMs], then rule 1 again (S1 panel run on e7d2d49d).
     */
    fun androidStopped(now: Long) {
        val s = state as? State.On ?: return
        lastStop = Stop.ANDROID
        state = if (s.armed) State.Waiting(now) else State.Idle
    }

    /**
     * Rule 1 was about to start the hotspot, but the Wi-Fi radio was off, so no saved network could have joined in
     * the [graceMs] it waited. The radio is on now: the saved network gets its [graceMs] from [now] first, as after
     * new Wi-Fi details, and the hotspot stays off. Only from rule 1's own start ([State.On] not [State.On.asked]).
     */
    fun radioSwitchedOn(now: Long) {
        val s = state as? State.On ?: return
        if (s.asked) return
        state = State.Waiting(now)
    }

    /** The hotspot didn't start: off, until someone asks again. */
    fun failed() {
        if (state is State.On) lastStop = Stop.FAILED
        state = State.Idle
    }

    /** Whether the last [update] saw a connection. */
    private var wasConnected = false

    /** Time left before the hotspot stops by itself, while it's on. */
    fun leftMs(now: Long): Long? = (state as? State.On)?.let { (it.until - now).coerceAtLeast(0) }

    private fun decision(now: Long, connected: Boolean): Decision = when (val s = state) {
        // Connected during a hold: wake at its end too, where a connection that outlasts it disarms (CodeRabbit on #418).
        is State.Waiting -> Decision(false, minOf(s.since + graceMs, s.holdUntil?.takeIf { connected && it > now } ?: Long.MAX_VALUE) - now)
        State.Idle -> Decision(false, null)
        is State.On -> Decision(true, s.until - now)
    }
}
