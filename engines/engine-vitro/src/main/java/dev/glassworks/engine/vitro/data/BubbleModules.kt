/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.model.LovelaceResource
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether Bubble Card modules may restyle every Bubble card on a Home Assistant, so Vitro can't draw one as Bubble
 * does. Bubble Card 3.4.3 applies its `default` module and every module marked `is_global` to each card (and the ones a
 * card lists in `modules`), reading them from three places (`bubble-card.js`, the module loader):
 *
 * 1. Bubble Card Tools, an integration whose WebSocket commands (`bubble_card_tools/list_modules`) hold them.
 * 2. The `sensor.bubble_card_modules` entity, its `modules` attribute.
 * 3. The legacy file `/local/bubble/bubble-modules.yaml`.
 *
 * Vitro doesn't read what the modules say: any of the three being there sends every Bubble card to the island, which
 * runs Bubble's own script with them. An answer from Tools or the sensor that Vitro can't read counts as modules:
 * drawing a card wrong is worse than its island's cost. The file is read as Bubble reads it ([fileAnswer]).
 */
object BubbleModules {
    const val SCRIPT = "bubble-card.js"
    const val TOOLS_COMMAND = "bubble_card_tools/list_modules"
    const val SENSOR = "sensor.bubble_card_modules"
    const val LEGACY_FILE = "/local/bubble/bubble-modules.yaml"

    /** True when [resources] load Bubble Card: only then does the probe run (and a Bubble card reach an island). */
    fun loaded(resources: List<LovelaceResource>): Boolean =
        resources.any { it.type != "css" && IslandScripts.fileName(it.url) == SCRIPT }

    /**
     * What Bubble Card Tools' command answered: modules may be there unless HA says the command doesn't exist. A
     * success means the integration is set up, whatever it lists; any other error (a dropped connection, a user it
     * refuses) can't tell.
     */
    fun toolsAnswer(res: JSONObject): Boolean =
        res.optBoolean("success", false) || res.optJSONObject("error")?.optString("code") != "unknown_command"

    /** The first `subscribe_entities` event for [SENSOR]: true when it lists the sensor (HA leaves a missing one out). */
    fun sensorAnswer(event: JSONObject): Boolean = event.optJSONObject("a")?.has(SENSOR) == true

    /**
     * What a GET of [LEGACY_FILE] answered, after its same-origin redirects: the HTTP status, and the first line of the
     * body's YAML content (after blank lines, comments, directives and a `---`), empty when it has none.
     */
    data class FileProbe(val status: Int, val firstLine: String = "")

    /**
     * Whether [LEGACY_FILE]'s answer ([probe], null when the request failed) may hold modules, as Bubble Card 3.4.3
     * reads it: `fetch` (redirects followed), `if (!response.ok) continue`, then the body parsed as YAML, and modules
     * only from a mapping; a failed request is caught and gives none. The content type plays no part. So only a 2xx
     * whose YAML document is a non-empty mapping ([isMapping]) counts: not an empty body, a list, a scalar, or an HTML
     * page (a proxy's or a frontend's page for a file that isn't there, which YAML reads as text).
     */
    fun fileAnswer(probe: FileProbe?): Boolean =
        probe != null && probe.status in 200..299 && isMapping(probe.firstLine)

    /**
     * True when a YAML document whose first content line is [line] is a non-empty mapping: a block mapping's first key
     * (`default:`, `"my module": …`, `? key`), or a flow mapping that isn't `{}`. The document's top-level form is decided
     * by that line. Vitro doesn't parse the rest: a file shaped as a mapping that YAML would refuse counts as modules
     * (Bubble would apply none), which errs toward the island.
     */
    fun isMapping(line: String): Boolean {
        val l = withoutProperties(line)
        if (l.isEmpty()) return false
        if (l.startsWith("{")) return !FLOW_EMPTY.matches(l)
        return BLOCK_KEY.containsMatchIn(l)
    }

    /** A block mapping's first key: a quoted or plain key, then `:` and a space or the line's end; or `? `. */
    private val BLOCK_KEY = Regex("""^(?:\?\s|(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^\s#\-?:,\[\]{}&*!|>'"%@`][^#]*?|-[^\s#][^#]*?)\s*:(?:\s|$))""")
    private val FLOW_EMPTY = Regex("""^\{\s*\}\s*(?:#.*)?$""")

    /**
     * YAML node properties in front of a node: an anchor (`&modules`) or a tag (`!!map`, `!local`, `!<uri>`), each followed
     * by a space or the line's end. A mapping may carry them (`&modules` on its own line, then `default:`), and YAML reads
     * the mapping all the same (Greptile on #952).
     */
    private val NODE_PROPERTIES = Regex("""^(?:(?:&[^\s\[\]{},]+|!(?:<[^>]*>|[^\s\[\]{},]*))(?:\s+|$))+""")

    /** [line] trimmed, its leading node properties ([NODE_PROPERTIES]) dropped. */
    internal fun withoutProperties(line: String): String {
        val t = line.trim()
        val m = NODE_PROPERTIES.find(t) ?: return t
        return t.substring(m.range.last + 1).trim()
    }

    /** The most of a body [probe] reads: blank lines and comments before its content count toward it. */
    internal const val MAX_BODY_CHARS = 1 shl 20

    /** The most same-origin redirects [probe] follows, as `fetch` follows them (another origin: the fetch fails). */
    internal const val MAX_REDIRECTS = 3

    /** A GET of [url] ([FileProbe]), its same-origin redirects followed, or null when it can't be read. Blocking. */
    fun probe(url: String): FileProbe? = runCatching {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            try {
                val status = conn.responseCode
                if (status in REDIRECTS) {
                    val next = conn.getHeaderField("Location")?.let { URL(target, it) } ?: return@runCatching null
                    // Bubble's fetch would follow another origin too, where HA's CORS refuses it: no file.
                    if (!sameOrigin(target, next)) return@runCatching null
                    target = next
                    return@repeat
                }
                val first = if (status in 200..299) conn.inputStream.use(::firstContentLine) else ""
                return@runCatching FileProbe(status, first)
            } finally {
                conn.disconnect()
            }
        }
        null // more redirects than fetch would reasonably need: no file
    }.getOrNull()

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
            (if (a.port < 0) a.defaultPort else a.port) == (if (b.port < 0) b.defaultPort else b.port)

    /**
     * The first line of [input]'s YAML content: blank lines, comments, `%` directives and a `---` document start are
     * skipped however long they run, up to [MAX_BODY_CHARS] read in all (then empty: nothing YAML reads as modules).
     */
    internal fun firstContentLine(input: java.io.InputStream): String {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8))
        var read = 0
        val line = StringBuilder()
        while (read < MAX_BODY_CHARS) {
            val c = reader.read()
            if (c < 0 || c == '\n'.code) {
                val l = line.toString().trimEnd('\r').removePrefix("\uFEFF")
                val t = l.trim()
                val content = when {
                    t.isEmpty() || t.startsWith("#") || t.startsWith("%") -> null
                    t == "---" || t.startsWith("--- ") -> t.removePrefix("---").trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    else -> l
                }
                // A line of node properties alone (`&modules`, `--- !!map`) belongs to the node on the next line.
                if (content != null && withoutProperties(content).let { it.isNotEmpty() && !it.startsWith("#") }) return content
                if (c < 0) return ""
                line.setLength(0)
            } else {
                line.append(c.toChar())
            }
            read++
        }
        return ""
    }
}
