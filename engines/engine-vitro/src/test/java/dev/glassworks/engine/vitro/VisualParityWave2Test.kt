/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.BarCardView
import dev.glassworks.engine.vitro.cards.BarSegment
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.FontWeightSpan
import dev.glassworks.engine.vitro.cards.LetterSpacingSpan
import dev.glassworks.engine.vitro.cards.MarkdownInline
import dev.glassworks.engine.vitro.cards.MarkdownRenderer
import dev.glassworks.engine.vitro.cards.MdBlock
import dev.glassworks.engine.vitro.cards.ModernCircularGaugeCardView
import dev.glassworks.engine.vitro.cards.parseMarkdownBlocks
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Markdown typography and HTML bars, big card-mod clocks, and the modern-circular-gauge and bar-card fast paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisualParityWave2Test {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun context(
        store: EntityStore = EntityStore(),
        haConfig: JSONObject = JSONObject(),
        subscribeTemplate: (String, (String) -> Unit) -> () -> Unit = { _, _ -> {} },
    ) = VitroCardContext(
        context = activity,
        haBase = FakeHaServer.BASE,
        store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        haConfig = { haConfig },
        callService = { _, _, _, _ -> },
        navigate = {},
        openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = subscribeTemplate,
    )

    private fun card(json: String) = JSONObject(json).let { CardConfig(it.getString("type"), it) }

    private fun sensor(id: String, state: String, unit: String? = null) =
        EntityState(id, state, buildMap { put("friendly_name", id.substringAfter('.')); unit?.let { put("unit_of_measurement", it) } })

    private inline fun <reified T : Any> Spanned.spansOf(): List<T> =
        getSpans(0, length, T::class.java).sortedBy { getSpanStart(it) }

    private fun ViewGroup.all(): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            out += c
            if (c is ViewGroup) out += c.all()
        }
        return out
    }

    // ---- Markdown typography ----

    private val inline = MarkdownInline(
        linkColor = 0xFF03A9F4.toInt(),
        codeBackground = 0x14FFFFFF,
        iconSizePx = 16,
        density = 2f,
        fontScale = 1f,
        typefaceFor = { Typeface.DEFAULT },
    )

    @Test
    fun spanStyleCarriesSizeWeightLetterSpacingAndTransform() {
        val out = inline.render(
            """<span style="font-size:52px;font-weight:300;letter-spacing:0.1em;text-transform:uppercase">good morning</span>""",
            Color.WHITE,
        )
        assertEquals("GOOD MORNING", out.toString())
        val size = out.spansOf<AbsoluteSizeSpan>().single()
        assertEquals(104, size.size) // 52 CSS px at density 2
        assertFalse(size.dip)
        assertEquals(300, out.spansOf<FontWeightSpan>().single().weight)
        assertEquals(0.1f, out.spansOf<LetterSpacingSpan>().single().em!!, 1e-6f)
        assertTrue(out.spansOf<StyleSpan>().isEmpty())
    }

    @Test
    fun relativeSizesBoldWeightsOpacityAndLowercase() {
        val out = inline.render(
            """<span style="font-size:0.75em">a</span><span style="font-size:150%">b</span>""" +
                """<span style="font-weight:bold">c</span><span style="color:#ffffff;opacity:0.5;text-transform:lowercase">DIM</span>""",
            Color.WHITE,
        )
        assertEquals("abcdim", out.toString())
        val rel = out.spansOf<RelativeSizeSpan>()
        assertEquals(listOf(0.75f, 1.5f), rel.map { it.sizeChange })
        assertEquals(Typeface.BOLD, out.spansOf<StyleSpan>().single().style)
        val dim = out.spansOf<ForegroundColorSpan>().single()
        assertEquals(128, Color.alpha(dim.foregroundColor))
        assertEquals(0xFFFFFF, dim.foregroundColor and 0xFFFFFF)
    }

    @Test
    fun blockTextAlignIsAnAlignmentSpanInline() {
        val out = inline.render("""<div style="text-align:center">Centered</div>""", Color.WHITE)
        assertEquals("Centered", out.toString())
        assertEquals(Layout.Alignment.ALIGN_CENTER, out.spansOf<AlignmentSpan.Standard>().single().alignment)
    }

    @Test
    fun aWrappingParagraphStyleSetsGravityAndLetterSpacing() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            """<p style="text-align:right;letter-spacing:0.14em;font-size:12px">LIVING ROOM</p>""",
            into,
        )
        val tv = into.getChildAt(0) as TextView
        assertEquals(Gravity.END, tv.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(0.14f, tv.letterSpacing, 1e-6f)
        assertEquals("LIVING ROOM", tv.text.toString())
    }

    // ---- Markdown stacked bars ----

    @Test
    fun aFlexRowOfWidthDivsIsAStackedBar() {
        val blocks = parseMarkdownBlocks(
            """<div style="display:flex;height:10px;border-radius:5px;overflow:hidden;gap:2px"><div style="width:40%;background:#38bdf8"></div><div style="width:60%;background:#34d399"></div></div>""",
        )
        assertEquals(
            listOf(
                MdBlock.StackedBar(
                    heightDp = 10f, radiusDp = 5f, trackColor = null, gapDp = 2f,
                    segments = listOf(BarSegment(40f, 0xFF38BDF8.toInt()), BarSegment(60f, 0xFF34D399.toInt())),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun aSingleProgressBarKeepsItsTrackAndTheTextAroundIt() {
        val blocks = parseMarkdownBlocks(
            "**Battery**\n" +
                """<div style="background: rgba(255,255,255,0.1); height: 8px; border-radius: 4px"><div style="width: 65%; height: 100%; background: #fbbf24"></div></div>""" +
                "\n65% left",
        )
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Paragraph("**Battery**"), blocks[0])
        val bar = blocks[1] as MdBlock.StackedBar
        assertEquals(8f, bar.heightDp)
        assertEquals(4f, bar.radiusDp)
        assertNotNull(bar.trackColor)
        assertEquals(listOf(BarSegment(65f, 0xFFFBBF24.toInt())), bar.segments)
        assertEquals(MdBlock.Paragraph("65% left"), blocks[2])
    }

    @Test
    fun plainDivsStayParagraphs() {
        val md = """<div style="color:red">Not a bar</div>"""
        assertEquals(listOf(MdBlock.Paragraph(md)), parseMarkdownBlocks(md))
    }

    @Test
    fun aStackedBarRendersWeightedClippedSegments() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            """<div style="display:flex;height:10px;border-radius:5px"><div style="width:40%;background:#38bdf8"></div><div style="width:60%;background:#34d399"></div></div>""",
            into,
        )
        val bar = into.all().single { it.tag == MarkdownRenderer.STACKED_BAR_TAG } as LinearLayout
        assertTrue(bar.clipToOutline)
        assertEquals(100f, bar.weightSum)
        assertEquals(2, bar.childCount)
        val weights = (0 until bar.childCount).map { (bar.getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight }
        assertEquals(listOf(40f, 60f), weights)
        assertTrue((0 until bar.childCount).all { bar.getChildAt(it).tag == MarkdownRenderer.STACKED_SEGMENT_TAG })
    }

    // ---- Clock ----

    @Test
    fun cardModFontSizeAndLightWeightSizeTheClock() {
        val big = ClockCardView(
            context(),
            card("""{"type": "clock", "card_mod": {"style": "ha-card { font-size: 164px; font-weight: 300; }"}}"""),
        )
        assertEquals(164f, big.digitsSp)
        assertEquals(164f * 0.26f, big.sideSp, 1e-4f)
        assertTrue(big.digitsLight)

        val plain = ClockCardView(context(), card("""{"type": "clock"}"""))
        assertEquals(42f, plain.digitsSp)
        assertEquals(16f, plain.sideSp)
        assertFalse(plain.digitsLight)

        val large = ClockCardView(context(), card("""{"type": "clock", "clock_size": "large"}"""))
        assertEquals(52f, large.digitsSp)
        assertEquals(20f, large.sideSp)

        val huge = ClockCardView(context(), card("""{"type": "clock", "card_mod": {"style": "ha-card { font-size: 400px }"}}"""))
        assertEquals(220f, huge.digitsSp)
        assertEquals(44f, huge.sideSp)
        assertFalse(huge.digitsLight)
    }

    // ---- custom:modern-circular-gauge ----

    @Test
    fun gaugeTypesSetTheSweep() {
        val full = ModernCircularGaugeCardView(context(), card("""{"type": "custom:modern-circular-gauge", "entity": "sensor.x", "gauge_type": "full"}"""))
        assertEquals("full", full.gaugeType)
        assertEquals(360f, full.ring.sweep)
        val std = ModernCircularGaugeCardView(context(), card("""{"type": "custom:modern-circular-gauge", "entity": "sensor.x"}"""))
        assertEquals("standard", std.gaugeType)
        assertEquals(270f, std.ring.sweep)
        val half = ModernCircularGaugeCardView(context(), card("""{"type": "custom:modern-circular-gauge", "entity": "sensor.x", "gauge_type": "half"}"""))
        assertEquals(180f, half.ring.sweep)
    }

    @Test
    fun gaugeFollowsTheStoreWithSegmentColorsAndRange() {
        val store = EntityStore()
        store.putLocal(sensor("sensor.co2", "600", "ppm"))
        val view = ModernCircularGaugeCardView(
            context(store),
            card(
                """{"type": "custom:modern-circular-gauge", "entity": "sensor.co2", "min": 400, "max": 1400,
                   "segments": [{"from": 400, "color": "#00ff00"}, {"from": 1000, "color": [255, 0, 0]}],
                   "secondary": {"entity": "sensor.temp", "unit": "°C"}}""",
            ),
        )
        assertEquals(400.0, view.minVal, 0.0)
        assertEquals(1400.0, view.maxVal, 0.0)
        assertEquals(2, view.segments.size)
        assertEquals(0.2f, view.ring.targetFraction, 1e-4f)
        assertEquals(0xFF00FF00.toInt(), view.ring.activeColor)
        assertEquals("600", view.ring.valueText)
        assertEquals("ppm", view.ring.unitText)

        store.putLocal(sensor("sensor.co2", "1150", "ppm"))
        store.putLocal(sensor("sensor.temp", "21.5"))
        ShadowLooper.idleMainLooper()
        assertEquals(0.75f, view.ring.targetFraction, 1e-4f)
        assertEquals(0xFFFF0000.toInt(), view.ring.activeColor)
        assertEquals("1150", view.ring.valueText)
        assertEquals("21.5 °C", view.ring.secondaryText)
        assertEquals("co2", view.nameView.text.toString())
    }

    @Test
    fun smoothSegmentsBlendBetweenStops() {
        val segs = ModernCircularGaugeCardView.parseSegments(
            org.json.JSONArray("""[{"from": 0, "color": "#000000"}, {"from": 100, "color": "#ffffff"}]"""),
        )
        assertEquals(0xFF000000.toInt(), ModernCircularGaugeCardView.segmentColor(segs, 50.0, smooth = false))
        val mid = ModernCircularGaugeCardView.segmentColor(segs, 50.0, smooth = true)!!
        assertTrue(Color.red(mid) in 120..135)
        assertEquals(0xFFFFFFFF.toInt(), ModernCircularGaugeCardView.segmentColor(segs, 150.0, smooth = true))
    }

    // ---- custom:bar-card ----

    @Test
    fun singleBarUsesSeverityAndUpdatesLive() {
        val store = EntityStore()
        store.putLocal(sensor("sensor.battery", "30", "%"))
        val view = BarCardView(
            context(store),
            card(
                """{"type": "custom:bar-card", "entity": "sensor.battery", "title": "Battery",
                   "severity": [{"from": 0, "to": 50, "color": "#ff0000"}, {"from": 51, "to": 100, "color": "#00ff00"}]}""",
            ),
        )
        assertEquals(1, view.rows.size)
        val row = view.rows.single()
        assertEquals(0.3f, row.fill.targetFraction, 1e-4f)
        assertEquals(0xFFFF0000.toInt(), row.fill.fillColor)
        assertEquals("30%", row.valueView.text.toString())

        store.putLocal(sensor("sensor.battery", "80", "%"))
        ShadowLooper.idleMainLooper()
        assertEquals(0.8f, row.fill.targetFraction, 1e-4f)
        assertEquals(0xFF00FF00.toInt(), row.fill.fillColor)
        assertEquals("80%", row.valueView.text.toString())
    }

    @Test
    fun multiEntityBarsTakePerEntityOverrides() {
        val store = EntityStore()
        store.putLocal(sensor("sensor.a", "25", "W"))
        store.putLocal(sensor("sensor.b", "100", "W"))
        val view = BarCardView(
            context(store),
            card(
                """{"type": "custom:bar-card", "color": "#123456",
                   "entities": ["sensor.a", {"entity": "sensor.b", "name": "Heater", "max": 200, "color": "#0000ff"}]}""",
            ),
        )
        assertEquals(2, view.rows.size)
        val (a, b) = view.rows
        assertEquals(0.25f, a.fill.targetFraction, 1e-4f)
        assertEquals(0xFF123456.toInt(), a.fill.fillColor)
        assertEquals("25 W", a.valueView.text.toString())
        assertEquals(0.5f, b.fill.targetFraction, 1e-4f)
        assertEquals(0xFF0000FF.toInt(), b.fill.fillColor)
        assertEquals("Heater", b.nameView.text.toString())

        store.putLocal(sensor("sensor.b", "50", "W"))
        ShadowLooper.idleMainLooper()
        assertEquals(0.25f, b.fill.targetFraction, 1e-4f)
    }

    // ---- Routing ----

    @Test
    fun bothCardsAreTierTwo() {
        for (t in listOf("custom:modern-circular-gauge", "custom:bar-card")) {
            assertEquals("$t is Tier 2", CardTier.NATIVE_HACS, CardRouter.tier(card("""{"type": "$t"}""")))
        }
        assertEquals(14, CardRouter.NATIVE_HACS_TYPES.size)
    }

    // ---- Mirror polish: clock zone and alignment, icons, markdown images, chip card-mod, to-do limits ----

    @Test
    fun clockFormatsInHomeAssistantsTimeZoneNotTheDevices() {
        val prev = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            // Any instant: Los Angeles is 7-8 hours behind UTC, so the two clocks must differ.
            val instant = java.util.Date(1_795_496_000_000L - (1_795_496_000_000L % 60_000L))
            val utcFmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val laFmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles") }
            val la = ClockCardView(
                context(haConfig = JSONObject().put("time_zone", "America/Los_Angeles")),
                card("""{"type": "clock", "time_format": "12"}"""),
            )
            la.renderAt(instant)
            assertEquals(laFmt.format(instant), la.renderedTime)
            val utc = ClockCardView(context(haConfig = JSONObject().put("time_zone", "UTC")), card("""{"type": "clock", "time_format": "12"}"""))
            utc.renderAt(instant)
            assertEquals(utcFmt.format(instant), utc.renderedTime)
            assertTrue(la.renderedTime != utc.renderedTime)
            // No HA zone: the device's zone.
            val none = ClockCardView(context(), card("""{"type": "clock", "time_format": "12"}"""))
            none.renderAt(instant)
            assertEquals(utcFmt.format(instant), none.renderedTime)
        } finally {
            java.util.TimeZone.setDefault(prev)
        }
    }

    @Test
    fun clockFollowsCardModTextAlignAndZeroPadding() {
        val left = ClockCardView(
            context(),
            card("""{"type": "clock", "time_format": "12", "card_mod": {"style": "ha-card { text-align: left; padding: 0; background: none }"}}"""),
        )
        assertEquals(Gravity.START, left.horizontalGravity)
        val col = left.getChildAt(0) as LinearLayout
        assertEquals(0, col.paddingStart)
        assertEquals(Gravity.START, col.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        val row = col.getChildAt(0) as LinearLayout
        assertEquals(Gravity.START, row.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        // AM/PM alone sits on the digits' baseline, not mid-height.
        assertEquals(Gravity.BOTTOM, row.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        val side = row.getChildAt(1) as LinearLayout
        assertEquals(Gravity.BOTTOM, side.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertTrue(side.paddingBottom > 0)

        val right = ClockCardView(context(), card("""{"type": "clock", "card_mod": {"style": "text-align: end"}}"""))
        assertEquals(Gravity.END, right.horizontalGravity)
        val centered = ClockCardView(context(), card("""{"type": "clock"}"""))
        assertEquals(Gravity.CENTER_HORIZONTAL, centered.horizontalGravity)
        assertTrue((centered.getChildAt(0) as LinearLayout).paddingStart > 0)
    }

    @Test
    fun mirrorDashboardIconsExistAndParse() {
        val names = listOf(
            "circle", "circle-small", "flash-alert", "wrench", "table-furniture", "hvac", "stove", "weather-fog",
            "weather-cloudy", "weather-rainy", "weather-partly-cloudy", "weather-night", "weather-sunny", "cart", "cart-outline",
        )
        for (n in names) {
            assertTrue("mdi:$n is known", dev.glassworks.engine.vitro.ui.MdiIcons.has("mdi:$n"))
            val bounds = android.graphics.RectF()
            dev.glassworks.engine.vitro.ui.MdiIcons.pathFor("mdi:$n").computeBounds(bounds, true)
            assertTrue("mdi:$n draws inside 24x24: $bounds", bounds.width() > 1f && bounds.left >= -0.5f && bounds.right <= 24.5f && bounds.bottom <= 24.5f)
        }
    }

    @Test
    fun markdownTableWithBlankHeaderDrawsNoHeaderRow() {
        val md = "|   |   |\n|---|--:|\n| Temp | 21 °C |\n| Humidity | 40 % |"
        val table = parseMarkdownBlocks(md).single() as MdBlock.Table
        assertTrue(table.header.all { it.isBlank() })
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        MarkdownRenderer(context()).render(md, box)
        val layout = box.getChildAt(0) as android.widget.TableLayout
        // Two rows and one hairline between them; no empty header row or top rule.
        assertEquals(3, layout.childCount)
        val firstRow = layout.getChildAt(0) as android.widget.TableRow
        assertEquals("Temp", (firstRow.getChildAt(0) as TextView).text.toString())

        val headed = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        MarkdownRenderer(context()).render("| A | B |\n|---|---|\n| 1 | 2 |", headed)
        assertEquals(3, (headed.getChildAt(0) as android.widget.TableLayout).childCount)
    }

    @Test
    fun markdownHtmlImageLinesBecomeNativeImages() {
        val md = """
            ### Now playing
            <img src="/api/media_player_proxy/media_player.kitchen?token=x" style="width:140px;height:140px;border-radius:24px">

            <div style="text-align:right"><img src="/local/a.png" width="64" height="32"></div>

            <div style="text-align:center">
            <img src="/local/b.png" style="margin-left:auto">
            </div>
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        val images = blocks.filterIsInstance<MdBlock.Image>()
        assertEquals(3, images.size)
        assertEquals(MdBlock.Image("/api/media_player_proxy/media_player.kitchen?token=x", 140f, 140f, 24f, "start"), images[0])
        assertEquals(MdBlock.Image("/local/a.png", 64f, 32f, 0f, "end"), images[1])
        assertEquals("end", images[2].align)
        assertFalse(blocks.any { it is MdBlock.Paragraph && "<" in it.text })

        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        MarkdownRenderer(context()).render(md, box)
        val views = box.all().filter { it.tag == MarkdownRenderer.IMAGE_TAG }.map { it as android.widget.ImageView }
        assertEquals(3, views.size)
        val first = views[0]
        assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, first.scaleType)
        assertTrue(first.clipToOutline)
        assertEquals((140f * activity.resources.displayMetrics.density).toInt(), first.layoutParams.width)
        assertEquals(Gravity.END, (views[1].layoutParams as LinearLayout.LayoutParams).gravity)
    }

    @Test
    fun chipCardModTintsItsPillBorderAndLabel() {
        val templates = HashMap<String, (String) -> Unit>()
        val chips = dev.glassworks.engine.vitro.cards.MushroomChipsCardView(
            context(subscribeTemplate = { tpl, cb -> templates[tpl] = cb; {} }),
            card(
                """{"type": "custom:mushroom-chips-card", "chips": [
                     {"type": "template", "content": "Peak", "icon": "mdi:flash-alert",
                      "card_mod": {"style": "ha-card { background: #112233; border: 2px solid #445566; border-radius: 8px; color: #fbbf24 }"}},
                     {"type": "template", "content": "AQI",
                      "card_mod": {"style": "ha-card { color: {{ '#4ade80' }} }"}}
                   ]}""",
            ),
        )
        val peak = chips.getChildAt(0) as LinearLayout
        val pill = peak.background as android.graphics.drawable.GradientDrawable
        assertEquals(0xFF112233.toInt(), pill.color!!.defaultColor)
        assertEquals(8f * activity.resources.displayMetrics.density, pill.cornerRadius, 0.01f)
        val peakLabel = peak.all().filterIsInstance<TextView>().single()
        assertEquals(0xFFFBBF24.toInt(), peakLabel.currentTextColor)

        val aqi = chips.getChildAt(1) as LinearLayout
        val aqiLabel = aqi.all().filterIsInstance<TextView>().single()
        val before = aqiLabel.currentTextColor
        assertTrue(before != 0xFF4ADE80.toInt())
        val live = templates.entries.single { "#4ade80" in it.key }.value
        live("ha-card { color: #4ade80 }")
        ShadowLooper.idleMainLooper()
        assertEquals(0xFF4ADE80.toInt(), aqiLabel.currentTextColor)
    }

    @Test
    fun todoListHonorsHideCreateAndMaxItems() {
        val items = (1..5).map { dev.glassworks.engine.vitro.cards.TodoItem("u$it", "Item $it", false) }
        val capped = dev.glassworks.engine.vitro.cards.TodoListCardView(
            context(),
            card("""{"type": "todo-list", "entity": "todo.shopping", "hide_create": true, "max_items": 3}"""),
        )
        assertFalse(capped.addRowVisible)
        capped.setItemsForTest(items)
        // Three items plus a "+ 2 more" line.
        assertEquals(4, capped.activeRowCount)
        assertTrue(capped.all().filterIsInstance<TextView>().any { it.text.toString() == "+ 2 more" })

        val open = dev.glassworks.engine.vitro.cards.TodoListCardView(context(), card("""{"type": "todo-list", "entity": "todo.shopping"}"""))
        assertTrue(open.addRowVisible)
        open.setItemsForTest(items)
        assertEquals(5, open.activeRowCount)
    }
}
