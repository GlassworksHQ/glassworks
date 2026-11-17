/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A computer's adb public key, as `~/.android/adbkey.pub` holds it and adbd hands it to Android:
 * `<base64 of adb's 2048-bit RSAPublicKey> user@host`. [base64] is the key alone, all Android needs.
 * [name] is the `user@host` the computer gives itself, which it can make up: the [fingerprint] is the check.
 */
class AdbKey private constructor(val base64: String, val name: String?, bytes: ByteArray) {
    /** MD5 of the decoded key in uppercase hex pairs joined by colons, as Android's "Allow USB debugging?" shows it. */
    val fingerprint: String = MessageDigest.getInstance("MD5").digest(bytes).joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    override fun equals(other: Any?) = other is AdbKey && other.base64 == base64
    override fun hashCode() = base64.hashCode()

    companion object {
        /** adb's RSAPublicKey: len, n0inv, n[64], rr[64], exponent. */
        private const val KEY_BYTES = 4 + 4 + 256 + 256 + 4

        /** The key in [line], or null when it isn't one adb makes. */
        fun parse(line: String?): AdbKey? {
            val parts = line?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
            val bytes = decodeBase64(parts[0]) ?: return null
            if (bytes.size != KEY_BYTES || le32(bytes, 0) != 64) return null
            val name = parts.getOrNull(1)?.trim()?.takeIf { n -> n.isNotEmpty() && n.length <= 64 && n.all { it in ' '..'~' } }
            return AdbKey(parts[0], name, bytes)
        }

        private fun le32(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8) or (b[at + 2].toInt() and 0xff shl 16) or (b[at + 3].toInt() and 0xff shl 24)

        /** Plain padded base64. Android 6 has no java.util.Base64, and JVM tests have no android.util.Base64. */
        private fun decodeBase64(s: String): ByteArray? {
            val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            if (s.isEmpty() || s.length % 4 != 0) return null
            val pad = if (s.endsWith("==")) 2 else if (s.endsWith("=")) 1 else 0
            val out = ByteArray(s.length / 4 * 3 - pad)
            var bits = 0
            var n = 0
            var o = 0
            for (i in 0 until s.length - pad) {
                val v = abc.indexOf(s[i])
                if (v < 0) return null
                bits = bits shl 6 or v
                n += 6
                if (n >= 8) {
                    n -= 8
                    out[o++] = (bits shr n and 0xff).toByte()
                }
            }
            return out
        }
    }
}

/**
 * The computers the panel lets in over USB adb, and the one asking now (#435). For a panel whose app may answer
 * Android's "Allow USB debugging?" (it holds `MANAGE_USB`, the Mirror's privileged app) and whose adbd forgets
 * every approval (the Glass Panes image ignores `adb_keys`): the app keeps the list and approves a listed
 * computer each time it connects. The computer that installed Glassworks needs no place here: the image lets it
 * in itself.
 *
 * [allow] asks Android to let [AdbKey] in for this connection. Android takes it only from the computer that asked
 * last, matched by fingerprint, so the app can never let in a computer that isn't plugged in and asking.
 * Nothing goes to `adb_keys`, so Remove takes a computer out for good. Thread-safe.
 */
class AdbComputers(
    private val file: File,
    /** Android's approval of the computer asking now; null when Android took it, else why not. */
    private val allow: (AdbKey) -> String?,
    private val event: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a computer's question shows on the page. Android keeps it until the next computer asks. */
    private val askingMs: Long = 10 * 60_000L,
    private val max: Int = 20,
) {
    /** A computer on the list: its key, who added it and when. */
    data class Listed(val key: AdbKey, val addedMs: Long, val by: String)

    /** The last computer that asked, and how it was answered, if it was. */
    private data class Request(val key: AdbKey, val atMs: Long, val answered: Boolean)

    private val listed = LinkedHashMap<String, Listed>()
    private var last: Request? = null

    init { load() }

    @Synchronized
    fun list(): List<Listed> = listed.values.toList()

    /** The computer asking now, unanswered, or null. */
    @Synchronized
    fun asking(): AdbKey? = last?.takeIf { !it.answered && clock() - it.atMs <= askingMs }?.key

    /** A computer asked (Android's log said so). A listed one is let in straight away. */
    @Synchronized
    fun asked(key: AdbKey) {
        val on = listed[key.fingerprint]
        if (on == null) {
            last = Request(key, clock(), answered = false)
            return
        }
        val problem = allow(on.key)
        last = Request(key, clock(), answered = problem == null)
        event(if (problem == null) "Let in ${label(on.key)} over USB: it's on the allowed list"
            else "Couldn't let in ${label(on.key)} over USB: $problem")
    }

    /** The image let the computer that asked in itself (the owner's key): nothing to answer. */
    @Synchronized
    fun letInByImage() {
        last = last?.copy(answered = true)
    }

    /** What [allowAsking] did. */
    enum class Allowed { ONCE, FROM_NOW_ON, CHANGED, NONE, FULL, FAILED }

    /**
     * Lets in the computer asking now, if it is still the one the page showed ([shown], its fingerprint). With
     * [remember] it goes on the list too, so it gets in each time it connects.
     */
    @Synchronized
    fun allowAsking(shown: String, remember: Boolean, who: String): Allowed {
        val key = asking()
        if (key == null) {
            event("$who tried to allow adb key $shown over USB: refused, no computer is asking now")
            return Allowed.NONE
        }
        if (key.fingerprint != shown) {
            event("$who tried to allow adb key $shown over USB: refused, adb key ${key.fingerprint} asked since")
            return Allowed.CHANGED
        }
        if (remember && key.fingerprint !in listed && listed.size >= max) {
            event("$who tried to add ${label(key)} to the allowed list: refused, the list holds $max")
            return Allowed.FULL
        }
        allow(key)?.let { why ->
            event("$who couldn't allow ${label(key)} over USB: $why")
            return Allowed.FAILED
        }
        last = last?.copy(answered = true)
        if (!remember) {
            event("$who allowed ${label(key)} over USB, for this connection")
            return Allowed.ONCE
        }
        listed[key.fingerprint] = Listed(key, clock(), who)
        if (!save()) {
            listed.remove(key.fingerprint)
            event("$who allowed ${label(key)} over USB, for this connection: the allowed list couldn't be saved")
            return Allowed.FAILED
        }
        event("$who allowed ${label(key)} over USB, from now on")
        return Allowed.FROM_NOW_ON
    }

    /** Takes the computer with [fingerprint] off the list. False when it wasn't on it, or the list couldn't be saved. */
    @Synchronized
    fun remove(fingerprint: String, who: String): Boolean {
        val gone = listed.remove(fingerprint) ?: return false
        if (!save()) {
            listed[fingerprint] = gone
            event("$who couldn't remove ${label(gone.key)} from the allowed list: it couldn't be saved")
            return false
        }
        event("$who removed ${label(gone.key)} from the allowed list")
        return true
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = AdbKey.parse(o.getString("key") + " " + o.optString("name")) ?: continue
                listed[key.fingerprint] = Listed(key, o.optLong("added"), o.optString("by"))
            }
        } catch (_: Exception) {
            // A damaged list lets no extra computer in, which is the safe side.
            listed.clear()
        }
    }

    private fun save(): Boolean = try {
        val arr = JSONArray()
        for (l in listed.values) arr.put(JSONObject().put("key", l.key.base64).put("name", l.key.name ?: "").put("added", l.addedMs).put("by", l.by))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
    } catch (_: Exception) {
        false
    }

    companion object {
        /** A computer as the log names it: the fingerprint ([Redact] keeps it after "adb key"), never the key. */
        fun label(key: AdbKey) = "adb key ${key.fingerprint}" + (key.name?.let { " ($it)" } ?: "")
    }
}
