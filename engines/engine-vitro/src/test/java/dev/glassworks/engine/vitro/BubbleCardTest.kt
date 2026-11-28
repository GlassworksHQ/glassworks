/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.BubbleCardView
import dev.glassworks.engine.vitro.cards.BubbleColors
import dev.glassworks.engine.vitro.cards.BubbleStateLine
import dev.glassworks.engine.vitro.compat.BUBBLE_MODULES_REASON
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
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

/**
 * Bubble Card 3.4.3's separator and switch, state and name buttons, drawn natively ([BubbleCardView]): which configs
 * Vitro claims, the island on a Home Assistant with Bubble modules, the actions and services each part runs, Bubble's
 * colors and its state line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BubbleCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    data class Call(val service: String, val data: JSONObject, val target: JSONObject? = null) {
        val entityId: String get() = data.optString("entity_id").ifEmpty { target?.optString("entity_id").orEmpty() }
    }

    private val calls = ArrayList<Call>()
    private val actions = ArrayList<String>()

    private fun context(store: EntityStore, haBase: String = FakeHaServer.BASE) = VitroCardContext(
        context = activity,
        haBase = haBase,
        store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, target -> calls += Call("$domain.$service", data ?: JSONObject(), target) },
        navigate = { actions += "navigate:$it" },
        openMoreInfo = { actions += "more-info:$it" },
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
    )

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    private fun entity(id: String, state: String, vararg attrs: Pair<String, Any?>, changedSecondsAgo: Long = 0) =
        EntityState(id, state, mapOf("friendly_name" to "Desk Lamp") + attrs, lastChanged = (NOW - changedSecondsAgo * 1000) / 1000.0)

    private fun store(vararg states: EntityState) = EntityStore().apply { states.forEach(::putLocal) }

    private fun bubble(st: EntityStore, json: String) = BubbleCardView(context(st), card(json)).also { ShadowLooper.idleMainLooper() }

    private fun View.texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }

    // ---- Which configs Vitro draws

    private fun route(json: String) = card(json).compat

    @Test
    fun theSeparatorAndThePlainButtonsAreNativeAndEverythingElseGoesToTheIsland() {
        for (json in listOf(
            """{"type": "custom:bubble-card", "card_type": "separator", "name": "Lights", "icon": "mdi:lightbulb-group"}""",
            """{"type": "custom:bubble-card", "card_type": "separator", "name": "House", "icon": "", "card_layout": "large", "modules": ["default"]}""",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk"}""",
            """{"type": "custom:bubble-card", "card_type": "button", "button_type": "state", "entity": "sensor.t", "show_last_changed": true,
                "show_attribute": true, "attribute": "brightness", "scrolling_effect": false, "grid_options": {"columns": 6}}""",
            """{"type": "custom:bubble-card", "card_type": "button", "button_type": "name", "name": "Scenes", "icon": "mdi:palette",
                "tap_action": {"action": "navigate", "navigation_path": "/home/scenes"}}""",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "use_accent_color": true,
                "button_action": {"tap_action": {"action": "more-info"}, "hold_action": {"action": "toggle"}}}""",
        )) assertEquals(json, CardRoute.NATIVE, route(json).route)

        for ((json, why) in listOf(
            """{"type": "custom:bubble-card", "card_type": "button", "button_type": "slider", "entity": "light.desk"}""" to "button_type",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "sub_button": [{"entity": "switch.fan"}]}""" to "sub_button",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "styles": ".bubble-icon { color: red; }"}""" to "styles",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "card_layout": "large-2-rows"}""" to "card_layout",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "name": "{{ states('sensor.x') }}"}""" to "name",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "state_content": ["state"]}""" to "state_content",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "double_tap_action": {"action": "toggle"}}""" to "double_tap_action",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "weather.home", "show_attribute": true, "attribute": "forecast"}""" to "attribute",
            """{"type": "custom:bubble-card", "card_type": "separator", "name": "Lights", "sub_button": [{"icon": "mdi:cog"}]}""" to "sub_button",
            """{"type": "custom:bubble-card", "card_type": "button", "button_type": "state", "entity": "climate.hall"}""" to "entity",
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "timer.tea"}""" to "entity",
            """{"type": "custom:bubble-card", "card_type": "pop-up", "hash": "#kitchen"}""" to "card_type",
            """{"type": "custom:bubble-card", "card_type": "media-player", "entity": "media_player.tv"}""" to "card_type",
        )) {
            val v = route(json)
            assertEquals(json, CardRoute.ISLAND, v.route)
            assertTrue("$json: ${v.reasons}", v.reasons.any { why in it })
        }
    }

    @Test
    fun bubbleModulesInHomeAssistantSendEveryBubbleCardToTheIsland() {
        val c = card("""{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk"}""")
        val bubble = listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "module"))
        // No modules: native.
        assertFalse(c.inIsland(IslandScripts.of(LovelaceResourceList(bubble, bubbleModules = false))))
        // Modules: the island, which runs Bubble with them.
        val withModules = IslandScripts.of(LovelaceResourceList(bubble, bubbleModules = true))
        assertTrue(c.inIsland(withModules))
        assertEquals(listOf(BUBBLE_MODULES_REASON), c.compat.effective(withModules).reasons)
        // Without Bubble's script the island couldn't draw it: it stays native.
        assertFalse(c.inIsland(IslandScripts.of(LovelaceResourceList(emptyList(), bubbleModules = true))))
        // A config the island takes anyway still goes there.
        val slider = card("""{"type": "custom:bubble-card", "card_type": "button", "button_type": "slider", "entity": "light.desk"}""")
        assertTrue(slider.inIsland(IslandScripts.of(LovelaceResourceList(bubble, bubbleModules = false))))
        // Other cards don't care about Bubble's modules.
        assertFalse(card("""{"type": "tile", "entity": "light.desk"}""").inIsland(withModules))
    }

    @Test
    fun rowSizesThatWouldEraseTheCardGoToTheIslandAndNeverBreakTheBuild() {
        fun json(extra: String) = """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "card_layout": "large", $extra}"""
        for (extra in listOf(""""rows": 0.125""", """"grid_options": {"rows": "NaN"}""", """"grid_options": {"rows": 0.5}""", """"rows": -1""")) {
            assertEquals(extra, CardRoute.ISLAND, route(json(extra)).route)
        }
        for (extra in listOf(""""rows": 2""", """"grid_options": {"rows": "auto", "columns": 6}""", """"grid_options": {"rows": 1}""")) {
            assertEquals(extra, CardRoute.NATIVE, route(json(extra)).route)
        }
        assertEquals(CardRoute.ISLAND, route("""{"type": "custom:bubble-card", "card_type": "separator", "rows": 0.4}""").route)
        assertEquals(CardRoute.NATIVE, route("""{"type": "custom:bubble-card", "card_type": "separator", "grid_options": {"rows": 0.8}}""").route)
        // Drawn anyway (a fallback with no island): the default row, not a crash or a card with no height.
        val ctx = context(store(entity("light.desk", "on"))).also { it.viewType = "sections" }
        val b = BubbleCardView(ctx, card(json(""""grid_options": {"rows": "NaN"}""")))
        ShadowLooper.idleMainLooper()
        assertEquals(Math.round(56 * ctx.dp), b.getChildAt(0).layoutParams.height)
    }

    @Test
    fun timestampAttributesGoToTheIsland() {
        for (attribute in listOf("next_rising", "start_time", "timestamp")) {
            val json = """{"type": "custom:bubble-card", "card_type": "button", "button_type": "state", "entity": "sun.sun",
                "show_state": false, "show_attribute": true, "attribute": "$attribute"}"""
            assertEquals(attribute, CardRoute.ISLAND, route(json).route)
        }
        assertEquals(CardRoute.NATIVE, route("""{"type": "custom:bubble-card", "card_type": "button", "button_type": "state",
            "entity": "sun.sun", "show_attribute": true, "attribute": "elevation"}""").route)
    }

    @Test
    fun anIconOnlyButtonIsNamedAfterWhatItsTapDoes() {
        val n = bubble(store(), """{"type": "custom:bubble-card", "card_type": "button", "button_type": "name", "icon": "mdi:palette",
            "tap_action": {"action": "navigate", "navigation_path": "/home/scenes"}}""")
        val info = n.iconTarget!!.createAccessibilityNodeInfo()
        assertEquals("Navigate", info.contentDescription?.toString())
        // A card with an entity is named after it.
        val b = bubble(store(entity("light.desk", "on")), """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "show_name": false}""")
        assertEquals("Desk Lamp", b.buttonTarget!!.createAccessibilityNodeInfo().contentDescription?.toString())
    }

    @Test
    fun theIconStaysUntilThePictureLoadsAndAFailedPictureIsAskedForAgainLater() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val png = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                // The first answer fails; the server recovers after.
                val ok = hits.incrementAndGet() > 1
                if (ok) { ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } }
                else { ex.sendResponseHeaders(500, -1); ex.close() }
            }
            start()
        }
        try {
            val st = store(entity("person.robin", "home", "entity_picture" to "/local/robin-${System.nanoTime()}.png"))
            val ctx = context(st, "http://127.0.0.1:${server.address.port}")
            val pendingBefore = VitroCardContext.pendingImageLoads.get()
            val b = BubbleCardView(ctx, card("""{"type": "custom:bubble-card", "card_type": "button", "entity": "person.robin"}"""))
            // On screen: HA loads no picture for a card that isn't shown, and neither does Vitro.
            activity.setContentView(b)
            ShadowLooper.idleMainLooper()
            fun views(): Pair<View, View> {
                val container = (b.iconTarget as ViewGroup)
                return container.getChildAt(0) to container.getChildAt(1)
            }
            // While it loads: the icon.
            assertEquals(View.VISIBLE, views().first.visibility)
            assertEquals(View.GONE, views().second.visibility)
            awaitCondition("the failed load", tick = { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }) { hits.get() == 1 && VitroCardContext.pendingImageLoads.get() == pendingBefore }
            ShadowLooper.idleMainLooper()
            assertEquals(View.VISIBLE, views().first.visibility)
            assertEquals(View.GONE, views().second.visibility)
            // An update soon after doesn't ask again.
            st.putLocal(st["person.robin"]!!.copy(lastUpdated = 1.0))
            ShadowLooper.idleMainLooper()
            assertEquals(1, hits.get())
            // Later, an update does, and the picture replaces the icon.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
            st.putLocal(st["person.robin"]!!.copy(lastUpdated = 2.0))
            awaitCondition("the picture", tick = { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }) { views().second.visibility == View.VISIBLE }
            assertEquals(2, hits.get())
            assertEquals(View.GONE, views().first.visibility)
        } finally {
            server.stop(0)
        }
    }

    // ---- Actions and services

    @Test
    fun aSwitchTogglesFromItsBackgroundOpensMoreInfoFromItsIconAndHolds() {
        val st = store(entity("light.desk", "on", "brightness" to 166))
        val b = bubble(st, """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk"}""")
        assertTrue(b.buttonTarget!!.performClick())
        assertEquals("light.desk", calls.single().entityId)
        assertTrue(calls.single().service, calls.single().service.endsWith(".toggle") || calls.single().service.endsWith(".turn_off"))
        assertTrue(b.iconTarget!!.performClick())
        assertEquals(listOf("more-info:light.desk"), actions)
        // The background's hold opens More Info (Bubble's default for a switch).
        assertTrue(b.buttonTarget!!.isLongClickable)
        assertTrue(b.buttonTarget!!.performLongClick())
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("more-info:light.desk", "more-info:light.desk"), actions)
        assertEquals(1, calls.size)
    }

    @Test
    fun aStateButtonOpensMoreInfoFromBothPartsAndANameButtonDoesNothing() {
        val st = store(entity("sensor.temp", "21.4", "unit_of_measurement" to "°C"))
        val s = bubble(st, """{"type": "custom:bubble-card", "card_type": "button", "button_type": "state", "entity": "sensor.temp"}""")
        assertTrue(s.buttonTarget!!.performClick())
        assertTrue(s.iconTarget!!.performClick())
        assertEquals(listOf("more-info:sensor.temp", "more-info:sensor.temp"), actions)
        assertTrue(calls.isEmpty())

        val n = bubble(st, """{"type": "custom:bubble-card", "card_type": "button", "button_type": "name", "name": "Scenes", "icon": "mdi:palette"}""")
        assertNull(n.buttonTarget)
        assertNull(n.iconTarget)
        assertEquals(listOf("Scenes"), n.texts())
    }

    @Test
    fun theCardsOwnActionsAndButtonActionCallTheirServices() {
        val st = store(entity("light.desk", "off"))
        val b = bubble(
            st,
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk",
                "tap_action": {"action": "perform-action", "perform_action": "scene.turn_on", "target": {"entity_id": "scene.evening"}},
                "button_action": {"tap_action": {"action": "perform-action", "perform_action": "script.turn_on",
                                                 "data": {"entity_id": "script.night"}},
                                  "hold_action": {"action": "none"}}}""",
        )
        assertTrue(b.buttonTarget!!.performClick())
        assertEquals("script.turn_on", calls.last().service)
        assertEquals("script.night", calls.last().entityId)
        assertTrue(b.iconTarget!!.performClick())
        assertEquals("scene.turn_on", calls.last().service)
        assertFalse(b.buttonTarget!!.isLongClickable)
        assertEquals(2, calls.size)
    }

    @Test
    fun aServiceTargetNamedEntityActsOnTheCardsEntityAndMoreInfoOpensTheActionsOwn() {
        val st = store(entity("light.desk", "on"))
        val b = bubble(
            st,
            """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk",
                "tap_action": {"action": "more-info", "entity": "sensor.power"},
                "button_action": {"tap_action": {"action": "perform-action", "perform_action": "light.turn_on",
                                                 "target": {"entity_id": "entity"}, "data": {"brightness_pct": 40}}}}""",
        )
        assertTrue(b.buttonTarget!!.performClick())
        assertEquals("light.turn_on", calls.single().service)
        assertEquals("light.desk", calls.single().target!!.getString("entity_id"))
        assertEquals(40, calls.single().data.getInt("brightness_pct"))
        assertTrue(b.iconTarget!!.performClick())
        assertEquals(listOf("more-info:sensor.power"), actions)
    }

    // ---- Colors

    @Test
    fun aSwitchThatIsOnTakesBubblesColorsAndAnOffOneNone() {
        val ctx = context(store())
        val colors = BubbleColors(ctx)
        val plain = JSONObject()
        // A light without a color: Bubble's warm white (225, 225, 210), a step darker under light text, at 70%.
        val (lamp, lampAlpha) = colors.buttonBackground("switch", entity("light.desk", "on", "brightness" to 166), plain)
        assertEquals(BubbleColors.rgb(189, 189, 176), lamp)
        assertEquals(0.7f, lampAlpha, 0f)
        assertEquals(BubbleColors.rgb(225, 225, 210), colors.iconColor(entity("light.desk", "on"), plain))
        // A colored light: its own color, then the same step.
        val (orange, _) = colors.buttonBackground("switch", entity("light.desk", "on", "rgb_color" to listOf(255, 129, 40)), plain)
        assertEquals(BubbleColors.adjust(BubbleColors.rgb(255, 129, 40), 0.84), orange)
        // Anything else: Bubble's blue mixed with the dark background (#111111), fully opaque.
        val (accent, accentAlpha) = colors.buttonBackground("switch", entity("switch.heater", "on"), plain)
        assertEquals(BubbleColors.rgb(5, 107, 184), accent)
        assertEquals(1f, accentAlpha, 0f)
        // use_accent_color on a light: the accent too.
        assertEquals(accent, colors.buttonBackground("switch", entity("light.desk", "on"), JSONObject("""{"use_accent_color": true}""")).first)
        // An unlocked lock asks for attention: red.
        assertEquals(0xFFF44336.toInt(), colors.buttonBackground("switch", entity("lock.door", "unlocked"), plain).first)
        // Off, or a state button: transparent at 50%.
        assertEquals(0 to 0.5f, colors.buttonBackground("switch", entity("light.desk", "off"), plain))
        assertEquals(0 to 0.5f, colors.buttonBackground("state", entity("switch.heater", "on"), plain))
        // A number in the state or a degree unit: the icon keeps the text color.
        assertEquals(ctx.theme.primaryTextColor, colors.iconColor(entity("sensor.power", "1234.5"), plain))
    }

    @Test
    fun bubblesOwnListOfOnStates() {
        val colors = BubbleColors(context(store()))
        for (s in listOf("on", "open", "playing", "home", "locked", "unlocked", "heat", "error", "12", "-3")) {
            assertTrue(s, colors.isStateOn(entity("sensor.x", s)))
        }
        for (s in listOf("off", "closed", "idle", "unavailable", "unknown", "0", "", "not_home")) {
            assertFalse(s, colors.isStateOn(entity("sensor.x", s)))
        }
        assertTrue(colors.isStateOn(entity("sensor.t", "cold", "unit_of_measurement" to "°C")))
    }

    // ---- The state line

    @Test
    fun theStateLineFollowsBubblesContent() {
        val lamp = entity("light.desk", "on", "brightness" to 166)
        // A state button shows the domain's default: a light's brightness.
        assertEquals(listOf("brightness"), BubbleStateLine.content(JSONObject(), "state", "light.desk"))
        assertEquals("65%", BubbleStateLine.render(lamp, listOf("brightness"), NOW, JSONObject()))
        // A switch shows nothing unless asked.
        assertNull(BubbleStateLine.content(JSONObject(), "switch", "light.desk"))
        // The legacy keys, in Bubble's order, joined by " • ".
        val content = BubbleStateLine.content(JSONObject("""{"show_state": true, "show_last_changed": true}"""), "switch", "light.desk")
        assertEquals(listOf("state", "last_changed"), content)
        val changed = entity("light.desk", "on", changedSecondsAgo = 35 * 60)
        assertEquals("On • 35 minutes ago", BubbleStateLine.render(changed, content!!, NOW, JSONObject()))
        // A brightness of 0 is left out; with nothing left the state shows.
        assertEquals("Off", BubbleStateLine.render(entity("light.desk", "off", "brightness" to 0), listOf("brightness"), NOW, JSONObject()))
        // show_state false on a state button leaves the attribute alone.
        assertEquals(listOf("current_temperature"), BubbleStateLine.content(JSONObject("""{"show_state": false, "attribute": "current_temperature"}"""), "state", "climate.hall"))
    }

    @Test
    fun relativeTimesAreBubblesOwn() {
        // Bubble counts one second more than elapsed, then rounds minutes, hours, days, weeks, 30-day months.
        assertEquals("31 seconds ago", BubbleStateLine.relativeTime(NOW - 30_000, NOW))
        assertEquals("35 minutes ago", BubbleStateLine.relativeTime(NOW - 35 * 60_000, NOW))
        assertEquals("3 hours ago", BubbleStateLine.relativeTime(NOW - (2 * 3_600_000 + 31 * 60_000), NOW))
        assertEquals("yesterday", BubbleStateLine.relativeTime(NOW - 30 * 3_600_000L, NOW))
        assertEquals("2 weeks ago", BubbleStateLine.relativeTime(NOW - 12 * 86_400_000L, NOW))
        assertEquals("in 5 minutes", BubbleStateLine.relativeTime(NOW + 5 * 60_000, NOW))
    }

    @Test
    fun theButtonShowsItsNameAndState() {
        val st = store(entity("light.desk", "on", "brightness" to 166))
        val b = bubble(st, """{"type": "custom:bubble-card", "card_type": "button", "button_type": "state", "entity": "light.desk"}""")
        assertEquals(listOf("Desk Lamp", "65%"), b.texts())
        st.putLocal(entity("light.desk", "unavailable"))
        ShadowLooper.idleMainLooper()
        assertEquals(0.5f, b.alpha, 0f)
        assertEquals(listOf("Desk Lamp", "Unavailable"), b.texts())
        val named = bubble(st, """{"type": "custom:bubble-card", "card_type": "button", "entity": "light.desk", "name": "Reading", "show_name": false, "show_state": true}""")
        assertEquals(listOf("Unavailable"), named.texts())
        assertNotNull(named.buttonTarget)
    }

    private companion object {
        const val NOW = 1_794_499_680_000L
    }
}
