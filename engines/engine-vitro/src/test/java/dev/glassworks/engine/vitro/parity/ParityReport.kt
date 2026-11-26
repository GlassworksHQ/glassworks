/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class ParityStatus(val label: String) {
    REGRESSION("regression"), ERROR("error"), NO_REFERENCE("no reference"), OPEN("open bug"), DRIFT("known drift"), MATCH("match"), COLLECTED("collected"),
}

/** One HA text run and where Vitro has it. Positions are CSS px relative to each side's own card slot. */
data class TextCheck(
    val text: String, val sizeHa: Double, val sizeVitro: Double?, val dx: Double?, val dy: Double?,
    /** Vitro has the text but cuts it with an ellipsis while HA shows it whole: counted as missing. */
    val elided: Boolean = false,
    /** Vitro draws the text on a canvas and describes it for screen readers: present, with nothing to measure. */
    val described: Boolean = false,
) {
    val found get() = sizeVitro != null || described
}

/** The structural and pixel numbers for one case on one frame. */
data class ParityMeasure(
    val pixel: PixelDiff,
    /** Largest edge difference of the card slot(s), CSS px. */
    val bounds: Double,
    val haSlots: List<CssRect>,
    val vitroSlots: List<CssRect>,
    val texts: List<TextCheck>,
) {
    val slotsHa: Int get() = haSlots.size
    val slotsVitro: Int get() = vitroSlots.size

    val fontDelta: Double get() = texts.mapNotNull { t -> t.sizeVitro?.let { abs(it - t.sizeHa) } }.maxOrNull() ?: 0.0
    val positionDelta: Double get() = texts.mapNotNull { t -> if (t.dx == null || t.dy == null) null else max(abs(t.dx), abs(t.dy)) }.maxOrNull() ?: 0.0
    val missingTexts: List<String> get() = texts.filter { !it.found }.map { if (it.elided) "${it.text} (cut by Vitro's ellipsis)" else it.text }

    fun over(t: Threshold): List<String> = buildList {
        if (pixel.tolerant > t.pixel) add("pixels ${pct(pixel.tolerant)} > ${pct(t.pixel)}")
        if (pixel.region > t.region) add("a ${ParityDiff.REGION} px region differs in color: Δ${num2(pixel.region)} > ${num2(t.region)}${pixel.regionAt?.let { " at ${it.left},${it.top}" } ?: ""}")
        if (bounds > t.bounds) add("bounds Δ${num(bounds)} px > ${num(t.bounds)}")
        if (fontDelta > t.font) add("font Δ${num(fontDelta)} px > ${num(t.font)}")
        if (missingTexts.size > t.missingText) add("${missingTexts.size} HA text(s) not found in Vitro's views")
        // A card Vitro adds (or drops) can sit outside the reference crop, where neither the pixels nor the
        // common slots' bounds see it: a different number of cards is itself a failure.
        if (slotsHa != slotsVitro) add("HA shows $slotsHa card slot(s), Vitro $slotsVitro")
    }
}

data class ParityResult(
    val corpus: String,
    val frame: ParityFrame,
    val case: ParityCase,
    val status: ParityStatus,
    val measure: ParityMeasure?,
    val reasons: List<String>,
    val notes: List<String>,
    val image: String?,
) {
    val key get() = "${frame.id}/${case.id}"
}

/**
 * The results that fail the run. A render error or a missing reference always does; a regression does unless
 * [acceptDrift] (a baseline update or a collect run, which record drift rather than judge it).
 */
fun fatalResults(results: List<ParityResult>, acceptDrift: Boolean): List<ParityResult> = results.filter {
    it.status == ParityStatus.ERROR || it.status == ParityStatus.NO_REFERENCE || (!acceptDrift && it.status == ParityStatus.REGRESSION)
}

internal fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
internal fun num2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
internal fun num(v: Double) = if (v.isInfinite()) "∞" else String.format(Locale.ROOT, "%.1f", v)

object ParityCompare {
    /**
     * Whether HA cuts [t] too: as the capture recorded it, or (older references) when its full text box runs past
     * HA's card slot.
     */
    fun haCuts(t: RefText, slot: CssRect?): Boolean = t.clipped ?: (slot != null && t.box.x + t.box.w > slot.w + 0.5)

    /** [c] lowercased on its own: one char in, one char out (String.lowercase can turn "İ" into two). */
    private fun fold(c: Char): Char = Character.toLowerCase(c)

    /**
     * Where [needle] first occurs in [haystack] at or after [from], ignoring case, as an index into [haystack] itself:
     * chars are folded one by one, so the index stays valid for [VitroText.offsets]. -1 when absent.
     */
    fun indexOfFolded(haystack: String, needle: String, from: Int = 0, whole: Boolean = false): Int {
        if (needle.isEmpty()) return if (from <= haystack.length) from else -1
        outer@ for (i in from.coerceAtLeast(0)..haystack.length - needle.length) {
            for (j in needle.indices) if (fold(haystack[i + j]) != fold(needle[j])) continue@outer
            if (whole && (haystack.getOrNull(i - 1)?.isLetterOrDigit() == true || haystack.getOrNull(i + needle.length)?.isLetterOrDigit() == true)) continue
            return i
        }
        return -1
    }

    /**
     * A run of one or two letters or digits (an avatar's initial, a badge) is matched only as a word of its own: the
     * "A" of a user's avatar must not be found inside "9:48 AM" and use that text up.
     */
    fun standalone(needle: String): Boolean = needle.length <= 2 && needle.all { it.isLetterOrDigit() }

    fun measure(case: ParityCase, frame: ParityFrame, ref: ParityReference, render: VitroRender): Pair<ParityMeasure, Bitmap> {
        val d = frame.density
        val refBmp = BitmapFactory.decodeFile(ref.png.path) ?: error("can't decode ${ref.png}")
        val clip = ref.clip
        val cx = (clip.x * d).roundToInt()
        val cy = (clip.y * d).roundToInt()
        val vitroCrop = ParityDiff.crop(render.bitmap, cx, cy, refBmp.width, refBmp.height)
        val sw = max(1, (refBmp.width / d).roundToInt())
        val sh = max(1, (refBmp.height / d).roundToInt())
        val haSlots = ref.slots
        val anchor = haSlots.firstOrNull()
        val masks = if (anchor == null) emptyList() else case.masks.map { (m, _) ->
            // A negative x counts from the slot's right edge (ParityCase.masks).
            val x = anchor.x + (if (m.x < 0) anchor.w + m.x else m.x) - clip.x
            val y = anchor.y + m.y - clip.y
            Rect(x.toInt(), y.toInt(), (x + m.w).roundToInt(), (y + m.h).roundToInt())
        }
        val pixel = ParityDiff.compare(refBmp, vitroCrop, sw, sh, masks)

        val n = minOf(haSlots.size, render.slots.size)
        // A card case needs its one card on both sides; a dashboard compares the slots both have (counts go in the notes).
        var bounds = when {
            case.card != null && (n == 0 || haSlots.size != render.slots.size) -> Double.POSITIVE_INFINITY
            case.card == null && n == 0 && haSlots.size + render.slots.size > 0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        for (i in 0 until n) {
            val a = haSlots[i]
            val b = render.slots[i]
            bounds = max(bounds, listOf(a.x - b.x, a.y - b.y, a.right - b.right, a.bottom - b.bottom).maxOf { abs(it) })
        }

        val texts = ArrayList<TextCheck>()
        val vitroTexts = (0 until render.slots.size).associateWith { render.texts(it) }
        val describedTexts = (0 until render.slots.size).associateWith { render.describedTexts(it) }
        val used = HashMap<VitroText, Int>()
        for (t in ref.texts) {
            val needle = t.text.replace(Regex("\\s+"), " ").trim()
            // A run of only whitespace matches anywhere and consumes nothing (and at 0 would read offsets[-1]).
            if (needle.isEmpty()) continue
            val candidates = vitroTexts[t.slot].orEmpty()
            val haCut = haCuts(t, ref.slots.getOrNull(t.slot))
            // The first visible match; an elided one only when no visible one exists (the view holds the whole text
            // even when its layout cuts it, and a cut HA doesn't make is a missing text).
            var hit: Pair<VitroText, Int>? = null
            var elidedHit: Pair<VitroText, Int>? = null
            for (vt in candidates) {
                val idx = indexOfFolded(vt.normalized, needle, used[vt] ?: 0, whole = standalone(needle))
                if (idx < 0) continue
                val cut = ParityViews.elided(vt.view, vt.offsets[idx], vt.offsets[idx + needle.length - 1] + 1) && !haCut
                if (!cut) { hit = vt to idx; break }
                if (elidedHit == null) elidedHit = vt to idx
            }
            if (hit == null && elidedHit != null) {
                used[elidedHit.first] = elidedHit.second + needle.length
                texts += TextCheck(t.text, t.size, null, null, null, elided = true)
                continue
            }
            if (hit == null) {
                val drawn = describedTexts[t.slot].orEmpty().any { indexOfFolded(it, needle, whole = standalone(needle)) >= 0 }
                texts += TextCheck(t.text, t.size, null, null, null, described = drawn)
                continue
            }
            val (vt, idx) = hit
            used[vt] = idx + needle.length
            val start = vt.offsets[idx]
            val (x, center, size) = render.textMetrics(vt.view, start)
            val slotV = render.slots[t.slot]
            texts += TextCheck(t.text, t.size, size, (x - slotV.x) - t.box.x, (center - slotV.y) - (t.box.y + t.box.h / 2))
        }
        val side = ParityDiff.sideBySide(refBmp, vitroCrop, pixel.image, "${pct(pixel.tolerant)} differ", d)
        return ParityMeasure(pixel, bounds, haSlots, render.slots, texts) to side
    }
}

/** baseline.json: the drift a corpus is known to have, per frame/case, with why. A case may not get worse than this. */
class ParityBaseline(private val file: File) {
    private val json: JSONObject = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()

    fun entry(key: String): JSONObject? = json.optJSONObject(key)

    /** Why [m] is worse than the known drift for [key], or empty when it isn't. */
    fun worse(key: String, m: ParityMeasure, t: Threshold = Threshold()): List<String> {
        val b = entry(key) ?: return listOf("not in baseline.json")
        return buildList {
            if (m.pixel.tolerant > b.optDouble("pixel", 0.0) + PIXEL_SLACK) add("pixels ${pct(m.pixel.tolerant)} > baseline ${pct(b.optDouble("pixel"))}")
            val known = boundsOf(b)
            if (m.bounds > known + BOUNDS_SLACK) add("bounds Δ${num(m.bounds)} > baseline ${num(known)}")
            // An entry from before the region check has none: the case's own threshold is what it was held to.
            val region = max(b.optDouble("region", 0.0), t.region)
            if (m.pixel.region > region + REGION_SLACK) add("region Δ${num2(m.pixel.region)} > baseline ${num2(region)}")
            if (m.fontDelta > b.optDouble("font", 0.0) + FONT_SLACK) add("font Δ${num(m.fontDelta)} > baseline ${num(b.optDouble("font"))}")
            if (m.missingTexts.size > b.optInt("missingText", 0)) add("${m.missingTexts.size} texts missing > baseline ${b.optInt("missingText")}")
            // Vitro's card count minus HA's: a known mismatch is accepted as it is, a new or changed one isn't.
            val slots = m.slotsVitro - m.slotsHa
            if (slots != 0 && slots != b.optInt("slotDelta", 0)) add("Vitro shows ${m.slotsVitro} card(s) for HA's ${m.slotsHa}; baseline: ${b.optInt("slotDelta", 0)} more")
        }
    }

    /** Rewrites the entries of the cases in [results]; entries for cases that didn't run stay as they are. */
    fun write(results: List<ParityResult>) {
        if (results.isEmpty()) return
        // Only measured cases replace (or drop) their entry: a render error or a missing reference keeps the old one.
        val ran = results.filter { it.measure != null }.map { it.key }.toSet()
        val out = JSONObject()
        out.put("_comment", "Known drift per frame/case: the harness fails when a case gets worse than this (plus slack: " +
            "${pct(PIXEL_SLACK)} pixels, $BOUNDS_SLACK px bounds, $FONT_SLACK px font, $REGION_SLACK region). Regenerate with " +
            "-PvitroParityUpdateBaseline=true; keep or write each \"reason\". An update records regressions too: read the " +
            "diff before committing it, and keep the entries of cases with an open regression. #794 empties this file.")
        val entries = java.util.TreeMap<String, Any>()
        for (k in json.keys().asSequence().filter { it !in ran && !it.startsWith("_") }) entries[k] = json.get(k)
        for (r in results) {
            val m = r.measure ?: continue
            // An open bug is tracked in open.json, never as drift.
            if (r.status == ParityStatus.OPEN) continue
            if (m.over(r.case.threshold).isEmpty()) continue
            entries[r.key] = JSONObject()
                .put("pixel", round(m.pixel.tolerant, 4)).put("bounds", if (m.bounds.isInfinite()) UNBOUNDED else round(m.bounds, 1))
                .put("font", round(m.fontDelta, 1)).put("missingText", m.missingTexts.size)
                .apply { if (m.pixel.region > r.case.threshold.region) put("region", round(m.pixel.region, 2)) }
                .apply { if (m.slotsVitro != m.slotsHa) put("slotDelta", m.slotsVitro - m.slotsHa) }
                .put("reason", entry(r.key)?.optString("reason")?.takeIf { it.isNotBlank() } ?: "not triaged yet (#794)")
        }
        entries.forEach { (k, v) -> out.put(k, v) }
        // org.json escapes "/" as "\/" (valid either way): plain slashes keep the keys readable and diffs small.
        file.writeText(out.toString(1).replace("\\/", "/") + "\n")
    }

    companion object {
        /** baseline.json's bounds for a card Vitro doesn't draw at all (no slot to measure). */
        const val UNBOUNDED = "unbounded"

        /** An entry's bounds: [UNBOUNDED] (and the 1e9 older files wrote for it) reads as infinite. */
        fun boundsOf(entry: JSONObject): Double {
            if (entry.optString("bounds") == UNBOUNDED) return Double.POSITIVE_INFINITY
            val v = entry.optDouble("bounds", 0.0)
            return if (v >= 1e9) Double.POSITIVE_INFINITY else v
        }

        const val PIXEL_SLACK = 0.02
        const val BOUNDS_SLACK = 1.0
        const val FONT_SLACK = 0.5
        const val REGION_SLACK = 0.02
        private fun round(v: Double, places: Int): Double { val f = Math.pow(10.0, places.toDouble()); return Math.round(v * f) / f }
    }
}

/**
 * open.json: cases Vitro draws wrong today whose fix an open PR owns, per frame/case, each with its `owner` (the PR,
 * `#928`) and `reason`. Unlike baseline.json, which is for drift HA's and Vitro's text engines can't avoid (sub-pixel
 * metrics), these are real bugs: the run reports them as open (not failing, so the case can land before its fix) and
 * fails once one matches, so the fix drops its entry and the case guards it from then on. A case is never in both files.
 */
class ParityOpen(private val file: File) {
    private val json: JSONObject = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()

    /** The entry for [key] (`frame/case`), or null. An entry without an owner PR is an error, not an open bug. */
    fun entry(key: String): JSONObject? = json.optJSONObject(key)?.also {
        require(Regex("^#\\d+$").matches(it.optString("owner"))) { "open.json $key: `owner` must be the PR that fixes it (#123)" }
        require(it.optString("reason").isNotBlank()) { "open.json $key: `reason` says what Vitro draws wrong" }
    }

    /** What the run says about an open entry: the bug and who owns its fix. */
    fun describe(key: String): String? = entry(key)?.let { "open (${it.getString("owner")}): ${it.getString("reason")}" }

    /**
     * How an open entry judges a measured case: null when [key] isn't listed; otherwise OPEN with the bug while the case
     * is still [over] its threshold, and ERROR once it matches (drop the entry) or when baseline.json lists it too
     * ([inBaseline]).
     */
    fun judge(key: String, over: List<String>, inBaseline: Boolean): Pair<ParityStatus, List<String>>? {
        val bug = describe(key) ?: return null
        return when {
            inBaseline -> ParityStatus.ERROR to listOf("listed in both open.json and baseline.json: an open bug isn't drift")
            over.isEmpty() -> ParityStatus.ERROR to listOf("matches HA now: drop it from open.json ($bug)")
            else -> ParityStatus.OPEN to over + bug
        }
    }

    /**
     * An open entry on a case whose view HA's frontend draws (route `frontend`): Vitro draws nothing there, so the bug it
     * names can't be checked, and the run says so as an ERROR instead of an ordinary match. Null when [key] isn't listed.
     */
    fun judgeFrontend(key: String): Pair<ParityStatus, List<String>>? {
        val bug = describe(key) ?: return null
        return ParityStatus.ERROR to listOf("HA's frontend draws its view, so Vitro draws nothing to check: drop it from open.json ($bug)")
    }

    val keys: Set<String> get() = json.keys().asSequence().filter { !it.startsWith("_") }.toSet()
}

/** index.html, report.md and report.json under [dir], with one side-by-side image per case in img/. */
object ParityReportWriter {
    fun write(dir: File, results: List<ParityResult>, haVersion: String) {
        dir.mkdirs()
        val sorted = results.sortedWith(compareBy<ParityResult> { it.status.ordinal }.thenByDescending { it.measure?.pixel?.tolerant ?: 2.0 }.thenBy { it.key })
        File(dir, "report.json").writeText(JSONObject().put("haVersion", haVersion).put("results", JSONArray(sorted.map(::json))).toString(1) + "\n")
        File(dir, "report.md").writeText(markdown(sorted, haVersion))
        File(dir, "index.html").writeText(html(sorted, haVersion))
    }

    private fun json(r: ParityResult) = JSONObject().apply {
        put("corpus", r.corpus); put("frame", r.frame.id); put("case", r.case.id); put("title", r.case.title); put("status", r.status.name.lowercase())
        r.measure?.let { m ->
            put("pixelTolerant", m.pixel.tolerant); put("pixelStrict", m.pixel.strict); put("region", m.pixel.region); put("meanLuma", m.pixel.meanLuma)
            put("bounds", if (m.bounds.isInfinite()) JSONObject.NULL else m.bounds); put("slotsHa", JSONArray(m.haSlots.map(::rectJson))); put("slotsVitro", JSONArray(m.vitroSlots.map(::rectJson)))
            put("fontDelta", m.fontDelta); put("positionDelta", m.positionDelta); put("missingTexts", JSONArray(m.missingTexts))
            put("texts", JSONArray(m.texts.map { t -> JSONObject().put("text", t.text).put("sizeHa", t.sizeHa).put("sizeVitro", t.sizeVitro ?: JSONObject.NULL).put("dx", t.dx ?: JSONObject.NULL).put("dy", t.dy ?: JSONObject.NULL).apply { if (t.described) put("described", true) } }))
        }
        put("reasons", JSONArray(r.reasons)); put("notes", JSONArray(r.notes)); r.image?.let { put("image", it) }
    }

    private fun rectJson(r: CssRect) = JSONArray(listOf(r.x, r.y, r.w, r.h).map { Math.round(it * 10) / 10.0 })

    private fun counts(results: List<ParityResult>) = ParityStatus.values().mapNotNull { s -> results.count { it.status == s }.takeIf { it > 0 }?.let { "$it ${s.label}" } }.joinToString(", ")

    private fun markdown(results: List<ParityResult>, haVersion: String) = buildString {
        appendLine("# Vitro parity report")
        appendLine()
        appendLine("Home Assistant $haVersion. ${counts(results)}. Pixels: share of the card's crop that differs (1 sample per CSS px, ±1 px shift tolerated). Region: the largest color distance between HA's and Vitro's mean over a 16 px square (±2 px shift), magenta in the diff. Bounds: largest card edge offset. Font: largest text size difference. Texts: HA text runs found in Vitro's views.")
        appendLine()
        appendLine("| Status | Frame | Case | Pixels | Region Δ | Bounds Δ | Font Δ | Texts | Why |")
        appendLine("|---|---|---|---:|---:|---:|---:|---:|---|")
        for (r in results) {
            val m = r.measure
            val texts = m?.let { "${it.texts.count { t -> t.found }}/${it.texts.size}" } ?: ""
            val why = (r.reasons + r.notes).joinToString("; ").replace("|", "\\|")
            appendLine("| ${r.status.label} | ${r.frame.id} | ${r.case.id} | ${m?.let { pct(it.pixel.tolerant) } ?: ""} | ${m?.let { num2(it.pixel.region) } ?: ""} | ${m?.let { num(it.bounds) } ?: ""} | ${m?.let { num(it.fontDelta) } ?: ""} | $texts | $why |")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(results: List<ParityResult>, haVersion: String) = buildString {
        append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Vitro parity</title><style>
:root{--bg:#fafafa;--fg:#18181b;--mu:#71717a;--card:#fff;--line:#e4e4e7}
@media (prefers-color-scheme:dark){:root{--bg:#0b0b0f;--fg:#f4f4f5;--mu:#a1a1aa;--card:#18181b;--line:#27272a}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif}
h1{font-size:20px;margin:0 0 4px}p{color:var(--mu);margin:0 0 16px;max-width:900px}
.r{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;margin:0 0 12px}
.h{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:baseline}.h b{font-size:15px}.m{color:var(--mu)}
.s{font-weight:600;padding:1px 8px;border-radius:99px;font-size:12px}
.regression,.error,.no-reference{background:#fee2e2;color:#991b1b}.known-drift{background:#fef3c7;color:#92400e}.open-bug{background:#fce7f3;color:#9d174d}.match{background:#dcfce7;color:#166534}.collected{background:#e0e7ff;color:#3730a3}
img{max-width:100%;height:auto;margin-top:8px;border-radius:6px;image-rendering:auto}
ul{margin:6px 0 0;padding-left:18px;color:var(--mu)}
</style></head><body>""")
        append("<h1>Vitro parity</h1><p>Home Assistant ${esc(haVersion)}. ${esc(counts(results))}. Each row shows Home Assistant's render, Vitro's, and the difference (red: differs even with a 1 px shift; orange: differs only by a shift; blue: masked).</p>")
        for (r in results) {
            val m = r.measure
            append("<div class=\"r\"><div class=\"h\"><span class=\"s ${r.status.label.replace(' ', '-')}\">${esc(r.status.label)}</span><b>${esc(r.frame.id)} / ${esc(r.case.id)}</b><span class=\"m\">${esc(r.case.title)}</span>")
            if (m != null) append("<span>pixels ${pct(m.pixel.tolerant)} (strict ${pct(m.pixel.strict)})</span><span>region Δ${num2(m.pixel.region)}</span><span>bounds Δ${num(m.bounds)} px</span><span>font Δ${num(m.fontDelta)} px</span><span>texts ${m.texts.count { it.found }}/${m.texts.size}</span>")
            append("</div>")
            val lines = r.reasons + r.notes + (m?.missingTexts?.take(8)?.map { "not in Vitro's views: “$it”" } ?: emptyList()) +
                (m?.texts?.filter { it.sizeVitro != null && abs(it.sizeVitro - it.sizeHa) > 0.5 }?.take(8)?.map { "“${it.text.take(40)}”: HA ${num(it.sizeHa)} px, Vitro ${num(it.sizeVitro!!)} px" } ?: emptyList())
            if (lines.isNotEmpty()) append("<ul>").append(lines.joinToString("") { "<li>${esc(it)}</li>" }).append("</ul>")
            r.image?.let { append("<img loading=\"lazy\" src=\"${esc(it)}\" alt=\"${esc(r.key)}\">") }
            append("</div>")
        }
        append("</body></html>\n")
    }
}
