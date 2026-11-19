/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import dev.glassworks.frame.Input

/**
 * The order in which notifications' views take the screen (#230), by [NotificationStore.Entry.id]: one
 * is [open], the rest wait in the order they came. [NotificationStore] keeps it.
 *
 * - A new view waits behind the open one ([arrive]). The open one keeps the screen.
 * - One the person asked for (a button's `uri`, a tap in the list) opens at once, in front ([jump]).
 * - One that replaces a view with the same tag takes its place in the line ([replace]).
 * - [next] moves on. Next, Back now, the countdown running out, Back and a tap outside a half view all
 *   do this: the next waiting view opens, or the dashboard comes back.
 * - A cleared, dismissed or expired view leaves the line wherever it is ([remove]). A waiting view's
 *   timeout keeps running from when it came, so a stale one never opens.
 *
 * Which views join is the store's ([NotificationStore.Entry.queued]): not one Do not disturb holds,
 * and not a critical one (its stage covers everything). Immutable.
 */
class ViewQueue(ids: List<Long> = emptyList()) {
    /** Its own copy: a list the caller changes later doesn't change the queue. */
    val ids: List<Long> = ids.toList()

    override fun equals(other: Any?): Boolean = other is ViewQueue && other.ids == ids
    override fun hashCode(): Int = ids.hashCode()
    override fun toString(): String = "ViewQueue($ids)"

    val open: Long? get() = ids.firstOrNull()

    /** How many wait behind [open]. */
    val waiting: Int get() = (ids.size - 1).coerceAtLeast(0)

    fun arrive(id: Long): ViewQueue = if (id in ids) this else ViewQueue(ids + id)

    fun jump(id: Long): ViewQueue = ViewQueue(listOf(id) + (ids - id))

    fun replace(old: Long, new: Long): ViewQueue =
        if (old in ids) ViewQueue(ids.map { if (it == old) new else it }) else arrive(new)

    fun remove(id: Long): ViewQueue = if (id in ids) ViewQueue(ids - id) else this

    fun next(): ViewQueue = ViewQueue(ids.drop(1))

    companion object {
        /**
         * Seconds a view with no `timeout`, or an untagged high or critical card with none, stays on a screen that
         * takes no tap ([Input.NONE]): nothing else could close it (NotificationCenter.noTouchTimeout).
         */
        const val NO_TOUCH_VIEW_S = 120
    }
}

/** What the open view's one button says. Each moves on, as [ViewQueue.next]; the words say where it leads. */
enum class ViewButton(val label: String) {
    /** Another view waits. */
    NEXT("Next"),

    /** The last view, full screen: the dashboard comes back. */
    BACK_NOW("Back now"),

    /** The last view, half screen: the dialog closes over the dashboard. */
    CLOSE("Close");

    companion object {
        /**
         * The button for the open view, with [waiting] views behind it, or null on a screen that takes no tap
         * ([Input.NONE]): there the countdown and Home Assistant's `clear_notification` move on.
         */
        fun of(waiting: Int, size: ViewSize, input: Input): ViewButton? = when {
            input == Input.NONE -> null
            waiting > 0 -> NEXT
            size == ViewSize.FULL -> BACK_NOW
            else -> CLOSE
        }
    }
}
