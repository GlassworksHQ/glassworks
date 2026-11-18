package dev.glassworks.auth

import dev.glassworks.net.HappyEyeballs
import dev.glassworks.net.HttpClient
import java.io.IOException

/** Minimal HTTP for the auth endpoints; an interface so tests can use a fake HA. */
interface HttpPoster {
    /** POSTs a form body; returns (status, body). Status <= 0 means the request didn't complete. */
    fun postForm(url: String, body: String): Pair<Int, String>

    /** GET; returns (status, body), body capped at [maxBytes]. */
    fun get(url: String, maxBytes: Int = 64 * 1024): Pair<Int, String>
}

/**
 * [HttpPoster] over [HttpClient], which connects to Home Assistant as a browser does ([HappyEyeballs]): a `.local`
 * name that also resolves to an IPv6 address the panel can't reach still connects over IPv4 at once.
 * [timeoutMs] bounds the connect and each read.
 */
class UrlConnectionPoster(timeoutMs: Int = 10_000, connector: HappyEyeballs = HappyEyeballs()) : HttpPoster {
    private val http = HttpClient(connector, timeoutMs, timeoutMs)

    override fun postForm(url: String, body: String): Pair<Int, String> = call {
        http.request("POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            body.toByteArray(Charsets.UTF_8), maxBytes = 256 * 1024)
    }

    override fun get(url: String, maxBytes: Int): Pair<Int, String> = call { http.request("GET", url, maxBytes = maxBytes) }

    private fun call(request: () -> HttpClient.Response): Pair<Int, String> = try {
        request().let { it.status to it.body.toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }
}

/** `/auth/token` and `/auth/revoke` calls (Home Assistant `components/auth/__init__.py`). */
class HaTokenClient(private val http: HttpPoster) {
    fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String): TokenResult {
        val (status, body) = http.postForm(
            OAuth.trimBase(haBase) + "/auth/token",
            OAuth.form("grant_type" to "authorization_code", "code" to code, "client_id" to clientId,
                "code_verifier" to codeVerifier),
        )
        return TokenResult.parse(status, body)
    }

    fun refresh(auth: StoredAuth): TokenResult {
        val (status, body) = http.postForm(
            OAuth.trimBase(auth.haBase) + "/auth/token",
            OAuth.form("grant_type" to "refresh_token", "refresh_token" to auth.refreshToken, "client_id" to auth.clientId),
        )
        return TokenResult.parse(status, body)
    }

    /**
     * True when Home Assistant took it: HA answers 200 to every revoke, even of a token it doesn't know. A transport
     * error or any other status (a proxy's 502 or 503 in front of HA) isn't a revoke (CodeRabbit on #534).
     */
    fun revoke(auth: StoredAuth): Boolean =
        http.postForm(OAuth.trimBase(auth.haBase) + "/auth/revoke", OAuth.form("token" to auth.refreshToken)).first in 200..299
}
