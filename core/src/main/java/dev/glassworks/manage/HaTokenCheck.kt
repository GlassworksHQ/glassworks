/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

/**
 * Checks a Home Assistant access token that a browser brings from another panel's Manage page (#466): the
 * panel asks its own Home Assistant who the token belongs to (`auth/current_user`), and takes it only from an
 * administrator. No panel trusts another: each one asks Home Assistant itself, at the address it uses, and
 * only when that address is on the local network ([HaSignIn.isLocal]), as its own sign-in does.
 *
 * An administrator's answer is kept for [ttlMs] per token, so a page that reads and then changes a panel doesn't
 * make it ask Home Assistant each time; a sign-in ([check] with `fresh`) always asks, past both caches. A token Home Assistant didn't
 * confirm is kept as refused for [refusedTtlMs], and at most [maxAsking] questions to Home Assistant run at once,
 * so made-up tokens can't hold the panel's few web workers while Home Assistant is slow or down (verifier on #487).
 * Whether the address is local is decided once per [ttlMs] for each address, behind the same limit: deciding can
 * mean a name lookup (CodeRabbit on #487).
 * Only the token's SHA-256 is kept, in memory. The token never goes in a log.
 */
class HaTokenCheck(
    private val gateway: HaGateway,
    private val haBase: () -> String?,
    private val isLocal: (String) -> Boolean,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 60_000,
    private val refusedTtlMs: Long = 10_000,
    private val maxAsking: Int = 2,
    private val max: Int = 32,
) {
    sealed class Result {
        /** [haBase]: the Home Assistant address that confirmed [user]. */
        class Ok(val user: HaUser, val haBase: String) : Result()
        class NotAdmin(val name: String) : Result()
        /** The panel has no Home Assistant address, or one that isn't on the local network. */
        object NoHa : Result()
        /** No token, or one Home Assistant didn't confirm (unknown, expired, or Home Assistant didn't answer). */
        object Refused : Result()
        /** [maxAsking] questions to Home Assistant are running already: try again in a moment. */
        object Busy : Result()
    }

    /** What Home Assistant said for a token at [haBase]: its user, or null when it didn't confirm it. */
    private class Known(val haBase: String, val user: HaUser?, val atMs: Long)

    private val known = LinkedHashMap<String, Known>()

    /** The last address [isLocal] decided on, its answer, and when. */
    private class Local(val haBase: String, val local: Boolean, val atMs: Long)

    @Volatile private var local: Local? = null
    private val asking = java.util.concurrent.Semaphore(maxAsking)

    /**
     * [token] as Home Assistant answers for it now, or as it answered in the last [ttlMs] ([refusedTtlMs] for a
     * refusal). [fresh]: no answer from before counts, so a token revoked since can't start a sign-in, and one Home
     * Assistant refused while it was down can sign in once it's back. Still under the [maxAsking] limit.
     */
    fun check(token: String?, fresh: Boolean = false): Result {
        val t = token?.trim()
        if (t.isNullOrEmpty() || t.length > MAX_TOKEN || t.any { it.code <= 0x20 || it.code >= 0x7f }) return Result.Refused
        val base = haBase()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return Result.NoHa
        val now = monoMs()
        when (isLocalNow(base, now)) {
            null -> return Result.Busy
            false -> return Result.NoHa
            true -> Unit
        }
        val key = SessionStore.hash(t)
        // Again: deciding can take a name lookup's time, and an answer must not outlive its minute by that much.
        val checkedAt = monoMs()
        val before = synchronized(known) {
            known.entries.removeAll { checkedAt - it.value.atMs >= if (it.value.user != null) ttlMs else refusedTtlMs }
            known[key]?.takeIf { it.haBase == base && !fresh }
        }
        val user = if (before != null) before.user else {
            if (!asking.tryAcquire()) return Result.Busy
            val u = try { gateway.currentUser(base, t) } finally { asking.release() }
            synchronized(known) {
                known[key] = Known(base, u, monoMs())
                while (known.size > max) known.remove(known.keys.first())
            }
            u
        }
        if (user == null) return Result.Refused
        return if (user.isAdmin) Result.Ok(user, base) else Result.NotAdmin(user.name)
    }

    /** [isLocal] for [base], from the last [ttlMs]; else decided now under the [maxAsking] limit, or null when that's full. */
    private fun isLocalNow(base: String, now: Long): Boolean? {
        local?.takeIf { it.haBase == base && now - it.atMs < ttlMs }?.let { return it.local }
        if (!asking.tryAcquire()) return null
        return try {
            isLocal(base).also { local = Local(base, it, now) }
        } finally {
            asking.release()
        }
    }

    companion object {
        /** HA's access tokens are JWTs of a few hundred characters; anything far longer isn't one. */
        const val MAX_TOKEN = 4096

        /** The token in an `Authorization: Bearer …` header, or null. */
        fun bearer(header: String?): String? =
            header?.trim()?.takeIf { it.length > 7 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }?.substring(7)?.trim()
    }
}
