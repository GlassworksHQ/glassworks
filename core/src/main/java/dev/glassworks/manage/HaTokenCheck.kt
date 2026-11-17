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
 * An answer is kept for [ttlMs] per token, so a page that reads and then changes a panel doesn't make it ask
 * Home Assistant each time. A token Home Assistant didn't confirm is not kept: the next request asks again.
 * Only the token's SHA-256 is kept, in memory. The token never goes in a log.
 */
class HaTokenCheck(
    private val gateway: HaGateway,
    private val haBase: () -> String?,
    private val isLocal: (String) -> Boolean,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 60_000,
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
    }

    private class Known(val haBase: String, val user: HaUser, val atMs: Long)

    private val known = LinkedHashMap<String, Known>()

    /** [token] as Home Assistant answers for it now, or from the last [ttlMs]. */
    fun check(token: String?): Result {
        val t = token?.trim()
        if (t.isNullOrEmpty() || t.length > MAX_TOKEN || t.any { it.code <= 0x20 || it.code >= 0x7f }) return Result.Refused
        val base = haBase()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && isLocal(it) } ?: return Result.NoHa
        val key = SessionStore.hash(t)
        val now = monoMs()
        val user = synchronized(known) {
            known.entries.removeAll { now - it.value.atMs >= ttlMs }
            known[key]?.takeIf { it.haBase == base }?.user
        } ?: gateway.currentUser(base, t)?.also { u ->
            synchronized(known) {
                known[key] = Known(base, u, now)
                while (known.size > max) known.remove(known.keys.first())
            }
        } ?: return Result.Refused
        return if (user.isAdmin) Result.Ok(user, base) else Result.NotAdmin(user.name)
    }

    companion object {
        /** HA's access tokens are JWTs of a few hundred characters; anything far longer isn't one. */
        const val MAX_TOKEN = 4096

        /** The token in an `Authorization: Bearer …` header, or null. */
        fun bearer(header: String?): String? =
            header?.trim()?.takeIf { it.length > 7 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }?.substring(7)?.trim()
    }
}
