/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sun.net.httpserver.HttpServer
import dev.glassworks.engine.vitro.cards.CustomButtonCardView
import dev.glassworks.engine.vitro.cards.HaErrorCardView
import dev.glassworks.engine.vitro.cards.LegacyWeatherCardView
import dev.glassworks.engine.vitro.cards.SwipeCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * custom:button-card, swipe-card, weather-card and HA's error card drawn natively, at the geometry HA's frontend gives
 * them (button-card v7.0.1 measured in HA 2026.11's frontend on 2026-11-24: Chromium, 200 or 300 CSS px wide, dark
 * theme). At mdpi one dp is one CSS px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonCardViewTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<String>()

    /** weather-card's icon folder, on loopback: a unit test never reaches the card's CDN. */
    private val iconRequests = CopyOnWriteArrayList<String>()
    private val icons = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            iconRequests += ex.requestURI.path
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="4" fill="#fc0"/></svg>""".toByteArray()
            ex.responseHeaders.add("Content-Type", "image/svg+xml")
            ex.sendResponseHeaders(200, svg.size.toLong())
            ex.responseBody.use { it.write(svg) }
        }
        start()
    }

    @After
    fun stopIcons() = icons.stop(0)

    private fun ctx(admin: Boolean = false) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { d, s, data, _ -> calls += "$d.$s ${data ?: ""}" }, navigate = {},
        openMoreInfo = { calls += "more-info $it" }, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haUser = { JSONObject().put("is_admin", admin) },
    )

    private fun put(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf(*attrs)))

    private fun button(json: String, width: Int = 200): CustomButtonCardView {
        val v = CustomButtonCardView(ctx(), LovelaceParser.parseCard(JSONObject(json)))
        activity.setContentView(v, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout(v, width)
        return v
    }

    private fun layout(v: View, width: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, width, v.measuredHeight)
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** [v]'s box in [root]'s coordinates (translation included). */
    private fun box(root: View, v: View): Rect {
        var x = 0f; var y = 0f
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View
        }
        return Rect(x.toInt(), y.toInt(), (x + v.width).toInt(), (y + v.height).toInt())
    }

    private fun text(root: View, t: String) = all(root).filterIsInstance<TextView>().single { it.visibility == View.VISIBLE && it.text.toString() == t }
    private fun icon(root: View) = all(root).filterIsInstance<MdiIconView>().first { it.visibility == View.VISIBLE }

    private fun near(what: String, expected: Float, actual: Int, tol: Float = 1.5f) =
        assertTrue("$what: HA $expected, Vitro $actual", abs(expected - actual) <= tol)

    @Test
    fun theDefaultVerticalCardMatchesHa() {
        put("light.desk_lamp", "on", "friendly_name" to "Desk lamp", "brightness" to 180)
        val card = button("""{"type": "custom:button-card", "entity": "light.desk_lamp"}""")
        // HA: card 200 x 117.19; icon 79.19 square at y 9; name 20 tall at y 88.19.
        near("height", 117.19f, card.measuredHeight)
        val ic = box(card, icon(card))
        near("icon width", 79.19f, ic.width())
        near("icon top", 9f, ic.top)
        val name = box(card, text(card, "Desk lamp"))
        near("name top", 88.19f, name.top)
        near("name height", 20f, name.height())
        // Amber, the light's state color (button-card ignores its rgb unless `color: auto`).
        assertEquals(0xFFFFC107.toInt(), icon(card).tintColor)
    }

    @Test
    fun stateLabelAndLayoutsMatchHa() {
        put("sensor.outdoor_temp", "7.4", "friendly_name" to "Outdoor temperature", "unit_of_measurement" to "°C", "state_class" to "measurement", "device_class" to "temperature")
        val vertical = button("""{"type": "custom:button-card", "entity": "sensor.outdoor_temp", "show_state": true}""")
        near("height", 137.19f, vertical.measuredHeight)
        near("state top", 108.19f, box(vertical, text(vertical, "7.4 °C")).top)
        // A sensor's icon is the text color: button-card's color for a domain HA doesn't color.
        assertEquals(0xFFE1E1E1.toInt(), icon(vertical).tintColor)
        val second = button("""{"type": "custom:button-card", "entity": "sensor.outdoor_temp", "layout": "icon_name_state2nd",
            "show_state": true, "show_label": true, "label": "Outside"}""", 300)
        // HA: 300 x 86; columns 119.19 / 178.81; rows 20, 20, 20 from y 13.
        near("height", 86f, second.measuredHeight)
        near("name top", 13f, box(second, text(second, "Outdoor temperature")).top)
        near("state top", 33f, box(second, text(second, "7.4 °C")).top)
        near("label top", 53f, box(second, text(second, "Outside")).top)
        near("icon width", 47.67f, box(second, icon(second)).width())
    }

    @Test
    fun nameStateJoinsAndNoIconRowsShareTheHeight() {
        put("light.floor_lamp", "off", "friendly_name" to "Floor lamp")
        val joined = button("""{"type": "custom:button-card", "entity": "light.floor_lamp", "layout": "icon_name_state", "show_state": true}""", 300)
        text(joined, "Floor lamp: Off")
        near("height", 73.67f, joined.measuredHeight)
        val noIcon = button("""{"type": "custom:button-card", "entity": "light.floor_lamp", "show_icon": false, "show_state": true, "show_label": true, "label": "Lbl"}""")
        // HA: 78 tall, name / state / label 20 each from y 9.
        near("height", 78f, noIcon.measuredHeight)
        near("label top", 49f, box(noIcon, text(noIcon, "Lbl")).top)
        // Inactive: button-card's --state-inactive-color is --state-icon-color.
        val off = button("""{"type": "custom:button-card", "entity": "light.floor_lamp"}""")
        assertEquals(0xFF44739E.toInt(), icon(off).tintColor)
    }

    @Test
    fun cardStylesSizeTheCard() {
        put("switch.coffee_maker", "off", "friendly_name" to "Coffee maker")
        val tiny = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "show_icon": false,
            "styles": {"card": [{"height": "24px"}, {"font-size": "14px"}, {"padding": "3px"}]}}""")
        near("height", 24f, tiny.measuredHeight)
        near("name top", 4f, box(tiny, text(tiny, "Coffee maker")).top)
        val square = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "aspect_ratio": "1/1"}""")
        near("height", 200f, square.measuredHeight)
        near("name top", 171f, box(square, text(square, "Coffee maker")).top)
        val fixed = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "styles": {"card": [{"height": "100px"}]}}""")
        near("height", 100f, fixed.measuredHeight)
        near("name top", 71f, box(fixed, text(fixed, "Coffee maker")).top)
        // `transform: translate(-0%, 12%)` moves the card by 12% of its height.
        val moved = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "styles": {"card": [{"transform": "translate(-0%, 12%)"}]}}""")
        near("moved", 14.06f, box(moved, moved.getChildAt(0)).top)
    }

    @Test
    fun colorTypeCardPaintsTheCardAndPicksTheTextColor() {
        put("light.kitchen_spots", "on", "friendly_name" to "Kitchen spots", "brightness" to 255, "rgb_color" to listOf(80, 160, 255))
        val card = button("""{"type": "custom:button-card", "entity": "light.kitchen_spots", "color": "auto", "color_type": "card"}""")
        // HA: the card rgb(80, 160, 255), its text and icon dark (28, 28, 28).
        assertEquals(0xFF1C1C1C.toInt(), text(card, "Kitchen spots").currentTextColor)
        assertEquals(0xFF1C1C1C.toInt(), icon(card).tintColor)
    }

    @Test
    fun statesStyleTheMatchedEntry() {
        put("sensor.outdoor_temp", "7.4", "friendly_name" to "Outdoor temperature", "unit_of_measurement" to "°C")
        val card = button("""{"type": "custom:button-card", "entity": "sensor.outdoor_temp", "show_state": true, "state": [
            {"value": 5, "operator": "<", "color": "blue", "name": "Cold"},
            {"value": 15, "operator": "<", "color": "rgb(0,200,0)", "name": "Mild", "styles": {"name": [{"color": "orange"}]}},
            {"operator": "default", "color": "red"}]}""")
        assertEquals(0xFF00C800.toInt(), icon(card).tintColor)
        assertEquals(0xFFFFA500.toInt(), text(card, "Mild").currentTextColor)
    }

    @Test
    fun tapRunsTheDefaultActionAndHoldNothingByDefault() {
        put("light.desk_lamp", "on", "friendly_name" to "Desk lamp")
        val card = button("""{"type": "custom:button-card", "entity": "sensor.none", "tap_action": {"action": "call-service", "service": "light.turn_on", "data": {"entity_id": "entity"}}}""")
        val surface = card.getChildAt(0)
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0)
        surface.dispatchTouchEvent(down)
        surface.dispatchTouchEvent(up)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(calls.toString(), 1, calls.size)
        assertTrue(calls.toString(), calls.single().startsWith("light.turn_on") && "sensor.none" in calls.single())
    }

    @Test
    fun accessibilityClicksRunTheTapButNeverAProtectedAction() {
        put("light.desk_lamp", "on", "friendly_name" to "Desk lamp")
        val card = button("""{"type": "custom:button-card", "entity": "light.desk_lamp", "tap_action": {"action": "call-service", "service": "light.turn_off", "data": {"entity_id": "entity"}}}""")
        assertTrue(card.getChildAt(0).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(calls.toString(), 1, calls.size)
        assertTrue(calls.toString(), calls.single().startsWith("light.turn_off") && "light.desk_lamp" in calls.single())
        calls.clear()
        // Natively (no script resource for the island), a PIN-protected action never runs unprotected.
        val guarded = button("""{"type": "custom:button-card", "entity": "light.desk_lamp", "tap_action": {"action": "call-service", "service": "light.turn_off", "protect": {"pin": "1234"}}}""")
        guarded.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls.toString(), calls.isEmpty())
    }

    @Test
    fun paddingFollowsDeclarationOrderAndTheLockStopsEveryAction() {
        put("switch.coffee_maker", "off", "friendly_name" to "Coffee maker")
        // A later shorthand resets the side a longhand set before it; a later longhand changes only its side.
        val later = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "styles": {"name": [{"padding-left": "20px"}, {"padding": "4px"}]}}""")
        assertEquals(4, text(later, "Coffee maker").paddingLeft)
        val longhand = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "styles": {"name": [{"padding": "4px"}, {"padding-left": "20px"}]}}""")
        assertEquals(20, text(longhand, "Coffee maker").paddingLeft)
        // Locked, no gesture runs the action, an accessibility click included.
        val locked = button("""{"type": "custom:button-card", "entity": "switch.coffee_maker", "lock": {"enabled": true},
            "tap_action": {"action": "call-service", "service": "switch.turn_on"}}""")
        locked.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls.toString(), calls.isEmpty())
        // Without touch, the lock is a "Locked" button; activating it unlocks the card and runs nothing.
        val overlay = locked.getChildAt(1)
        assertEquals("Locked", overlay.contentDescription)
        assertTrue(overlay.performClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls.toString(), calls.isEmpty())
        assertEquals(null, overlay.contentDescription)
        locked.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(calls.toString(), 1, calls.size)
    }

    @Test
    fun aReleaseOutsideTheCardDoesNotSwallowTheNextClick() {
        put("light.desk_lamp", "on", "friendly_name" to "Desk lamp")
        val card = button("""{"type": "custom:button-card", "entity": "light.desk_lamp",
            "tap_action": {"action": "call-service", "service": "light.turn_off"}, "double_tap_action": {"action": "more-info"}}""")
        val surface = card.getChildAt(0)
        // A touch that slides off the card and lets go there: no click, no action.
        surface.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        surface.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 20, android.view.MotionEvent.ACTION_MOVE, -500f, -500f, 0))
        surface.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, -500f, -500f, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls.toString(), calls.isEmpty())
        // The next keyboard or accessibility click still runs the tap, once.
        surface.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(calls.toString(), 1, calls.size)
        assertTrue(calls.toString(), calls.single().startsWith("light.turn_off"))
    }

    @Test
    fun entityButtonWithoutAnEntityIsHasErrorCard() {
        val err = HaErrorCardView(ctx(), "Entity must be specified")
        layout(err, 300)
        near("height", 56f, err.measuredHeight)
        assertTrue(all(err).filterIsInstance<TextView>().isEmpty())
        // HA titles the card "Configuration error" for an administrator: the message shows only in the editor's preview.
        val admin = HaErrorCardView(ctx(admin = true), "Entity must be specified")
        layout(admin, 300)
        assertEquals("Configuration error", all(admin).filterIsInstance<TextView>().single().text.toString())
        assertEquals("Entity must be specified", admin.message)
    }

    @Test
    fun swipeCardIsAsTallAsItsTallestCard() {
        put("light.desk_lamp", "on", "friendly_name" to "Desk lamp")
        put("light.floor_lamp", "off", "friendly_name" to "Floor lamp")
        val config = LovelaceParser.parseCard(JSONObject("""{"type": "custom:swipe-card", "parameters": {"pagination": {"type": "bullets"}},
            "cards": [{"type": "custom:button-card", "entity": "light.desk_lamp"}, {"type": "custom:button-card", "entity": "light.floor_lamp", "show_state": true}]}"""))
        val c = ctx()
        val swipe = SwipeCardView(c, config) { child -> CustomButtonCardView(c, child) }
        activity.setContentView(swipe, ViewGroup.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout(swipe, 300)
        // HA: 185.19, the second card's height; the first page shows the first card.
        near("height", 185.19f, swipe.measuredHeight)
        assertEquals(0, swipe.getChildAt(0).left)
        assertEquals(300, swipe.getChildAt(1).left)
    }

    @Test
    fun weatherCardLaysOutCurrentAndDetails() {
        put("weather.studio_forecast", "partlycloudy", "temperature" to 8.6, "humidity" to 72, "pressure" to 1014,
            "wind_speed" to 11, "wind_bearing" to 240, "visibility" to 16)
        val pendingBefore = VitroCardContext.pendingImageLoads.get()
        val folder = "http://127.0.0.1:${icons.address.port}/icons/"
        val card = LegacyWeatherCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "custom:weather-card", "entity": "weather.studio_forecast", "name": "Studio", "icons": "$folder"}""")))
        activity.setContentView(card, ViewGroup.LayoutParams(460, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout(card, 460)
        awaitImageLoads(pendingBefore)
        assertEquals("the condition's icon, from the card's own folder", listOf("/icons/cloudy-day-3.svg"), iconRequests.toList())
        val icon = all(card).single { it.javaClass.simpleName == "SvgView" }
        assertNotNull("the icon is drawn", icon.javaClass.getDeclaredMethod("getSvg").apply { isAccessible = true }.invoke(icon))
        layout(card, 460)
        // HA (no sun.sun here): 1 + 18.2 + 33.6 + 49 + 2 rows of 24 + 18.2 + 1.
        near("height", 168.97f, card.measuredHeight)
        near("title left", 127f, box(card, text(card, "Studio")).left)
        near("temp right", 403f, box(card, text(card, "8.6")).right.toFloat().toInt())
        assertTrue(all(card).filterIsInstance<TextView>().any { it.text.toString() == "WSW 11 km/h" })
    }
}
