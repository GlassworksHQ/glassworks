/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaStateDisplay
import dev.glassworks.engine.vitro.cards.HeadingCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The tile options and domains the strict compat rule now lets Vitro draw (`color`, `state_content`,
 * `show_entity_picture`; climate, person, timer and update tiles) and the `cover-open-close` and `fan-speed` features,
 * as HA's frontend 20261028.5 runs them. How they look is compared with HA in the parity corpus (`tile-*`); these
 * check what the pixels can't: the services they call, and a timer counting down on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TileNativeOptionsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private class Call(val domain: String, val service: String, val data: JSONObject) {
        override fun toString() = "$domain.$service $data"
    }

    private val calls = ArrayList<Call>()
    private val opened = ArrayList<String>()
    private var now = 1_794_499_680_000L

    private var haBase = FakeHaServer.BASE

    private fun context(store: EntityStore, watchEntity: (String) -> Unit = {}) = VitroCardContext(
        context = activity, haBase = haBase, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += Call(domain, service, data ?: JSONObject()) },
        navigate = { opened += "navigate $it" }, openMoreInfo = { opened += "more-info $it" }, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { now },
        haConfig = { JSONObject("""{"unit_system": {"temperature": "°C", "length": "km"}}""") },
        watchEntity = watchEntity,
    )

    private fun tile(json: String, vararg states: EntityState): TileCardView {
        val store = EntityStore().also { s -> states.forEach { s.putLocal(it) } }
        val raw = JSONObject(json)
        val view = TileCardView(context(store), CardConfig(raw.getString("type"), raw))
        val host = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view, FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 800, view.measuredHeight)
        ShadowLooper.idleMainLooper()
        return view
    }

    private fun View.all(): List<View> = if (this is ViewGroup) listOf(this) + (0 until childCount).flatMap { getChildAt(it).all() } else listOf(this)
    private fun View.texts(): List<String> = all().filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
    private fun View.icons(): List<MdiIconView> = all().filterIsInstance<MdiIconView>()
    private fun View.shownWith(label: String): View? = all().firstOrNull { it.isShown && it.contentDescription?.toString() == label }

    private fun cover(state: String, position: Int, features: Int, deviceClass: String? = null) = EntityState(
        "cover.curtain", state,
        mapOf("friendly_name" to "Curtain", "current_position" to position, "supported_features" to features) +
            (deviceClass?.let { mapOf("device_class" to it) } ?: emptyMap()),
    )

    @Test
    fun coverButtonsAreTheSupportedOnesAndTheClosedCoverCanOnlyOpen() {
        // OPEN | CLOSE | STOP | SET_POSITION, closed at 0: closing again is disabled, as HA's canClose says.
        val view = tile("""{"type": "tile", "entity": "cover.curtain", "features": [{"type": "cover-open-close"}]}""",
            cover("closed", 0, 15, deviceClass = "curtain"))
        val open = view.shownWith("Open cover")!!
        val stop = view.shownWith("Stop cover")!!
        val close = view.shownWith("Close cover")!!
        assertTrue(open.isEnabled)
        assertTrue(stop.isEnabled)
        assertFalse(close.isEnabled)
        // A curtain opens sideways: HA's computeOpenIcon.
        assertTrue(view.icons().map { it.icon }.toString(), view.icons().any { "arrow-expand-horizontal" in it.icon })
        open.performClick()
        close.performClick()
        assertEquals("cover.open_cover", calls.single().let { "${it.domain}.${it.service}" })
        assertEquals("cover.curtain", calls.single().data.getString("entity_id"))
    }

    @Test
    fun aCoverWithoutStopShowsNoStopButton() {
        val view = tile("""{"type": "tile", "entity": "cover.curtain", "features": [{"type": "cover-open-close"}]}""",
            cover("open", 50, 3))
        assertTrue(view.shownWith("Open cover")!!.isEnabled)
        assertTrue(view.shownWith("Close cover")!!.isEnabled)
        assertEquals(null, view.shownWith("Stop cover"))
    }

    private fun fan(step: Double, pct: Int, state: String = "on") = EntityState(
        "fan.ceiling", state,
        mapOf("friendly_name" to "Ceiling", "percentage" to pct, "percentage_step" to step, "supported_features" to 57),
    )

    @Test
    fun theFanSliderSetsAPercentageOnTheFansStep() {
        val view = tile("""{"type": "tile", "entity": "fan.ceiling", "features": [{"type": "fan-speed"}]}""", fan(10.0, 20))
        val slider = view.all().first { it.isShown && it.javaClass.simpleName == "HaHorizontalSliderView" }
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 43f) }
        assertTrue(slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        // Snapped to the 10 % step, and HA's set_percentage even at 0 (no turn_off).
        assertEquals("fan.set_percentage", calls.last().let { "${it.domain}.${it.service}" })
        assertEquals(40L, calls.last().data.getLong("percentage"))
        val zero = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2f) }
        slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, zero)
        assertEquals("fan.set_percentage", calls.last().let { "${it.domain}.${it.service}" })
        assertEquals(0L, calls.last().data.getLong("percentage"))
    }

    @Test
    fun aFanThatGoesUnavailableMidDragDropsTheDragAndFollowsHaAgain() {
        val store = EntityStore().also { it.putLocal(fan(10.0, 20)) }
        val raw = JSONObject("""{"type": "tile", "entity": "fan.ceiling", "features": [{"type": "fan-speed"}]}""")
        val view = TileCardView(context(store), CardConfig("tile", raw))
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 800, view.measuredHeight)
        ShadowLooper.idleMainLooper()
        val slider = view.all().first { it.isShown && it.javaClass.simpleName == "HaHorizontalSliderView" }
        fun shown(): Float {
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
            slider.onInitializeAccessibilityNodeInfo(info)
            return info.rangeInfo.current
        }
        val t = android.os.SystemClock.uptimeMillis()
        val y = slider.height / 2f
        slider.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, slider.width * 0.9f, y, 0))
        assertEquals("the drag is live: it shows 90 %", 90f, shown(), 1f)
        // The fan goes unavailable mid-drag: the slider is disabled and the drag's up never arrives.
        store.putLocal(fan(10.0, 20, state = "unavailable"))
        ShadowLooper.idleMainLooper()
        assertTrue(!slider.isEnabled)
        // Back, at 70 %: the slider shows Home Assistant's value, not a drag stuck at 90 %.
        store.putLocal(fan(10.0, 70))
        ShadowLooper.idleMainLooper()
        android.os.SystemClock.sleep(0)
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(70f, shown(), 1f)
        assertTrue(calls.none { it.service == "set_percentage" })
        // The finger was still down through all that: its late UP, on a slider enabled again, sends nothing.
        slider.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 900, android.view.MotionEvent.ACTION_UP, slider.width * 0.9f, y, 0))
        assertEquals(70f, shown(), 1f)
        assertTrue(calls.none { it.service == "set_percentage" })
    }

    @Test
    fun aFanWithFourSpeedsIsSetByItsSpeedButtons() {
        val view = tile("""{"type": "tile", "entity": "fan.ceiling", "features": [{"type": "fan-speed"}]}""",
            fan(33.333333333333336, 67))
        assertTrue(view.all().none { it.isShown && it.javaClass.simpleName == "HaHorizontalSliderView" })
        val icons = view.icons().filter { it.isShown }.map { it.icon }
        for (i in listOf("fan-off", "fan-speed-1", "fan-speed-2", "fan-speed-3")) assertTrue("$i in $icons", icons.any { i in it })
        // HA's fanSpeedToPercentage: floor(3 × 33.333333333333336) = floor(100.00000000000001) = 100 for high.
        // Medium is the selected speed at 67 %: its icon is white on the fan's color.
        assertEquals(android.graphics.Color.WHITE, view.icons().first { "fan-speed-2" in it.icon }.tintColor)
        val options = view.all().filter { it.isShown && it.isClickable && it.parent?.javaClass?.simpleName == "HaControlSelect" }
        assertEquals(4, options.size)
        options[3].performClick()
        assertEquals("fan.set_percentage", calls.last().let { "${it.domain}.${it.service}" })
        assertEquals(100L, calls.last().data.getLong("percentage"))
        // The select takes the new speed at once, as HA's does.
        assertEquals(android.graphics.Color.WHITE, view.icons().first { "fan-speed-3" in it.icon }.tintColor)
    }

    @Test
    fun aRunningTimerCountsDownEverySecond() {
        val finishes = dev.glassworks.engine.vitro.data.EntityStore.formatHaIso((now + 125_000L) / 1000.0)
        val view = tile("""{"type": "tile", "entity": "timer.laundry"}""", EntityState(
            "timer.laundry", "active",
            mapOf("friendly_name" to "Laundry", "duration" to "0:30:00", "remaining" to "0:30:00", "finishes_at" to finishes),
        ))
        assertTrue(view.texts().toString(), "2:05" in view.texts())
        now += 1_000L
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(view.texts().toString(), "2:04" in view.texts())
        now += 61_000L
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(view.texts().toString(), "1:03" in view.texts())
        // Off screen it stops; back on screen it catches up.
        val host = view.parent as ViewGroup
        host.removeView(view)
        now += 5_000L
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertTrue(view.texts().toString(), "1:03" in view.texts())
        host.addView(view)
        ShadowLooper.idleMainLooper()
        assertTrue(view.texts().toString(), "58" in view.texts())
    }

    @Test
    fun timerDisplayFollowsHomeAssistant() {
        val t = { state: String, attrs: Map<String, Any?> -> EntityState("timer.t", state, attrs) }
        val ticks = ArrayList<Long>()
        assertEquals("41:07 (Paused)", HaStateDisplay.timerDisplay(t("paused", mapOf("remaining" to "0:41:07")), now) { ticks += it })
        assertEquals("1:00:00", HaStateDisplay.timerDisplay(t("paused", mapOf("remaining" to "1:00:00")), now) { ticks += it }.substringBefore(' '))
        assertTrue(ticks.isEmpty())
        assertEquals("9", HaStateDisplay.secondsToDuration(9.7))
        assertEquals(null, HaStateDisplay.secondsToDuration(0.4))
    }

    @Test
    fun stateContentJoinsItsPartsWithADot() {
        val lamp = EntityState("light.desk", "on", mapOf("friendly_name" to "Desk", "brightness" to 166), lastChanged = (now - 35 * 60_000L) / 1000.0)
        val view = tile("""{"type": "tile", "entity": "light.desk", "state_content": ["state", "brightness", "last-changed"]}""", lamp)
        assertTrue(view.texts().toString(), view.texts().any { it.startsWith("On · 65%") && it.endsWith("35 minutes ago") })
        // The default for a light: its brightness alone, and the state when it's off.
        assertTrue(tile("""{"type": "tile", "entity": "light.desk"}""", lamp).texts().contains("65%"))
    }

    @Test
    fun theTileColorIsTheCardsOwnWhileActive() {
        val on = EntityState("light.desk", "on", mapOf("friendly_name" to "Desk", "brightness" to 166))
        val view = tile("""{"type": "tile", "entity": "light.desk", "color": "teal"}""", on)
        assertEquals(0xFF009688.toInt(), view.tileColor(on))
        val off = on.copy(state = "off")
        assertEquals(ThemeResolver.resolve(ThemeCatalog(), true).stateInactiveColor, view.tileColor(off))
        // A near-white light is drawn at HSV value 225 for contrast, its slight tint kept, as HA's tile does.
        assertEquals(0xFFE1E1DD.toInt(), TileCardView.contrastLightColor(255.0, 255.0, 250.0))
        // A pale one gets saturation 0.4.
        assertEquals(0xFFFF9999.toInt(), TileCardView.contrastLightColor(255.0, 204.0, 204.0))
    }

    @Test
    fun aPersonInAZoneShowsTheZonesIconAndAClimateItsAction() {
        val person = EntityState("person.sam", "Work", mapOf("friendly_name" to "Sam", "in_zones" to listOf("zone.work")))
        val zone = EntityState("zone.work", "1", mapOf("friendly_name" to "Work", "icon" to "mdi:briefcase"))
        assertTrue(tile("""{"type": "tile", "entity": "person.sam"}""", person, zone).icons().any { "briefcase" in it.icon && it.isShown })
        val away = tile("""{"type": "tile", "entity": "person.sam"}""", person.copy(state = "not_home"))
        assertTrue(away.icons().any { "home-export-outline" in it.icon && it.isShown })
        val climate = EntityState("climate.hall", "heat", mapOf("friendly_name" to "Hall", "hvac_action" to "heating", "current_temperature" to 20.5))
        val c = tile("""{"type": "tile", "entity": "climate.hall"}""", climate)
        assertTrue(c.texts().toString(), "Heat · 20.5 °C" in c.texts())
        assertTrue(c.all().any { it.isShown && it.contentDescription != null && "heating" in it.contentDescription.toString().lowercase() })
    }

    private fun heading(json: String, vararg states: EntityState, missingKnown: Boolean = false): HeadingCardView {
        val store = EntityStore().also { s -> states.forEach { s.putLocal(it) } }
        if (missingKnown) store.markSnapshot(emptySet())
        val raw = JSONObject(json)
        val view = HeadingCardView(context(store), CardConfig(raw.getString("type"), raw))
        val host = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view, FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT))
        ShadowLooper.idleMainLooper()
        return view
    }

    private val lamp = EntityState("light.desk", "on", mapOf("friendly_name" to "Desk", "brightness" to 166, "rgb_color" to listOf(255, 152, 9)))

    @Test
    fun aHeadingBadgeActsOnlyOnItsOwnActions() {
        // HA's entity heading badge: tap and hold are `none` unless the badge sets them.
        val plain = heading("""{"type": "heading", "heading": "Office", "badges": [{"type": "entity", "entity": "light.desk"}]}""", lamp)
        val badge = plain.all().first { it.contentDescription == "Desk" }
        assertFalse(badge.isClickable)
        badge.performClick()
        assertTrue(opened.toString(), opened.isEmpty())
        val acting = heading("""{"type": "heading", "heading": "Office", "badges": [{"type": "entity", "entity": "light.desk",
            "tap_action": {"action": "more-info"}}]}""", lamp)
        acting.all().first { it.contentDescription == "Desk" }.performClick()
        assertEquals(listOf("more-info light.desk"), opened)
    }

    @Test
    fun aHeadingWithAnActionShowsTheChevronAndNavigates() {
        val view = heading("""{"type": "heading", "heading": "Office", "tap_action": {"action": "navigate", "navigation_path": "/lovelace/office"}}""")
        assertTrue(view.icons().any { "chevron-right" in it.icon })
        // One button, named by the heading, as HA's `.content` is.
        view.all().single { it.isClickable }.also { assertEquals("Office", it.contentDescription) }.performClick()
        assertEquals(listOf("navigate /lovelace/office"), opened)
        assertTrue(heading("""{"type": "heading", "heading": "Office"}""").icons().none { "chevron-right" in it.icon })
    }

    @Test
    fun aHeadingBadgeShowsWhatItsOptionsSay() {
        val view = heading("""{"type": "heading", "heading": "Office", "badges": [
            {"type": "entity", "entity": "light.desk", "color": "state", "state_content": ["state", "brightness"]},
            {"type": "entity", "entity": "light.desk", "show_icon": false},
            {"type": "entity", "entity": "sensor.gone"}]}""", lamp, missingKnown = true)
        val texts = view.texts()
        assertTrue(texts.toString(), "On · 65%" in texts && "65%" in texts && "-" in texts)
        // `color: state`: the light's own color for its icon; an entity HA doesn't have is a red alert.
        val icons = view.icons().filter { it.isShown }
        assertEquals(2, icons.count { "lightbulb" in it.icon || "alert-circle" in it.icon })
        assertEquals(TileCardView.contrastLightColor(255.0, 152.0, 9.0), icons.first { "lightbulb" in it.icon }.tintColor)
        assertTrue(icons.any { "alert-circle" in it.icon && it.tintColor == 0xFFF44336.toInt() })
    }

    @Test
    fun aPersonTileFollowsItsZonesNotEveryEntity() {
        fun view(entity: String) = dev.glassworks.engine.vitro.model.LovelaceParser.parseDashboard(JSONObject(
            """{"views": [{"cards": [{"type": "tile", "entity": "$entity"}]}]}""")).views.single()
        assertFalse(dev.glassworks.engine.vitro.model.CardRouter.followsAllEntities(view("person.sam")))
        assertFalse(dev.glassworks.engine.vitro.model.CardRouter.followsAllEntities(view("device_tracker.phone")))
        // The tile asks HA for the zones it may be in, and draws the zone's icon once it arrives.
        val store = EntityStore().also { it.putLocal(EntityState("person.sam", "Work", mapOf("friendly_name" to "Sam", "in_zones" to listOf("zone.work")))) }
        val watched = ArrayList<String>()
        val raw = JSONObject("""{"type": "tile", "entity": "person.sam"}""")
        val view = TileCardView(context(store) { watched += it }, CardConfig("tile", raw))
        val host = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view, FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("zone.work"), watched)
        assertFalse(view.icons().any { "briefcase" in it.icon && it.isShown })
        store.putLocal(EntityState("zone.work", "1", mapOf("friendly_name" to "Work", "icon" to "mdi:briefcase")))
        ShadowLooper.idleMainLooper()
        assertTrue(view.icons().any { "briefcase" in it.icon && it.isShown })
    }

    @Test
    fun aHeadingBadgeBuiltBeforeItsAttachedStillCountsDown() {
        val finishes = EntityStore.formatHaIso((now + 125_000L) / 1000.0)
        val timer = EntityState("timer.laundry", "active", mapOf("friendly_name" to "Laundry", "remaining" to "0:30:00", "finishes_at" to finishes))
        // Constructed (and drawn) before it's attached, as a view is built: its ticker starts on attachment.
        val view = heading("""{"type": "heading", "heading": "Utilities", "badges": [{"type": "entity", "entity": "timer.laundry"}]}""", timer)
        assertTrue(view.texts().toString(), "2:05" in view.texts())
        now += 1_000L
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(view.texts().toString(), "2:04" in view.texts())
    }

    @Test
    fun theIconKeepsItsActionUnderTheEntityPicture() {
        // A 4 px PNG on a local server standing in for HA's /api/person picture.
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/local/sam.png") { ex -> ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } }
            start()
        }
        try {
            haBase = "http://127.0.0.1:${server.address.port}"
            val sw = EntityState("switch.fountain", "on", mapOf("friendly_name" to "Fountain", "entity_picture" to "/local/sam.png"))
            val view = tile("""{"type": "tile", "entity": "switch.fountain", "show_entity_picture": true}""", sw)
            val deadline = System.currentTimeMillis() + 5_000
            while (view.all().none { it is android.widget.ImageView && it.isShown } && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                ShadowLooper.idleMainLooper()
            }
            assertTrue("the picture shows", view.all().any { it is android.widget.ImageView && it.isShown })
            // The icon's default toggle still runs from its box, under the picture.
            val iconBox = view.all().first { it.isShown && it.isClickable && it.contentDescription?.toString() == "Toggle Fountain" }
            iconBox.performClick()
            assertEquals("switch.toggle", calls.lastOrNull()?.let { "${it.domain}.${it.service}" } ?: "none")
        } finally {
            server.stop(0)
        }
    }
}
