/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityRowView
import dev.glassworks.engine.vitro.cards.HaSwitchView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.TimestampText
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The entities card's plain row drawn as one view ([EntityRowView]): it draws what the row of four views it replaced
 * drew, pixel for pixel, on Android 8, the S1's Android 10 and Android 14 (whose TextViews lay text out differently),
 * and it taps, holds, toggles, reads and updates as that row did, with one view a row (two with a toggle).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 29, 34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class EntityRowViewTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<Triple<String, String, JSONObject?>>()
    private val moreInfo = ArrayList<String>()
    private var dark = true

    @After fun reset() {
        EntityRowView.enabled = true
        activity.applicationInfo.flags = activity.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL.inv()
    }

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), dark), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> calls += Triple(d, s, data) },
        navigate = {}, openMoreInfo = { moreInfo += it },
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { NOW },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun put(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf(*attrs), lastChanged = (NOW - 5 * 60_000) / 1000.0))

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun count(v: View): Int = all(v).size

    /** [json]'s card on screen in a column [widthPx] wide ([rtl]: a right-to-left page), and its pixels. */
    private fun render(json: String, widthPx: Int = 464, rtl: Boolean = false): Pair<EntitiesCardView, IntArray> {
        // A view resolves right-to-left only in an app that says it supports it (the test app doesn't by itself).
        if (rtl) activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val root = FrameLayout(activity)
        if (rtl) root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        activity.setContentView(root)
        val card = EntitiesCardView(ctx(), LovelaceParser.parseCard(JSONObject(json)))
        root.addView(card, FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        repeat(3) { idle() }
        val rows = all(card).filter { it is EntityRowView || it.javaClass.simpleName == "EntityRowLayout" }
        assertTrue("the card has rows", rows.isNotEmpty())
        val want = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        assertTrue("every row is laid out ${if (rtl) "right to left" else "left to right"}", rows.all { it.layoutDirection == want })
        return card to pixels(card)
    }

    private fun pixels(v: View): IntArray {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return IntArray(v.width * v.height).also { bmp.getPixels(it, 0, v.width, 0, 0, v.width, v.height) }
    }

    /** [json] drawn with the one-view rows and with the row of views they replace: the same size and the same pixels. */
    private fun assertSamePixels(json: String, widthPx: Int = 464, rtl: Boolean = false) {
        EntityRowView.enabled = false
        val (old, before) = render(json, widthPx, rtl)
        EntityRowView.enabled = true
        val (new, after) = render(json, widthPx, rtl)
        assertTrue("the old card has no one-view rows", all(old).none { it is EntityRowView })
        assertTrue("the new card has one-view rows", all(new).any { it is EntityRowView })
        assertEquals("size", old.width to old.height, new.width to new.height)
        val differ = before.indices.filter { before[it] != after[it] }
        if (differ.isNotEmpty()) dump(old.width, old.height, before, after)
        System.getProperty("vitro.mockupDir")?.takeIf { it.isNotBlank() }?.let { dump(old.width, old.height, before, after, java.io.File(it)) }
        assertTrue(
            "${differ.size} pixels differ, first at ${differ.take(5).map { it % old.width to it / old.width }}",
            differ.isEmpty(),
        )
    }

    /**
     * A failure's two renders, for a look: `build/reports/entity-row-view/<n>-{views,row}.png`; with
     * `-PvitroMockupDir=<dir>`, every case's, there (a PR's before and after).
     */
    private fun dump(w: Int, h: Int, before: IntArray, after: IntArray, dir: java.io.File = java.io.File("build/reports/entity-row-view")) {
        dir.mkdirs()
        val n = dir.list()?.size ?: 0
        for ((name, px) in listOf("views" to before, "row" to after)) {
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            java.io.File(dir, "$n-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun plainHome() {
        put("light.desk", "on", "friendly_name" to "Desk lamp", "rgb_color" to listOf(255, 120, 0))
        put("light.hall", "off", "friendly_name" to "Hall")
        put("switch.fan", "on", "friendly_name" to "Fan")
        put("switch.gone", "unavailable", "friendly_name" to "Gone")
        put("sensor.temp", "21.456", "friendly_name" to "Living room temperature", "unit_of_measurement" to "°C", "device_class" to "temperature", "state_class" to "measurement")
        put("binary_sensor.door", "on", "friendly_name" to "Front door", "device_class" to "door")
        put("sensor.unknown", "unknown", "friendly_name" to "Mystery")
    }

    @Test
    fun plainRowsDrawAsTheirViewsDid() {
        plainHome()
        for (d in listOf(true, false)) {
            dark = d
            assertSamePixels("""{"type": "entities", "title": "Home", "entities": ["light.desk", "light.hall", "switch.fan", "switch.gone", "sensor.temp", "binary_sensor.door", "sensor.unknown", "sensor.missing"]}""")
            assertSamePixels("""{"type": "entities", "entities": ["light.desk", {"entity": "sensor.temp", "name": "Temp", "icon": "mdi:thermometer"}]}""")
        }
    }

    @Test
    fun longNamesAndStatesDrawAsTheirViewsDid() {
        put("sensor.long_state", "a state long enough to push the name all the way out of the row and then some more", "friendly_name" to "Squeezed out")
        put("sensor.long_name", "on", "friendly_name" to "A sensor name far too long for its column, cut with an ellipsis at its end")
        put("switch.long_name", "off", "friendly_name" to "A switch name far too long for its column, cut with an ellipsis at its end")
        put("sensor.wide", "1234567.89", "friendly_name" to "Energy used this year so far", "unit_of_measurement" to "kWh")
        val json = """{"type": "entities", "entities": ["sensor.long_state", "sensor.long_name", "switch.long_name", "sensor.wide"]}"""
        assertSamePixels(json)
        assertSamePixels(json, widthPx = 260)
        // The squeezed name keeps its first letters in the 8 px after it.
        val (card, _) = render(json)
        assertTrue(all(card).filterIsInstance<EntityRowView>().first().nameSqueezed)
    }

    @Test
    fun otherScriptsDrawAsTheirViewsDid() {
        put("sensor.cjk", "开", "friendly_name" to "客厅温度传感器")
        put("light.emoji", "on", "friendly_name" to "🛋️ Sofa lamp ✨")
        put("sensor.hebrew", "פתוח", "friendly_name" to "דלת כניסה")
        put("sensor.mixed", "12", "friendly_name" to "Salon température", "unit_of_measurement" to "%")
        val json = """{"type": "entities", "entities": ["sensor.cjk", "light.emoji", "sensor.hebrew", "sensor.mixed"]}"""
        assertSamePixels(json)
        assertSamePixels(json, rtl = true)
    }

    @Test
    fun aRightToLeftPageDrawsAsItsViewsDid() {
        plainHome()
        put("sensor.long_state", "a state long enough to push the name all the way out of the row", "friendly_name" to "Squeezed")
        assertSamePixels("""{"type": "entities", "title": "Home", "entities": ["light.desk", "switch.fan", "sensor.temp", "binary_sensor.door", "sensor.long_state"]}""", rtl = true)
    }

    @Test
    fun aTimestampDrawsAsItsViewsDidAndKeepsCounting() {
        put("sensor.next_alarm", "2026-11-12T07:30:00+00:00", "friendly_name" to "Next alarm", "device_class" to "timestamp")
        val json = """{"type": "entities", "entities": ["sensor.next_alarm"]}"""
        assertSamePixels(json)
        val (card, _) = render(json)
        val row = all(card).filterIsInstance<EntityRowView>().single()
        assertTrue(row.stateText.toString(), row.stateText.toString().contains("ago") || row.stateText.toString().startsWith("In "))
        assertTrue("its relative time ticks", TimestampText.isTicking(row))
    }

    @Test
    fun aRowIsOneViewAndATogglesRowTwo() {
        plainHome()
        val (card, _) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp"]}""")
        val rows = all(card).filterIsInstance<EntityRowView>()
        assertEquals(2, rows.size)
        assertEquals("a toggle's row: the row and its toggle", 2, count(rows[0]))
        assertTrue(rows[0].getChildAt(0) is HaSwitchView)
        assertEquals("a state's row: the row alone", 1, count(rows[1]))
        // The rows the old card made of four views each.
        EntityRowView.enabled = false
        val (old, _) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp"]}""")
        assertEquals("two rows fewer by 5 views", count(old) - 5, count(card))
    }

    @Test
    fun rowsWithAControlOrASecondaryLineKeepTheirViews() {
        put("input_number.level", "40", "friendly_name" to "Level", "min" to 0, "max" to 100, "step" to 1, "mode" to "slider")
        put("cover.garage", "open", "friendly_name" to "Garage")
        put("sensor.temp", "21", "friendly_name" to "Temp")
        val (card, _) = render("""{"type": "entities", "entities": ["input_number.level", "cover.garage", {"entity": "sensor.temp", "secondary_info": "last-changed"}]}""")
        assertTrue(all(card).none { it is EntityRowView })
    }

    @Test
    fun aTapOpensMoreInfoAndTheToggleToggles() {
        plainHome()
        val (card, _) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp", {"entity": "binary_sensor.door", "tap_action": {"action": "toggle"}, "hold_action": {"action": "none"}}]}""")
        val rows = all(card).filterIsInstance<EntityRowView>()
        assertTrue(rows[0].performClick())
        assertTrue(rows[1].performClick())
        assertEquals(listOf("light.desk", "sensor.temp"), moreInfo)
        assertTrue(rows[1].performLongClick())
        assertEquals(listOf("light.desk", "sensor.temp", "sensor.temp"), moreInfo)
        assertFalse("hold_action none: no long press", rows[2].isLongClickable)
        assertTrue(rows[0].switch!!.performClick())
        assertEquals("light" to "toggle", calls.last().first to calls.last().second)
        assertEquals("light.desk", calls.last().third!!.optString("entity_id"))
    }

    @Test
    fun talkBackReadsTheNameAndStateAndTheToggleOnItsOwn() {
        plainHome()
        val (card, _) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp"]}""")
        val rows = all(card).filterIsInstance<EntityRowView>()
        assertEquals("Desk lamp", rows[0].contentDescription.toString())
        assertEquals("Toggle Desk lamp", rows[0].switch!!.contentDescription.toString())
        assertEquals("Living room temperature, 21.456 °C", rows[1].contentDescription.toString())
        // A tap target as tall as HA's row.
        assertEquals((40 * activity.resources.displayMetrics.density).toInt(), rows[1].height)
    }

    @Test
    fun aNewStateLaysOutTheRowAloneAndUpdatesWhatItShows() {
        plainHome()
        val (card, _) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp"]}""")
        val rows = all(card).filterIsInstance<EntityRowView>()
        assertTrue(card.isLaidOut && !card.isLayoutRequested)
        put("sensor.temp", "1234.5", "friendly_name" to "Living room temperature", "unit_of_measurement" to "°C", "device_class" to "temperature", "state_class" to "measurement")
        put("light.desk", "off", "friendly_name" to "Desk lamp")
        idle()
        assertEquals("1,234.5 °C", rows[1].stateText.toString())
        assertFalse("the card isn't laid out again for a new width", card.isLayoutRequested)
        assertFalse(rows[1].isLayoutRequested)
        assertEquals("the light's icon in the inactive color", ctx().theme.stateIconColor, rows[0].iconColor)
        assertEquals("mdi:lightbulb-off", rows[0].icon)
        // And it draws what a card built for the new states draws.
        val now = pixels(card)
        val (_, fresh) = render("""{"type": "entities", "entities": ["light.desk", "sensor.temp"]}""")
        assertTrue("updated in place = built fresh", now.contentEquals(fresh))
    }

    /** A host that counts the layouts asked of it: one from a row's update means the update left the row. */
    private class Root(context: android.content.Context) : FrameLayout(context) {
        var requests = 0
        override fun requestLayout() {
            requests++
            super.requestLayout()
        }
    }

    @Test
    fun aRowHeldShorterThanItsTextUpdatesOnItsOwn() {
        put("sensor.power", "5", "friendly_name" to "Desk power", "unit_of_measurement" to "W")
        val root = Root(activity)
        activity.setContentView(root)
        // A card held 100 px tall: its one row gets less than its 40 dp (60 px here) and is laid out that short.
        val card = EntitiesCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": ["sensor.power"]}""")))
        root.addView(card, FrameLayout.LayoutParams(464, 100))
        repeat(3) { idle() }
        val row = all(card).filterIsInstance<EntityRowView>().single()
        val held = row.height
        assertTrue("the row is held shorter than its 60 px ($held)", held in 1 until (40 * activity.resources.displayMetrics.density).toInt())
        root.requests = 0
        for ((v, shown) in listOf("12345" to "12,345 W", "7" to "7 W")) {
            put("sensor.power", v, "friendly_name" to "Desk power", "unit_of_measurement" to "W")
            idle()
            assertEquals(shown, row.stateText.toString())
            assertEquals("no layout asked above the row for $v", 0, root.requests)
            assertFalse(root.isLayoutRequested)
            assertEquals(held, row.height)
        }
    }

    @Test
    fun aRightToLeftRowWithAnOverflowingStateKeepsItsIconOnTheRight() {
        put("sensor.long_state", "a state long enough to push the name all the way out of the row", "friendly_name" to "Squeezed")
        val (card, _) = render("""{"type": "entities", "entities": ["sensor.long_state"]}""", rtl = true)
        val row = all(card).filterIsInstance<EntityRowView>().single()
        val icon = row.iconBounds()
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        assertEquals("the icon 8 dp in from the right padding", row.width - pad - (8 * activity.resources.displayMetrics.density).toInt(), icon.right)
    }

    @Test
    fun theStateFilterHidesAndShowsRows() {
        put("binary_sensor.a", "on", "friendly_name" to "A")
        put("binary_sensor.b", "off", "friendly_name" to "B")
        val (card, _) = render("""{"type": "entities", "state_filter": ["on"], "entities": ["binary_sensor.a", "binary_sensor.b"]}""")
        val rows = all(card).filterIsInstance<EntityRowView>()
        assertEquals(listOf(View.VISIBLE, View.GONE), rows.map { it.visibility })
        put("binary_sensor.b", "on", "friendly_name" to "B")
        idle()
        assertEquals(listOf(View.VISIBLE, View.VISIBLE), rows.map { it.visibility })
    }

    private companion object {
        const val NOW = 1_794_500_000_000L
    }
}
