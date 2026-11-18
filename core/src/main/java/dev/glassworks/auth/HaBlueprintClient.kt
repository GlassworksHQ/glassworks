package dev.glassworks.auth

import dev.glassworks.pane.BlueprintAccess
import dev.glassworks.pane.HaBlueprint
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * What the panel's own Home Assistant sign-in may do with blueprints, over HA's websocket API. It only
 * reads (pane rule 4): `auth/current_user`, then `blueprint/list` for an administrator. Imports go
 * through Home Assistant's own dialog. Pure JVM (HaBlueprintClientTest, against a scripted socket).
 *
 * Checked against Home Assistant 2026.11.1 (`components/auth/__init__.py` `websocket_current_user`,
 * `components/blueprint/websocket_api.py` `ws_list_blueprints`):
 * - `auth/current_user` → `{id, name, is_owner, is_admin, …}`.
 * - `blueprint/list` + `domain` → `{"<path>": {"metadata": {name, domain, source_url?, …}}}`, or
 *   `{"<path>": {"error": "…"}}` for a file it can't read. `@require_admin`: a non-admin gets `unauthorized`.
 */
class HaBlueprintClient(private val connect: (url: String) -> HaSocket) {

    fun check(haBase: String, accessToken: String): BlueprintAccess {
        val url = HaThreadClient.websocketUrl(haBase) ?: return BlueprintAccess.Off(NOT_HTTP)
        val ws = try { connect(url) } catch (e: IOException) { return BlueprintAccess.Off(UNREACHABLE) }
        return try {
            run(ws, accessToken)
        } catch (e: IOException) {
            BlueprintAccess.Off(UNREACHABLE)
        } catch (e: JSONException) {
            BlueprintAccess.Off(UNEXPECTED)
        } finally {
            runCatching { ws.close() }
        }
    }

    private fun run(ws: HaSocket, accessToken: String): BlueprintAccess {
        if (next(ws)?.optString("type") != "auth_required") return BlueprintAccess.Off(UNEXPECTED)
        ws.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
        when (next(ws)?.optString("type")) {
            "auth_ok" -> {}
            "auth_invalid" -> return BlueprintAccess.Off(REFUSED)
            else -> return BlueprintAccess.Off(UNREACHABLE)
        }
        ws.send(JSONObject().put("id", 1).put("type", "auth/current_user").toString())
        val user = result(ws, 1) ?: return BlueprintAccess.Off(UNEXPECTED)
        val name = user.optString("name").ifEmpty { "this panel's user" }
        if (!user.optBoolean("is_admin", false)) return BlueprintAccess.NotAdmin(name)

        ws.send(JSONObject().put("id", 2).put("type", "blueprint/list").put("domain", "automation").toString())
        val list = result(ws, 2) ?: return BlueprintAccess.Off(UNEXPECTED)
        // A file Home Assistant can't load ({"error": …}) can't make an automation: it counts as not there.
        val inHa = list.keys().asSequence().mapNotNull { path ->
            val meta = list.optJSONObject(path)?.optJSONObject("metadata") ?: return@mapNotNull null
            HaBlueprint(path, meta.optString("source_url").ifEmpty { null })
        }.toList()
        return BlueprintAccess.Admin(inHa)
    }

    /** The `result` with [id], or null when Home Assistant answered an error or closed. */
    private fun result(ws: HaSocket, id: Int): JSONObject? {
        while (true) {
            val m = next(ws) ?: return null
            if (m.optString("type") != "result" || m.optInt("id", -1) != id) continue
            return if (m.optBoolean("success", false)) m.optJSONObject("result") ?: JSONObject() else null
        }
    }

    private fun next(ws: HaSocket): JSONObject? = ws.receive()?.let(::JSONObject)

    companion object {
        const val UNREACHABLE = "Can't reach Home Assistant now."
        const val REFUSED = "Home Assistant refused the panel's sign-in. Sign the panel in again."
        const val UNEXPECTED = "Home Assistant answered something the panel didn't expect."
        const val NOT_HTTP = "The panel's Home Assistant address isn't an http(s) address."
    }
}
