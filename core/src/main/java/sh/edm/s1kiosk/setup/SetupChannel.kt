package sh.edm.s1kiosk.setup

import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupStep
import org.json.JSONArray
import org.json.JSONObject
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.discovery.HaDiscovery
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The setup channel (pure; the Android side is SetupChannelServer): everything the panel's own
 * onboarding asks for besides Wi-Fi, so the web installer can do it from the computer. Abstract
 * socket `@s1kiosk_setup`, reached through adb `localabstract:`; only adb is accepted (AdbPeer).
 * The client is kiln/packages/setup-channel/src/channel.ts (protocol source of truth); keep both in sync.
 *
 * One JSON object per line, UTF-8, both ways. Requests carry an `id`; the reply echoes it:
 * ```
 * → {"id":1,"op":"state"}                         ← {"id":1,"ok":true,"state":{…}}
 * → {"id":2,"op":"ha.set_url","url":"http://…"}   ← {"id":2,"ok":true,"state":{…}} | {"id":2,"ok":false,"error":"…"}
 * → {"id":3,"op":"radio.set_mode","mode":"off"}   ← the same, for each setup step's ops (`<step id>.<op>`)
 * ```
 * The state is the core's (`ip`, `ha`) plus each pane step's own part under its id (`radio`). The S1's
 * first names (`set_ha`, `set_radio`, `set_radio_allow`) still work: Kiln sends them.
 * `pairing.arm` opens pairing over the LAN again for a while (dev.glassworks.setup.LanSetup).
 * `wifi.scan` answers with the networks the panel sees (`networks`, besides the state), and `wifi.join` (`ssid`,
 * `password`) joins one, after the reply is written: the panel's screen shows how it goes (#197).
 * The same loop serves the LAN's WebSocket (LanSetup), where [mayChange] is false until the browser
 * has paired: then every request but `state` is refused. Over USB it is always true.
 * While a client is connected, `{"event":"state","state":{…}}` is pushed whenever the serialized
 * state changes (checked every [SetupChannel.POLL_MS]). No tokens or API key cross it. One secret does: the
 * Wi-Fi password `wifi.join` carries, which is never logged nor sent back. `wifi.join` is refused while the panel
 * is on a network ([ChannelState.online]), so the password never crosses a working LAN as plain HTTP: it comes
 * over the panel's own WPA2 setup hotspot, or over USB. Kiln's USB wizard keeps Improv Serial for Wi-Fi.
 */
data class ChannelState(
    val ip: String?,
    val ha: Ha,
    /** The panel is on a network now: Wi-Fi or Ethernet, with or without internet. */
    val online: Boolean = false,
) {
    data class Ha(val discovered: String?, val url: String, val signedIn: Boolean, val signinUrl: String?)

    /** The core's part of the `SetupState` shape of channel.ts; optional fields are left out when unknown. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        ip?.let { o.put("ip", it) }
        o.put("online", online)
        o.put("ha", JSONObject().apply {
            ha.discovered?.let { put("discovered", it) }
            put("url", ha.url)
            put("signedIn", ha.signedIn)
            ha.signinUrl?.let { put("signinUrl", it) }
        })
        return o
    }

    companion object {
        /** The panel's sign-in page (its QR code link), once it has an address and knows HA. */
        fun signinUrl(ip: String?, haUrl: String?): String? =
            if (ip.isNullOrEmpty() || haUrl.isNullOrEmpty()) null else "http://$ip:${OAuth.SIGNIN_PORT}/signin"

        /** The HA address the app found by itself (mDNS, or HA's ESPHome connection); not one typed in. */
        fun discovered(haUrl: String, source: String): String? =
            haUrl.takeIf { it.isNotEmpty() && (source == HaDiscovery.SOURCE_MDNS || source == HaDiscovery.SOURCE_ESPHOME) }

        /** http(s)://host[:port][/path], no spaces or quotes (channel.ts validHaUrl). */
        fun validHaUrl(u: String): Boolean {
            if (u.any { it.isWhitespace() || it == '\'' || it == '"' }) return false
            val x = runCatching { URI(u) }.getOrNull() ?: return false
            val scheme = x.scheme?.lowercase()
            // URI leaves host null for names java.net doesn't like (e.g. with "_"); fall back to the authority.
            val host = x.host ?: x.rawAuthority?.substringAfter('@')?.substringBefore(':')
            return (scheme == "http" || scheme == "https") && !host.isNullOrEmpty()
        }
    }
}

/** A network the panel sees: its name, its signal in dBm, and whether it asks for a password. */
data class WifiNetwork(val ssid: String, val rssi: Int, val secure: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("ssid", ssid).put("rssi", rssi).put("secure", secure)

    companion object {
        /** One entry per name, the strongest; strongest first. Hidden networks (no name) are left out. */
        fun list(seen: List<WifiNetwork>): List<WifiNetwork> =
            seen.filter { it.ssid.isNotEmpty() }.groupBy { it.ssid }.map { (_, same) -> same.maxBy { it.rssi } }.sortedByDescending { it.rssi }
    }
}

class SetupChannel(
    private val backend: Backend,
    /** The panes' setup steps (through SetupFlow.usable): their state and ops ([SetupStep.ops]). */
    steps: List<SetupStep> = emptyList(),
    private val log: (String) -> Unit = {},
    /** Whether a request that changes something may run now; `state` always may. */
    private val mayChange: () -> Boolean = { true },
) {

    interface Backend {
        /** The core's part of the state. */
        fun state(): ChannelState
        /**
         * Use [url] (already validated) as Home Assistant's address, as the `url` intent extra does.
         * Blocking. [Change.afterReply] runs once the reply is written (e.g. a process restart).
         */
        fun setHa(url: String): Change

        /** Opens pairing over the LAN again for a while, though Home Assistant has adopted the panel. Null when it worked. */
        fun armPairing(): String? = "This panel can't be set up over the network."

        /** The networks a fresh scan finds. Blocking, up to about 15 s. Empty when the panel can't scan. */
        fun scanWifi(): List<WifiNetwork> = emptyList()

        /**
         * Joins [ssid] with [password] (checked already; never logged). The join runs in [Change.afterReply], after the
         * reply: joining can take the panel off the network the browser reached it on.
         */
        fun joinWifi(ssid: String, password: String): Change = Change(error = "This panel can't join Wi-Fi from here.")

        /** The Wi-Fi radio is off and the app can't turn it on (Android 10+): a join could never happen. */
        fun wifiRadioLocked(): Boolean = false
    }

    /** What a change request did: [error] refuses it; [afterReply] runs once the reply is written. */
    data class Change(val error: String? = null, val afterReply: (() -> Unit)? = null)

    private val steps = steps.toList()

    /** Every step op by its wire name, `<step id>.<op>`. */
    private val ops: Map<String, SetupOp> = steps.flatMap { s -> s.ops.map { (name, op) -> "${s.id}.$name" to op } }.toMap()

    /** Older wire names: the core's, then each step's. */
    private val aliases: Map<String, String> = mapOf("set_ha" to HA_SET_URL) +
        steps.flatMap { s -> s.legacyOps.map { (old, name) -> old to "${s.id}.$name" } }

    /** The whole state: the core's, and each step's part under its id. */
    private fun state(): JSONObject = backend.state().toJson().apply {
        steps.forEach { s -> s.channelState()?.let { put(s.id, it) } }
    }

    /** One request line → the reply line (without "\n") and what to run after writing it. */
    fun handle(line: String): Pair<String, (() -> Unit)?> {
        val req = runCatching { JSONObject(line) }.getOrNull()
            ?: return reply(null, error = "not a JSON object") to null
        val id: Any? = req.opt("id")?.takeIf { it != JSONObject.NULL }
        val asked = req.optString("op", "")
        val op = aliases[asked] ?: asked
        log("request ${id ?: "-"} $asked")
        return when (op) {
            "state" -> reply(id, state = state()) to null
            else -> if (!mayChange()) reply(id, error = NOT_PAIRED) to null else changing(id, op, asked, req)
        }
    }

    /** A request that changes something, once [mayChange] allowed it. */
    private fun changing(id: Any?, op: String, asked: String, req: JSONObject): Pair<String, (() -> Unit)?> =
        when (op) {
            PAIRING_ARM -> when (val err = backend.armPairing()) {
                null -> reply(id, state = state())
                else -> reply(id, error = err)
            } to null
            WIFI_SCAN -> reply(id, state = state(), networks = WifiNetwork.list(backend.scanWifi())) to null
            WIFI_JOIN -> joinWifi(id, req)
            HA_SET_URL -> {
                val url = req.optString("url", "").trim()
                if (!ChannelState.validHaUrl(url)) {
                    reply(id, error = "That doesn't look like a web address (http://… or https://…).") to null
                } else {
                    val change = backend.setHa(url)
                    if (change.error != null) reply(id, error = change.error) to null
                    else reply(id, state = state()) to change.afterReply
                }
            }
            else -> when (val step = ops[op]) {
                null -> reply(id, error = "Unknown request '$asked'.") to null
                else -> when (val err = step.run(req)) {
                    null -> reply(id, state = state()) to null
                    else -> reply(id, error = err) to null
                }
            }
        }

    /** `wifi.join`: checks the network and password, and refuses while the panel is on a network. */
    private fun joinWifi(id: Any?, req: JSONObject): Pair<String, (() -> Unit)?> {
        val ssid = req.optString("ssid", "")
        val password = req.optString("password", "")
        val why = when {
            ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32 -> "A network name is 1 to 32 bytes long (an accented letter takes 2)."
            !validPassword(password) -> "A Wi-Fi password is at most 63 bytes long (an accented letter takes 2), or a 64-digit hex key."
            backend.state().online -> ON_A_NETWORK
            // Before the reply, not after: the page hears it instead of "Joining…" (S1 panel run on e7d2d49d).
            backend.wifiRadioLocked() -> WIFI_OFF
            else -> null
        }
        if (why != null) return reply(id, error = why) to null
        val change = backend.joinWifi(ssid, password)
        return if (change.error != null) reply(id, error = change.error) to null else reply(id, state = state()) to change.afterReply
    }

    /** The state the client last got (reply or event), to push only changes. */
    private var lastSent: String? = null

    private fun reply(id: Any?, state: JSONObject? = null, error: String? = null, networks: List<WifiNetwork>? = null): String {
        val o = JSONObject()
        id?.let { o.put("id", it) }
        o.put("ok", error == null)
        networks?.let { n -> o.put("networks", JSONArray(n.map { it.toJson() })) }
        if (state != null) {
            lastSent = state.toString()
            o.put("state", state)
        }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    /** A state event if the state changed since the client last saw it (nothing before it asked once). */
    fun pushIfChanged(): String? {
        val prev = lastSent ?: return null
        val s = state()
        val now = s.toString()
        if (now == prev) return null
        lastSent = now
        return JSONObject().put("event", "state").put("state", s).toString()
    }

    /**
     * Serves one connection until the input ends: requests are handled in order on this thread, and
     * between them the state is checked every [pollMs] for a push. A helper thread only splits the
     * input into lines.
     */
    fun serve(input: InputStream, output: OutputStream, pollMs: Long = POLL_MS) {
        val lines = LinkedBlockingQueue<Any>()
        val reader = Thread({
            try { splitLines(input) { lines.put(it) } } catch (_: Exception) {} finally { lines.put(EOF) }
        }, "s1-setup-reader").apply { isDaemon = true; start() }
        fun write(s: String) { output.write((s + "\n").toByteArray(Charsets.UTF_8)); output.flush() }
        try {
            while (true) {
                val item = lines.poll(pollMs, TimeUnit.MILLISECONDS)
                if (item === EOF) break
                if (item is String) {
                    if (item.isBlank()) continue
                    val (r, after) = handle(item)
                    write(r)
                    after?.let { runCatching(it).onFailure { e -> log("after reply: $e") } }
                }
                pushIfChanged()?.let(::write)
            }
        } finally {
            reader.interrupt()
        }
    }

    companion object {
        const val SOCKET = "s1kiosk_setup"
        const val HA_SET_URL = "ha.set_url"
        const val PAIRING_ARM = "pairing.arm"
        const val WIFI_SCAN = "wifi.scan"
        const val WIFI_JOIN = "wifi.join"
        const val WIFI_OFF = "The panel's Wi-Fi is turned off, and it can't turn it on itself. Turn on Wi-Fi on the panel first."
        const val ON_A_NETWORK = "The panel is on a network already. Change its Wi-Fi on the panel's screen."
        const val NOT_PAIRED = "Pair with the panel first: enter the code it shows."
        /** The core's keys in the state; no step may take them. */
        val CORE_KEYS = setOf("ip", "ha", "online")
        /** The core's requests, by every name they have. */
        val CORE_OPS = setOf("state", HA_SET_URL, "set_ha", PAIRING_ARM, WIFI_SCAN, WIFI_JOIN)

        /**
         * No network takes a longer password: at most 63 bytes of UTF-8, or 64 ASCII hex digits (a raw WPA key). Shorter ones go
         * on to the provisioner, which checks them against the network's security: WPA2 wants 8, WPA3-SAE and WEP
         * don't (CodeRabbit on #418).
         */
        fun validPassword(p: String): Boolean =
            // WPA counts bytes: "é" is one character and two bytes (CodeRabbit on #416).
            p.toByteArray(Charsets.UTF_8).size <= 63 || (p.length == 64 && p.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        const val POLL_MS = 1_500L
        /** Longer lines are dropped (requests are tiny). */
        const val MAX_LINE = 16 * 1024
        private val EOF = Any()

        /** Splits [input] into UTF-8 lines (LF, optional CR), dropping lines over [MAX_LINE] bytes. */
        fun splitLines(input: InputStream, onLine: (String) -> Unit) {
            val buf = java.io.ByteArrayOutputStream()
            var tooLong = false
            val chunk = ByteArray(1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) return
                for (i in 0 until n) {
                    val b = chunk[i]
                    if (b == '\n'.code.toByte()) {
                        if (!tooLong) onLine(buf.toString("UTF-8").trimEnd('\r'))
                        buf.reset()
                        tooLong = false
                    } else if (!tooLong) {
                        buf.write(b.toInt())
                        if (buf.size() > MAX_LINE) { tooLong = true; buf.reset() }
                    }
                }
            }
        }
    }
}
