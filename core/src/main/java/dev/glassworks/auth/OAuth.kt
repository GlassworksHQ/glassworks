package dev.glassworks.auth

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * Home Assistant OAuth2 / IndieAuth client logic for the QR sign-in (pure JVM, see OAuthTest).
 *
 * Checked against Home Assistant 2026.11.0:
 * - `components/auth/indieauth.py` `verify_redirect_uri`: the redirect_uri is accepted without any
 *   fetch when it has the **same scheme and netloc** (host + port) as the client_id;
 *   `_parse_client_id`: http(s), no fragment/userinfo, and the host is a domain name or a *local*
 *   IP (private LAN addresses are fine).
 * - `components/auth/__init__.py` `TokenView`: form-encoded POST `/auth/token`;
 *   `grant_type=authorization_code` + `code` + `client_id` returns `access_token`, `token_type`,
 *   `refresh_token`, `expires_in` (1800), `ha_auth_provider`; codes are single use and expire after
 *   10 min (`AUTH_CODE_EXPIRATION`) → `{"error":"invalid_request","error_description":"Invalid code"}`.
 *   `grant_type=refresh_token` + `refresh_token` + `client_id` returns `access_token`, `expires_in`;
 *   the client_id must equal the one the refresh token was created for, else `invalid_request`; an
 *   unknown/revoked token gives `invalid_grant`. `POST /auth/revoke` with `token` always answers 200.
 * - frontend `src/data/auth.ts` `redirectWithAuthCode`: the phone is sent to
 *   `<redirect_uri>?code=<code>&state=<state>` (plus `&storeToken=true` if ticked).
 * - PKCE (RFC 7636, S256 only): `components/auth/login_flow.py` `LoginFlowIndexView` takes
 *   `code_challenge` + `code_challenge_method`; `__init__.py` `TokenView` `_verify_code_verifier` then
 *   requires `code_verifier` at `/auth/token`. HA before 2026.10 ignores both, so we always send them.
 */
object OAuth {
    const val SIGNIN_PORT = 8099

    /** `http://<host:port>/`: the client_id is the panel's own URL, as the phone reached it. */
    fun clientId(hostPort: String): String = "http://$hostPort/"

    fun redirectUri(hostPort: String): String = "http://$hostPort/callback"

    /**
     * Validates a `Host` header for use in the client_id: an IPv4 address or a hostname, with an
     * optional port. Anything else (userinfo, paths, IPv6 brackets we don't need) is refused.
     */
    fun sanitizeHost(host: String?): String? {
        val h = host?.trim()?.lowercase() ?: return null
        return h.takeIf { HOST_RE.matches(it) }
    }

    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?$")

    fun authorizeUrl(haBase: String, clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        trimBase(haBase) + "/auth/authorize?response_type=code" +
            "&client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(redirectUri) +
            "&state=" + enc(state) +
            "&code_challenge=" + enc(codeChallenge) +
            "&code_challenge_method=S256"

    /**
     * `http://h:8123/` or `http://h:8123/lovelace/x` → `http://h:8123` (scheme://host[:port]), stripping default
     * ports (`:80` for `http`, `:443` for `https`) per RFC 6454 so origins match what browsers report. A user and
     * password in the URL aren't part of its origin: they stay out of the base the panel logs, shows and signs in to (#449).
     */
    fun origin(url: String): String? = try {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase(Locale.ROOT)
        val host = u.host?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) null
        else {
            val defaultPort = if (scheme == "http") 80 else 443
            val port = u.port
            if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }
    } catch (_: Exception) {
        null
    }

    fun trimBase(url: String): String = url.trim().trimEnd('/')

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    /** Parses a URL query (`a=1&b=x%20y`); repeated keys keep the first value. */
    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = dec(if (i < 0) part else part.substring(0, i))
            val v = if (i < 0) "" else dec(part.substring(i + 1))
            if (k !in out) out[k] = v // the first wins (putIfAbsent is API 24)
        }
        return out
    }

    private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private val random = SecureRandom()

    fun randomState(): String {
        val b = ByteArray(24)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** 32 random bytes → 43 base64url chars, inside HA's `^[A-Za-z0-9._~-]{43,128}$`. */
    fun newCodeVerifier(): String {
        val b = ByteArray(32)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** S256: base64url (no padding) of SHA-256 over the ASCII verifier. */
    fun codeChallenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/**
 * One pending sign-in: what /signin (or /thread) promised HA, needed again at its callback.
 * [purpose] keeps the two flows apart: a /signin state is refused at /thread/callback and back.
 */
data class PendingSignIn(
    val clientId: String, val redirectUri: String, val haBase: String, val createdAt: Long,
    val purpose: String = PURPOSE_SIGNIN,
    /** PKCE secret for this sign-in. Never log it. */
    val codeVerifier: String,
    /** Where it started, so how its callback ends (#453). */
    val then: Then = Then.QR,
    /** A setup sign-in's binding cookie ([StateStore.create]), or -1. */
    val slot: Int = -1,
    /**
     * Started while the panel was signed in, by someone allowed to replace that sign-in ([SignInServer]: a browser signed
     * in to Manage, or while the panel's screen asked for a sign-in). Its callback may then replace it.
     */
    val replaces: Boolean = false,
) {
    override fun toString() =
        "PendingSignIn(clientId=$clientId, redirectUri=$redirectUri, haBase=$haBase, createdAt=$createdAt, " +
            "purpose=$purpose, then=$then, slot=$slot, replaces=$replaces, codeVerifier=<redacted>)"

    companion object {
        const val PURPOSE_SIGNIN = "signin"
        const val PURPOSE_THREAD = "thread"
    }
}

/**
 * Where a sign-in started (`/signin/continue?then=…`), so how its callback ends: the QR code's done page, back to the
 * setup page in the same tab (bound to that browser), or Kiln's window, which closes as Kiln moves on by itself.
 */
enum class Then {
    QR, SETUP, KILN;

    companion object {
        fun of(param: String?) = when (param) { "setup" -> SETUP; "kiln" -> KILN; else -> QR }
    }
}

/** What the caller needs to build the authorize URL. The verifier stays in the store. */
data class NewSignIn(val state: String, val codeChallenge: String, val slot: Int = -1)

/**
 * Where a [StateStore] keeps its pending sign-ins, so they outlive the app's process: one text, sealed by the caller
 * (each sign-in holds its PKCE verifier). The panel restarts its process when Home Assistant's address first goes in
 * (KioskPrefs.engineStart), which Kiln's Home Assistant step does right before it opens the sign-in window: a sign-in
 * kept only in memory was gone by the time Home Assistant sent the person back, and its first try always ran out.
 */
interface SavedSignIns {
    fun read(): String?
    /** [text] null: none pending. Throws when it couldn't be stored; the sign-ins then live in memory alone. */
    fun write(text: String?)
}

/**
 * OAuth `state` values handed out by /signin. Single use, expire after [ttlMs] (HA's own codes live
 * 10 minutes), at most [max] outstanding (oldest dropped). Thread-safe. With [saved], every change is written through
 * before the caller hands the state out, and a new store (the next process) takes up what the last one left.
 */
class StateStore(
    private val ttlMs: Long = 10 * 60_000L,
    private val max: Int = 16,
    private val clock: () -> Long = System::currentTimeMillis,
    private val saved: SavedSignIns? = null,
) {
    private val pending = LinkedHashMap<String, PendingSignIn>()

    init {
        val text = saved?.let { s -> runCatching { s.read() }.onFailure { AuthLog.w("pending sign-ins can't be read", it) }.getOrNull() }
        if (text != null) {
            pending.putAll(decode(text))
            val before = pending.size
            prune()
            if (pending.size != before) save()
            if (pending.isNotEmpty()) AuthLog.i("${pending.size} pending sign-in(s) carried over from before the restart")
        }
    }

    @Synchronized
    fun create(
        clientId: String, redirectUri: String, haBase: String, purpose: String = PendingSignIn.PURPOSE_SIGNIN, then: Then = Then.QR,
        replaces: Boolean = false,
    ): NewSignIn {
        prune()
        while (pending.size >= max) pending.remove(pending.keys.first())
        val s = OAuth.randomState()
        val verifier = OAuth.newCodeVerifier()
        // A setup sign-in gets a slot no other pending one holds, so its binding cookie never replaces a live one,
        // and a browser never holds more than [max] of them. There is always one free: fewer than [max] are pending.
        val slot = if (then == Then.SETUP) (0 until max).first { n -> pending.values.none { it.then == Then.SETUP && it.slot == n } } else -1
        pending[s] = PendingSignIn(clientId, redirectUri, haBase, clock(), purpose, verifier, then, slot, replaces)
        save()
        return NewSignIn(s, OAuth.codeChallenge(verifier), slot)
    }

    /**
     * Returns and removes the pending sign-in for [state], or null if unknown/expired/used, or made
     * for another [purpose] (then it is left alone).
     */
    @Synchronized
    fun consume(state: String?, purpose: String = PendingSignIn.PURPOSE_SIGNIN): PendingSignIn? {
        if (state.isNullOrEmpty()) return null
        prune()
        val p = pending[state] ?: return null
        if (p.purpose != purpose) return null
        return pending.remove(state).also { save() }
    }

    @Synchronized
    fun size(): Int { prune(); return pending.size }

    /** Drops every pending sign-in (a factory reset): none of their callbacks finishes now. */
    @Synchronized
    fun clear() {
        pending.clear()
        save()
    }

    private fun prune() {
        val now = clock()
        pending.entries.removeAll { now - it.value.createdAt >= ttlMs }
    }

    /**
     * Writes what is pending now. A failure leaves the sign-ins in memory, as before there was a [saved]: they work
     * unless the process restarts. An old copy left behind can't be used twice: its state is single use at HA too.
     */
    private fun save() {
        val s = saved ?: return
        try {
            s.write(if (pending.isEmpty()) null else encode(pending))
        } catch (t: Throwable) {
            AuthLog.w("pending sign-ins not saved; they last until the app restarts", t)
        }
    }

    private companion object {
        fun encode(pending: Map<String, PendingSignIn>): String = JSONArray().apply {
            for ((state, p) in pending) put(JSONObject()
                .put("state", state).put("client_id", p.clientId).put("redirect_uri", p.redirectUri).put("ha_base", p.haBase)
                .put("created_at", p.createdAt).put("purpose", p.purpose).put("verifier", p.codeVerifier)
                .put("then", p.then.name).put("slot", p.slot).put("replaces", p.replaces))
        }.toString()

        /** What [encode] wrote; anything it can't read is left out (a sign-in it drops just runs out). */
        fun decode(text: String): Map<String, PendingSignIn> {
            val a = try { JSONArray(text) } catch (_: JSONException) { return emptyMap() }
            val out = LinkedHashMap<String, PendingSignIn>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                try {
                    val then = Then.entries.firstOrNull { it.name == o.getString("then") } ?: continue
                    out[o.getString("state")] = PendingSignIn(
                        o.getString("client_id"), o.getString("redirect_uri"), o.getString("ha_base"), o.getLong("created_at"),
                        o.getString("purpose"), o.getString("verifier"), then, o.getInt("slot"),
                        // Saved before it was recorded: no replacing.
                        o.optBoolean("replaces", false),
                    )
                } catch (_: JSONException) {
                }
            }
            return out
        }
    }
}

/** Parsed `/auth/token` reply. */
sealed class TokenResult {
    data class Ok(
        val accessToken: String,
        val expiresIn: Long,
        /** Only for grant_type=authorization_code. */
        val refreshToken: String?,
    ) : TokenResult() {
        override fun toString() = "Ok(expiresIn=$expiresIn, refreshToken=${if (refreshToken != null) "<set>" else "none"})"
    }

    /** OAuth error (`invalid_grant`, `invalid_request`, `access_denied`, …) or a transport problem. */
    data class Error(val code: String, val description: String, val httpStatus: Int) : TokenResult() {
        /** HA no longer knows the refresh token / code: the panel must sign in again. */
        val isRevoked: Boolean get() = code == "invalid_grant" || code == "access_denied"
        val isInvalidCode: Boolean get() = code == "invalid_request" && description.contains("code", ignoreCase = true)
    }

    companion object {
        fun parse(status: Int, body: String): TokenResult {
            val json = try { JSONObject(body) } catch (_: JSONException) { null }
            if (status in 200..299 && json != null) {
                val at = json.optString("access_token", "")
                if (at.isNotEmpty()) {
                    return Ok(at, json.optLong("expires_in", 1800L), json.optString("refresh_token", "").ifEmpty { null })
                }
                return Error("invalid_response", "no access_token in reply", status)
            }
            if (json != null && json.has("error")) {
                return Error(json.optString("error"), json.optString("error_description", ""), status)
            }
            return Error(if (status <= 0) "network_error" else "http_$status", body.take(200), status)
        }
    }
}

/**
 * Caches the access token until [marginMs] before it expires (`expires_in` is 1800 s on HA).
 * Thread-safe.
 */
class AccessTokenCache(private val marginMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private var token: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun put(accessToken: String, expiresInSec: Long) {
        token = accessToken
        expiresAt = clock() + expiresInSec * 1000L
    }

    /** The cached token if it is still valid for at least the margin. */
    @Synchronized
    fun get(): String? = token?.takeIf { clock() < expiresAt - marginMs }

    /** Seconds left (for the frontend's `expires_in`). */
    @Synchronized
    fun secondsLeft(): Long = ((expiresAt - clock()) / 1000L).coerceAtLeast(0L)

    @Synchronized
    fun clear() { token = null; expiresAt = 0L }
}

/** What is stored after a successful sign-in. Never log [refreshToken]. */
data class StoredAuth(val haBase: String, val clientId: String, val refreshToken: String) {
    override fun toString() = "StoredAuth(haBase=$haBase, clientId=$clientId, refreshToken=<redacted>)"

    /**
     * False when the dashboard's Home Assistant ([base]) has another origin than this sign-in's: a DHCP move, https
     * turned on at the same host, or another Home Assistant (#467). The panel can't prove which device answers
     * there (discovery trusts the LAN, and HA's mDNS uuid is public), so the token goes to neither address.
     */
    fun isFor(base: String?): Boolean = base == null || FrontendUrls.sameOrigin(base, haBase)
}

/**
 * URLs the kiosk loads in GeckoView once the panel is signed in. The HA frontend switches to the
 * external auth bridge when `window.externalApp` exists or the query contains `external_auth=1`
 * (frontend `src/data/external.ts` `isExternal`); the content script only installs the bridge on
 * pages carrying the flag, so the app adds it to every top-level HA page load.
 */
object FrontendUrls {
    const val FLAG = "external_auth=1"

    fun hasFlag(url: String): Boolean {
        val q = url.substringBefore('#').substringAfter('?', "")
        return q.split('&').any { it == FLAG }
    }

    /** Adds `external_auth=1` to the query, keeping any fragment. */
    fun withFlag(url: String): String {
        if (hasFlag(url)) return url
        val hash = url.indexOf('#')
        val main = if (hash >= 0) url.substring(0, hash) else url
        val frag = if (hash >= 0) url.substring(hash) else ""
        val sep = when {
            !main.contains('?') -> "?"
            main.endsWith("?") || main.endsWith("&") -> ""
            else -> "&"
        }
        return main + sep + FLAG + frag
    }

    fun sameOrigin(url: String?, base: String?): Boolean {
        if (url == null || base == null) return false
        val a = OAuth.origin(url) ?: return false
        return a == OAuth.origin(base)
    }

    /**
     * [ref] as a URL an engine can load. A path on Home Assistant (`/history`, `logbook?entity_id=light.x`,
     * `/lovelace/0#top`) goes on [base]'s origin, its query and fragment kept as they are; a URL with a scheme
     * (`http://…`, `about:blank`) stays as it is. Null for a blank [ref], or a path with nowhere to go ([pathOn]).
     *
     * A bare path reaches no engine as one: GeckoView doesn't load it, and the legacy engine's lockdown refuses it as
     * "a URL without a network origin", so Vitro's fallback showed "This address isn't the Home Assistant address…"
     * for `--es open /history` on Android 6.
     */
    fun resolve(ref: String?, base: String?): String? {
        val r = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (SCHEME.containsMatchIn(r)) r else pathOn(base, r)
    }

    /**
     * [path] on [base]'s origin ([OAuth.origin]: lower case, the default port dropped, as #983 writes Home Assistant's
     * address), with a `/` before it if it has none. Home Assistant serves its frontend at the root, so a path in
     * [base] (the dashboard's own) is no prefix. Null when [base] has no http(s) origin, for a scheme-relative `//host`
     * (it names a host, not a path) and for a control character.
     */
    fun pathOn(base: String?, path: String): String? {
        if (path.startsWith("//") || path.any { it.code < 0x20 || it.code == 0x7f }) return null
        val origin = base?.let(OAuth::origin) ?: return null
        return origin + if (path.startsWith("/")) path else "/$path"
    }

    /** A URL's scheme (RFC 3986): `http:`, `about:`. `homeassistant.local:8123/x` reads as one too, and stays as it is. */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private fun path(url: String): String = try { java.net.URI(url).rawPath ?: "/" } catch (_: Exception) { "/" }

    /** HA's login page (what an unauthenticated frontend redirects to). */
    fun isAuthorizePage(url: String?, base: String?): Boolean =
        url != null && sameOrigin(url, base) && path(url).startsWith("/auth/authorize")

    /** A top-level HA page load that should get the flag (not the /auth/ or /api/ endpoints). */
    fun needsFlag(url: String, base: String?): Boolean {
        if (!sameOrigin(url, base) || hasFlag(url)) return false
        val p = path(url)
        return !p.startsWith("/auth/") && !p.startsWith("/api/")
    }
}
