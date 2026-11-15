package sh.edm.s1kiosk.auth

import android.os.SystemClock

/**
 * Who the dashboard is signed in as in Home Assistant, for the "Dashboard sign-in" row. Asks Home
 * Assistant once per sign-in, and again a minute after an ask that got no name. A new sign-in (another
 * stored one) forgets the name, and an answer about an earlier sign-in is dropped. The runtime owns the
 * one instance.
 */
class DashboardAccount(
    /** The panel's stored sign-in; null when it holds none. A new sign-in is a different value. */
    private val stored: () -> StoredAuth?,
    /** Asks Home Assistant at the address who the stored sign-in is; answers with the name, or null, on any thread. */
    private val ask: (base: String, done: (String?) -> Unit) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** The sign-in [name] and [askedAt] are about. */
    private var about: StoredAuth? = null
    private var name: String? = null
    private var askedAt: Long? = null

    /**
     * "Signed in as Alex", or "Signed in" until Home Assistant has said. Null while the panel holds no
     * sign-in of its own: one made on the panel's own screen leaves none, so the app can't say.
     */
    fun shown(): String? {
        val now = stored() ?: return null
        synchronized(this) {
            if (now != about) { about = now; name = null; askedAt = null }
            name?.let { return "Signed in as $it" }
            val t = clock()
            val last = askedAt
            if (last != null && t - last <= RETRY_MS) return SIGNED_IN
            askedAt = t
        }
        ask(now.haBase) { answer -> synchronized(this) { if (answer != null && about == now) name = answer } }
        return SIGNED_IN
    }

    companion object {
        const val SIGNED_IN = "Signed in"
        const val RETRY_MS = 60_000L
    }
}
