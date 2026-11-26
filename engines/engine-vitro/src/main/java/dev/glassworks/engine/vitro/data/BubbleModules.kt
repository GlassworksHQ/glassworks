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
     * What a GET of [LEGACY_FILE] answered: its HTTP status, its `Content-Type`, and the first characters of its body
     * that aren't white space (empty for an empty body).
     */
    data class FileProbe(val status: Int, val contentType: String? = null, val start: String = "")

    /**
     * Whether [LEGACY_FILE]'s answer ([probe], null when the request failed) may hold modules, as Bubble Card 3.4.3
     * reads it: `fetch`, then `if (!response.ok) continue`, then the body parsed as YAML into a mapping of modules. A
     * failed request is caught and gives none. So only a 2xx can, and not an empty body or an HTML page (a proxy's or
     * a frontend's page for a file that isn't there: YAML reads it as text, never a mapping of modules).
     */
    fun fileAnswer(probe: FileProbe?): Boolean {
        if (probe == null || probe.status !in 200..299) return false
        if (probe.contentType?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true) return false
        return probe.start.isNotEmpty() && !probe.start.startsWith("<")
    }

    /** A GET of [url] ([FileProbe]: reads at most the body's first 4 KiB), or null when it can't be read. Blocking. */
    fun probe(url: String): FileProbe? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        try {
            val status = conn.responseCode
            val start = if (status in 200..299) {
                conn.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n, Charsets.UTF_8).trimStart().take(16)
                }
            } else ""
            FileProbe(status, conn.contentType, start)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
