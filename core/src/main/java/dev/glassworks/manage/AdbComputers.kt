/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * every approval (its adbd ignores `adb_keys`, and the app never writes there): the app keeps the list and approves a listed
 * computer each time it connects. The computer that installed Glassworks needs no place here: the image lets it
 * in itself.
 *
 * [allow] asks Android to let [AdbKey] in for this connection. Android takes it only from the computer that asked
 * last, matched by fingerprint, so the app can never let in a computer that isn't plugged in and asking.
 * Android says nothing when it lets a computer in, and logs "Fingerprints do not match" when it doesn't
 * ([refused]). So Allow waits [confirmMs] for that line before it says the computer is in, or puts it on the list.
 * Nothing goes to `adb_keys`, so Remove takes a computer out for good. Thread-safe.
 */
class AdbComputers(
    private val file: File,
    /** Sends Android the approval of the computer asking now; null when the call went through, else why not. */
    private val allow: (AdbKey) -> String?,
    private val event: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a computer's question shows on the page. Android keeps it until the next computer asks. */
    private val askingMs: Long = 10 * 60_000L,
    private val max: Int = 20,
    /** How long Allow waits for Android's refusal before it counts the approval as taken. */
    private val confirmMs: Long = 3_000L,
    /** Whether Android's log is being read now: without it a refusal can't arrive, and Allow confirms nothing. */
    private val listening: () -> Boolean = { true },
    /** A computer was let in, from the page or from the list: the screen can put Android's own question away. */
    private val letIn: () -> Unit = {},
) : UsbComputers {
    /**
     * A computer on the list: its key, who added it ([by], may be empty) and when ([addedMs], 0 when nobody recorded it).
     * [installer]: the computer that installed Glassworks (Kiln, or the command-line installer), where a frame lists it.
     */
    data class Listed(val key: AdbKey, val addedMs: Long, val by: String, val installer: Boolean = false)

    /** The last computer that asked, and how it was answered, if it was. [retried]: its automatic approval was sent twice. */
    private data class Request(val key: AdbKey, val atMs: Long, val answered: Boolean, val retried: Boolean = false)

    /** An approval Allow has sent and waits on: Android's refusal of [fingerprint] ends the wait. */
    private class Sent(val fingerprint: String) {
        val answered = CountDownLatch(1)
        @Volatile var waiting: String? = null
    }

    private val listed = LinkedHashMap<String, Listed>()
    private var last: Request? = null
    private var sent: Sent? = null

    /** One Allow at a time, so each waits on its own refusal. Not this object's lock: [refused] needs that one meanwhile. */
    private val attempt = Any()

    init { load() }

    @Synchronized
    override fun list(): List<Listed> = listed.values.toList()

    /** The computer asking now, unanswered, or null. */
    @Synchronized
    override fun asking(): AdbKey? = last?.takeIf { !it.answered && clock() - it.atMs <= askingMs }?.key

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
        if (problem == null) letIn()
        event(if (problem == null) "Asked Android to let in ${label(on.key)} over USB: it's on the allowed list"
            else "Couldn't let in ${label(on.key)} over USB: $problem")
    }

    /**
     * Android refused the approval of [got]: [waiting] is the computer it waits on, or null when none. Allow, if it
     * waits on [got], fails. A listed computer's automatic approval is sent once more: the log line that showed it
     * asking comes before Android queues its question (`UsbDebuggingManager.java:138`–`141`), so the first can
     * arrive too early. Refused again, it shows as asking, for Allow on the page.
     */
    @Synchronized
    fun refused(got: String, waiting: String?) {
        val s = sent
        if (s != null && s.fingerprint == got) {
            s.waiting = waiting ?: NO_ONE
            s.answered.countDown()
            return
        }
        val r = last?.takeIf { it.key.fingerprint == got }
        val on = listed[got]
        if (r != null && on != null && !r.retried) {
            last = r.copy(retried = true, answered = allow(on.key) == null)
            return
        }
        if (r != null) last = r.copy(answered = false)
        event("Android didn't let in adb key $got over USB: ${waitingText(waiting)}")
    }

    /** The image let the computer that asked in itself (the owner's key): nothing to answer. */
    @Synchronized
    fun letInByImage() {
        last = last?.copy(answered = true)
    }

    /** What [allowAsking] did. */
    enum class Allowed { ONCE, FROM_NOW_ON, CHANGED, NONE, FULL, FAILED, REFUSED, REFUSED_NONE_WAITING, UNCONFIRMED, NOT_REMEMBERED, NO_ANSWER }

    /**
     * Lets in the computer asking now, if it is still the one the page showed ([shown], its fingerprint). With
     * [remember] it goes on the list too, so it gets in each time it connects. Blocks up to [confirmMs].
     */
    override fun allowAsking(shown: String, remember: Boolean, who: String): Allowed = synchronized(attempt) { attempt(shown, remember, who) }

    private fun attempt(shown: String, remember: Boolean, who: String): Allowed {
        val key: AdbKey
        val s: Sent
        val asked: Request?
        synchronized(this) {
            key = asking() ?: run {
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
            if (!listening()) {
                event("$who tried to allow ${label(key)} over USB: not sent, the panel isn't reading Android's log now")
                return Allowed.UNCONFIRMED
            }
            s = Sent(key.fingerprint)
            sent = s
            asked = last
        }
        try {
            allow(key)?.let { why ->
                event("$who couldn't allow ${label(key)} over USB: $why")
                return Allowed.FAILED
            }
            s.answered.await(confirmMs, TimeUnit.MILLISECONDS)
        } finally {
            synchronized(this) { if (sent === s) sent = null }
        }
        synchronized(this) {
            s.waiting?.let { w ->
                event("$who tried to allow ${label(key)} over USB: Android refused it, ${waitingText(w.takeIf { it != NO_ONE })}")
                return if (w == NO_ONE) Allowed.REFUSED_NONE_WAITING else Allowed.REFUSED
            }
            if (!listening()) {
                event("$who allowed ${label(key)} over USB, but the panel stopped reading Android's log, so it can't tell whether Android took it")
                return Allowed.UNCONFIRMED
            }
            // Only the request Allow was for: the same computer asking again meanwhile is a new question.
            if (last === asked) last = asked?.copy(answered = true)
            letIn()
            // Android logs nothing when it takes an approval: all the app saw is that it didn't refuse this one.
            if (!remember) {
                event("$who allowed ${label(key)} over USB, for this connection; Android didn't refuse it")
                return Allowed.ONCE
            }
            // Already listed (its automatic approval failed): the list stays as it is.
            if (key.fingerprint in listed) {
                event("$who allowed ${label(key)} over USB; it's on the allowed list already")
                return Allowed.FROM_NOW_ON
            }
            listed[key.fingerprint] = Listed(key, clock(), who)
            if (!save()) {
                listed.remove(key.fingerprint)
                event("$who allowed ${label(key)} over USB, for this connection: the allowed list couldn't be saved")
                return Allowed.NOT_REMEMBERED
            }
            event("$who allowed ${label(key)} over USB, from now on; Android didn't refuse it")
            // The same computer asked again while Allow waited (a reconnect): that question came before it was listed,
            // so nothing answered it. It's listed now.
            last?.takeIf { it !== asked && it.key == key && !it.answered }?.let { newer ->
                val problem = allow(key)
                last = newer.copy(answered = problem == null)
                if (problem == null) letIn()
                event(if (problem == null) "Asked Android to let in ${label(key)} over USB: it's on the allowed list"
                    else "Couldn't let in ${label(key)} over USB: $problem")
            }
            return Allowed.FROM_NOW_ON
        }
    }

    private fun waitingText(waiting: String?) =
        if (waiting != null && FINGERPRINT.matches(waiting)) "adb key $waiting is the one waiting" else "no computer is waiting"

    /** What [remove] did. */
    enum class Removed { DONE, NOT_LISTED, NOT_SAVED, NO_ANSWER }

    /** Takes the computer with [fingerprint] off the list. When the list can't be saved, it stays on it. */
    @Synchronized
    override fun remove(fingerprint: String, who: String): Removed {
        val gone = listed.remove(fingerprint) ?: return Removed.NOT_LISTED
        if (!save()) {
            listed[fingerprint] = gone
            event("$who couldn't remove ${label(gone.key)} from the allowed list: it couldn't be saved")
            return Removed.NOT_SAVED
        }
        event("$who removed ${label(gone.key)} from the allowed list")
        return Removed.DONE
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
        private const val NO_ONE = "-"
        private val FINGERPRINT = Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}")

        /** A computer as the log names it: the fingerprint ([Redact] keeps it after "adb key"), never the key. */
        fun label(key: AdbKey) = "adb key ${key.fingerprint}" + (key.name?.let { " ($it)" } ?: "")
    }
}
