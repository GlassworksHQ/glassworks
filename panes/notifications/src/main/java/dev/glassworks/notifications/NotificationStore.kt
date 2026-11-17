/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

/**
 * What the panel does with a notification when it arrives (#118).
 *
 * | Priority | Shows | Sound | Wakes the screen | Over night dimming | Card folds into the list |
 * |---|---|---|---|---|---|
 * | low | when the screen is next on | no | no | no | after 10 s on screen |
 * | normal | when the screen is next on | yes | no | no | after 15 s on screen |
 * | high | at once | yes | yes | yes | no: stays until answered |
 * | critical | at once | yes | yes | yes | no: stays until answered |
 *
 * Do not disturb holds everything but critical: it goes to the list, silent, and the screen stays
 * as it is.
 */
data class Delivery(
    val held: Boolean,
    val sound: Boolean,
    val wake: Boolean,
    val overNight: Boolean,
    /**
     * Seconds the card stays over the dashboard while the screen is on, before it folds into the list.
     * Null: it stays until it is answered, dismissed, cleared or times out. A held one (Do not
     * disturb) has none: it has no card until the person opens it from the list, and then it stays
     * until they close it.
     */
    val foldAfterS: Int? = null,
) {
    companion object {
        const val FOLD_LOW_S = 10
        const val FOLD_NORMAL_S = 15

        fun of(priority: Priority, doNotDisturb: Boolean): Delivery = when {
            priority == Priority.CRITICAL -> Delivery(held = false, sound = true, wake = true, overNight = true)
            doNotDisturb -> Delivery(held = true, sound = false, wake = false, overNight = false)
            priority == Priority.HIGH -> Delivery(held = false, sound = true, wake = true, overNight = true)
            priority == Priority.NORMAL -> Delivery(held = false, sound = true, wake = false, overNight = false, foldAfterS = FOLD_NORMAL_S)
            else -> Delivery(held = false, sound = false, wake = false, overNight = false, foldAfterS = FOLD_LOW_S)
        }
    }
}

/**
 * The notifications on the panel, newest first. In memory only: a restart starts empty, because Home
 * Assistant holds the facts and can send again (#118's open question).
 *
 * - A notification with a tag replaces the one with the same tag.
 * - [clear] closes by tag, [dismiss] by id, [expire] the ones whose timeout has passed.
 * - A *held* entry is in the list but has no card: Do not disturb held it. Tapping it in the list shows it.
 * - Views ([Notification.url]) take the screen one at a time, in the order of a [ViewQueue] (#230): the
 *   open one keeps it, later ones wait, and closing the open one opens the next. A critical one is never
 *   a view: it is a card (the alert stage), whose view opens from there ([openView]).
 * - At most [max] entries; past that the oldest goes, critical ones last.
 *
 * Thread-safe. [now] is in milliseconds (`SystemClock.elapsedRealtime` on the panel).
 */
class NotificationStore(private val now: () -> Long, val max: Int = 20) {
    /**
     * Tests only: runs after each of [cards], [page] and [list] returns, never inside [snapshot]. A test
     * changes the store from it, to prove a render reads one snapshot and not three separate lists.
     */
    internal var afterRead: ((String) -> Unit)? = null


    data class Entry(
        val id: Long,
        val notification: Notification,
        val postedAt: Long,
        val delivery: Delivery,
        val held: Boolean = delivery.held,
    ) {
        val expiresAt: Long? get() = notification.timeoutS?.let { postedAt + it * 1000L }

        /** A view that takes the screen in its turn: one with a `url`, not held, and not critical. */
        val queued: Boolean get() = notification.url != null && !held && notification.priority != Priority.CRITICAL
    }

    /** What [post] did: the new entry, the one it replaced (same tag), and any dropped to stay under [max]. */
    data class Posted(val entry: Entry, val replaced: Entry?, val dropped: List<Entry>)

    private val entries = ArrayList<Entry>()
    private var nextId = 1L
    private var views = ViewQueue()

    /**
     * Adds [n]. A view joins the line behind the open one, or takes the place of the one it replaces (same
     * tag); [jump]: the person asked for it, so it opens at once, in front.
     */
    @Synchronized
    fun post(n: Notification, doNotDisturb: Boolean, jump: Boolean = false): Posted {
        val replaced = n.tag?.let { t -> entries.firstOrNull { it.notification.tag == t } }
        if (replaced != null) entries.remove(replaced)
        // Overdue entries are gone already: they must not take a place from the new one.
        val t = now()
        removeWhere { e -> e.expiresAt?.let { it <= t } == true }
        val e = Entry(nextId++, n, t, Delivery.of(n.priority, doNotDisturb))
        entries.add(0, e)
        if (e.queued) {
            views = when {
                jump -> views.remove(replaced?.id ?: -1).jump(e.id)
                replaced != null -> views.replace(replaced.id, e.id)
                else -> views.arrive(e.id)
            }
        } else if (replaced != null) {
            views = views.remove(replaced.id)
        }
        val dropped = ArrayList<Entry>()
        while (entries.size > max) {
            val victim = entries.lastOrNull { it.notification.priority != Priority.CRITICAL } ?: entries.last()
            remove(victim)
            dropped += victim
        }
        return Posted(e, replaced, dropped)
    }

    @Synchronized
    fun clear(tag: String): Entry? = entries.firstOrNull { it.notification.tag == tag }?.also { remove(it) }

    @Synchronized
    fun dismiss(id: Long): Entry? = entries.firstOrNull { it.id == id }?.also { remove(it) }

    /**
     * The view of critical entry [id], from its alert stage (#230): the alert closes, and its `url` opens at
     * once, in front, until it is closed. Null when there is no such entry or it has no view.
     */
    @Synchronized
    fun openView(id: Long): Entry? {
        val e = entries.firstOrNull { it.id == id } ?: return null
        val url = e.notification.url ?: return null
        remove(e)
        // The same tag: clear_notification closes the view, and a same-tag post replaces it.
        val n = Notification(e.notification.title.ifEmpty { e.notification.message }, "", tag = e.notification.tag, url = url, sound = Sound.NONE)
        return post(n, doNotDisturb = false, jump = true).entry
    }

    private fun remove(e: Entry) {
        entries.remove(e)
        views = views.remove(e.id)
    }

    private fun removeWhere(gone: (Entry) -> Boolean) {
        entries.filter(gone).forEach(::remove)
    }

    /**
     * Do not disturb turned on: every entry it would have held now is held too, so its card and its
     * page go to the list. Critical ones stay. True when something changed. (Turning it off holds
     * nothing back out: the list keeps them, #118.)
     */
    @Synchronized
    fun holdForDoNotDisturb(): Boolean {
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            if (!e.held && Delivery.of(e.notification.priority, doNotDisturb = true).held) {
                entries[i] = e.copy(held = true)
                views = views.remove(e.id)
                changed = true
            }
        }
        return changed
    }

    /** Shows an entry tapped in the list: a held one gets its card back, and a view opens at once, in front. */
    @Synchronized
    fun show(id: Long): Entry? {
        val i = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val e = entries[i].copy(held = false)
        entries[i] = e
        if (e.queued) views = views.jump(e.id)
        return e
    }

    /** What [act] did: the action tapped, the notification's tag, and whether it closed. */
    data class Acted(val action: NotificationAction, val tag: String?, val closed: Boolean)

    /**
     * A tap on [actionId] of entry [id], in one step: the entry can't be replaced or cleared between
     * finding it and reading its tag. It closes unless [Notification.sticky]. Null when there is no
     * such entry or action.
     */
    @Synchronized
    fun act(id: Long, actionId: String): Acted? {
        val e = entries.firstOrNull { it.id == id } ?: return null
        val a = e.notification.actions.firstOrNull { it.id == actionId } ?: return null
        val closes = !e.notification.sticky
        if (closes) remove(e)
        return Acted(a, e.notification.tag, closes)
    }

    /** Removes and returns every entry whose timeout has passed. */
    @Synchronized
    fun expire(): List<Entry> {
        val t = now()
        val gone = entries.filter { e -> e.expiresAt?.let { it <= t } == true }
        gone.forEach(::remove)
        return gone
    }

    /** When the next timeout falls, or null for none. */
    @Synchronized
    fun nextExpiry(): Long? = entries.mapNotNull { it.expiresAt }.minOrNull()

    /** What the panel shows, read in one step: the cards, the open page and the whole list. */
    data class Snapshot(val cards: List<Entry>, val page: Entry?, val list: List<Entry>)

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(cardsNow(), pageNow(), entries.toList())

    /** Every entry, newest first. */
    fun list(): List<Entry> = synchronized(this) { entries.toList() }.also { afterRead?.invoke("list") }

    /** The view that is open, if any: the first in the [ViewQueue]. */
    fun page(): Entry? = synchronized(this) { pageNow() }.also { afterRead?.invoke("page") }

    /** The cards over the dashboard: entries that aren't held and aren't a view (a critical one is always a card), newest first. */
    fun cards(): List<Entry> = synchronized(this) { cardsNow() }.also { afterRead?.invoke("cards") }

    private fun pageNow() = views.open?.let { id -> entries.firstOrNull { it.id == id } }
    private fun cardsNow() = entries.filter { !it.held && !it.queued }
}
