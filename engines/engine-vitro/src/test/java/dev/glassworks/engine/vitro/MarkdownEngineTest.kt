/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.cards.MarkdownRenderer
import dev.glassworks.engine.vitro.cards.MarkedLite
import dev.glassworks.engine.vitro.cards.MdBox
import dev.glassworks.engine.vitro.cards.MdCascade
import dev.glassworks.engine.vitro.cards.MdHtml
import dev.glassworks.engine.vitro.cards.MdIconSpan
import dev.glassworks.engine.vitro.cards.MdNode
import dev.glassworks.engine.vitro.cards.MdSheet
import dev.glassworks.engine.vitro.cards.MdStyle
import dev.glassworks.engine.vitro.cards.MdSvg
import dev.glassworks.engine.vitro.cards.MdTableView
import dev.glassworks.engine.vitro.cards.StackInCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaGridLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Markdown engine (marked, HA's sanitizer, the cascade, layout), HA's sections grid, the clock and stack-in-card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownEngineTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun context(haConfig: JSONObject = JSONObject()) = VitroCardContext(
        context = activity,
        haBase = FakeHaServer.BASE,
        store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        haConfig = { haConfig },
        callService = { _, _, _, _ -> },
        navigate = {},
        openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
    )

    private fun card(json: String) = dev.glassworks.engine.vitro.model.LovelaceParser.parseCard(JSONObject(json))

    private fun ViewGroup.all(): List<View> = (0 until childCount).flatMap { i ->
        val c = getChildAt(i)
        listOf(c) + ((c as? ViewGroup)?.all() ?: emptyList())
    }

    private fun MdNode.find(tag: String): MdNode? {
        if (this.tag == tag) return this
        for (c in children) c.find(tag)?.let { return it }
        return null
    }

    private fun MdNode.findAll(tag: String): List<MdNode> =
        (if (this.tag == tag) listOf(this) else emptyList()) + children.flatMap { it.findAll(tag) }

    private fun styled(md: String, sheet: String = ""): MdNode {
        val root = MdHtml.fromMarkdown(md)
        val parent = MdStyle().apply { display = "block"; fontSize = 14f; lineHeightFactor = 1.6f; lineHeight = 22.4f }
        MdCascade.apply(root, parent, MdSheet.parse(sheet), emptyMap())
        return root
    }

    private fun measure(v: View, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    // ---- marked ----

    @Test
    fun markedTurnsNewlinesIntoBreaksAndPassesHtmlBlocksThrough() {
        assertEquals("<p>one<br>two</p>\n", MarkedLite.toHtml("one\ntwo"))
        assertEquals("<h2>Title</h2>\n<h6>Small</h6>\n", MarkedLite.toHtml("## Title\n###### Small"))
        val html = MarkedLite.toHtml("<table role=\"presentation\"><tr><td>a *b*</td></tr></table>\n\nnext **bold**")
        assertTrue(html.startsWith("<table role=\"presentation\"><tr><td>a *b*</td></tr></table>\n"))
        assertTrue("<p>next <strong>bold</strong></p>" in html)
        // A pipe table is wrapped in a div, as HA's renderer does.
        assertTrue(MarkedLite.toHtml("| A | B |\n|---|--:|\n| 1 | 2 |").startsWith("<div><table>"))
        // snake_case and arithmetic stay literal; setext headings work.
        assertEquals("<p>light_kitchen_main is 2 * 3 * 4</p>\n", MarkedLite.toHtml("light_kitchen_main is 2 * 3 * 4"))
        assertEquals("<h1>Big</h1>\n", MarkedLite.toHtml("Big\n==="))
    }

    // ---- HA's sanitizer ----

    @Test
    fun sanitizerDropsStyleAndClassAndEscapesUnknownTags() {
        val root = MdHtml.fromMarkdown(
            """<div style="color:red" class="x">a</div><span style="font-size:40px">b</span>""" +
                """<td width="30%" align="right" style="x">c</td><font color="#ff0000" style="x">d</font><blink>e</blink>""",
        )
        val div = root.find("div")!!
        assertTrue(div.attrs.isEmpty())
        assertTrue(root.find("span")!!.attrs.isEmpty())
        assertEquals(mapOf("color" to "#ff0000"), root.find("font")!!.attrs)
        assertNull(root.find("blink"))
        assertTrue("<blink>e</blink>" in root.textContent())
        val icon = MdHtml.fromMarkdown("""<ha-icon icon="mdi:sofa" style="color:red"></ha-icon>""").find("ha-icon")!!
        assertEquals(mapOf("icon" to "mdi:sofa"), icon.attrs)
    }

    @Test
    fun htmlParserBuildsTableStructure() {
        val root = MdHtml.fromMarkdown("""<table role="presentation"><tr><td width="35%"></td><td>x</td></tr><tr><td>y</td></table>""")
        val table = root.find("table")!!
        assertEquals("presentation", table.attrs["role"])
        val tbody = table.elementChildren().single()
        assertEquals("tbody", tbody.tag)
        assertEquals(2, tbody.elementChildren().size)
        assertEquals("35%", tbody.elementChildren()[0].elementChildren()[0].attrs["width"])
    }

    // ---- cascade ----

    @Test
    fun haStylesWinSpecificityTiesAndHigherSpecificityWinsBack() {
        // `h2 { … }` from card-mod loses to HA's own `h2` (adopted sheets win ties)…
        val tie = styled("## Hi", "h2 { font-size: 44px; font-weight: 300 }").find("h2")!!.style!!
        assertEquals(20f, tie.fontSize)
        assertEquals(700, tie.fontWeight)
        // …so dashboards write `ha-markdown-element h2`, which outranks it.
        val win = styled("## Hi", "ha-markdown-element h2 { font-size: 44px; font-weight: 300 }").find("h2")!!.style!!
        assertEquals(44f, win.fontSize)
        assertEquals(300, win.fontWeight)
        // Headings keep `line-height: normal`; the first child loses its top margin.
        assertTrue(win.lineHeight < 0f)
        assertEquals(0f, win.margin[0])
    }

    @Test
    fun selectorsMatchStructuralPseudoClassesAndAttributes() {
        val md = "<table role=\"presentation\"><tr><td width=\"0%\"></td><td>a</td><td>b</td></tr></table>\n\n" +
            "<table role=\"presentation\"><tr><td>c</td></tr></table>\n\n<table role=\"presentation\"><tr><td>d</td></tr></table>"
        val root = styled(
            md,
            "ha-markdown-element table:nth-of-type(2n+2) { height: 9px } " +
                "ha-markdown-element td[width=\"0%\"] { display: none } " +
                "ha-markdown-element td:nth-child(3) { width: 64px } " +
                "ha-markdown-element table:last-of-type td:first-child { font-size: 24px } " +
                "ha-markdown-element td:last-child, ha-markdown-element td:first-child { opacity: 0.5 }",
        )
        val tables = root.findAll("table")
        assertNull(tables[0].style!!.height)
        assertEquals(9f, tables[1].style!!.height!!.value)
        assertNull(tables[2].style!!.height)
        val tds = tables[0].findAll("td")
        assertEquals("none", tds[0].style!!.display)
        assertEquals(64f, tds[2].style!!.width!!.value)
        assertEquals(24f, tables[2].findAll("td")[0].style!!.fontSize)
        assertEquals(0.5f, tds[2].style!!.opacity)
        assertEquals(1f, tds[1].style!!.opacity)
    }

    @Test
    fun varsInheritEmsResolveAndPresentationTablesDropBorders() {
        val root = styled(
            "# <ha-icon icon=\"mdi:fog\"></ha-icon> 8° <small>Fog</small>\n\n<table role=\"presentation\"><tr><td>x</td></tr></table>",
            "ha-markdown-element h1 { font-size: 48px; --mdc-icon-size: 40px } ha-markdown-element h1 small { font-size: 24px; opacity: 0.65 } " +
                "ha-markdown-element { letter-spacing: 0.1em; text-transform: uppercase }",
        )
        val h1 = root.find("h1")!!
        assertEquals(40f, h1.find("ha-icon")!!.style!!.width!!.value)
        assertEquals(24f, h1.find("small")!!.style!!.fontSize)
        // letter-spacing in em computes to px on the element that sets it, then inherits as px.
        assertEquals(1.4f, h1.style!!.letterSpacing, 1e-4f)
        assertEquals("uppercase", h1.style!!.textTransform)
        val td = root.find("td")!!.style!!
        assertEquals(0f, td.borderWidth[0])
        assertEquals(0f, td.padding[0])
        assertEquals(2f, root.find("table")!!.style!!.borderSpacingH)
        // A pipe table gets 1px borders and 0.25em 0.5em padding.
        val pipe = styled("| A |\n|---|\n| 1 |").find("td")!!.style!!
        assertEquals(1f, pipe.borderWidth[0])
        assertEquals(7f, pipe.padding[3], 1e-4f)
    }

    // ---- layout ----

    @Test
    fun adjacentMarginsCollapseLikeCss() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "## 11:39\n\n---\n\n<ha-icon icon=\"mdi:weather-fog\"></ha-icon><br>9°\n\n<font color=\"#34d399\">●</font> Air 33",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element h2 { margin: 0 } ha-markdown-element p { margin: 10px 0 } ha-markdown-element hr { margin: 12px 8px }"),
        )
        val lps = (0 until into.childCount).map { into.getChildAt(it).layoutParams as LinearLayout.LayoutParams }
        val dp = activity.resources.displayMetrics.density
        // h2, hr, p, p: the hr's 12 beats the paragraphs' 10; two paragraphs share one 10.
        assertEquals(listOf(0, 12, 12, 10).map { (it * dp).toInt() }, lps.map { it.topMargin })
        assertEquals((8 * dp).toInt(), lps[1].leftMargin)
        assertEquals(MarkdownRenderer.RULE_TAG, into.getChildAt(1).tag)
        val icons = ((into.getChildAt(2) as ViewGroup).getChildAt(0) as TextView).text as Spanned
        assertEquals("mdi:weather-fog", icons.getSpans(0, icons.length, MdIconSpan::class.java).single().iconName)
    }

    @Test
    fun fixedTableLayoutSplitsPercentagesAfterSpacing() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "<table role=\"presentation\"><tr><td width=\"25%\"></td><td width=\"75%\"></td></tr></table>",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element table { width: 100%; height: 10px; border-spacing: 4px 0; table-layout: fixed } " +
                "ha-markdown-element td:nth-child(1) { background: #a78bfa }"),
        )
        measure(into, 408)
        val table = into.getChildAt(0) as MdTableView
        assertEquals(408, table.width)
        assertEquals(10, table.height)
        val cells = (0 until table.childCount).map { table.getChildAt(it) }
        // 408 - 3 x 4 spacing = 396: 99 and 297.
        assertEquals(4, cells[0].left)
        assertEquals(99, cells[0].width)
        assertEquals(107, cells[1].left)
        assertEquals(297, cells[1].width)
        assertNotNull(cells[0].background)
    }

    @Test
    fun autoTableLayoutGivesFixedColumnsTheirWidthAndAlignsCells() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "<table role=\"presentation\"><tr><td>Living Room</td><td align=\"right\">20.9°</td><td align=\"right\">49%</td></tr></table>",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element table { width: 100% } ha-markdown-element td:nth-child(3) { width: 64px }"),
        )
        measure(into, 500)
        val table = into.getChildAt(0) as MdTableView
        val cells = (0 until table.childCount).map { table.getChildAt(it) as MdBox }
        val dp = activity.resources.displayMetrics.density
        assertEquals((64 * dp).toInt(), cells[2].width)
        assertTrue(kotlin.math.abs(500 - 4 * 2 - cells.sumOf { it.width }) <= 2)
        val tv = cells[1].getChildAt(0) as TextView
        assertEquals(Gravity.END, tv.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
    }

    // ---- the card ----

    @Test
    fun cardVarsAndHaMarkdownPaddingComeFromTheCardSheet() {
        val view = MarkdownCardView(
            context(),
            card(
                """{"type": "markdown", "content": "## Hi", "card_mod": {"style": {
                    ".": "ha-card { --card-text-align: right; } ha-card ha-markdown { padding: 0; }",
                    "ha-markdown$": "ha-markdown-element h2 { font-size: 30px; }"}}}""",
            ),
        )
        view.applyCardMod(card("""{"type": "markdown", "card_mod": {"style": {".": "ha-card { --card-text-align: right; } ha-card ha-markdown { padding: 0; }"}}}"""))
        val host = view.body.parent as LinearLayout
        assertEquals(0, host.paddingLeft)
        val tv = view.body.all().filterIsInstance<TextView>().single()
        assertEquals("Hi", tv.text.toString())
        assertEquals(Gravity.END, tv.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(30f * activity.resources.displayMetrics.density, tv.textSize, 0.5f)
    }

    @Test
    fun textOnlyCardsPadTwoByFourAndDropTheirSurface() {
        val view = MarkdownCardView(context(), card("""{"type": "markdown", "text_only": true, "content": "Hello"}"""))
        val host = view.body.parent as LinearLayout
        val dp = activity.resources.displayMetrics.density
        assertEquals((4 * dp).toInt(), host.paddingLeft)
        assertEquals((2 * dp).toInt(), host.paddingTop)
        assertNull(view.background)
        val plain = MarkdownCardView(context(), card("""{"type": "markdown", "content": "Hello"}"""))
        assertEquals((16 * dp).toInt(), (plain.body.parent as LinearLayout).paddingLeft)
    }

    // ---- SVG data URIs ----

    @Test
    fun svgDataUrisParseShapesAndViewBox() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 156 156'><circle cx='78' cy='78' r='66' fill='none' stroke='#34d399' " +
            "stroke-width='9' pathLength='100' stroke-dasharray='90 100' transform='rotate(-90 78 78)'/><path d='M 30 110 Q 440 -40 850 110'/>" +
            "<text x='78' y='99' text-anchor='middle'>90</text></svg>"
        val parsed = MdSvg.fromDataUri("data:image/svg+xml," + java.net.URLEncoder.encode(svg, "UTF-8").replace("+", "%20"))
        assertNotNull(parsed)
        assertEquals(156f, parsed!!.viewBox.width())
        assertEquals(1f, parsed.ratio)
        assertEquals("a+b c", MdSvg.percentDecode("a+b%20c"))
        // Drawing must not throw.
        val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        parsed.draw(android.graphics.Canvas(bmp), 100f, 100f, activity)
    }

    @Test
    fun svgPathsEndAtAnOperandAfterClosepathAndKeepSupplementaryCharacters() {
        // `Z 5` used to loop forever on the UI thread.
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><path d='M0 0 L5 5 Z 5 L 9 9'/></svg>"
        assertNotNull(MdSvg.parse(svg))
        assertEquals("a\uD83D\uDE00 b", MdSvg.percentDecode("a\uD83D\uDE00%20b"))
    }

    @Test
    fun markdownKeepsParenthesesInImageDestinationsAndIndentedFencesInCode() {
        assertTrue(MarkedLite.toHtml("![Plan](/local/plan(v2).png)").contains("src=\"/local/plan(v2).png\""))
        // A `<…>` destination takes its parentheses as they are, balanced or not.
        assertTrue(MarkedLite.toHtml("![Plan](</local/plan(v2.png>)").contains("src=\"/local/plan(v2.png\""))
        val html = MarkedLite.toHtml("```\ncode\n    ```\nstill code\n```")
        assertTrue(html, html.contains("still code"))
        assertTrue(html, html.indexOf("still code") < html.indexOf("</code>"))
    }

    @Test
    fun outOfRangeNthNumbersDropTheSelectorNotTheSheet() {
        val root = styled(
            "<p>a</p>",
            "ha-markdown-element p:nth-child(2147483648n+1) { opacity: 0.1 } ha-markdown-element p { opacity: 0.5 }",
        )
        assertEquals(0.5f, root.findAll("p")[0].style!!.opacity)
    }

    // ---- sections grid ----

    @Test
    fun haGridWrapsSpansStretchesRowsAndSkipsGoneChildren() {
        val grid = HaGridLayout(activity, 12, 12, 8, stretch = true)
        fun box(h: Int) = object : View(activity) {
            override fun onMeasure(w: Int, hs: Int) = setMeasuredDimension(MeasureSpec.getSize(w), if (MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY) MeasureSpec.getSize(hs) else h)
        }
        val a = box(100).also { grid.addView(it, HaGridLayout.LayoutParams(4)) }
        val b = box(60).also { grid.addView(it, HaGridLayout.LayoutParams(8)) }
        val gone = box(50).also { grid.addView(it, HaGridLayout.LayoutParams(12)); it.visibility = View.GONE }
        val c = box(30).also { grid.addView(it, HaGridLayout.LayoutParams(12, fixedHeight = 124)) }
        measure(grid, 672 + 0)
        // (672 - 11 x 12) / 12 = 45 per column: 4 columns = 216, then 12 gap, then 8 columns = 444.
        assertEquals(216, a.width)
        assertEquals(228, b.left)
        assertEquals(444, b.width)
        assertEquals(100, b.height) // stretched to the row
        assertEquals(108, c.top) // the GONE card leaves no gap
        assertEquals(124, c.height)
        assertEquals(0, gone.width)
    }

    // ---- clock ----

    @Test
    fun clockReadsHaVariablesAndCardModSheets() {
        val clock = ClockCardView(
            context(JSONObject().put("time_zone", "UTC")),
            card(
                """{"type": "clock", "clock_size": "large", "time_format": "12", "time_zone": "America/Los_Angeles", "no_background": true,
                    "card_mod": {"style": {
                      ".": "ha-card { --ha-clock-card-digital-font-size-large: 136px; --ha-font-weight-medium: 300; } ha-card .time-wrapper.size-large { align-items: flex-start; }",
                      "hui-clock-card-digital$": "div.clock-container { justify-content: flex-start; }"}}}""",
            ),
        )
        assertEquals(136f, clock.digitsPx)
        assertEquals(300, clock.digitsWeight)
        assertEquals(Gravity.START, clock.horizontalGravity)
        assertNull(clock.background)
        val instant = java.util.Date(1_795_496_000_000L)
        clock.renderAt(instant)
        val la = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles") }
        assertEquals(la.format(instant), clock.renderedTime)
        // Without card-mod the digits are centered at HA's large default.
        val plain = ClockCardView(context(), card("""{"type": "clock", "clock_size": "large"}"""))
        assertEquals(64f, plain.digitsPx)
        assertEquals(Gravity.CENTER_HORIZONTAL, plain.horizontalGravity)
    }

    // ---- stack-in-card ----

    @Test
    fun stackInCardPadsOnlyWithKeepMarginAndUsesTheStackGap() {
        val build: (CardConfig) -> View = { View(activity) }
        val dp = activity.resources.displayMetrics.density
        val margin = StackInCardView(
            context(),
            card("""{"type": "custom:stack-in-card", "keep": {"margin": true, "background": true}, "cards": [{"type": "x"}, {"type": "y"}]}"""),
            build,
        )
        val inner = margin.children[0].parent as LinearLayout
        assertEquals((8 * dp).toInt(), inner.paddingTop)
        assertEquals((8 * dp).toInt(), (margin.children[1].layoutParams as LinearLayout.LayoutParams).topMargin)
        val gapVar = StackInCardView(
            context(),
            card("""{"type": "custom:stack-in-card", "keep": {"background": true}, "card_mod": {"style": "ha-card { --vertical-stack-card-gap: 12px; }"}, "cards": [{"type": "x"}, {"type": "y"}]}"""),
            build,
        )
        assertEquals(0, (gapVar.children[0].parent as LinearLayout).paddingTop)
        assertEquals((12 * dp).toInt(), (gapVar.children[1].layoutParams as LinearLayout.LayoutParams).topMargin)
        assertFalse(gapVar.children.isEmpty())
    }
}
