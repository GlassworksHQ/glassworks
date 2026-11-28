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
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.ModernCircularGaugeCardView
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
import org.junit.Assert.assertNull
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

    // ---- Markdown stacked bars ----

    // ---- Clock ----

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
        assertEquals("1,150", view.ring.valueText)
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
        // Neither matches its card yet (a `gap` in native-options.json): the island draws them when a Lovelace resource
        // provides their script, and Vitro's fast path only when none does.
        for (t in listOf("custom:modern-circular-gauge", "custom:bar-card")) {
            assertEquals("$t is Tier 2", CardTier.NATIVE_HACS, CardRouter.tier(card("""{"type": "$t"}"""), dev.glassworks.engine.vitro.compat.IslandScripts.of(emptyList())))
            assertEquals("$t with its script", CardTier.WEB_ISLAND, CardRouter.tier(card("""{"type": "$t"}""")))
        }
        assertEquals(30, CardRouter.NATIVE_HACS_TYPES.size)
    }

    // ---- Mirror polish: clock zone and alignment, icons, markdown images, chip card-mod, to-do limits ----

    @Test
    fun clockFormatsInHomeAssistantsTimeZoneWhenTheUserPicksIt() {
        val prev = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
        // HA 2026.11's clock with no `time_zone` of its own uses the user's choice (resolveTimeZone): here HA's.
        dev.glassworks.engine.vitro.data.HaLocale.set(dev.glassworks.engine.vitro.data.FrontendLocale(timeZone = "server"), notify = false)
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
            dev.glassworks.engine.vitro.data.HaLocale.reset()
        }
    }

    @Test
    fun theClockDateUsesTheSameZoneAsItsTime() {
        val prev = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
        dev.glassworks.engine.vitro.data.HaLocale.set(dev.glassworks.engine.vitro.data.FrontendLocale(timeZone = "server"), notify = false)
        try {
            // 20:00 UTC on a Monday is already Tuesday in Tokyo: the date must say so, from the very first render.
            val instant = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse("2026-11-23 20:00")!!
            val tokyo = ClockCardView(
                context(haConfig = JSONObject().put("time_zone", "Asia/Tokyo")),
                card("""{"type": "clock", "time_format": "24", "date_format": ["weekday-long"]}"""),
            )
            tokyo.renderAt(instant)
            val weekday = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo") }.format(instant)
            assertTrue("${tokyo.renderedDate} is $weekday", tokyo.renderedDate.contains(weekday))
        } finally {
            java.util.TimeZone.setDefault(prev)
            dev.glassworks.engine.vitro.data.HaLocale.reset()
        }
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
            assertTrue("mdi:$n draws inside 24x24: $bounds", bounds.width() > 1f && bounds.left >= -0.5f && bounds.top >= -0.5f && bounds.right <= 24.5f && bounds.bottom <= 24.5f)
        }
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

        // HA offers the add row only on a list that can create items (CREATE_TODO_ITEM = 1).
        val store = EntityStore()
        store.putLocal(EntityState("todo.shopping", "0", mapOf("supported_features" to 1)))
        val open = dev.glassworks.engine.vitro.cards.TodoListCardView(context(store), card("""{"type": "todo-list", "entity": "todo.shopping"}"""))
        activity.setContentView(open)
        assertTrue(open.addRowVisible)
        open.setItemsForTest(items)
        assertEquals(5, open.activeRowCount)
        store.putLocal(EntityState("todo.shopping", "0", mapOf("supported_features" to 4)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(open.addRowVisible)
    }

    @Test
    fun aTodoListFollowsItsItemsNotJustTheCount() {
        // Another client renames an item: the entity's count stays, the item subscription carries the new name.
        var onItems: ((JSONObject) -> Unit)? = null
        val subscribed = ArrayList<JSONObject>()
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = EntityStore(), theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(), accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {},
            openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { msg, onEvent, _ -> subscribed += msg; onItems = onEvent; { onItems = null } },
        )
        val todo = dev.glassworks.engine.vitro.cards.TodoListCardView(ctx, card("""{"type": "todo-list", "entity": "todo.shopping"}"""))
        val root = android.widget.FrameLayout(activity)
        activity.setContentView(root)
        root.addView(todo)
        assertEquals("todo/item/subscribe", subscribed.single().getString("type"))
        onItems!!(JSONObject("""{"items": [{"uid": "1", "summary": "Oat milk", "status": "needs_action"}]}"""))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(todo.all().filterIsInstance<TextView>().any { it.text.toString() == "Oat milk" })
        // Off screen, the subscription ends.
        root.removeView(todo)
        assertNull(onItems)
    }

    @Test
    fun aTodoListWhoseSubscriptionFailsStillFollowsItsEntityAndTriesAgain() {
        // The first item subscription is refused; every later one answers with the list at once.
        var calls = 0
        val store = EntityStore().apply { putLocal(dev.glassworks.engine.vitro.data.EntityState("todo.shopping", "1", emptyMap())) }
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(), accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {},
            openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { _, onEvent, onError ->
                if (calls++ == 0) onError(null)
                else onEvent(JSONObject("""{"items": [{"uid": "1", "summary": "Item $calls", "status": "needs_action"}]}"""))
                ({})
            },
        )
        val todo = dev.glassworks.engine.vitro.cards.TodoListCardView(ctx, card("""{"type": "todo-list", "entity": "todo.shopping"}"""))
        val root = android.widget.FrameLayout(activity)
        activity.setContentView(root)
        root.addView(todo)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val afterFallback = calls
        assertTrue("refused, then read once: $calls", afterFallback >= 2)
        // While it's down, an entity change reads the list again.
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("todo.shopping", "2", emptyMap()))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(calls > afterFallback)
        // Later the subscription is asked for again.
        val beforeRetry = calls
        org.robolectric.shadows.ShadowLooper.idleMainLooper(31, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(calls > beforeRetry)
    }
}
