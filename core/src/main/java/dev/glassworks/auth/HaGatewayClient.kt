package dev.glassworks.auth

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

    private fun next(ws: HaSocket): JSONObject? = ws.receive()?.let(::JSONObject)
}
