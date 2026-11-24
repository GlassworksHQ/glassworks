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
    REGRESSION("regression"), ERROR("error"), NO_REFERENCE("no reference"), DRIFT("known drift"), MATCH("match"), COLLECTED("collected"),
}

/** One HA text run and where Vitro has it. Positions are CSS px relative to each side's own card slot. */
data class TextCheck(val text: String, val sizeHa: Double, val sizeVitro: Double?, val dx: Double?, val dy: Double?) {
    val found get() = sizeVitro != null
}

/** The structural and pixel numbers for one case on one frame. */
data class ParityMeasure(
    val pixel: PixelDiff,
    /** Largest edge difference of the card slot(s), CSS px. */
    val bounds: Double,
    val slotsHa: Int,
    val slotsVitro: Int,
    val texts: List<TextCheck>,
) {
    val fontDelta: Double get() = texts.mapNotNull { t -> t.sizeVitro?.let { abs(it - t.sizeHa) } }.maxOrNull() ?: 0.0
    val positionDelta: Double get() = texts.mapNotNull { t -> if (t.dx == null || t.dy == null) null else max(abs(t.dx), abs(t.dy)) }.maxOrNull() ?: 0.0
    val missingTexts: List<String> get() = texts.filter { !it.found }.map { it.text }

    fun over(t: Threshold): List<String> = buildList {
        if (pixel.tolerant > t.pixel) add("pixels ${pct(pixel.tolerant)} > ${pct(t.pixel)}")
        if (bounds > t.bounds) add("bounds Δ${num(bounds)} px > ${num(t.bounds)}")
        if (fontDelta > t.font) add("font Δ${num(fontDelta)} px > ${num(t.font)}")
        if (missingTexts.size > t.missingText) add("${missingTexts.size} HA text(s) not found in Vitro's views")
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

internal fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
internal fun num(v: Double) = if (v.isInfinite()) "∞" else String.format(Locale.ROOT, "%.1f", v)

object ParityCompare {
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
            val x = anchor.x + m.x - clip.x
            val y = anchor.y + m.y - clip.y
            Rect(x.toInt(), y.toInt(), (x + m.w).roundToInt(), (y + m.h).roundToInt())
        }
        val pixel = ParityDiff.compare(refBmp, vitroCrop, sw, sh, masks)

        val n = minOf(haSlots.size, render.slots.size)
        var bounds = if (n == 0 || haSlots.size != render.slots.size && case.card != null) Double.POSITIVE_INFINITY else 0.0
        for (i in 0 until n) {
            val a = haSlots[i]
            val b = render.slots[i]
            bounds = max(bounds, listOf(a.x - b.x, a.y - b.y, a.right - b.right, a.bottom - b.bottom).maxOf { abs(it) })
        }

        val texts = ArrayList<TextCheck>()
        val vitroTexts = (0 until render.slots.size).associateWith { render.texts(it) }
        val used = HashMap<VitroText, Int>()
        for (t in ref.texts) {
            val needle = t.text.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
            val candidates = vitroTexts[t.slot].orEmpty()
            var hit: Pair<VitroText, Int>? = null
            for (vt in candidates) {
                val idx = vt.normalized.lowercase(Locale.ROOT).indexOf(needle, used[vt] ?: 0)
                if (idx >= 0) { hit = vt to idx; break }
            }
            if (hit == null) {
                texts += TextCheck(t.text, t.size, null, null, null)
                continue
            }
            val (vt, idx) = hit
            used[vt] = idx + needle.length
            val (x, center, size) = render.textMetrics(vt.view, vt.offsets[idx])
            val slotV = render.slots[t.slot]
            texts += TextCheck(t.text, t.size, size, (x - slotV.x) - t.box.x, (center - slotV.y) - (t.box.y + t.box.h / 2))
        }
        val side = ParityDiff.sideBySide(refBmp, vitroCrop, pixel.image, "${pct(pixel.tolerant)} differ", d)
        return ParityMeasure(pixel, bounds, haSlots.size, render.slots.size, texts) to side
    }
}

/** baseline.json: the drift a corpus is known to have, per frame/case, with why. A case may not get worse than this. */
class ParityBaseline(private val file: File) {
    private val json: JSONObject = if (file.isFile) JSONObject(file.readText()) else JSONObject()

    fun entry(key: String): JSONObject? = json.optJSONObject(key)

    /** Why [m] is worse than the known drift for [key], or empty when it isn't. */
    fun worse(key: String, m: ParityMeasure): List<String> {
        val b = entry(key) ?: return listOf("not in baseline.json")
        return buildList {
            if (m.pixel.tolerant > b.optDouble("pixel", 0.0) + PIXEL_SLACK) add("pixels ${pct(m.pixel.tolerant)} > baseline ${pct(b.optDouble("pixel"))}")
            if (m.bounds > b.optDouble("bounds", 0.0) + BOUNDS_SLACK) add("bounds Δ${num(m.bounds)} > baseline ${num(b.optDouble("bounds"))}")
            if (m.fontDelta > b.optDouble("font", 0.0) + FONT_SLACK) add("font Δ${num(m.fontDelta)} > baseline ${num(b.optDouble("font"))}")
            if (m.missingTexts.size > b.optInt("missingText", 0)) add("${m.missingTexts.size} texts missing > baseline ${b.optInt("missingText")}")
        }
    }

    fun write(results: List<ParityResult>) {
        val out = JSONObject()
        out.put("_comment", "Known drift per frame/case: the harness fails when a case gets worse than this (plus slack: " +
            "${pct(PIXEL_SLACK)} pixels, $BOUNDS_SLACK px bounds, $FONT_SLACK px font). Regenerate with " +
            "-PvitroParityUpdateBaseline=true; keep or write each \"reason\". #794 empties this file.")
        for (r in results.sortedBy { it.key }) {
            val m = r.measure ?: continue
            if (m.over(r.case.threshold).isEmpty()) continue
            val bounds = if (m.bounds.isInfinite()) 1e9 else m.bounds
            out.put(r.key, JSONObject()
                .put("pixel", round(m.pixel.tolerant, 4)).put("bounds", round(bounds, 1))
                .put("font", round(m.fontDelta, 1)).put("missingText", m.missingTexts.size)
                .put("reason", entry(r.key)?.optString("reason")?.takeIf { it.isNotBlank() } ?: "not triaged yet (#794)"))
        }
        file.writeText(out.toString(1) + "\n")
    }

    companion object {
        const val PIXEL_SLACK = 0.02
        const val BOUNDS_SLACK = 1.0
        const val FONT_SLACK = 0.5
        private fun round(v: Double, places: Int): Double { val f = Math.pow(10.0, places.toDouble()); return Math.round(v * f) / f }
    }
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
            put("pixelTolerant", m.pixel.tolerant); put("pixelStrict", m.pixel.strict); put("meanLuma", m.pixel.meanLuma)
            put("bounds", if (m.bounds.isInfinite()) JSONObject.NULL else m.bounds); put("slotsHa", m.slotsHa); put("slotsVitro", m.slotsVitro)
            put("fontDelta", m.fontDelta); put("positionDelta", m.positionDelta); put("missingTexts", JSONArray(m.missingTexts))
            put("texts", JSONArray(m.texts.map { t -> JSONObject().put("text", t.text).put("sizeHa", t.sizeHa).put("sizeVitro", t.sizeVitro ?: JSONObject.NULL).put("dx", t.dx ?: JSONObject.NULL).put("dy", t.dy ?: JSONObject.NULL) }))
        }
        put("reasons", JSONArray(r.reasons)); put("notes", JSONArray(r.notes)); r.image?.let { put("image", it) }
    }

    private fun counts(results: List<ParityResult>) = ParityStatus.values().mapNotNull { s -> results.count { it.status == s }.takeIf { it > 0 }?.let { "$it ${s.label}" } }.joinToString(", ")

    private fun markdown(results: List<ParityResult>, haVersion: String) = buildString {
        appendLine("# Vitro parity report")
        appendLine()
        appendLine("Home Assistant $haVersion. ${counts(results)}. Pixels: share of the card's crop that differs (1 sample per CSS px, ±1 px shift tolerated). Bounds: largest card edge offset. Font: largest text size difference. Texts: HA text runs found in Vitro's views.")
        appendLine()
        appendLine("| Status | Frame | Case | Pixels | Bounds Δ | Font Δ | Texts | Why |")
        appendLine("|---|---|---|---:|---:|---:|---:|---|")
        for (r in results) {
            val m = r.measure
            val texts = m?.let { "${it.texts.count { t -> t.found }}/${it.texts.size}" } ?: ""
            val why = (r.reasons + r.notes).joinToString("; ").replace("|", "\\|")
            appendLine("| ${r.status.label} | ${r.frame.id} | ${r.case.id} | ${m?.let { pct(it.pixel.tolerant) } ?: ""} | ${m?.let { num(it.bounds) } ?: ""} | ${m?.let { num(it.fontDelta) } ?: ""} | $texts | $why |")
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
.regression,.error,.no-reference{background:#fee2e2;color:#991b1b}.known-drift{background:#fef3c7;color:#92400e}.match{background:#dcfce7;color:#166534}.collected{background:#e0e7ff;color:#3730a3}
img{max-width:100%;height:auto;margin-top:8px;border-radius:6px;image-rendering:auto}
ul{margin:6px 0 0;padding-left:18px;color:var(--mu)}
</style></head><body>""")
        append("<h1>Vitro parity</h1><p>Home Assistant ${esc(haVersion)}. ${esc(counts(results))}. Each row shows Home Assistant's render, Vitro's, and the difference (red: differs even with a 1 px shift; orange: differs only by a shift; blue: masked).</p>")
        for (r in results) {
            val m = r.measure
            append("<div class=\"r\"><div class=\"h\"><span class=\"s ${r.status.label.replace(' ', '-')}\">${esc(r.status.label)}</span><b>${esc(r.frame.id)} / ${esc(r.case.id)}</b><span class=\"m\">${esc(r.case.title)}</span>")
            if (m != null) append("<span>pixels ${pct(m.pixel.tolerant)} (strict ${pct(m.pixel.strict)})</span><span>bounds Δ${num(m.bounds)} px</span><span>font Δ${num(m.fontDelta)} px</span><span>texts ${m.texts.count { it.found }}/${m.texts.size}</span>")
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
