package dev.glassworks.auth

import dev.glassworks.dashboard.Dashboard
import dev.glassworks.dashboard.DashboardOptions
import dev.glassworks.manage.HaGateway
import dev.glassworks.manage.HaUser
import org.json.JSONObject

/**
 * Home Assistant for the Manage page's sign-in and the dashboard sign-in row: the token endpoint,
 * `auth/current_user` over the websocket, revoke.
 */
class HaGatewayClient(private val tokens: HaTokenClient, private val connect: (String) -> HaSocket) : HaGateway {
    override fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String): HaGateway.Tokens =
        when (val r = tokens.exchangeCode(haBase, clientId, code, codeVerifier)) {
            is TokenResult.Ok -> HaGateway.Tokens.Ok(r.accessToken, r.refreshToken, r.expiresIn)
            is TokenResult.Error -> HaGateway.Tokens.Failed(r.code, invalidCode = r.isInvalidCode, unreachable = r.httpStatus <= 0)
        }

    override fun currentUser(haBase: String, accessToken: String): HaUser? {
        val url = HaThreadClient.websocketUrl(haBase) ?: return null
        // Any failure to connect is an unknown user: the lookup's own thread must not die of it.
        val ws = try { connect(url) } catch (e: Exception) { return null }
        try {
            if (next(ws)?.optString("type") != "auth_required") return null
            ws.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
            if (next(ws)?.optString("type") != "auth_ok") return null
            ws.send(JSONObject().put("id", 1).put("type", "auth/current_user").toString())
            while (true) {
                val m = next(ws) ?: return null
                if (m.optString("type") != "result" || m.optInt("id", -1) != 1) continue
                if (!m.optBoolean("success", false)) return null
                val u = m.optJSONObject("result") ?: return null
                return HaUser(u.optString("name").ifEmpty { "Home Assistant user" }, u.optBoolean("is_admin", false))
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { ws.close() }
        }
    }

    override fun refresh(haBase: String, clientId: String, refreshToken: String): HaGateway.Tokens =
        when (val r = tokens.refresh(StoredAuth(haBase, clientId, refreshToken))) {
            is TokenResult.Ok -> HaGateway.Tokens.Ok(r.accessToken, null, r.expiresIn)
            // invalid_grant with HA's 400: HA no longer has this sign-in (a proxy's 5xx with that body isn't HA saying so). access_denied (an account turned off) keeps it: it may come back,
            // and it must stay revocable (CodeRabbit on #534). Anything else is an outage.
            is TokenResult.Error -> HaGateway.Tokens.Failed(r.code, invalidCode = r.isInvalidCode, unreachable = r.httpStatus <= 0, refused = r.code == "invalid_grant" && r.httpStatus == 400)
        }

    override fun revoke(haBase: String, clientId: String, refreshToken: String): Boolean =
        tokens.revoke(StoredAuth(haBase, clientId, refreshToken))

    override fun createLongLived(haBase: String, accessToken: String, name: String, days: Int): String? =
        signedIn(haBase, accessToken) { ws, _ ->
            val m = ask(ws, 1, JSONObject().put("type", "auth/long_lived_access_token").put("lifespan", days).put("client_name", name))
            m?.takeIf { it.optBoolean("success", false) }?.optString("result")?.takeIf { it.isNotEmpty() }
        }

    override fun endLongLived(haBase: String, token: String): Boolean =
        signedIn(haBase, token, refusedMeans = true) { ws, _ ->
            // This connection is signed in with the token itself, so HA lists it as the current one: its id is all we need.
            val list = ask(ws, 1, JSONObject().put("type", "auth/refresh_tokens"))?.takeIf { it.optBoolean("success", false) }
                ?.optJSONArray("result") ?: return@signedIn false
            val id = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optBoolean("is_current") }
                ?.optString("id")?.takeIf { it.isNotEmpty() } ?: return@signedIn false
            ask(ws, 2, JSONObject().put("type", "auth/delete_refresh_token").put("refresh_token_id", id))?.optBoolean("success", false) == true
        } ?: false

    /**
     * Fetches the Lovelace dashboards visible to [accessToken]'s user (`auth/current_user` + `lovelace/dashboards/list`),
     * leaving out `require_admin` dashboards for non-administrators just like the kiosk web extension does.
     * Null when Home Assistant didn't answer or refused the request.
     */
    fun dashboards(haBase: String, accessToken: String): List<Dashboard>? =
        signedIn(haBase, accessToken) { ws, _ ->
            val user = ask(ws, 1, JSONObject().put("type", "auth/current_user")) ?: return@signedIn null
            val isAdmin = user.takeIf { it.optBoolean("success", false) }?.optJSONObject("result")?.optBoolean("is_admin", false) == true
            val res = ask(ws, 2, JSONObject().put("type", "lovelace/dashboards/list")) ?: return@signedIn null
            if (!res.optBoolean("success", false)) return@signedIn null
            val list = res.optJSONArray("result") ?: return@signedIn null
            DashboardOptions.fromJson(list, includeAdmin = isAdmin)
        }

    /**
     * [body] on a websocket signed in with [token]; null when HA didn't answer. When HA refuses the token itself, the
     * answer is [refusedMeans] (a token HA no longer takes is as good as deleted), else null.
     */
    private fun <T> signedIn(haBase: String, token: String, refusedMeans: T? = null, body: (HaSocket, JSONObject) -> T?): T? {
        val url = HaThreadClient.websocketUrl(haBase) ?: return null
        val ws = try { connect(url) } catch (e: Exception) { return null }
        try {
            if (next(ws)?.optString("type") != "auth_required") return null
            ws.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            val auth = next(ws) ?: return null
            return when (auth.optString("type")) {
                "auth_ok" -> body(ws, auth)
                "auth_invalid" -> refusedMeans
                else -> null
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { ws.close() }
        }
    }

    /** Sends [msg] as call [id] and waits for its result (other messages skipped); null when the connection ended. */
    private fun ask(ws: HaSocket, id: Int, msg: JSONObject): JSONObject? {
        ws.send(msg.put("id", id).toString())
        while (true) {
            val m = next(ws) ?: return null
            if (m.optString("type") == "result" && m.optInt("id", -1) == id) return m
        }
    }

    private fun next(ws: HaSocket): JSONObject? = ws.receive()?.let(::JSONObject)
}
