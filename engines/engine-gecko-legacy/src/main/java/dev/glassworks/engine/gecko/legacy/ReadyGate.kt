/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

/**
 * Holds work until it [open]s, then runs it in order; once open, runs work at once. It never opens by itself:
 * the lockdown fails closed. When it [fail]s, every waiting caller hears why (and later ones at once), so the
 * screen can say it; the work still runs if it opens after all. Main thread only.
 */
internal class ReadyGate {
    private var isOpen = false
    private var failure: String? = null
    private val waiting = ArrayDeque<Pair<() -> Unit, (String) -> Unit>>()

    fun whenOpen(stuck: (String) -> Unit = {}, work: () -> Unit) {
        if (isOpen) return work()
        waiting.addLast(work to stuck)
        failure?.let(stuck)
    }

    /** Opens the gate and runs what waited. Returns true the first time only. */
    fun open(): Boolean {
        if (isOpen) return false
        isOpen = true
        failure = null
        while (waiting.isNotEmpty()) waiting.removeFirst().first()
        return true
    }

    /** Tells every waiting caller [why] nothing loads. Returns false if the gate is already open. */
    fun fail(why: String): Boolean {
        if (isOpen) return false
        failure = why
        waiting.forEach { it.second(why) }
        return true
    }
}
