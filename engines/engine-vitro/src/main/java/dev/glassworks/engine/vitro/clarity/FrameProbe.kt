/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.clarity

/**
 * One look at each frame for a Clarity wait ([VitroClarity]'s `awaitFrames`): the main thread calls [look] each
 * frame until [step] says done, and the waiting thread calls [end] when its wait is over, done or not.
 *
 * Both hold the probe's lock, so the two never overlap. A step already running when the wait ends finishes before
 * [end] returns, and none runs after it, so the waiting thread can read whatever the steps wrote (a dialog's frames,
 * whether it settled) without the next frame changing it underneath.
 */
internal class FrameProbe(private val step: (Double) -> Boolean) {
    private var ended = false
    private var finished = false

    /** Main thread, each frame: true once the probe can be dropped (its step said done, or its wait ended). */
    @Synchronized
    fun look(ts: Double): Boolean {
        if (ended || finished) return true
        finished = step(ts)
        return finished
    }

    /** The waiting thread, when its wait ends: whether the step said done. The step never runs again after this. */
    @Synchronized
    fun end(): Boolean {
        ended = true
        return finished
    }
}
