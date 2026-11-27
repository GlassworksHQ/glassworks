/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import dev.glassworks.testing.RepoFiles
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The parity harness (#793): every corpus case drawn by Vitro at each panel's size and density, compared with the
 * render Home Assistant's own frontend made of it (refs/, from tools/vitro-parity/capture.mjs). Writes
 * build/reports/vitro-parity/index.html (and report.md, report.json) and fails when a case drifts past its
 * threshold and isn't listed in baseline.json, or drifts further than baseline.json says it does.
 *
 * Needs no device and no Home Assistant: CI runs it on the committed references. Regenerating them needs HA:
 * tools/vitro-parity/run.sh. Options (Gradle -P): vitroParityCases, vitroParityFrames, vitroParityOut,
 * vitroParityLocal (a local corpus of real dashboards), vitroParityCollect, vitroParityUpdateBaseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VitroParityHarnessTest {
    @Test
    fun corpusMatchesHomeAssistant() {
        val fontFile = File(RepoFiles.root, "core/src/main/res/font/inter.ttf")
        Inter.load = { fontFile.readBytes() }
        val roots = listOfNotNull("committed" to ParityFiles.committed, ParityFiles.local?.let { "local" to it })
        val allFrames = ParityFrame.load()
        val unknown = ParityFiles.unknownSelections(
            ParityFiles.onlyCases, roots.flatMap { (_, root) -> ParityCase.loadAll(root).map { it.id } }.toSet(),
            ParityFiles.onlyFrames, allFrames.map { it.id }.toSet(),
        )
        val badCases = roots.flatMap { (_, root) -> ParityCase.loadAll(root) }.mapNotNull { c ->
            c.unknownFrames(allFrames.map { it.id }.toSet()).takeIf { it.isNotEmpty() }?.let { "case ${c.id} lists unknown frame(s) $it" }
        }
        // An open.json entry for a case or frame that no longer exists, or a frame its case doesn't run on (checked
        // against every frame, whatever this run selects), would never be checked again.
        val staleOpen = roots.flatMap { (_, root) ->
            val cases = ParityCase.loadAll(root).associateBy { it.id }
            ParityOpen(File(root, "open.json")).keys.filter { k ->
                val frame = allFrames.firstOrNull { it.id == k.substringBefore('/') }
                val case = cases[k.substringAfter('/')]
                frame == null || case == null || !case.runsOn(frame)
            }.map { "open.json lists $it, which isn't a frame/case pair the corpus runs" }
        }
        if (unknown.isNotEmpty() || badCases.isNotEmpty() || staleOpen.isNotEmpty()) fail("Vitro parity: " + (unknown + badCases + staleOpen).joinToString("; "))
        val frames = allFrames.filter { ParityFiles.onlyFrames == null || it.id in ParityFiles.onlyFrames!! }
        val outDir = ParityFiles.out
        val tz = TimeZone.getDefault()
        val locale = Locale.getDefault()
        val results = ArrayList<ParityResult>()

        var haVersion = "?"
        // Baselines to write, staged until every selected corpus has run: one written before another corpus fails
        // (an error, a missing reference) would accept its drift without the full run passing.
        val staged = ArrayList<Pair<ParityBaseline, List<ParityResult>>>()
        try {
            Locale.setDefault(Locale.US)
            for ((label, root) in roots) {
                val shared = ParityShared.load(root)
                shared?.let { haVersion = it.haVersion }
                // Each corpus starts from the harness's own zone: one without a zone doesn't inherit the last one's.
                TimeZone.setDefault(tz)
                dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
                shared?.haConfig?.optString("time_zone")?.takeIf { it.isNotBlank() }?.let { TimeZone.setDefault(TimeZone.getTimeZone(it)) }
                dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
                val cases = ParityCase.loadAll(root).filter { ParityFiles.onlyCases == null || it.id in ParityFiles.onlyCases!! }
                val baseline = ParityBaseline(File(root, "baseline.json"))
                val open = ParityOpen(File(root, "open.json"))
                val wanted = LinkedHashMap<String, LinkedHashSet<String>>()
                val mine = ArrayList<ParityResult>()
                for (frame in frames) {
                    val runs = cases.filter { it.runsOn(frame) }
                    if (runs.isEmpty()) continue
                    RuntimeEnvironment.setQualifiers(frame.qualifiers)
                    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                    assertEquals("${frame.id}: density from ${frame.qualifiers}", frame.density, activity.resources.displayMetrics.density, 0.01f)
                    val renderer = ParityRenderer(activity, frame, root, shared)
                    for (case in runs) {
                        val r = runCase(label, root, frame, case, renderer, baseline, open, outDir, wanted)
                        mine += r
                    }
                    // The last case's view leaves the window: a view still on screen would answer the next frame's
                    // font notices (WebFonts) with its own dashboard and server.
                    activity.setContentView(View(activity))
                }
                if (ParityFiles.collect) writeWanted(label, wanted)
                if (ParityFiles.updateBaseline && !ParityFiles.collect) staged += baseline to mine
                results += mine
            }
        } finally {
            TimeZone.setDefault(tz)
            dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
            Locale.setDefault(locale)
        }
        // Cases and frames that pass the ID checks can still pair up to nothing (a case only for s1, run on tablet).
        if (results.isEmpty()) fail("Vitro parity: the selected cases and frames give no runs (a case runs only on the frames it lists)")
        if (staged.isNotEmpty() && fatalResults(results, acceptDrift = true).isEmpty()) staged.forEach { (b, r) -> b.write(r) }
        if (!ParityFiles.collect) ParityReportWriter.write(outDir, results, haVersion)
        println("Vitro parity: ${results.count { it.status == ParityStatus.MATCH }} match, " +
            "${results.count { it.status == ParityStatus.DRIFT }} known drift, ${results.count { it.status == ParityStatus.OPEN }} open (open.json), ${fatalResults(results, false).size} failing; report: ${File(outDir, "index.html")}")
        // A baseline update (and a collect run) accepts visual drift, never a case it couldn't measure.
        val fatal = fatalResults(results, acceptDrift = ParityFiles.collect || ParityFiles.updateBaseline)
            .map { r -> "${r.key} [${r.corpus}]: ${r.status.label}: ${(r.reasons + r.notes).joinToString("; ")}" }
        if (fatal.isNotEmpty()) {
            fail("Vitro parity: ${fatal.size} case(s) failed (report: ${File(outDir, "index.html")}):\n" + fatal.joinToString("\n"))
        }
    }

    private fun runCase(
        label: String,
        root: File,
        frame: ParityFrame,
        case: ParityCase,
        renderer: ParityRenderer,
        baseline: ParityBaseline,
        open: ParityOpen,
        outDir: File,
        wanted: MutableMap<String, LinkedHashSet<String>>,
    ): ParityResult {
        return try {
            // The strict compat rule decides first: a case whose view opens in HA's frontend has nothing of Vitro's to
            // compare, and a case that routes elsewhere than it says is a change in what Vitro claims to draw.
            ParityRoute.of(case, root)?.let { (route, why) ->
                if (route != case.route) {
                    return ParityResult(label, frame, case, ParityStatus.ERROR, null,
                        listOf("Vitro routes it `$route`" + (why?.let { " ($it)" } ?: "") + ", the case says `${case.route}`"), emptyList(), null)
                }
                if (route == ParityRoute.FRONTEND) {
                    // An open entry here names a bug nothing checks: never reported as an ordinary match.
                    open.judgeFrontend("${frame.id}/${case.id}")?.let { (status, reasons) ->
                        return ParityResult(label, frame, case, status, null, reasons, listOf("HA's frontend draws its view: $why"), null)
                    }
                    return ParityResult(label, frame, case, ParityStatus.MATCH, null, emptyList(), listOf("HA's frontend draws its view: $why"), null)
                }
            }
            // Inside the try: unreadable reference metadata is this case's error, not the end of the run.
            val ref = ParityReference.find(root, frame, case)
            val render = renderer.render(case, ref)
            wanted.getOrPut(case.id) { LinkedHashSet() }.addAll(render.templatesAsked)
            val notes = ArrayList<String>()
            if (ref != null && !ref.stable) notes += "HA's render was still moving when captured"
            // Collect mode is how templates are found without a reference: they're only checked after it.
            if (ParityFiles.collect) return ParityResult(label, frame, case, ParityStatus.COLLECTED, null, emptyList(), notes, null)
            if (ref == null) return ParityResult(label, frame, case, ParityStatus.NO_REFERENCE, null, listOf("no refs/${frame.id}/${case.id}.png: run tools/vitro-parity/run.sh"), notes, null)
            // A template Vitro asked for with no result in the reference is a gap in the inputs, like a missing image:
            // grading would compare a card that never got its text.
            if (render.templatesMissing.isNotEmpty()) {
                return ParityResult(label, frame, case, ParityStatus.ERROR, null,
                    listOf("capture input missing: ${render.templatesMissing.size} template(s) Vitro asked for have no rendered result in the reference (rerun tools/vitro-parity/run.sh --refresh)"), notes, null)
            }
            // An image Vitro asks for that the capture didn't save is a gap in the inputs, not drift: comparing would
            // charge Vitro with a picture it was never given. One HA couldn't load either is no gap.
            val (gaps, alsoMissingInHa) = ParityInputs.missingImages(render.assetsMissing, ref)
            if (alsoMissingInHa.isNotEmpty()) notes += "images HA couldn't load either: ${alsoMissingInHa.joinToString()}"
            if (gaps.isNotEmpty()) {
                return ParityResult(label, frame, case, ParityStatus.ERROR, null,
                    listOf("capture input missing: Vitro asked for ${gaps.joinToString()}, which the capture didn't save (rerun tools/vitro-parity/run.sh --refresh)"), notes, null)
            }
            // A history or statistics call HA's page never made: Vitro asked for other data than HA drew.
            if (render.wsUnmatched.isNotEmpty()) {
                return ParityResult(label, frame, case, ParityStatus.ERROR, null,
                    listOf("Vitro's graph call differs from every call HA made (or the capture predates it): ${render.wsUnmatched.distinct().joinToString(" | ").take(600)}"), notes, null)
            }
            val (m, side) = ParityCompare.measure(case, frame, ref, render)
            if (m.slotsHa != m.slotsVitro) notes += "HA shows ${m.slotsHa} card slot(s), Vitro ${m.slotsVitro}"
            // A view with no Sections grid (masonry, panel) has no slots to compare: only its pixels were checked.
            if (m.slotsHa == 0 && m.slotsVitro == 0) notes += "pixels only: no card slots to compare (not a Sections view), so bounds and texts weren't checked"
            val img = "img/$label/${frame.id}/${case.id}.png"
            File(outDir, img).apply { parentFile.mkdirs() }.outputStream().use { side.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val over = m.over(case.threshold)
            val known = baseline.entry("${frame.id}/${case.id}")
            open.judge("${frame.id}/${case.id}", over, known != null)?.let { (status, why) ->
                return ParityResult(label, frame, case, status, m, why, notes, img)
            }
            when {
                over.isEmpty() -> {
                    if (known != null) notes += "now within threshold: drop it from baseline.json"
                    ParityResult(label, frame, case, ParityStatus.MATCH, m, emptyList(), notes, img)
                }
                else -> {
                    val worse = baseline.worse("${frame.id}/${case.id}", m, case.threshold)
                    val reason = known?.optString("reason")?.takeIf { it.isNotBlank() }?.let { listOf("known: $it") }.orEmpty()
                    if (worse.isEmpty()) ParityResult(label, frame, case, ParityStatus.DRIFT, m, over + reason, notes, img)
                    else ParityResult(label, frame, case, ParityStatus.REGRESSION, m, over + worse, notes, img)
                }
            }
        } catch (t: Throwable) {
            ParityResult(label, frame, case, ParityStatus.ERROR, null, listOf("${t.javaClass.simpleName}: ${t.message}"), emptyList(), null)
        }
    }

    /** Collect mode: the templates Vitro asked for, per case, for capture.mjs to render in HA. */
    private fun writeWanted(label: String, wanted: Map<String, Set<String>>) {
        val f = File(RepoFiles.root, "engines/engine-vitro/build/vitro-parity/" + if (label == "local") "templates-wanted-local.json" else "templates-wanted.json")
        f.parentFile.mkdirs()
        val o = JSONObject()
        wanted.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) }
        f.writeText(o.toString(1) + "\n")
        println("Vitro parity: templates Vitro asks for -> $f")
    }
}
