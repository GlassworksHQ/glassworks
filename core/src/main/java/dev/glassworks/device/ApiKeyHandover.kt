/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.glassworks.KioskPrefs
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.ReadableNoiseKeyStore
import dev.glassworks.esphome.StoredKey
import dev.glassworks.improv.AdbSocketServer
import java.io.InputStream
import java.io.OutputStream

/**
 * Carries the ESPHome API key (the Noise PSK Home Assistant provisioned) over a reflash, which wipes the app's data
 * and with it the key (#574). The installer reads the key from the running app before the flash and hands it to the
 * fresh app at its first boot, so Home Assistant's entry, which still holds that key, reconnects with no re-auth.
 *
 * Pure: the protocol over a [store]. The socket is [ApiKeyHandoverServer]. One text line per request, one per reply:
 * ```
 * get           → key <base64> | none | unreadable
 * set <base64>  → ok | refused <why>
 * ```
 * `set` only fills an empty store (or gives the key it holds): it never replaces a key Home Assistant uses now. Two
 * `set`s at once can't both fill it. Home Assistant's own provisioning ([NoiseKeys]) writes the same store under its own
 * lock; it can only run while the API server is up, and on a reflash the server waits for this `set` first. The key is
 * never logged; [log] gets its fingerprint ([NoiseKeys.fingerprint]) at most.
 */
class ApiKeyHandover(
    private val store: ReadableNoiseKeyStore,
    private val encode: (ByteArray) -> String,
    /** Base64 text to bytes, or null when it isn't base64. */
    private val decode: (String) -> ByteArray?,
    private val log: (String) -> Unit = {},
) {
    /** One request line → the reply line (without "\n"). */
    fun handle(line: String): String {
        val parts = line.trim().split(' ', limit = 2)
        return when (parts[0]) {
            "get" -> when (val r = store.read()) {
                is StoredKey.Present -> { log("API key read by adb (fingerprint ${NoiseKeys.fingerprint(r.key)})"); "key ${encode(r.key)}" }
                StoredKey.Absent -> "none"
                StoredKey.Unreadable -> "unreadable"
            }
            "set" -> set(parts.getOrNull(1).orEmpty())
            else -> "refused unknown request"
        }
    }

    /** Guards [set]'s read and save: each adb connection has its own thread, and two must not both fill the store. */
    private val lock = Any()

    private fun set(text: String): String {
        val key = decode(text.trim())?.takeIf { it.size == 32 && it.any { b -> b != 0.toByte() } }
            ?: return "refused not base64 of 32 bytes, or all zeros"
        return synchronized(lock) { fill(key) }
    }

    private fun fill(key: ByteArray): String {
        return when (val r = store.read()) {
            is StoredKey.Present ->
                if (r.key.contentEquals(key)) "ok" else "refused a different key is set already"
            StoredKey.Unreadable -> "refused a key is stored but can't be read now"
            StoredKey.Absent ->
                if (!store.save(key)) "refused the key can't be stored right now"
                else { log("API key handed over by adb (fingerprint ${NoiseKeys.fingerprint(key)})"); "ok" }
        }
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
}

/**
 * [ApiKeyHandover] on `@glassworks_api_key`, adb only ([AdbSocketServer]: uid 0 or 2000). KioskService starts it and
 * holds it. Never on the LAN: the setup channel's WebSocket carries no key.
 */
object ApiKeyHandoverServer {
    const val SOCKET = "glassworks_api_key"
    private const val TAG = "Glassworks.ApiKey"

    /** A new server over the app's key store, not started yet. */
    fun create(context: Context): AdbSocketServer {
        val handover = ApiKeyHandover(
            KioskPrefs(context.applicationContext).noiseKeyStore,
            encode = { Base64.encodeToString(it, Base64.NO_WRAP) },
            decode = { KioskPrefs.decodeApiKey(it) },
            log = { Log.i(TAG, it) },
        )
        return AdbSocketServer(SOCKET, TAG) { input, output -> handover.serve(input, output) }
    }
}
