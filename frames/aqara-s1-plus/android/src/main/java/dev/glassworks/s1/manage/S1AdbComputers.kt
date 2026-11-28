package dev.glassworks.s1.manage

import dev.glassworks.manage.AdbComputers
import dev.glassworks.manage.AdbComputers.Allowed
import dev.glassworks.manage.AdbComputers.Listed
import dev.glassworks.manage.AdbComputers.Removed
import dev.glassworks.manage.AdbKey
import dev.glassworks.manage.UsbComputers
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * The computers the S1 lets in over USB adb, kept by its frame agent in `adb_keys` (glassworks_agent.sh, "computers
 * allowed over USB adb"). The app only reads what the agent publishes and hands it requests: it never names a key.
 * - [pub]`/adb-asking`: `<epoch> <fingerprint> <key line>`, the computer system_server logged asking.
 * - [pub]`/adb-keys`: `<installer|added> <fingerprint> <key line>` per key in `adb_keys`.
 * - [request]: `<id> allow|remove <fingerprint>`, which the agent takes within a loop (3 s) and answers in
 *   [pub]`/adb-result` as `<id> <ok|error> <detail>`.
 * Android forgets no key by itself here (the agent turns its 7-day timeout off), so every computer allowed stays
 * until Remove: [alwaysRemembers]. Who added a computer and when is the app's own note, in [notes].
 */
class S1AdbComputers(
    private val pub: File,
    private val request: File,
    private val notes: File,
    private val event: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a question shows on the page: the agent's own limit (ADB_TTL). */
    private val askingMs: Long = 10 * 60_000L,
    /** How long Allow or Remove waits for the agent: a loop, Android's answer (2 s) and some room. */
    private val answerMs: Long = 15_000L,
    private val pollMs: Long = 200L,
) : UsbComputers {
    override val alwaysRemembers get() = true

    override fun asking(): AdbKey? {
        val line = read("adb-asking")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        val key = AdbKey.parse(parts[2]) ?: return null
        if (key.fingerprint != parts[1]) return null
        return key.takeIf { clock() - at * 1000 in 0..askingMs }
    }

    /** The agent's list, without the installer's key: [Listed.addedMs] is 0 for a computer let in elsewhere. */
    override fun list(): List<Listed> {
        val noted = notes()
        return keys().filter { !it.first }.map { (_, key) ->
            val n = noted.optJSONObject(key.fingerprint)
            Listed(key, n?.optLong("added") ?: 0L, n?.optString("by").orEmpty())
        }
    }

    /** Every key the agent lists: (installer, key). */
    private fun keys(): List<Pair<Boolean, AdbKey>> = read("adb-keys")?.lineSequence()?.mapNotNull { l ->
        val parts = l.trim().split(' ', limit = 3)
        if (parts.size < 3 || (parts[0] != "installer" && parts[0] != "added")) return@mapNotNull null
        val key = AdbKey.parse(parts[2])?.takeIf { it.fingerprint == parts[1] } ?: return@mapNotNull null
        (parts[0] == "installer") to key
    }?.toList().orEmpty()

    @Synchronized
    override fun allowAsking(shown: String, remember: Boolean, who: String): Allowed {
        val key = asking() ?: run {
            event("$who tried to allow adb key $shown over USB: refused, no computer is asking now")
            return Allowed.NONE
        }
        if (key.fingerprint != shown) {
            event("$who tried to allow adb key $shown over USB: refused, adb key ${key.fingerprint} asked since")
            return Allowed.CHANGED
        }
        val (ok, detail) = ask("allow", shown) ?: run {
            event("$who tried to allow ${AdbComputers.label(key)} over USB: the panel's root side didn't answer")
            return Allowed.NO_ANSWER
        }
        if (!ok) {
            event("$who tried to allow ${AdbComputers.label(key)} over USB: refused, $detail")
            return when (detail) {
                "none-asking" -> Allowed.NONE
                "changed" -> Allowed.CHANGED
                else -> Allowed.FAILED
            }
        }
        if (detail != "already") note(key.fingerprint, who)
        event("$who allowed ${AdbComputers.label(key)} over USB, from now on")
        return Allowed.FROM_NOW_ON
    }

    @Synchronized
    override fun remove(fingerprint: String, who: String): Removed {
        val listed = keys().firstOrNull { it.second.fingerprint == fingerprint }
        // The installer's key isn't on the page's list, and the agent refuses it too.
        if (listed == null || listed.first) return Removed.NOT_LISTED
        val label = AdbComputers.label(listed.second)
        val (ok, detail) = ask("remove", fingerprint) ?: run {
            event("$who tried to remove $label from the allowed list: the panel's root side didn't answer")
            return Removed.NO_ANSWER
        }
        if (!ok) {
            event("$who tried to remove $label from the allowed list: refused, $detail")
            return if (detail == "not-listed" || detail == "installer") Removed.NOT_LISTED else Removed.NOT_SAVED
        }
        note(fingerprint, null)
        event("$who removed $label from the allowed list")
        return Removed.DONE
    }

    /** Hands the agent "<id> [op] [fingerprint]" and waits for its answer: (ok, detail), or null when none came. */
    private fun ask(op: String, fingerprint: String): Pair<Boolean, String>? {
        val id = buildString { repeat(12) { append(ID[RANDOM.nextInt(ID.length)]) } }
        try {
            request.parentFile?.mkdirs()
            val tmp = File(request.parentFile, request.name + ".tmp")
            tmp.writeText("$id $op $fingerprint\n")
            if (!tmp.renameTo(request)) return null
        } catch (_: Exception) {
            return null
        }
        val until = System.nanoTime() + answerMs * 1_000_000
        while (true) {
            val parts = read("adb-result")?.trim()?.split(' ', limit = 3)
            if (parts != null && parts.size >= 2 && parts[0] == id) return (parts[1] == "ok") to parts.getOrElse(2) { "" }
            if (System.nanoTime() >= until) break
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        // Not taken in time: take it back, so it can't act later than the page says. One taken meanwhile still answers.
        request.delete()
        return null
    }

    private fun read(name: String): String? = try {
        File(pub, name).takeIf { it.isFile && it.length() <= MAX_FILE }?.readText()
    } catch (_: Exception) {
        null
    }

    private fun notes(): JSONObject = try {
        if (notes.isFile) JSONObject(notes.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    /** Records who added [fingerprint] and when; [who] null forgets it. A note that can't be saved only loses that line. */
    private fun note(fingerprint: String, who: String?) {
        try {
            val n = notes()
            if (who == null) n.remove(fingerprint) else n.put(fingerprint, JSONObject().put("added", clock()).put("by", who))
            notes.parentFile?.mkdirs()
            val tmp = File(notes.parentFile, notes.name + ".tmp")
            tmp.writeText(n.toString())
            if (!tmp.renameTo(notes)) tmp.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** The frame agent's files for the app (glassworks_agent.sh, `PUB_DIR`). */
        const val PUB = "/data/glassworks-pub"

        /** The request, relative to the app's files dir: the agent's APP_ADB_REQ. */
        const val REQUEST = "adb/request"

        /** The agent's status: set once it keeps the computers (S1Names: the loader's agent only). */
        const val STATUS = "sys.glassworks.adb.status"

        private const val ID = "0123456789abcdefghijklmnopqrstuvwxyz"
        private val RANDOM = SecureRandom()
        private const val MAX_FILE = 64L * 1024
    }
}
