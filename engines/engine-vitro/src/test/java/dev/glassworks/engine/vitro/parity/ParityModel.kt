/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import dev.glassworks.testing.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The parity harness's inputs (#793): the panels it renders at, the corpus of cases, and the references Home
 * Assistant's own frontend drew for them (tools/vitro-parity/capture.mjs). See tools/vitro-parity/README.md.
 *
 * A corpus root holds `corpus/<case>.json`, `refs/<frame>/<case>.png|.json`, `refs/shared.json`, `assets/` (images the
 * fixture server hands Vitro), `baseline.json` (known drift) and, for a local corpus, `dashboards/<url_path>.json`.
 */
object ParityFiles {
    /** The committed corpus: synthetic card configs on harmless entities. CI runs it. */
    val committed: File get() = File(RepoFiles.root, "engines/engine-vitro/src/test/parity")

    /** A local corpus (real dashboards), from -PvitroParityLocal=<dir>. Never committed. */
    val local: File? get() = System.getProperty("vitro.parity.local")?.takeIf { it.isNotBlank() }?.let(::File)

    /** Where the report goes: -PvitroParityOut=<dir>, else the module's build/reports/vitro-parity. */
    val out: File get() = System.getProperty("vitro.parity.out")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(RepoFiles.root, "engines/engine-vitro/build/reports/vitro-parity")

    /** Collect mode (-PvitroParityCollect=true): render only, and write the templates Vitro asks for. */
    val collect: Boolean get() = System.getProperty("vitro.parity.collect") == "true"

    /** -PvitroParityUpdateBaseline=true: rewrite baseline.json with the drift found now. */
    val updateBaseline: Boolean get() = System.getProperty("vitro.parity.updateBaseline") == "true"

    /** -PvitroParityCases=a,b and -PvitroParityFrames=s1: run part of the corpus. */
    val onlyCases: Set<String>? get() = System.getProperty("vitro.parity.cases")?.takeIf { it.isNotBlank() }?.split(',')?.toSet()
    val onlyFrames: Set<String>? get() = System.getProperty("vitro.parity.frames")?.takeIf { it.isNotBlank() }?.split(',')?.toSet()

    /**
     * What's wrong with a selection: each requested case id in none of [caseIds] and each requested frame id not in
     * [frameIds]. A typo would otherwise select nothing, and a run that compares nothing passes.
     */
    fun unknownSelections(cases: Set<String>?, caseIds: Set<String>, frames: Set<String>?, frameIds: Set<String>): List<String> =
        cases.orEmpty().filter { it !in caseIds }.map { "no case \"$it\" in any corpus (vitroParityCases)" } +
            frames.orEmpty().filter { it !in frameIds }.map { "no frame \"$it\" in frames.json (vitroParityFrames); known: ${frameIds.sorted().joinToString()}" }
}

/**
 * One panel: CSS size and density. The same numbers drive Chromium and Robolectric's qualifiers. [memTotalKb] is the
 * RAM the panel reports, which picks Vitro's card layers as on the panel (0: not known, the low-RAM rule).
 */
data class ParityFrame(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val density: Float,
    val qualifiers: String,
    val memTotalKb: Long = 0,
) {
    val pxWidth: Int get() = Math.round(width * density)
    val pxHeight: Int get() = Math.round(height * density)

    companion object {
        fun load(): List<ParityFrame> {
            val arr = JSONObject(File(ParityFiles.committed, "frames.json").readText()).getJSONArray("frames")
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                ParityFrame(it.getString("id"), it.getString("name"), it.getInt("width"), it.getInt("height"),
                    it.getDouble("density").toFloat(), it.getString("qualifiers"), it.optLong("memTotalKb", 0))
            }
        }
    }
}

/** How far a case may drift before it counts. Pixel is the share of the crop that differs (0..1). */
data class Threshold(val pixel: Double = 0.02, val bounds: Double = 2.0, val font: Double = 1.0, val missingText: Int = 0) {
    companion object {
        fun parse(o: JSONObject?): Threshold {
            val d = Threshold()
            if (o == null) return d
            return Threshold(o.optDouble("pixel", d.pixel), o.optDouble("bounds", d.bounds), o.optDouble("font", d.font), o.optInt("missingText", d.missingText))
        }
    }
}

/** A rectangle in CSS px. */
data class CssRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val bottom get() = y + h

    companion object {
        fun of(o: JSONObject) = CssRect(o.optDouble("x"), o.optDouble("y"), o.optDouble("w", o.optDouble("width")), o.optDouble("h", o.optDouble("height")))
    }
}

/**
 * One corpus case: a single card (shown alone in a one-section sections view, like the HA side's `vitro-harness`
 * dashboard), or a real dashboard view (local corpus only).
 */
data class ParityCase(
    val id: String,
    val title: String,
    val card: JSONObject?,
    val dashboard: String?,
    val view: String?,
    val frames: Set<String>?,
    val dark: Boolean,
    val theme: String?,
    val span: Int,
    val threshold: Threshold,
    /** Regions (CSS px, relative to the card) left out of the pixel diff, each with a reason. */
    val masks: List<Pair<CssRect, String>>,
) {
    fun runsOn(frame: ParityFrame) = frames == null || frame.id in frames

    /** The frame ids this case lists that aren't in frames.json: a typo ("sl") would otherwise skip the case. */
    fun unknownFrames(frameIds: Set<String>): List<String> = frames.orEmpty().filter { it !in frameIds }.sorted()

    /** The Lovelace dashboard Vitro renders: the same one-card view capture.mjs saves to `vitro-harness`. */
    fun dashboardJson(root: File): JSONObject {
        if (dashboard != null) return JSONObject(File(root, "dashboards/$dashboard.json").readText())
        val section = JSONObject().put("type", "grid").put("cards", JSONArray().put(JSONObject(card!!.toString())))
        if (span > 1) section.put("column_span", span)
        val view = JSONObject().put("title", id).put("path", id).put("type", "sections").put("max_columns", span)
            .put("sections", JSONArray().put(section))
        theme?.let { view.put("theme", it) }
        return JSONObject().put("title", "Vitro harness").put("views", JSONArray().put(view))
    }

    companion object {
        fun loadAll(root: File): List<ParityCase> {
            val dir = File(root, "corpus")
            val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
            return files.map { f -> parse(f.name.removeSuffix(".json"), JSONObject(f.readText())) }
        }

        fun parse(id: String, o: JSONObject): ParityCase {
            val frames = o.optJSONArray("frames")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val masks = o.optJSONArray("masks")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { CssRect.of(it) to it.optString("why", "volatile") }
            }.orEmpty()
            return ParityCase(
                id = id,
                title = o.optString("title", id),
                card = o.optJSONObject("card"),
                dashboard = o.optString("dashboard").ifBlank { null },
                view = o.optString("view").ifBlank { null },
                frames = frames,
                dark = o.optBoolean("dark", true),
                theme = o.optString("theme").ifBlank { null },
                span = o.optInt("span", 1),
                threshold = Threshold.parse(o.optJSONObject("threshold")),
                masks = masks,
            )
        }
    }
}

/** A text run HA drew: where (CSS px, relative to its card slot), and in what font. */
data class RefText(
    val slot: Int, val text: String, val box: CssRect, val size: Double, val weight: Int, val transform: String,
    /** HA cuts this text itself (overflow, an ellipsis): null in references taken before the capture recorded it. */
    val clipped: Boolean? = null,
)

/** What capture.mjs recorded for one case on one frame. */
class ParityReference(val png: File, val json: JSONObject) {
    val now: Long get() = json.getLong("now")
    val clip: CssRect get() = CssRect.of(json.getJSONObject("clip"))
    val stable: Boolean get() = json.optBoolean("stable", true)
    val states: JSONObject get() = json.optJSONObject("states") ?: JSONObject()

    /** Image paths HA's page asked for and didn't get (written by the capture only when there are some). */
    val imagesHaMissing: Set<String> get() = json.optJSONArray("imagesHaMissing")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    /**
     * The history and statistics calls HA's page made for the case's graphs, with what came back (`request`, then
     * `result` or `error`, and a subscription's `events`): written by the capture only when there are some.
     */
    val ws: JSONArray? get() = json.optJSONArray("ws")

    /** What HaDisplay held for the case's entities (registry entries, device names, icons, state labels), or null. */
    val display: JSONObject? get() = json.optJSONObject("display")
    val slots: List<CssRect> get() = json.getJSONArray("slots").let { a -> (0 until a.length()).map { CssRect.of(a.getJSONObject(it)) } }
    val slotTypes: List<String> get() = json.getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("type") } }

    /** Template string -> what HA rendered for it when the reference was taken. */
    val templates: Map<String, String> get() {
        val o = json.optJSONObject("templates") ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> o.getJSONObject(k).optString("result", null)?.let { k to it } }.toMap()
    }

    val texts: List<RefText> get() = json.optJSONArray("texts")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            RefText(it.optInt("slot"), it.getString("text"), CssRect.of(it), it.optDouble("size"), it.optInt("weight", 400), it.optString("transform", "none"),
                if (it.has("clipped")) it.optBoolean("clipped") else null)
        }
    }.orEmpty()

    companion object {
        fun find(root: File, frame: ParityFrame, case: ParityCase): ParityReference? {
            val png = File(root, "refs/${frame.id}/${case.id}.png")
            val json = File(root, "refs/${frame.id}/${case.id}.json")
            return if (png.isFile && json.isFile) ParityReference(png, JSONObject(json.readText())) else null
        }
    }
}

/** refs/shared.json: the HA version, the config fields Vitro reads, and the themes, from the last capture. */
class ParityShared(val json: JSONObject) {
    val haConfig: JSONObject get() = json.optJSONObject("haConfig") ?: JSONObject()
    val themes: JSONObject? get() = json.optJSONObject("themes")
    val haVersion: String get() = json.optString("haVersion", "?")

    companion object {
        fun load(root: File): ParityShared? = File(root, "refs/shared.json").takeIf { it.isFile }?.let { ParityShared(JSONObject(it.readText())) }
    }
}

/** Whether a render had every input the capture should have given it. */
object ParityInputs {
    /**
     * The image paths Vitro asked the fixture server for and didn't get, split into capture gaps (HA loaded or never
     * asked for them: the case can't be compared) and ones HA's own page couldn't load either.
     */
    fun missingImages(asked: List<String>, ref: ParityReference): Pair<List<String>, List<String>> {
        val missing = asked.distinct().sorted()
        val haToo = ref.imagesHaMissing
        return missing.filter { it !in haToo } to missing.filter { it in haToo }
    }
}

/**
 * Answers Vitro's history and statistics calls with what HA's page got for the same calls ([ParityReference.ws]),
 * so a graph draws exactly the data HA drew. A call must match one HA made, field for field (its times included,
 * which come from the pinned clock): one that doesn't is listed in [unmatched], and the case fails as a different
 * input rather than as drift.
 */
class ParityWs(recorded: JSONArray?) {
    private val calls: List<JSONObject> = recorded?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val unmatched = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * The recorded call whose request is [msg] (its `id` aside), or null. When the page made the same call more than
     * once, the last one: its answer is the one HA held when the screenshot was taken.
     */
    fun find(msg: JSONObject): JSONObject? {
        val want = canonical(msg)
        return calls.lastOrNull { canonical(it.getJSONObject("request")) == want }
    }

    /** A one-shot command: its recorded result, or its recorded error. */
    fun call(msg: JSONObject, done: (Boolean, Any?, JSONObject?) -> Unit) {
        val hit = find(msg)
        when {
            hit == null -> {
                unmatched += canonical(msg)
                done(false, null, JSONObject().put("code", "not_recorded"))
            }
            hit.has("result") -> done(true, hit.opt("result").takeIf { it != JSONObject.NULL }, null)
            else -> done(false, null, hit.optJSONObject("error") ?: JSONObject().put("code", "unknown"))
        }
    }

    /**
     * A subscription: its recorded events, in order, each through [deliver], or its recorded rejection to [onError].
     * Returns the unsubscribe handle.
     */
    fun subscribe(msg: JSONObject, deliver: (Runnable) -> Unit, onEvent: (JSONObject) -> Unit, onError: (JSONObject?) -> Unit = {}): () -> Unit {
        val hit = find(msg)
        if (hit == null) {
            unmatched += canonical(msg)
            return {}
        }
        var live = true
        if (hit.has("error")) {
            val error = hit.optJSONObject("error")
            deliver(Runnable { if (live) onError(error) })
            return { live = false }
        }
        val events = hit.optJSONArray("events") ?: JSONArray()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            deliver(Runnable { if (live) onEvent(ev) })
        }
        return { live = false }
    }

    companion object {
        /** [o] without its top-level `id`, as JSON text with keys sorted at every level: equal for equal requests. */
        fun canonical(o: JSONObject): String = text(o, top = true)

        private fun text(v: Any?, top: Boolean = false): String = when (v) {
            is JSONObject -> v.keys().asSequence().filter { !(top && it == "id") }.sorted()
                .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + text(v.get(k)) }
            is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { text(v.get(it)) }
            is String -> JSONObject.quote(v)
            null, JSONObject.NULL -> "null"
            is Number -> JSONObject.numberToString(v)
            else -> v.toString()
        }
    }
}
