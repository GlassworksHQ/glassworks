package sh.edm.s1kiosk.auth

import java.net.URI

/**
 * Home Assistant as discovery last saw it: the [origin] it reached and the instance's [uuid] from the same mDNS
 * record (`_home-assistant._tcp` TXT `uuid`, HA's `instance_id`, written by `components/zeroconf/__init__.py`
 * `_async_get_local_service_info`). HA gives its uuid nowhere else without a token: `/api/config` needs one, and
 * its `config.as_dict()` has no uuid.
 */
data class HaSeen(val origin: String, val uuid: String)

/**
 * What the stored sign-in does when the dashboard's Home Assistant ([decide]'s `base`) is somewhere else (#467).
 * DHCP can move Home Assistant to a new address that discovery finds. The sign-in follows by itself only from https
 * to https, where the certificate proves the host, and only where the uuid stored with it answers. On http nothing
 * proves which device answers: the uuid is public in mDNS, and anything on the LAN can advertise it. So a moved
 * http Home Assistant always means signing in again, and no token goes to an address that isn't confirmed.
 */
sealed interface SignInMove {
    /** The sign-in is for the dashboard's address, or there is no address to compare. */
    data object Stay : SignInMove

    /** The sign-in's own address, and the panel learnt that Home Assistant's [uuid] (a sign-in from before #467). */
    data class Learn(val uuid: String) : SignInMove

    /** The same Home Assistant answers at [base] now, both addresses https: the sign-in moves there. */
    data class Move(val base: String) : SignInMove

    /** Not confirmed as the sign-in's Home Assistant (another address on http, another uuid): the person signs in again. */
    data object Unconfirmed : SignInMove

    companion object {
        fun decide(auth: StoredAuth, base: String?, seen: HaSeen?): SignInMove {
            val to = base?.let(OAuth::origin) ?: return Stay
            val at = seen?.takeIf { OAuth.origin(it.origin) == to }?.uuid
            if (OAuth.origin(auth.haBase) == to) {
                return when {
                    at == null || at == auth.haUuid -> Stay
                    auth.haUuid == null -> Learn(at)
                    else -> Unconfirmed // another Home Assistant took the sign-in's own address
                }
            }
            val https = scheme(auth.haBase) == "https" && scheme(to) == "https"
            return if (https && auth.haUuid != null && auth.haUuid == at) Move(to) else Unconfirmed
        }

        private fun scheme(url: String) = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
    }
}

/**
 * The stored sign-in ([load], [save]) kept with its Home Assistant: [now] learns HA's uuid at the sign-in's own
 * address and moves the sign-in where the same uuid answers over https ([SignInMove]), against the dashboard's address
 * ([base]) and what discovery saw ([seen]). Idempotent, so every reader of the sign-in's address goes through it.
 * [log] gets each change once, without addresses or tokens.
 */
class FollowedSignIn(
    private val load: () -> StoredAuth?,
    /** Throws [sh.edm.s1kiosk.SignInNotStored] when the key store can't take it now. */
    private val save: (StoredAuth) -> Unit,
    private val base: () -> String?,
    private val seen: () -> HaSeen?,
    private val log: (String) -> Unit = { AuthLog.i(it) },
) {
    private var logged: SignInMove? = null

    /** The sign-in and where it stands: [SignInMove.Stay] (usable) or [SignInMove.Unconfirmed]. Null when signed out. */
    @Synchronized
    fun now(): Pair<StoredAuth, SignInMove>? {
        val auth = load() ?: return null
        val move = SignInMove.decide(auth, base(), seen())
        val next = when (move) {
            is SignInMove.Learn -> auth.copy(haUuid = move.uuid)
            is SignInMove.Move -> auth.copy(haBase = move.base)
            else -> return auth to move.also(::logOnce)
        }
        try {
            save(next)
        } catch (e: sh.edm.s1kiosk.SignInNotStored) {
            log("${e.message}; the sign-in stays where it was")
            return auth to (if (move is SignInMove.Move) SignInMove.Unconfirmed else SignInMove.Stay).also(::logOnce)
        }
        logOnce(move)
        return next to SignInMove.Stay
    }

    /** [auth], just signed in, with the uuid discovery saw at its address. */
    fun withUuid(auth: StoredAuth): StoredAuth =
        auth.copy(haUuid = seen()?.takeIf { OAuth.origin(it.origin) == OAuth.origin(auth.haBase) }?.uuid)

    private fun logOnce(move: SignInMove) {
        val was = logged
        if (move == was) return
        logged = move
        when (move) {
            is SignInMove.Learn -> log("Sign-in: learnt which Home Assistant it is for (its uuid, from mDNS)")
            is SignInMove.Move -> log("Sign-in moved to Home Assistant's new https address: the same Home Assistant (uuid) answers there")
            SignInMove.Unconfirmed -> log("Home Assistant is at another address than the sign-in's, and the panel can't confirm " +
                "it's the same one: asking to sign in again, no token sent")
            SignInMove.Stay -> if (was == SignInMove.Unconfirmed) log("Home Assistant is back at the sign-in's address")
        }
    }
}
