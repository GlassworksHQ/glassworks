/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import java.net.URI

/**
 * "Sign in on this panel instead" (#633): the QR code's sign-in, with the panel's own page as the browser. The page
 * shows Home Assistant's login page, and the app takes Home Assistant's redirect back before it loads and trades the
 * code for a refresh token, as [SignInServer] does for a phone. A session the page already has with Home Assistant
 * never counts as the panel's sign-in: before this, the dashboard showed through one and nothing was stored.
 *
 * The client_id and redirect_uri are on Home Assistant's own origin. Home Assistant accepts a redirect_uri on the
 * client_id's host without fetching anything (`indieauth.verify_redirect_uri`), the legacy engine loads no other
 * origin (#198), and the login page then looks like Home Assistant's own: no "You're about to give access" note.
 *
 * Pure JVM (PanelSignInTest).
 */
class PanelSignIn(private val tokens: HaTokenClient, private val states: StateStore = StateStore()) {

    sealed class Outcome {
        class SignedIn(val auth: StoredAuth, val first: TokenResult.Ok) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Home Assistant's login page at [haBase], for a new sign-in. */
    fun start(haBase: String): String {
        val base = OAuth.trimBase(haBase)
        val sign = states.create(clientId(base), redirectUri(base), base)
        return OAuth.authorizeUrl(base, clientId(base), redirectUri(base), sign.state, sign.codeChallenge)
    }

    /** [url] is Home Assistant at [haBase] sending the page back from its login page. */
    fun isCallback(url: String, haBase: String?): Boolean =
        FrontendUrls.sameOrigin(url, haBase) && runCatching { URI(url).rawPath }.getOrNull() == CALLBACK

    /** Trades the code in [url], the redirect back, for the panel's refresh token. Blocks on the network. */
    fun finish(url: String): Outcome {
        val q = OAuth.parseQuery(runCatching { URI(url).rawQuery }.getOrNull())
        val pending = states.consume(q["state"]) ?: return Outcome.Failed("the sign-in ran out or was already used")
        q["error"]?.let { return Outcome.Failed("Home Assistant answered $it") }
        val code = q["code"]?.takeIf { it.isNotEmpty() } ?: return Outcome.Failed("Home Assistant sent no code")
        return when (val r = tokens.exchangeCode(pending.haBase, pending.clientId, code, pending.codeVerifier)) {
            is TokenResult.Ok -> r.refreshToken?.let { Outcome.SignedIn(StoredAuth(pending.haBase, pending.clientId, it), r) }
                ?: Outcome.Failed("Home Assistant returned no refresh token")
            is TokenResult.Error -> Outcome.Failed("code exchange failed: ${r.code} (${r.httpStatus})")
        }
    }

    companion object {
        const val CLIENT = "/glassworks/"
        const val CALLBACK = "/glassworks/callback"

        fun clientId(base: String) = OAuth.trimBase(base) + CLIENT
        fun redirectUri(base: String) = OAuth.trimBase(base) + CALLBACK
    }
}
