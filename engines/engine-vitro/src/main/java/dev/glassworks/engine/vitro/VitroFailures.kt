/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a card's own failure goes once it is caught: one card that throws (a build, a state update, a draw) never
 * takes the view or the app down. A failing card shows HA's error card in its place
 * ([dev.glassworks.engine.vitro.ui.CardSurface.fail]); this only logs it, once per kind of failure, and counts the rest.
 * An update whose card isn't known ([GuardedHandler], the store's listeners) is dropped after it's logged.
 */
object VitroFailures {
    private const val TAG = "Vitro"

    /** Distinct failures logged with their stack per process; past it only the count grows ([contained]). */
    const val MAX_LOGGED = 16

    // A map, not ConcurrentHashMap.newKeySet (API 24): this module runs on Android 6.
    private val logged = ConcurrentHashMap<String, Boolean>()
    private val count = AtomicLong()

    /** Every failure contained since the process started (each one, logged or not). */
    val contained: Long get() = count.get()

    /** Tests: each contained failure as it's caught (where, the card's type or null, the throwable). Any thread. */
    @Volatile
    internal var listener: ((where: String, cardType: String?, error: Throwable) -> Unit)? = null

    /**
     * A failure caught at [where] (`build`, `measure`, `layout`, `draw`, `touch`, `update`, `action`) in a [cardType]
     * card, or in code no card owns (null). Logged once per kind: the same card type failing the same way at the same
     * place on every state update writes one line, not one per update. Never throws.
     */
    fun report(where: String, cardType: String?, error: Throwable) {
        count.incrementAndGet()
        runCatching { listener?.invoke(where, cardType, error) }
        val site = error.stackTrace.firstOrNull { it.className.startsWith("dev.glassworks") } ?: error.stackTrace.firstOrNull()
        val key = "$where|$cardType|${error.javaClass.name}|$site"
        if (logged.size >= MAX_LOGGED || logged.putIfAbsent(key, true) != null) return
        runCatching {
            val what = cardType?.let { "The $it card" } ?: "A card update"
            Log.w(TAG, "$what failed in $where and was contained (${error.javaClass.simpleName} at $site); " +
                "later failures like it aren't logged", error)
        }
    }

    /** Tests: forget what was logged and counted. */
    internal fun resetForTest() {
        logged.clear()
        count.set(0)
    }
}

/**
 * A main-thread [Handler] whose callbacks can't take the app down: one that throws is [VitroFailures.report]ed and
 * dropped. Cards post their template answers, timers and state updates through it ([dev.glassworks.engine.vitro.ui
 * .VitroCardContext.main]); a card's own entity updates are guarded per card as well, so that card shows the error
 * card instead.
 */
class GuardedHandler(looper: Looper = Looper.getMainLooper()) : Handler(looper) {
    override fun dispatchMessage(msg: Message) {
        try {
            super.dispatchMessage(msg)
        } catch (e: Exception) {
            VitroFailures.report("update", null, e)
        }
    }
}
