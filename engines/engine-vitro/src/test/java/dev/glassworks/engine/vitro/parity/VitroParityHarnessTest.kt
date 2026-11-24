/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
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
        if (unknown.isNotEmpty()) fail("Vitro parity: " + unknown.joinToString("; "))
        val frames = allFrames.filter { ParityFiles.onlyFrames == null || it.id in ParityFiles.onlyFrames!! }
        val outDir = ParityFiles.out
        val tz = TimeZone.getDefault()
        val locale = Locale.getDefault()
        val results = ArrayList<ParityResult>()

        var haVersion = "?"
        try {
            Locale.setDefault(Locale.US)
            for ((label, root) in roots) {
                val shared = ParityShared.load(root)
                shared?.let { haVersion = it.haVersion }
                shared?.haConfig?.optString("time_zone")?.takeIf { it.isNotBlank() }?.let { TimeZone.setDefault(TimeZone.getTimeZone(it)) }
                val cases = ParityCase.loadAll(root).filter { ParityFiles.onlyCases == null || it.id in ParityFiles.onlyCases!! }
                val baseline = ParityBaseline(File(root, "baseline.json"))
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
                        val r = runCase(label, root, frame, case, renderer, baseline, outDir, wanted)
                        mine += r
                    }
                }
                if (ParityFiles.collect) writeWanted(label, wanted)
                if (ParityFiles.updateBaseline && !ParityFiles.collect) baseline.write(mine)
                results += mine
            }
        } finally {
            TimeZone.setDefault(tz)
            Locale.setDefault(locale)
        }
        if (!ParityFiles.collect) ParityReportWriter.write(outDir, results, haVersion)
        println("Vitro parity: ${results.count { it.status == ParityStatus.MATCH }} match, " +
            "${results.count { it.status == ParityStatus.DRIFT }} known drift, ${fatalResults(results, false).size} failing; report: ${File(outDir, "index.html")}")
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
        outDir: File,
        wanted: MutableMap<String, LinkedHashSet<String>>,
    ): ParityResult {
        val ref = ParityReference.find(root, frame, case)
        return try {
            val render = renderer.render(case, ref)
            wanted.getOrPut(case.id) { LinkedHashSet() }.addAll(render.templatesAsked)
            val notes = ArrayList<String>()
            if (render.templatesMissing.isNotEmpty() && ref != null) notes += "${render.templatesMissing.size} template(s) Vitro asked for have no rendered result in the reference (recapture)"
            if (render.assetsMissing.isNotEmpty()) notes += "images not in assets/: ${render.assetsMissing.distinct().joinToString()}"
            if (ref != null && !ref.stable) notes += "HA's render was still moving when captured"
            if (ParityFiles.collect) return ParityResult(label, frame, case, ParityStatus.COLLECTED, null, emptyList(), notes, null)
            if (ref == null) return ParityResult(label, frame, case, ParityStatus.NO_REFERENCE, null, listOf("no refs/${frame.id}/${case.id}.png: run tools/vitro-parity/run.sh"), notes, null)
            val (m, side) = ParityCompare.measure(case, frame, ref, render)
            if (m.slotsHa != m.slotsVitro) notes += "HA shows ${m.slotsHa} card slot(s), Vitro ${m.slotsVitro}"
            val img = "img/$label/${frame.id}/${case.id}.png"
            File(outDir, img).apply { parentFile.mkdirs() }.outputStream().use { side.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val over = m.over(case.threshold)
            val known = baseline.entry("${frame.id}/${case.id}")
            when {
                over.isEmpty() -> {
                    if (known != null) notes += "now within threshold: drop it from baseline.json"
                    ParityResult(label, frame, case, ParityStatus.MATCH, m, emptyList(), notes, img)
                }
                else -> {
                    val worse = baseline.worse("${frame.id}/${case.id}", m)
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
