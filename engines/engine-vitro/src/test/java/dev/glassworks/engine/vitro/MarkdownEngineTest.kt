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
    fun markedReadsIndentedCodeEscapedDestinationsAndWholeBacktickRuns() {
        // Four spaces at a block's start: literal code, its indentation off; not inside a paragraph.
        assertEquals("<pre><code>**literal**\n</code></pre>\n", MarkedLite.toHtml("    **literal**"))
        assertTrue(MarkedLite.toHtml("text\n    **bold**").contains("<strong>bold</strong>"))
        // Backslash-escaped parentheses in a destination reach the loader as plain ones.
        assertTrue(MarkedLite.toHtml("![Plan](/local/plan\\(v2\\).png)").contains("src=\"/local/plan(v2).png\""))
        // `` x ````` y `` : the run of five isn't a closer (nor part of one); the last two ticks are.
        assertTrue(MarkedLite.toHtml("``a`````b``").contains("<code>a`````b</code>"))
    }

    @Test
    fun aDoubleCombinatorIsInvalidAndAnInvalidVariableTakesItsFallback() {
        assertNull(dev.glassworks.engine.vitro.cards.CssSelector.parse("div > > p"))
        assertNotNull(dev.glassworks.engine.vitro.cards.CssSelector.parse("div  >  p"))
        // `--size` needs `--missing`: var(--size, 24px) takes 24px; an empty but valid value stays empty.
        assertEquals("24px", MdCascade.resolveVars("var(--size, 24px)", mapOf("size" to "var(--missing)"), emptyMap()))
        assertEquals("", MdCascade.resolveVars("var(--empty, 24px)", mapOf("empty" to ""), emptyMap()))
        // A cycle makes the property invalid: the fallback outside it wins, not one inside the cycle.
        assertEquals("24px", MdCascade.resolveVars("var(--size, 24px)", emptyMap(), mapOf("size" to "var(--size, 12px)")))
        assertEquals("24px", MdCascade.resolveVars("var(--a, 24px)", mapOf("a" to "var(--b, 1px)", "b" to "var(--a, 2px)"), emptyMap()))
    }

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

    @Test
    fun revertRollsBackToTheBrowsersValue() {
        // HA's `:is(ol, ul) { padding-inline-start: var(--markdown-list-indent, revert) }`: with no indent set, the
        // list keeps the browser's 40px; the card's variable sets it.
        assertEquals(40f, styled("- a\n- b").find("ul")!!.style!!.padding[3])
        assertEquals(12f, styled("- a\n- b", "ha-markdown-element { --markdown-list-indent: 12px }").find("ul")!!.style!!.padding[3])
        // Outside the browser's sheet, `revert` of a property the browser leaves alone is `unset`.
        assertEquals(0f, styled("x", "ha-markdown-element p { padding-left: revert }").find("p")!!.style!!.padding[3])
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
    fun visibilityHiddenKeepsTheRoomAndPaintsNothing() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "first\n\nsecond\n\n[go](/lovelace/wall)",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element p:first-child { visibility: hidden; background: #ff0000 }"),
        )
        val texts = into.all().filterIsInstance<TextView>()
        val hidden = texts.first { it.text.toString() == "first" }
        val spans = (hidden.text as Spanned).getSpans(0, hidden.text.length, android.text.style.ForegroundColorSpan::class.java)
        assertTrue(spans.isNotEmpty() && spans.all { android.graphics.Color.alpha(it.foregroundColor) == 0 })
        assertEquals(View.VISIBLE, hidden.visibility) // still in the layout
        val shown = texts.first { it.text.toString() == "second" }
        assertTrue((shown.text as Spanned).getSpans(0, 6, android.text.style.ForegroundColorSpan::class.java).any { android.graphics.Color.alpha(it.foregroundColor) > 0 })
    }

    @Test
    fun aDashboardLinkInMarkdownNavigates() {
        val into = LinearLayout(activity)
        val went = ArrayList<String>()
        val base = context()
        val ctx = VitroCardContext(
            context = activity, haBase = base.haBase, store = base.store, theme = base.theme, resources = emptyList(),
            accessToken = { null }, callService = { _, _, _, _ -> }, navigate = { went += it }, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        MarkdownRenderer(ctx).render("[Wall](/lovelace/wall) and [docs](https://example.com)", into, MarkdownRenderer(ctx).defaultRootStyle(), MdSheet.parse(""))
        val tv = into.all().filterIsInstance<TextView>().single()
        val links = (tv.text as Spanned).getSpans(0, tv.text.length, dev.glassworks.engine.vitro.cards.MdLinkSpan::class.java)
        // A hidden part of a link takes no tap; a part that is visible again does.
        val hiddenInto = LinearLayout(activity)
        MarkdownRenderer(ctx).render(
            "<a href=\"/lovelace/x\">gone <b>back</b></a>", hiddenInto, MarkdownRenderer(ctx).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element a { visibility: hidden } ha-markdown-element b { visibility: visible }"),
        )
        val htv = hiddenInto.all().filterIsInstance<TextView>().single()
        val hs = htv.text as Spanned
        val linked = hs.getSpans(0, hs.length, dev.glassworks.engine.vitro.cards.MdLinkSpan::class.java)
            .joinToString("") { hs.subSequence(hs.getSpanStart(it), hs.getSpanEnd(it)).toString() }
        assertEquals("back", linked.trim())
        // Only the dashboard path is a link; the external one stays text.
        assertEquals(listOf("/lovelace/wall"), links.map { it.href })
        links.single().onClick(tv)
        assertEquals(listOf("/lovelace/wall"), went)
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
    fun aSpanningCellGetsItsWidthAcrossItsColumns() {
        // One cell over two empty-width columns: its text needs room, which the two columns now give it.
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "<table><tr><td colspan=\"2\">A fairly long label</td></tr><tr><td></td><td></td></tr></table>",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse(""),
        )
        measure(into, 1000)
        val table = into.getChildAt(0) as MdTableView
        val spanning = table.getChildAt(0)
        spanning.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("${spanning.width} < ${spanning.measuredWidth}", spanning.width >= spanning.measuredWidth - 2)
    }

    @Test
    fun anInlineImageTakesTheOpacityAroundIt() {
        val into = LinearLayout(activity)
        MarkdownRenderer(context()).render(
            "<span>x <img src=\"/local/a.png\" width=\"10\" height=\"10\"></span>",
            into,
            MarkdownRenderer(context()).defaultRootStyle(),
            MdSheet.parse("ha-markdown-element span { opacity: 0.5 }"),
        )
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val span = all(into).filterIsInstance<TextView>().mapNotNull { tv -> (tv.text as? android.text.Spanned)?.let { it.getSpans(0, it.length, dev.glassworks.engine.vitro.cards.MdImageSpan::class.java).singleOrNull() } }.single()
        assertEquals(0.5f, span.opacity, 0.01f)
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
    fun bulletsArePaintedSymbolsHangingBeforeTheText() {
        // Chromium paints disc / circle / square as shapes sized from the font's ascent, hanging `2a/3 + 8` px
        // before the item; ordered lists keep their number text.
        val view = MarkdownCardView(context(), card("""{"type": "markdown", "content": "- a\n  - b\n\n1. c"}"""))
        val symbols = view.body.all().filterIsInstance<dev.glassworks.engine.vitro.cards.MdListSymbol>()
        assertEquals(2, symbols.size)
        val dp = activity.resources.displayMetrics.density
        val ascent = -android.graphics.Paint().apply { textSize = 14f; typeface = android.graphics.Typeface.DEFAULT }.fontMetrics.ascent
        assertEquals((((Math.round(ascent) * 2 / 3) + 8) * dp).toInt().toFloat(), symbols[0].boxWidth.toFloat(), 1f)
        assertTrue(view.body.all().filterIsInstance<TextView>().any { it.text.toString() == "1." })
        assertTrue(view.body.all().filterIsInstance<TextView>().none { it.text.toString() == "•" })
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
        // A quoted title's parentheses don't count either.
        assertTrue(MarkedLite.toHtml("![Plan](/local/plan.png \"Floor (draft\")").contains("src=\"/local/plan.png\""))
        assertTrue(MarkedLite.toHtml("![Plan](/local/plan.png \"Floor \\\" (draft\")").contains("src=\"/local/plan.png\""))
        // A `<…>` destination takes its parentheses as they are, balanced or not.
        assertTrue(MarkedLite.toHtml("![Plan](</local/plan(v2.png>)").contains("src=\"/local/plan(v2.png\""))
        val html = MarkedLite.toHtml("```\ncode\n    ```\nstill code\n```")
        assertTrue(html, html.contains("still code"))
        assertTrue(html, html.indexOf("still code") < html.indexOf("</code>"))
    }

    @Test
    fun aFenceIndentedTwoSpacesTakesTwoSpacesOffItsLines() {
        val html = MarkedLite.toHtml("  ```\n  two\n    four\n one\n  ```")
        assertTrue(html, html.contains("<code>two\n  four\none\n</code>"))
    }

    @Test
    fun aBorderStyleAloneDrawsAMediumBorderInTheTextColor() {
        val root = styled("<p>a</p>", "ha-markdown-element p { border-style: solid; color: #ff0000 }")
        val p = root.findAll("p")[0].style!!
        assertEquals(3f, p.borderWidth[0], 0f)
        assertEquals(p.color, p.borderColor[0])
        // An explicit zero width stays zero.
        val zero = styled("<p>a</p>", "ha-markdown-element p { border-style: solid; border-width: 0 }").findAll("p")[0].style!!
        assertEquals(0f, zero.borderWidth[0], 0f)
    }

    @Test
    fun anInvalidBorderColorResetsToTheTextColor() {
        // HA's `hr { border-color: var(--divider-color) }` with no such variable: the browser draws currentColor.
        val root = styled("<p>a</p>\n\n<hr>", "ha-markdown-element hr { border-color: var(--no-such-color); color: #ff0000 }")
        val hr = root.findAll("hr")[0].style!!
        assertEquals(hr.color, hr.borderColor[0])
        assertEquals(hr.color, hr.borderColor[2])
    }

    @Test
    fun outOfRangeNthNumbersDropTheSelectorNotTheSheet() {
        val root = styled(
            "<p>a</p>",
            "ha-markdown-element p:nth-child(2147483648n+1) { opacity: 0.1 } ha-markdown-element p { opacity: 0.5 }",
        )
        assertEquals(0.5f, root.findAll("p")[0].style!!.opacity)
    }

    @Test
    fun horizontalStackInCardLaysOutMarkdownThatArrivesAfterTheFirstPass() {
        // The tablet's Home card: a horizontal stack-in-card with a text-only Markdown (templated) and a chips card.
        val templates = ArrayList<(String) -> Unit>()
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, haConfig = { JSONObject() }, callService = { _, _, _, _ -> },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> },
            subscribeTemplate = { _, cb -> templates += cb; {} },
        )
        val cfg = card(
            """{"type": "custom:stack-in-card", "mode": "horizontal", "card_mod": {"style": "ha-card { height: 100%; box-sizing: border-box; }"},
               "cards": [
                 {"type": "markdown", "text_only": true, "content": "###### Home\n## {{ 'All lights off' }}",
                  "card_mod": {"style": {".": "ha-card { padding: 30px 20px 0; } ha-card ha-markdown { padding: 0; }",
                    "ha-markdown$": "ha-markdown-element h2 { font-size: 24px; font-weight: 500; line-height: 1.5; margin: 2px 0 0; }"}}},
                 {"type": "custom:mushroom-chips-card", "alignment": "end", "chips": [{"type": "template", "content": "All off"}]}]}""",
        )
        val stack = StackInCardView(ctx, cfg, { child: CardConfig ->
            if (child.type == "markdown") MarkdownCardView(ctx, child).also { it.applyCardMod(child) }
            else dev.glassworks.engine.vitro.cards.MushroomChipsCardView(ctx, child)
        })
        stack.applyCardMod(cfg)
        val host = android.widget.FrameLayout(activity)
        host.addView(stack, android.widget.FrameLayout.LayoutParams(400, 120))
        activity.setContentView(host)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val md = stack.children[0] as MarkdownCardView
        // An ancestor still holding a layout request from an earlier pass: requestLayout stops there.
        (md.parent as View).forceLayout()
        templates.forEach { it("###### Home\n## All lights off") }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val text = md.body.all().filterIsInstance<TextView>().first { it.text.toString() == "All lights off" }
        assertTrue("the Markdown body is laid out: ${md.body.height}", md.body.height > 0)
        assertTrue("its text has a size: ${text.width}x${text.height}", text.width > 0 && text.height > 0)
    }

    @Test
    fun lineBoxesSplitTheirLeadingAsBlinkDoes() {
        // HA's large clock in Chrome, Roboto (ascent 0.928 em, descent 0.244), at density 1.5 and 2: the 64 px digits'
        // 51.2 px line puts its text 12 px above the line (half of -23.8, floored) with the ascent rounded to 59; the
        // 24 px AM's 21.6 px line 4 px above (-3.2 floored), ascent 22.
        val span = dev.glassworks.engine.vitro.cards.CssLineBoxSpan
        for (dp in listOf(1.5f, 2f)) {
            assertEquals(47f * dp, span.above(59.375f * dp, 15.625f * dp, 51.2f * dp, dp), 1e-3f)
            assertEquals(18f * dp, span.above(22.27f * dp, 5.86f * dp, 21.6f * dp, dp), 1e-3f)
            // `normal` is the rounded ascent and descent: 28 px for 24 px Roboto, so no leading at all.
            assertEquals(28f * dp, span.normalLineHeight(22.27f * dp, 5.86f * dp, 0f, dp), 1e-3f)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real font metrics
    fun normalLineHeightIsTheFontsOwn() {
        // `line-height: normal` (HA's headings) is the drawn font's ascent + descent: Inter's 1.21 em, not Roboto's 1.17.
        val ctx = context()
        val cfg = card(
            """{"type": "markdown", "content": "# Hi\nplain",
               "card_mod": {"style": {"ha-markdown$": "ha-markdown-element h1 { font-family: Inter; font-size: 40px; }"}}}""",
        )
        val md = MarkdownCardView(ctx, cfg).also { it.applyCardMod(cfg) }
        val host = android.widget.FrameLayout(activity)
        host.addView(md, android.widget.FrameLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(host)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val h1 = md.body.all().filterIsInstance<TextView>().first { it.text.toString() == "Hi" }
        // The h1's font as Markdown draws it (bold, from the card's sheet), at its size.
        val paint = android.text.TextPaint().apply {
            typeface = dev.glassworks.engine.vitro.cards.MdFonts.typeface(activity, 700, false, "Inter", 40f)
            textSize = 40f * ctx.dp
        }
        val fm = paint.fontMetrics
        val own = fm.descent - fm.ascent + fm.leading
        assertTrue("Inter's normal line is taller than Roboto's: $own", own > 40f * ctx.dp * 1.19f)
        // Its ascent and descent each rounded to whole CSS px, as Blink takes them.
        val dp = ctx.dp
        assertEquals(Math.round((Math.round(-fm.ascent / dp) + Math.round(fm.descent / dp) + Math.round(fm.leading / dp)) * dp), h1.height)
    }

    @Test
    fun relativePositionNudgesWithoutMovingTheLine() {
        val icon = styled("##### <ha-icon icon=\"mdi:weather-fog\"></ha-icon> 9°", "ha-markdown-element h5 ha-icon { position: relative; top: -2px; }")
            .find("ha-icon")!!.style!!
        assertEquals(-2f, icon.relativeShiftY())
        val static = styled("<p><ha-icon icon=\"mdi:home\"></ha-icon></p>", "ha-markdown-element ha-icon { top: 5px; }").find("ha-icon")!!.style!!
        assertEquals(0f, static.relativeShiftY()) // `top` alone does nothing without position: relative
    }

    @Test
    fun svgArcsDrawAsArcsNotLines() {
        // A half circle from (0,50) to (100,50), sweeping through the top; flags written together as "01".
        val arc = MdSvg.pathData("M0 50 A50 50 0 01100 50")
        val bounds = android.graphics.RectF()
        @Suppress("DEPRECATION") arc.computeBounds(bounds, true)
        assertEquals(0f, bounds.top, 0.5f)
        assertEquals(100f, bounds.right, 0.5f)
        val length = android.graphics.PathMeasure(arc, false).length
        assertEquals((Math.PI * 50).toFloat(), length, 1.5f)
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

    @Test
    fun haGridGapsShrinkWhenTheyOutgrowTheContent() {
        // 12 columns with 8 px gaps in 72 px: the gaps alone would need 88. Two halves still end inside 72.
        val grid = HaGridLayout(activity, 12, 8, 8, stretch = false)
        val left = View(activity).also { grid.addView(it, HaGridLayout.LayoutParams(6)) }
        val right = View(activity).also { grid.addView(it, HaGridLayout.LayoutParams(6)) }
        measure(grid, 72)
        assertTrue("${right.right}", right.right <= 72)
        assertTrue(left.right <= right.left)
    }

    // ---- clock ----

    @Test
    fun aHiddenClockStopsTicking() {
        val clock = ClockCardView(context(), card("""{"type": "clock", "show_seconds": true}"""))
        val holder = android.widget.FrameLayout(activity).apply { addView(clock) }
        activity.setContentView(holder)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        assertTrue("ticking while shown", looper.nextScheduledTaskTime.toMillis() > 0)
        // A cached tab behind another: nothing left scheduled.
        holder.visibility = View.GONE
        looper.idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(java.time.Duration.ZERO, looper.nextScheduledTaskTime)
        // Shown again: ticking resumes.
        holder.visibility = View.VISIBLE
        assertTrue(looper.nextScheduledTaskTime.toMillis() > 0)
    }

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

    @Test
    fun hostAndHasSelectorsMatchAsInTheBrowser() {
        val CssSelector = dev.glassworks.engine.vitro.cards.CssSelector
        val host = MdNode("hui-markdown-card", mapOf(CssSelector.HOST_ATTR to ""))
        val card = host.add(MdNode("ha-card", mapOf("class" to "with-header")))
        val md = card.add(MdNode("ha-markdown"))
        val el = md.add(MdNode("ha-markdown-element"))
        val h3 = el.add(MdNode("h3"))
        val p1 = el.add(MdNode("p")); val strong = p1.add(MdNode("strong"))
        val p2 = el.add(MdNode("p"))
        fun m(sel: String, n: MdNode) = CssSelector.parse(sel)!!.matches(n)
        // :host is the sheet's host element; :host(<compound>) also tests it; `:host x` reaches inside.
        assertTrue(m(":host", host)); assertFalse(m(":host", card))
        assertTrue(m(":host ha-card", card)); assertTrue(m(":host > ha-card.with-header", card))
        assertFalse(m(":host(.other)", host))
        // :has(): a descendant by default, or as its combinator says; scoped to the subject.
        assertTrue(m("ha-card:has(ha-markdown)", card)); assertFalse(m("ha-markdown:has(ha-card)", md))
        assertTrue(m("ha-markdown-element:has(h3) h3", h3))
        assertTrue(m("p:has(strong)", p1)); assertFalse(m("p:has(strong)", p2))
        assertTrue(m("p:has(> strong)", p1)); assertFalse(m("ha-markdown-element:has(> strong)", el))
        assertTrue(m("h3:has(+ p)", h3)); assertTrue(m("h3:has(~ p strong)", h3)); assertFalse(m("p:has(+ h3)", p1))
        assertTrue(m("ha-card:has(h3, video)", card)); assertFalse(m("ha-card:has(video)", card))
        // Specificity: :has() as its most specific argument; :host a pseudo-class plus its argument.
        assertEquals(CssSelector.parse("p strong")!!.specificity + 1, CssSelector.parse("p:has(p strong)")!!.specificity)
        assertEquals(200, CssSelector.parse(":host(.x)")!!.specificity)
        // A card with `:host { --card-text-align: center }` centers its Markdown.
        val view = MarkdownCardView(context(), card("""{"type": "markdown", "content": "Hi",
            "card_mod": {"style": {".": ":host { --card-text-align: center; }"}}}"""))
        view.applyCardMod(card("""{"type": "markdown", "card_mod": {"style": {".": ":host { --card-text-align: center; }"}}}"""))
        assertEquals(Gravity.CENTER_HORIZONTAL, view.body.all().filterIsInstance<TextView>().single().gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertTrue(strong.parent === p1)
    }

    @Test
    fun anImageWithNoSizeTakesItsNaturalSize() {
        // One CSS px per image pixel, at most the line's width (`max-width: 100%`) with the height in proportion; its line
        // box grows with it.
        val picture = dev.glassworks.engine.vitro.cards.MdPicture(null).apply {
            bitmap = android.graphics.Bitmap.createBitmap(192, 96, android.graphics.Bitmap.Config.ARGB_8888)
        }
        val run = dev.glassworks.engine.vitro.cards.CssLineBoxSpan.Run(0, 1, 0f, 0f)
        val span = dev.glassworks.engine.vitro.cards.MdImageSpan("/x.png", 0, 0, 0f, false, picture, activity).apply { natural = true; this.run = run }
        assertTrue(span.applyNaturalSize(2f, 0))
        assertEquals(384, span.w); assertEquals(192, span.h); assertEquals(192f, run.above)
        assertTrue(span.applyNaturalSize(2f, 200))
        assertEquals(200, span.w); assertEquals(100, span.h); assertEquals(100f, run.above)
        assertFalse("nothing to lay out again", span.applyNaturalSize(2f, 200))
    }

    @Test
    fun clockDateFormatFollowsHa() {
        val monday = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US).apply {
            clear(); set(2026, java.util.Calendar.NOVEMBER, 23, 10, 8)
        }.time
        val utc = java.util.TimeZone.getTimeZone("UTC")
        fun fmt(v: Any?) = dev.glassworks.engine.vitro.cards.ClockDate.of(v)?.format(monday, "en", utc)
        // Values get one space between them; separators are written as they are; a new line starts another line.
        assertEquals("Monday November 23", fmt(org.json.JSONArray(listOf("weekday-long", "month-long", "day-numeric"))))
        assertEquals("23/11/2026", fmt(org.json.JSONArray(listOf("day-2-digit", "separator-slash", "month-2-digit", "separator-slash", "year-numeric"))))
        assertEquals("Mon, Nov 23\n26", fmt(org.json.JSONArray(listOf("weekday-short", "separator-comma", "month-short", "day-numeric", "separator-new-line", "year-2-digit"))))
        assertEquals("2026-11-23", fmt("iso"))
        assertEquals("Monday, November 23, 2026", fmt("full"))
        // No date: none, an empty list, an unknown preset or only unknown parts.
        assertNull(fmt(null)); assertNull(fmt(org.json.JSONArray())); assertNull(fmt("fancy")); assertNull(fmt(org.json.JSONArray(listOf("nope"))))

        val clock = ClockCardView(context(JSONObject().put("time_zone", "UTC")), card(
            """{"type": "clock", "clock_size": "large", "time_format": "12", "date_format": ["weekday-long", "month-long", "day-numeric"]}"""))
        clock.renderAt(monday)
        assertEquals(java.text.SimpleDateFormat("EEEE MMMM d", java.util.Locale.getDefault()).apply { timeZone = utc }.format(monday), clock.renderedDate)
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
