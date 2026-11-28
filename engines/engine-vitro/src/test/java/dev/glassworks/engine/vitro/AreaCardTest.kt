/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AreaCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaGridSize
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
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

/**
 * HA 2026's area card: the area's icon from the area registry over a tile row, the `sensor_classes` line and the
 * `alert_classes` boxes from the area's entities (its own or its device's area), and HA's grid size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AreaCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After
    fun forgetRegistries() = HaDisplay.reset()

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun shownTexts(card: View) = all(card).filterIsInstance<TextView>().filter { it.isShownIn(card) }.map { it.text.toString() }
    private fun shownIcons(card: View) = all(card).filterIsInstance<MdiIconView>().filter { it.isShownIn(card) }

    /** Visible, with every ancestor up to [root] and [root] itself visible too. */
    private fun View.isShownIn(root: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent as? View
        }
        return true
    }

    private fun card(config: String): AreaCardView {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return AreaCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    private fun kitchen(icon: String? = "mdi:stove") = HaDisplay.applyAreas(JSONArray().put(
        JSONObject().put("area_id", "kitchen").put("name", "Kitchen").put("icon", icon ?: JSONObject.NULL).put("picture", JSONObject.NULL),
    ))

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    @Test
    fun theDefaultCardIsTheAreaIconOverItsNameWithNoTapAction() {
        kitchen()
        val card = card("""{"type": "area", "area": "kitchen"}""")
        val dp = activity.resources.displayMetrics.density
        layout(card, (246 * dp).toInt(), (184 * dp).toInt())
        assertEquals(listOf("Kitchen"), shownTexts(card))
        val icons = shownIcons(card)
        assertEquals(listOf("mdi:stove", "mdi:stove"), icons.map { it.icon })
        // The header's 48 px icon, then the tile's 24 px one.
        assertEquals(listOf(48, 24), icons.map { Math.round(it.width / dp) })
        // HA's tap_action defaults to none.
        assertFalse(card.isClickable)
        // 6 columns by 3 rows (the tile and two for the picture); compact is the tile's one row.
        assertEquals(HaGridSize(6, 3), HaGridSize.of(LovelaceParser.parseCard(JSONObject("""{"type": "area", "area": "kitchen"}"""))))
        assertEquals(HaGridSize(6, 1), HaGridSize.of(LovelaceParser.parseCard(JSONObject("""{"type": "area", "area": "k", "display_type": "compact"}"""))))
        assertEquals(HaGridSize(6, 2), HaGridSize.of(LovelaceParser.parseCard(JSONObject("""{"type": "area", "area": "k", "display_type": "compact", "vertical": true}"""))))
    }

    @Test
    fun anAreaWithoutAnIconHasAnEmptyHeaderAndTheTextureBoxInItsTile() {
        kitchen(icon = null)
        val card = card("""{"type": "area", "area": "kitchen"}""")
        assertEquals(listOf("mdi:texture-box"), shownIcons(card).map { it.icon })
    }

    @Test
    fun theAreaArrivingAfterTheCardRendersItsNameAndIcon() {
        val card = card("""{"type": "area", "area": "kitchen"}""")
        assertEquals(listOf("Kitchen"), shownTexts(card)) // the id in words until then
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Cocina").put("icon", "mdi:stove")))
        store.renotifyAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Cocina"), shownTexts(card))
        assertEquals(listOf("mdi:stove", "mdi:stove"), shownIcons(card).map { it.icon })
    }

    @Test
    fun sensorClassesShowTheMedianOfTheAreasSensorsByTheirOwnOrTheirDevicesArea() {
        kitchen()
        HaDisplay.applyDevices(JSONArray().put(JSONObject().put("id", "dev1").put("name", "Hygrometer").put("area_id", "kitchen")))
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "sensor.counter_t").put("ai", "kitchen"))
            .put(JSONObject().put("ei", "sensor.window_t").put("ai", "kitchen"))
            .put(JSONObject().put("ei", "sensor.fridge_t").put("ai", "kitchen"))
            .put(JSONObject().put("ei", "sensor.chip_t").put("ai", "kitchen").put("ec", 1)) // diagnostic: left out
            .put(JSONObject().put("ei", "sensor.office_t").put("ai", "office"))
            .put(JSONObject().put("ei", "sensor.hygro_h").put("di", "dev1")))) // the device's area
        fun t(id: String, v: String) = store.putLocal(EntityState(id, v, mapOf("device_class" to "temperature", "unit_of_measurement" to "°C")))
        t("sensor.counter_t", "21.0"); t("sensor.window_t", "19.0"); t("sensor.fridge_t", "22.5"); t("sensor.chip_t", "60"); t("sensor.office_t", "30")
        store.putLocal(EntityState("sensor.hygro_h", "48", mapOf("device_class" to "humidity", "unit_of_measurement" to "%")))
        val card = card("""{"type": "area", "area": "kitchen", "sensor_classes": ["temperature", "humidity"]}""")
        assertEquals(listOf("Kitchen", "21 °C · 48%"), shownTexts(card))
    }

    @Test
    fun anActiveAlertIsABoxOverTheHeaderOrABadgeWhenCompact() {
        kitchen()
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.sink_leak").put("ai", "kitchen"))
            .put(JSONObject().put("ei", "binary_sensor.door").put("ai", "kitchen"))))
        store.putLocal(EntityState("binary_sensor.sink_leak", "on", mapOf("device_class" to "moisture")))
        store.putLocal(EntityState("binary_sensor.door", "on", mapOf("device_class" to "door")))
        val picture = card("""{"type": "area", "area": "kitchen", "alert_classes": ["moisture"]}""")
        // The header icon, the alert's icon, the tile icon: only the moisture class shows.
        assertEquals(3, shownIcons(picture).size)
        val compact = card("""{"type": "area", "area": "kitchen", "display_type": "compact", "alert_classes": ["moisture"]}""")
        // No header: the tile icon and the badge's.
        assertEquals(2, shownIcons(compact).size)
        store.putLocal(EntityState("binary_sensor.sink_leak", "off", mapOf("device_class" to "moisture")))
        val dry = card("""{"type": "area", "area": "kitchen", "display_type": "compact", "alert_classes": ["moisture"]}""")
        assertEquals(1, shownIcons(dry).size)
        assertTrue(shownTexts(dry).contains("Kitchen"))
    }
}
