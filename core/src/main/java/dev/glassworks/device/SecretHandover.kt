/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.StoredKey
import dev.glassworks.improv.AdbSocketServer
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretPrefs.CarryOver
import java.io.InputStream
import java.io.OutputStream

/**
 * Carries the app's two sealed secrets that Home Assistant holds the other half of across a change that loses them:
 * the ESPHome API key (the Noise PSK Home Assistant provisioned) and the Home Assistant sign-in (its refresh token).
 * The installer reads them from the running app before the change and hands them to the new app after it.
 *
 * - **A reflash** (the Mirror's flash-node, #574) wipes the app's data: the API key goes into an empty store.
 * - **A signing-key switch** (#584: Kiln's and `install.sh`'s, with root) uninstalls the app, which deletes its
 *   Android Keystore key, then copies its data back. The copied secrets are sealed with the deleted key and never
 *   open again; the handed values replace them, sealed with the new app's key ([SecretPrefs.carryOver]).
 *
 * Pure: the protocol over a [HandoverStore]. The socket is [SecretHandoverServer]. One text line per request, one per
 * reply:
 * ```
 * carries              → carries api_key signin   (what this app can hand over; no secret)
 * get                  → key <base64> | none | unreadable
 * set <base64>         → ok | refused <why>
 * get-signin           → signin <base64 of the token> | none | unreadable
 * set-signin <base64>  → ok | refused <why>
 * ```
 * A `set` never replaces a value in use: only an empty one, the same one (a retried hand-over), or one sealed with a
 * key this app no longer has. Two `set`s at once can't both fill it. The API key's store is the one Home Assistant's
 * provisioning ([NoiseKeys]) writes; HA can't write it while the hand-over can (a reflash keeps the server shut until
 * the key comes, and after a switch the server is locked: the copied key is stored but can't be read). The sign-in
 * goes only into a sign-in the copied data holds (its Home Assistant address and client id): it never signs the
 * panel in to anything new. No value is ever logged; [log] gets a fingerprint ([NoiseKeys.fingerprint]) at most.
 */
class SecretHandover(
    private val store: HandoverStore,
    private val encode: (ByteArray) -> String,
    /** Base64 text to bytes, or null when it isn't base64. */
    private val decode: (String) -> ByteArray?,
    private val log: (String) -> Unit = {},
    /** A sign-in was carried over: the screens should show the dashboard now. */
    private val onSignIn: () -> Unit = {},
) {
    /** One request line → the reply line (without "\n"). */
    fun handle(line: String): String {
        val parts = line.trim().split(' ', limit = 2)
        val arg = parts.getOrNull(1).orEmpty().trim()
        return when (parts[0]) {
            "carries" -> "carries api_key signin"
            "get" -> when (val r = store.apiKey()) {
                is StoredKey.Present -> { log("API key read by adb (fingerprint ${NoiseKeys.fingerprint(r.key)})"); "key ${encode(r.key)}" }
                StoredKey.Absent -> "none"
                StoredKey.Unreadable -> "unreadable"
            }
            "set" -> setKey(arg)
            "get-signin" -> when (val r = store.signIn()) {
                is SecretPrefs.Secret.Value -> {
                    val token = r.text.toByteArray(Charsets.UTF_8)
                    log("Home Assistant sign-in read by adb (fingerprint ${NoiseKeys.fingerprint(token)})")
                    "signin ${encode(token)}"
                }
                SecretPrefs.Secret.Absent -> "none"
                SecretPrefs.Secret.Unavailable -> "unreadable"
            }
            "set-signin" -> setSignIn(arg)
            else -> "refused unknown request"
        }
    }

    /** Guards each `set`'s check and write: each adb connection has its own thread, and two must not both fill a store. */
    private val lock = Any()

    private fun setKey(text: String): String {
        val key = decode(text)?.takeIf { it.size == 32 && it.any { b -> b != 0.toByte() } }
            ?: return "refused not base64 of 32 bytes, or all zeros"
        return synchronized(lock) {
            reply(store.carryOverApiKey(key), "key") {
                log("API key handed over by adb (fingerprint ${NoiseKeys.fingerprint(key)})")
            }
        }
    }

    private fun setSignIn(text: String): String {
        val raw = decode(text)?.takeIf { it.size in 1..MAX_TOKEN_BYTES && it.all { b -> b in 0x21..0x7e } }
            ?: return "refused not base64 of a token"
        val token = String(raw, Charsets.US_ASCII)
        return synchronized(lock) {
            val r = store.carryOverSignIn(token) ?: return@synchronized "refused no sign-in here to carry it into"
            // SAME too: a retried hand-over whose first reply was lost, or a commit Android kept in memory but
            // reported failed. The screens may still show the QR code; telling them twice only reloads the dashboard.
            reply(r, "sign-in", same = onSignIn) {
                log("Home Assistant sign-in handed over by adb (fingerprint ${NoiseKeys.fingerprint(raw)})")
                onSignIn()
            }
        }
    }

    private inline fun reply(r: CarryOver, what: String, same: () -> Unit = {}, stored: () -> Unit): String = when (r) {
        CarryOver.STORED -> { stored(); "ok" }
        CarryOver.SAME -> { same(); "ok" }
        CarryOver.DIFFERENT -> "refused a different $what is set already"
        CarryOver.UNAVAILABLE -> "refused the key store isn't available right now"
        CarryOver.NOT_STORED -> "refused the $what can't be stored right now"
    }

    /** Serves request lines until the client closes. */
    fun serve(input: InputStream, output: OutputStream) {
        val reader = input.bufferedReader(Charsets.US_ASCII)
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            output.write("${handle(line)}\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    companion object {
        /** Home Assistant's refresh tokens are 128 hex characters; anything this long isn't one. */
        const val MAX_TOKEN_BYTES = 4096
    }
}

/** The secrets [SecretHandover] carries, as the app stores them ([KioskPrefs] on a panel, a fake in a test). */
interface HandoverStore {
    fun apiKey(): StoredKey
    fun carryOverApiKey(key: ByteArray): CarryOver
    fun signIn(): SecretPrefs.Secret
    /** Null when there is no sign-in to carry the token into (no Home Assistant address or client id here). */
    fun carryOverSignIn(token: String): CarryOver?
}

/**
 * [SecretHandover] on `@glassworks_api_key`, adb only ([AdbSocketServer]: uid 0 or 2000). KioskService starts it and
 * holds it. Never on the LAN: the setup channel's WebSocket carries no secret. The socket keeps its name from #574,
 * when it carried only the API key: flash-node and Kiln look for it by that name.
 */
object SecretHandoverServer {
    const val SOCKET = "glassworks_api_key"
    private const val TAG = "Glassworks.Secrets"

    /** A new server over the app's settings, not started yet. */
    fun create(context: Context): AdbSocketServer {
        val prefs = KioskPrefs(context.applicationContext)
        val store = object : HandoverStore {
            override fun apiKey() = prefs.noiseKeyStore.read()
            override fun carryOverApiKey(key: ByteArray) = prefs.carryOverApiKey(key)
            override fun signIn() = prefs.refreshTokenSecret()
            override fun carryOverSignIn(token: String) = prefs.carryOverRefreshToken(token)
        }
        val handover = SecretHandover(
            store,
            encode = { Base64.encodeToString(it, Base64.NO_WRAP) },
            decode = { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
            log = { Log.i(TAG, it) },
            onSignIn = { HaAuth.init(context); HaAuth.signInCarriedOver() },
        )
        return AdbSocketServer(SOCKET, TAG) { input, output -> handover.serve(input, output) }
    }
}
