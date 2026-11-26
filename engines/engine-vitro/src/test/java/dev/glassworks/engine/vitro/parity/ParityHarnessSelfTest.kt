/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The harness's own checks: the diff measures what it claims, and the committed fixtures hold nothing private. */
/**
 * The end of a MAC as device names and hostnames carry it ("AirDot 4c83f8", "AirDot_a1b2c3d4e5f6"): a 6- or
 * 12-hex-digit run mixing digits and letters, set off by anything but a letter or digit (so `_` and `-` too), and
 * not a CSS colour. tools/vitro-parity/lib/scrub.mjs's MAC_FRAGMENT is the same pattern.
 */
internal val MAC_FRAGMENT = Regex("""(?<![#0-9A-Za-z])(?=[0-9a-fA-F]*\d)(?=[0-9a-fA-F]*[a-fA-F])(?:[0-9a-fA-F]{12}|[0-9a-fA-F]{6})(?![0-9A-Za-z])""")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParityHarnessSelfTest {
    private fun box(w: Int, h: Int, density: Int, dx: Int = 0, color: Int = Color.WHITE): Bitmap {
        val b = Bitmap.createBitmap(w * density, h * density, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 20, 24))
        c.drawRect(((10 + dx) * density).toFloat(), (10 * density).toFloat(), ((30 + dx) * density).toFloat(), (30 * density).toFloat(), Paint().apply { this.color = color })
        return b
    }

    @Test
    fun identicalRendersDontDiffer() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2), 40, 40)
        assertEquals(0.0, d.strict, 0.0)
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun aOnePixelShiftIsToleratedButCountedAsStrict() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, dx = 1), 40, 40)
        assertTrue("strict sees the shift: ${d.strict}", d.strict > 0.0)
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun aMissingOrRecolouredShapeDiffers() {
        val gone = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.rgb(20, 20, 24)), 40, 40)
        // The 20x20 box is a quarter of the image; only its 1 px rim is forgiven as a shift.
        assertTrue("tolerant ${gone.tolerant}", gone.tolerant > 0.15)
        val red = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.RED), 40, 40)
        assertTrue("tolerant ${red.tolerant}", red.tolerant > 0.15)
    }

    @Test
    fun aRecolouredBackgroundDiffers() {
        // pixelmatch compares its squared YIQ distance with 0.1²: black against dark grey is a difference.
        val black = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val grey = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(64, 64, 64)) }
        val d = ParityDiff.compare(black, grey, 20, 20)
        assertEquals(1.0, d.strict, 0.0)
        assertEquals(1.0, d.tolerant, 0.0)
        // A step pixelmatch calls the same stays the same.
        val near = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(8, 8, 8)) }
        assertEquals(0.0, ParityDiff.compare(black, near, 20, 20).strict, 0.0)
    }

    @Test
    fun masksLeaveTheirRegionOut() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.RED), 40, 40, listOf(android.graphics.Rect(5, 5, 35, 35)))
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun aMaskedStripNeverExcusesAnUnmaskedRegion() {
        // 18 x 16: HA all grey; Vitro's first two columns red. The last two columns are masked. Vitro's square shifted
        // two px right would match HA's, but it reaches into the mask: it may not excuse the red.
        val w = 18
        val h = 16
        val grey = Color.rgb(128, 128, 128)
        val a = IntArray(w * h) { grey }
        val b = IntArray(w * h) { i -> if (i % w < 2) Color.RED else grey }
        val (unmasked, _) = ParityDiff.worstRegion(a, b, w, h)
        val (masked, _) = ParityDiff.worstRegion(a, b, w, h, listOf(android.graphics.Rect(16, 0, 18, 16)))
        assertTrue("the red still counts: $masked", masked > 0.0)
        assertEquals("unmasked, the shift excuses it", 0.0, unmasked, 0.0)
    }

    @Test
    fun cropOutsideTheRenderIsFilledNotStretched() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val c = ParityDiff.crop(b, 5, 5, 10, 10)
        assertEquals(Color.BLUE, c.getPixel(0, 0))
        assertEquals(Color.MAGENTA, c.getPixel(9, 9))
    }

    // ---- verdicts and the baseline ----

    private val frame = ParityFrame("s1", "S1", 960, 480, 1.5f, "w960dp-h480dp-land-hdpi")

    private fun case(id: String = "c") = ParityCase(id, id, JSONObject().put("type", "tile"), null, null, null, true, null, 1, Threshold(), emptyList())

    private val slot = CssRect(0.0, 0.0, 100.0, 50.0)

    private fun measure(pixel: Double = 0.0, bounds: Double = 0.0, ha: Int = 1, vitro: Int = 1) = ParityMeasure(
        PixelDiff(pixel, pixel, 0.0, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)), bounds,
        List(ha) { slot }, List(vitro) { slot }, emptyList(),
    )

    private fun result(status: ParityStatus, m: ParityMeasure?, c: ParityCase = case()) =
        ParityResult("committed", frame, c, status, m, emptyList(), emptyList(), null)

    private fun entry(pixel: Double, bounds: Any, reason: String? = null) = JSONObject().put("pixel", pixel).put("bounds", bounds)
        .put("font", 0).put("missingText", 0).apply { reason?.let { put("reason", it) } }

    @Test
    fun aDifferentNumberOfCardsIsAStructuralFailure() {
        assertTrue(measure(ha = 3, vitro = 3).over(Threshold()).isEmpty())
        val over = measure(ha = 3, vitro = 4).over(Threshold())
        assertTrue(over.toString(), over.any { "card slot" in it })
    }

    @Test
    fun anUnmeasuredCaseKeepsItsBaselineEntry() {
        val f = File.createTempFile("baseline", ".json")
        f.writeText(JSONObject().put("s1/c", entry(0.5, 3, "known")).toString())
        ParityBaseline(f).write(listOf(result(ParityStatus.ERROR, null), result(ParityStatus.MATCH, measure(), case("other"))))
        val kept = JSONObject(f.readText()).getJSONObject("s1/c")
        assertEquals("known", kept.getString("reason"))
        assertEquals(0.5, kept.getDouble("pixel"), 0.0)
    }

    @Test
    fun aMissingCardStaysKnownDriftAcrossABaselineRoundTrip() {
        val f = File.createTempFile("baseline", ".json")
        val gone = measure(pixel = 0.5, bounds = Double.POSITIVE_INFINITY)
        ParityBaseline(f).write(listOf(result(ParityStatus.REGRESSION, gone)))
        assertEquals(ParityBaseline.UNBOUNDED, JSONObject(f.readText()).getJSONObject("s1/c").getString("bounds"))
        assertTrue(ParityBaseline(f).worse("s1/c", gone).isEmpty())
        // Older files wrote 1e9 for the same thing.
        f.writeText(JSONObject().put("s1/c", entry(0.5, 1e9)).toString())
        assertTrue(ParityBaseline(f).worse("s1/c", gone).isEmpty())
        // A finite baseline still catches a card that disappears.
        f.writeText(JSONObject().put("s1/c", entry(0.5, 3)).toString())
        assertTrue(ParityBaseline(f).worse("s1/c", gone).isNotEmpty())
    }

    @Test
    fun anOpenBugIsReportedNotFailedAndFailsOnceItMatches() {
        val f = File.createTempFile("open", ".json")
        f.writeText(JSONObject().put("s1/c", JSONObject().put("owner", "#928").put("reason", "the brightness bar is a square")).toString())
        val open = ParityOpen(f)
        assertEquals(null, open.judge("s1/other", listOf("pixels 30%"), false))
        val (still, why) = open.judge("s1/c", listOf("pixels 30% > 2%"), false)!!
        assertEquals(ParityStatus.OPEN, still)
        assertTrue(why.toString(), why.any { "#928" in it && "square" in it })
        assertTrue(fatalResults(listOf(result(still, measure(pixel = 0.3))), acceptDrift = false).isEmpty())
        // Fixed: the entry must go, so the case guards the fix from then on.
        assertEquals(ParityStatus.ERROR, open.judge("s1/c", emptyList(), false)!!.first)
        // An open bug is never drift as well.
        assertEquals(ParityStatus.ERROR, open.judge("s1/c", listOf("pixels 30% > 2%"), true)!!.first)
        // On a case HA's frontend draws, nothing checks the bug: an error, never an ordinary match.
        assertEquals(null, open.judgeFrontend("s1/other"))
        val (frontend, frontendWhy) = open.judgeFrontend("s1/c")!!
        assertEquals(ParityStatus.ERROR, frontend)
        assertTrue(frontendWhy.toString(), frontendWhy.any { "frontend" in it && "#928" in it })
        assertEquals(listOf("c"), fatalResults(listOf(result(frontend, null)), acceptDrift = true).map { it.case.id })
    }

    @Test
    fun aBaselineUpdateNeverRecordsAnOpenBugAsDrift() {
        val f = File.createTempFile("baseline", ".json")
        f.writeText("{}")
        ParityBaseline(f).write(listOf(result(ParityStatus.OPEN, measure(pixel = 0.3))))
        assertFalse(JSONObject(f.readText()).has("s1/c"))
    }

    @Test
    fun anOpenEntryNamesItsOwnerPr() {
        val f = File.createTempFile("open", ".json")
        f.writeText(JSONObject().put("s1/c", JSONObject().put("owner", "someone").put("reason", "x")).toString())
        assertTrue(runCatching { ParityOpen(f).judge("s1/c", listOf("x"), false) }.isFailure)
    }

    @Test
    fun aBaselineUpdateStillFailsOnErrorsAndMissingReferences() {
        val results = listOf(
            result(ParityStatus.REGRESSION, measure(pixel = 0.5)),
            result(ParityStatus.ERROR, null, case("e")),
            result(ParityStatus.NO_REFERENCE, null, case("n")),
            result(ParityStatus.MATCH, measure(), case("m")),
        )
        assertEquals(listOf("c", "e", "n"), fatalResults(results, acceptDrift = false).map { it.case.id })
        assertEquals(listOf("e", "n"), fatalResults(results, acceptDrift = true).map { it.case.id })
    }

    @Test
    fun textCutByAnEllipsisIsTold() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        fun tv(width: Int) = android.widget.TextView(activity).apply {
            text = "Kitchen Blind Position"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
            measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            layout(0, 0, width, measuredHeight)
        }
        val narrow = tv(80)
        assertTrue("the end is cut", ParityViews.elided(narrow, 0, narrow.text.length))
        assertTrue("its first word isn't", !ParityViews.elided(narrow, 0, 3))
        val wide = tv(2000)
        assertTrue(!ParityViews.elided(wide, 0, wide.text.length))
    }

    @Test
    fun aTextDrawnOnACanvasCountsWhenItsViewDescribesIt() {
        // A dial's "Off" lives in its content description: present, with no size or position to compare.
        val described = TextCheck("Off", 36.0, null, null, null, described = true)
        val missing = TextCheck("17.5 °C", 16.0, null, null, null)
        val pixels = PixelDiff(0.0, 0.0, 0.0, android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        val m = ParityMeasure(pixels, 0.0, emptyList(), emptyList(), listOf(described, missing))
        assertEquals(listOf("17.5 °C"), m.missingTexts)
        assertEquals(0.0, m.fontDelta, 0.0)
    }

    @Test
    fun aTextHaCutsItselfIsNotChargedToVitro() {
        val slot = CssRect(0.0, 0.0, 246.0, 120.0)
        fun t(w: Double, clipped: Boolean? = null) = RefText(0, "Entryway Ceiling Light", CssRect(56.0, 17.0, w, 16.0), 14.0, 500, "none", clipped)
        assertTrue("runs past HA's card", ParityCompare.haCuts(t(282.0), slot))
        assertTrue("fits HA's card", !ParityCompare.haCuts(t(140.0), slot))
        assertTrue("the capture's own flag wins", ParityCompare.haCuts(t(140.0, clipped = true), slot))
        assertTrue(!ParityCompare.haCuts(t(282.0, clipped = false), slot))
    }

    @Test
    fun aCaseWithAMistypedFrameIsCaught() {
        val ids = setOf("s1", "tablet", "mirror")
        assertEquals(listOf("sl"), ParityCase.parse("x", JSONObject().put("frames", org.json.JSONArray().put("sl").put("tablet"))).unknownFrames(ids))
        assertTrue(ParityCase.parse("x", JSONObject()).unknownFrames(ids).isEmpty())
    }

    @Test
    fun anUnknownCaseOrFrameIsRejected() {
        assertTrue(ParityFiles.unknownSelections(setOf("clock"), setOf("clock", "tile-light"), setOf("s1"), setOf("s1", "tablet")).isEmpty())
        assertTrue(ParityFiles.unknownSelections(null, setOf("clock"), null, setOf("s1")).isEmpty())
        assertEquals(1, ParityFiles.unknownSelections(setOf("clokc"), setOf("clock"), null, setOf("s1")).size)
        assertEquals(1, ParityFiles.unknownSelections(null, setOf("clock"), setOf("s2"), setOf("s1")).size)
    }

    @Test
    fun caseInsensitiveSearchKeepsIndicesInTheOriginalText() {
        // "İ".lowercase() is two chars: a folded copy would shift every index after it.
        assertEquals(1, ParityCompare.indexOfFolded("İX", "x"))
        assertEquals(3, ParityCompare.indexOfFolded("İ  Open", "open"))
        assertEquals(-1, ParityCompare.indexOfFolded("İX", "y"))
        assertEquals(5, ParityCompare.indexOfFolded("open open", "OPEN", 1))
    }

    @Test
    fun anAvatarInitialIsNotFoundInsideATime() {
        // The logbook's user avatar "A" next to "9:48:00 AM": only a standalone "A" is the avatar.
        assertTrue(ParityCompare.standalone("A") && ParityCompare.standalone("12") && !ParityCompare.standalone("On ·"))
        assertEquals(-1, ParityCompare.indexOfFolded("9:48:00 AM", "A", whole = true))
        assertEquals(11, ParityCompare.indexOfFolded("9:48:00 AM A", "a", whole = true))
        assertEquals(8, ParityCompare.indexOfFolded("9:48:00 AM", "A"))
    }

    @Test
    fun aPlayingSpeakerAdvancesFromItsLastPositionUpdate() {
        // The fixture's speaker reported 92 s of 245 a minute before the pinned instant: Vitro shows 152 s, as HA does.
        val ref = JSONObject(File(ParityFiles.committed, "refs/s1/media-control-playing.json").readText())
        val st = ref.getJSONObject("states").getJSONObject("media_player.living_room_speaker")
        val a = st.getJSONObject("a")
        val attrs = a.keys().asSequence().associateWith { k -> a.get(k).takeIf { it != JSONObject.NULL } }
        val state = dev.glassworks.engine.vitro.data.EntityState("media_player.living_room_speaker", st.getString("s"), attrs)
        val f = dev.glassworks.engine.vitro.cards.mediaProgressFraction(state, ref.getLong("now"))
        assertEquals(152.0 / 245.0, f!!.toDouble(), 1e-4)
    }

    @Test
    fun aKnownCardCountMismatchIsAcceptedButAChangedOneIsNot() {
        val f = File.createTempFile("baseline", ".json")
        val extra = measure(pixel = 0.5, ha = 3, vitro = 4)
        ParityBaseline(f).write(listOf(result(ParityStatus.REGRESSION, extra)))
        assertEquals(1, JSONObject(f.readText()).getJSONObject("s1/c").getInt("slotDelta"))
        assertTrue(ParityBaseline(f).worse("s1/c", extra).isEmpty())
        assertTrue(ParityBaseline(f).worse("s1/c", measure(pixel = 0.5, ha = 3, vitro = 5)).isNotEmpty())
        // An entry without a mismatch rejects a new one, even when nothing else moved.
        f.writeText(JSONObject().put("s1/c", entry(0.5, 0)).toString())
        assertTrue(ParityBaseline(f).worse("s1/c", extra).isNotEmpty())
        assertTrue(ParityBaseline(f).worse("s1/c", measure(pixel = 0.5, ha = 3, vitro = 3)).isEmpty())
    }

    @Test
    fun theReferencesDisplayDataReachesTheCardsAndIsDroppedAfter() {
        org.robolectric.RuntimeEnvironment.setQualifiers(frame.qualifiers)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = java.nio.file.Files.createTempDirectory("parity").toFile()
        val tile = ParityCase.parse("t", JSONObject().put("card", JSONObject().put("type", "tile").put("entity", "sensor.fridge")))
        val state = JSONObject().put("sensor.fridge", JSONObject().put("s", "1.11111111")
            .put("a", JSONObject().put("friendly_name", "Fridge").put("unit_of_measurement", "°C").put("device_class", "temperature")))
        fun ref(display: JSONObject?) = ParityReference(File(root, "none.png"), JSONObject().put("now", 0L).put("states", state)
            .apply { display?.let { put("display", it) } })
        fun shown(r: ParityReference) = ParityRenderer(activity, frame, root, null).render(tile, r).texts(0).joinToString(" | ") { it.normalized }
        val display = JSONObject()
            .put("entities", org.json.JSONArray().put(JSONObject().put("ei", "sensor.fridge").put("pl", "demo").put("dp", 1)))
            .put("requested", JSONObject().put("domains", org.json.JSONArray().put("sensor")).put("platforms", org.json.JSONArray()))
        // Without the registry Vitro shows the raw state; with it, HA's display precision.
        assertTrue(shown(ref(null)), "1.11111111" in shown(ref(null)))
        assertTrue(shown(ref(display)), "1.1 °C" in shown(ref(display)) && "1.11" !in shown(ref(display)))
        // HaDisplay is process-wide: nothing of the case is left for the next one.
        assertTrue(dev.glassworks.engine.vitro.data.HaDisplay.registry.isEmpty())
    }

    @Test
    fun aPageThePanelsHeightIsDrawnToItsBottom() {
        // The activity's window is the panel less Robolectric's status and navigation bars; the kiosk has none.
        org.robolectric.RuntimeEnvironment.setQualifiers(frame.qualifiers)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = java.nio.file.Files.createTempDirectory("parity").toFile()
        File(root, "dashboards").mkdirs()
        val view = JSONObject().put("path", "home").put("type", "sections").put("sections", org.json.JSONArray().put(
            JSONObject().put("type", "grid").put("cards", org.json.JSONArray().put(JSONObject().put("type", "tile").put("entity", "sensor.x"))),
        ))
        File(root, "dashboards/short.json").writeText(JSONObject().put("views", org.json.JSONArray().put(view)).toString())
        val dash = ParityCase("short", "short", null, "short", "home", null, true, null, 1, Threshold(), emptyList())
        val ref = ParityReference(File(root, "none.png"), JSONObject().put("now", 0L).put("docSize", JSONObject().put("height", frame.height)))
        val render = ParityRenderer(activity, frame, root, null).render(dash, ref)
        assertEquals(frame.pxHeight, render.bitmap.height)
        assertEquals("Vitro laid out to the panel's bottom, not the window's", frame.pxHeight, render.root.height)
        val black = android.graphics.Color.BLACK
        assertTrue("the bottom row is the dashboard's, not the bitmap's black", render.bitmap.getPixel(frame.pxWidth / 2, frame.pxHeight - 2) != black)
    }

    @Test
    fun aPinnedCardStaysAtThePanelsEdgeWhenThePageIsTaller() {
        org.robolectric.RuntimeEnvironment.setQualifiers(frame.qualifiers)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = java.nio.file.Files.createTempDirectory("parity").toFile()
        File(root, "dashboards").mkdirs()
        val pinned = JSONObject().put("type", "tile").put("entity", "sensor.x")
            .put("card_mod", JSONObject().put("style", "ha-card { position: fixed; left: 16px; bottom: 16px; width: 200px; }"))
        val view = JSONObject().put("path", "home").put("type", "sections").put("sections", org.json.JSONArray().put(
            JSONObject().put("type", "grid").put("cards", org.json.JSONArray().put(JSONObject().put("type", "tile").put("entity", "sensor.x")).put(pinned)),
        ))
        File(root, "dashboards/tall.json").writeText(JSONObject().put("views", org.json.JSONArray().put(view)).toString())
        val dash = ParityCase("tall", "tall", null, "tall", "home", null, true, null, 1, Threshold(), emptyList())
        // HA's page is 1500 CSS px tall, so the render grows to 1500 while the panel is 480.
        val ref = ParityReference(File(root, "none.png"), JSONObject().put("now", 0L).put("docSize", JSONObject().put("height", 1500)))
        val render = ParityRenderer(activity, frame, root, null).render(dash, ref)
        assertEquals((1500 * frame.density).toInt(), render.bitmap.height)
        assertEquals("Vitro drawn as tall as the bitmap", render.bitmap.height, render.root.height)
        val card = render.slots.last()
        assertEquals("anchored 16 px above the panel's bottom: $card", 480.0 - 16.0, card.bottom, 1.5)
    }

    @Test
    fun theFixtureServerWithholdsAnImageHaCouldntLoad() {
        val dir = java.nio.file.Files.createTempDirectory("assets").toFile()
        File(dir, "static").mkdirs()
        File(dir, "static/stale.png").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "static/ok.png").writeBytes(byteArrayOf(4, 5))
        fun get(http: FixtureHttp, path: String) = (java.net.URL(http.base + path).openConnection() as java.net.HttpURLConnection).run {
            try { responseCode } finally { disconnect() }
        }
        FixtureHttp(dir, setOf("/static/stale.png")).use { http ->
            // A file left from an earlier capture is never handed to Vitro once HA can't load that path.
            assertEquals(404, get(http, "/static/stale.png"))
            assertEquals(200, get(http, "/static/ok.png"))
            assertEquals(listOf("/static/stale.png"), http.missing.toList())
        }
    }

    @Test
    fun theFixtureServerFindsAFileByTheCapturesEncodedName() {
        val dir = java.nio.file.Files.createTempDirectory("assets").toFile()
        File(dir, "local").mkdirs()
        File(dir, "local/floor%20plan.png").writeBytes(byteArrayOf(1, 2))
        FixtureHttp(dir).use { http ->
            val code = (java.net.URL(http.base + "/local/floor%20plan.png").openConnection() as java.net.HttpURLConnection).run {
                try { responseCode } finally { disconnect() }
            }
            assertEquals(200, code)
        }
    }

    @Test
    fun aReferenceTextWithoutASizeFailsItsOwnCase() {
        val ref = ParityReference(File("none.png"), JSONObject().put("texts", org.json.JSONArray()
            .put(JSONObject().put("slot", 0).put("text", "x").put("x", 1).put("y", 1).put("w", 1).put("h", 1))))
        try {
            ref.texts
            org.junit.Assert.fail("a text without a size is refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), "size" in e.message.orEmpty())
        }
    }

    @Test
    fun aHiddenSectionsPinnedCardKeepsTheNextSectionsPinPaired() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        fun section(visible: Boolean) = android.widget.FrameLayout(activity).apply {
            addView(dev.glassworks.engine.vitro.ui.HaGridLayout(activity, 12, 8, 8, stretch = false).apply { addView(android.view.View(activity)) })
            visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        }
        val sections = dev.glassworks.engine.vitro.ui.HaGridLayout(activity, 1, 0, 0, stretch = false).apply {
            addView(section(visible = false))
            addView(section(visible = true))
        }
        val pinA = android.view.View(activity).apply { layout(0, 0, 10, 10) }
        val pinB = android.view.View(activity).apply { layout(0, 0, 20, 20) }
        // Section 1 (hidden) holds pin A; section 2 holds pin B, which is the one reported.
        assertEquals(listOf(pinB), ParityViews.cardSlots(sections, listOf(pinA, pinB)))
    }

    @Test
    fun anImageTheCaptureDidntSaveIsAnInputGapUnlessHaMissedItToo() {
        val ref = ParityReference(File("none.png"), JSONObject().put("imagesHaMissing", org.json.JSONArray().put("/None")))
        val (gaps, haToo) = ParityInputs.missingImages(listOf("/static/x.png", "/None", "/static/x.png"), ref)
        assertEquals(listOf("/static/x.png"), gaps)
        assertEquals(listOf("/None"), haToo)
        assertEquals(emptyList<String>() to emptyList<String>(), ParityInputs.missingImages(emptyList(), ref))
        // A reference without the field (nothing HA missed): every missing image is a gap.
        assertEquals(listOf("/None"), ParityInputs.missingImages(listOf("/None"), ParityReference(File("none.png"), JSONObject())).first)
    }

    @Test
    fun aMacFragmentIsCaughtWhateverSetsItOff() {
        for (bad in listOf("AirDot 4c83f8", "airdot-4c83f8", "AirDot_a1b2c3d4e5f6", "esp_4C83F8.local", "(a1b2c3)")) {
            assertTrue(bad, MAC_FRAGMENT.containsMatchIn(bad))
        }
        for (ok in listOf("#34d399", "color: #a1b2c3", "TS0501B", "_TZB210_gumjktrw", "abcdef", "123456", "1795504930.063075", "Fog 9.1 °C")) {
            assertTrue(ok, !MAC_FRAGMENT.containsMatchIn(ok))
        }
    }

    /** CLAUDE.md hard rule 2: no tokens, addresses, MACs or serials in fixtures, references or reports. */
    @Test
    fun committedFixturesHoldNothingPrivate() {
        val root = ParityFiles.committed
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("the corpus has fixtures", files.size > 10)
        val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
        val mac = Regex("""\b[0-9a-fA-F]{2}([:-])[0-9a-fA-F]{2}(\1[0-9a-fA-F]{2}){4}\b""")
        val jwt = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
        // The fixtures are made up (the fake HA serves them), so a playing speaker's title and artist are fine; a media
        // list (sources, playlists) still isn't: those came from real homes.
        val privateKeys = Regex("""\"(ip_address|mac_address|serial_number|latitude|longitude|access_token|ssid|bssid|source_list|sound_mode_list|media_playlist)\"\s*:""")
        val problems = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val rel = f.relativeTo(root).path
            ipv4.findAll(text).filter { m -> m.groupValues.drop(1).all { it.toInt() <= 255 } }
                .filterNot { it.value.startsWith("192.0.2.") || it.value.startsWith("198.51.100.") || it.value.startsWith("203.0.113.") }
                .forEach { problems += "$rel: an IPv4 address outside RFC 5737 (${it.value.take(4)}…)" }
            if (mac.containsMatchIn(text.replace("00:00:5e:00:53:00", ""))) problems += "$rel: a MAC address"
            if (jwt.containsMatchIn(text)) problems += "$rel: a token"
            // HA's device and config-entry ids: 32 hex chars, unique to one HA (the fixtures use device-1, ...).
            if (Regex("""\b[0-9a-f]{32}\b""").containsMatchIn(text)) problems += "$rel: an HA registry id"
            // The end of a MAC in a device name or hostname; a CSS colour (#34d399) is fine.
            if (MAC_FRAGMENT.containsMatchIn(text)) problems += "$rel: a MAC fragment"
            if (FixtureScan.tokenParameter(text)) problems += "$rel: a token parameter"
            privateKeys.find(text)?.let { problems += "$rel: a private attribute (${it.groupValues[1]})" }
            if (FixtureScan.externalHost(text)) problems += "$rel: an absolute URL to a host"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aMaskedNeighborDoesntExcuseADifference() {
        // One unmasked pixel differs; its masked neighbor happens to hold the other image's colour.
        val ha = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.WHITE); setPixel(1, 0, Color.BLACK) }
        val vitro = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.BLACK); setPixel(1, 0, Color.WHITE) }
        val d = ParityDiff.compare(ha, vitro, 2, 1, listOf(android.graphics.Rect(1, 0, 2, 1)))
        assertEquals(1.0, d.tolerant, 0.0)
    }

    @Test
    fun theFixtureScanReadsWholeValuesAndWholeHostnames() {
        // Only the exact placeholder passes; one that merely starts with it is a value.
        assertFalse(FixtureScan.tokenParameter("/api/camera_proxy/camera.a?token=redacted&x=1"))
        assertTrue(FixtureScan.tokenParameter("/api/camera_proxy/camera.a?token=redactedExtra"))
        assertTrue(FixtureScan.tokenParameter("?authSig=abc"))
        // The whole hostname: a longer name that starts with an allowed one is another host, and a bare origin at the
        // end of a JSON string counts.
        assertFalse(FixtureScan.externalHost("""{"u": "http://homeassistant.local:8123/x", "v": "http://127.0.0.1/y"}"""))
        assertTrue(FixtureScan.externalHost("""{"u": "http://homeassistant.local.example.test/x"}"""))
        assertTrue(FixtureScan.externalHost("""{"u": "https://example.test"}"""))
        // An IPv6 origin, which the IPv4 check can't see.
        assertTrue(FixtureScan.externalHost("""{"u": "https://[2001:db8::1]/image"}"""))
        assertTrue(FixtureScan.externalHost("""{"u": "http://user@[::1]:8123/x"}"""))
        // The whole authority, not a prefix of it; schemes in any case.
        assertTrue(FixtureScan.externalHost("""{"u": "http://homeassistant.local_private.example.test/x"}"""))
        assertTrue(FixtureScan.externalHost("""{"u": "HTTPS://private.example.test/x"}"""))
        // Credentials in the URL fail it even for an allowed host.
        assertTrue(FixtureScan.externalHost("""{"u": "https://fixture-user:pw@homeassistant.local/x"}"""))
        assertFalse(FixtureScan.externalHost("""{"u": "HTTP://homeassistant.local:8123/x?y=1"}"""))
    }

    @Test
    fun everyCaseHasAReferenceOnEachOfItsFrames() {
        val root = ParityFiles.committed
        val frameIds = ParityFrame.load().map { it.id }.toSet()
        val typos = ParityCase.loadAll(root).associate { it.id to it.unknownFrames(frameIds) }.filterValues { it.isNotEmpty() }
        assertTrue("cases list frames frames.json doesn't have: $typos", typos.isEmpty())
        val missing = ParityCase.loadAll(root).flatMap { c ->
            ParityFrame.load().filter { c.runsOn(it) && ParityReference.find(root, it, c) == null }.map { "${it.id}/${c.id}" }
        }
        assertTrue("no reference for: $missing (run tools/vitro-parity/run.sh)", missing.isEmpty())
        assertTrue(File(root, "refs/shared.json").isFile)
        assertTrue(JSONObject(File(root, "refs/shared.json").readText()).has("themes"))
    }
}

/** The committed-fixture checks for a token parameter and an absolute URL to another host. */
internal object FixtureScan {
    private val TOKEN_PARAM = Regex("""[?&](token|access_token|authSig)=([^&"'\s#]*)""")
    // The whole authority, up to the path, query, fragment or the end of the string (schemes in any case).
    private val URL_AUTHORITY = Regex("""(?i)https?://([^/\s"'?#\\]*)""")
    private val ALLOWED_HOSTS = setOf("127.0.0.1", "homeassistant.local")

    /** A token parameter with any value but exactly `redacted`. */
    fun tokenParameter(text: String): Boolean = TOKEN_PARAM.findAll(text).any { it.groupValues[2] != "redacted" }

    /** An absolute URL whose whole hostname isn't one the fixtures may name. */
    fun externalHost(text: String): Boolean = URL_AUTHORITY.findAll(text).any { m ->
        // The host is what's left of the authority without its user info and port; a bracketed IPv6 one is never
        // allowed. Anything that doesn't read as one of the allowed names counts as external.
        // User info is a credential in a fixture whatever the host: never allowed.
        if ('@' in m.groupValues[1]) return@any true
        val hostPort = m.groupValues[1]
        val host = if (hostPort.startsWith("[")) hostPort else hostPort.substringBefore(':')
        host.lowercase().trimEnd('.') !in ALLOWED_HOSTS
    }
}
