/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

/**
 * Holds work until it [open]s, then runs it in order; once open, runs work at once. It never opens by itself:
 * the lockdown fails closed. Main thread only.
 */
internal class ReadyGate {
    private var isOpen = false
    private val waiting = ArrayDeque<() -> Unit>()

    fun run(work: () -> Unit) {
        if (isOpen) work() else waiting.addLast(work)
    }

    /** Opens the gate and runs what waited. Returns true the first time only. */
    fun open(): Boolean {
        if (isOpen) return false
        isOpen = true
        while (waiting.isNotEmpty()) waiting.removeFirst()()
        return true
    }
}
