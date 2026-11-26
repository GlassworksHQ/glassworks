/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.ButtonCardView
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.HaStateActive
import dev.glassworks.engine.vitro.cards.TodoItem
import dev.glassworks.engine.vitro.cards.TodoListCardView
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

/**
 * The state colors HA's button and glance cards draw their icons in (frontend 20261028.5: `hui-button-card`'s
 * `_computeColor`, `<state-badge>` with `color: "state"`, `stateColorCss`, `stateActive`), and the to-do list's due
 * line on a completed item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StateIconColorTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val theme = ThemeResolver.resolve(ThemeCatalog(), true)

    private fun context(store: EntityStore = EntityStore(), now: () -> Long = System::currentTimeMillis) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, now = now,
    )

    private fun card(json: String) = JSONObject(json).let { CardConfig(it.getString("type"), it) }

    private fun View.all(): List<View> = listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    private val states = listOf(
        EntityState("lock.locked", "locked", emptyMap()),
        EntityState("lock.unlocked", "unlocked", emptyMap()),
        EntityState("lock.jammed", "jammed", emptyMap()),
        EntityState("light.gone", "unavailable", emptyMap()),
        EntityState("light.off", "off", emptyMap()),
        EntityState("light.on", "on", emptyMap()),
        EntityState("light.dimmed", "on", mapOf("brightness" to 166)),
        EntityState("light.orange", "on", mapOf("brightness" to 255, "rgb_color" to listOf(255, 129, 40))),
        EntityState("sensor.temperature", "21.4", mapOf("unit_of_measurement" to "°C", "device_class" to "temperature")),
        EntityState("sensor.battery", "12", mapOf("unit_of_measurement" to "%", "device_class" to "battery")),
        EntityState("sensor.gone", "unavailable", mapOf("device_class" to "temperature")),
        EntityState("climate.heating", "heat_cool", mapOf("hvac_action" to "heating", "rgb_color" to listOf(0, 0, 255))),
        EntityState("climate.unmapped", "heat", mapOf("hvac_action" to "made_up")),
    )

    private fun store() = EntityStore().apply { states.forEach { putLocal(it) } }

    private val green = 0xFF4CAF50.toInt()
    private val red = 0xFFF44336.toInt()
    private val amber = 0xFFFFC107.toInt()
    private val deepOrange = 0xFFFF6F22.toInt()

    /** HA's `stateColorBrightness`: `brightness((b + 245) / 5 %)` on each channel. */
    private fun dimmed(c: Int, brightness: Int): Int {
        val f = (brightness + 245) / 500f
        fun ch(v: Int) = Math.round(v * f).coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    /** What both cards draw for each entity (the button card's hvac rule differs, checked apart). */
    private val expected: Map<String, Int> get() = mapOf(
        "lock.locked" to green,
        "lock.unlocked" to red,
        "lock.jammed" to red,
        "light.gone" to theme.disabledTextColor,
        "light.off" to theme.stateIconColor,
        "light.on" to amber,
        "light.dimmed" to dimmed(amber, 166),
        "light.orange" to Color.rgb(255, 129, 40),
        "sensor.temperature" to theme.stateIconColor,
        "sensor.battery" to red,
        "sensor.gone" to theme.disabledTextColor,
        "climate.unmapped" to theme.stateIconColor,
    )

    private fun hex(c: Int) = String.format("#%08X", c)

    @Test
    fun anUnavailableIconTakesTheThemesUnavailableColor() {
        // HA's iconColorCSS reads --state-unavailable-color: a theme that sets it wins over the disabled text color.
        val themed = ThemeResolver.resolve(ThemeCatalog(defaultTheme = "t", themes = mapOf("t" to ThemeCatalog.ThemeDefinition(
            base = mapOf("state-unavailable-color" to "#ff0000", "disabled-text-color" to "#666666")))), true)
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store(), theme = themed, resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val gone = EntityState("light.gone", "unavailable", emptyMap())
        assertEquals(hex(Color.RED), hex(dev.glassworks.engine.vitro.cards.HaStateColor.icon(ctx, gone, hvacOverRgb = false)))
        // Without it, HA's default: the disabled text color.
        assertEquals(hex(theme.disabledTextColor), hex(dev.glassworks.engine.vitro.cards.HaStateColor.icon(context(), gone, hvacOverRgb = false)))
    }

    @Test
    fun buttonIconsTakeHasStateColors() {
        val store = store()
        for ((entity, color) in expected + ("climate.heating" to Color.rgb(0, 0, 255))) {
            val b = ButtonCardView(context(store), card("""{"type": "button", "entity": "$entity"}"""))
            val icon = b.all().filterIsInstance<MdiIconView>().single()
            assertEquals(entity, hex(color), hex(icon.tintColor))
        }
    }

    @Test
    fun glanceIconsTakeHasStateBadgeColors() {
        val store = store()
        val entities = (expected.keys + "climate.heating").toList()
        val g = GlanceCardView(context(store), card("""{"type": "glance", "columns": 6, "entities": ${org.json.JSONArray(entities)}}"""))
        val icons = g.all().filterIsInstance<MdiIconView>()
        assertEquals(entities.size, icons.size)
        // The badge applies hvac_action after rgb_color: heating is the heat color, as HA's state-badge draws it.
        val want = expected + ("climate.heating" to deepOrange)
        entities.forEachIndexed { i, e -> assertEquals(e, hex(want.getValue(e)), hex(icons[i].tintColor)) }
    }

    @Test
    fun stateActiveIsHas() {
        fun active(id: String, state: String) = HaStateActive.of(EntityState(id, state, emptyMap()), state)
        assertTrue(active("lock.front", "jammed"))
        assertFalse(active("lock.front", "locked"))
        assertTrue(active("alarm_control_panel.home", "armed_away"))
        assertTrue(active("water_heater.tank", "eco"))
        assertTrue(active("alert.door", "off"))
        assertFalse(active("alert.door", "idle"))
        assertFalse(active("light.desk", "unknown"))
        assertTrue(active("scene.evening", "unknown")) // a timestamp domain: anything but unavailable
        assertFalse(active("media_player.tv", "standby"))
    }

    @Test
    fun aCompletedItemsOverdueDateIsTheSecondaryColor() {
        val todo = TodoListCardView(context(), card("""{"type": "todo-list", "entity": "todo.shopping"}"""))
        todo.setItemsForTest(listOf(
            TodoItem("1", "Open and late", false, due = "2020-01-02"),
            TodoItem("2", "Done and late", true, due = "2020-01-02"),
        ))
        val warning = ThemeResolver.parseColor(theme.vars["warning-color"]) ?: 0xFFFFA600.toInt()
        // The due line's text: a relative time ("6 years ago"), one per item, in the order of the items.
        val due = todo.all().filterIsInstance<TextView>().filter { it.text.endsWith("ago") }
        assertEquals(due.map { it.text }.toString(), 2, due.size)
        assertEquals(hex(warning), hex(due[0].currentTextColor))
        assertEquals(hex(theme.secondaryTextColor), hex(due[1].currentTextColor))
    }
}
