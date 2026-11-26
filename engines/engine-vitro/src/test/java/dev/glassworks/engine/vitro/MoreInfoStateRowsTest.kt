/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaLogbook
import dev.glassworks.engine.vitro.cards.HaSwitchView
import dev.glassworks.engine.vitro.cards.HaTimelineChart
import dev.glassworks.engine.vitro.cards.LogbookCardView
import dev.glassworks.engine.vitro.cards.MoreInfoLogbookView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MoreInfoStateCard
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu

/**
 * The state rows of HA's default More Info view that Vitro's sheet draws ([MoreInfoStateCard], frontend 20261028.5):
 * a select's menu, a button's Press, an alert's toggle, an event's last event, the display row when unavailable; and
 * a logbook card entry's tap, which hands off to HA's own dialog. The parity cases `more-info-select`,
 * `more-info-input-select`, `more-info-button`, `more-info-input-button`, `more-info-alert-*` and `more-info-event`
 * compare the drawn rows with HA's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoStateRowsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** 2026-11-12 10:08 in Chicago, as the parity harness's fixed instant. */
    private val now = 1_794_499_680_000L

    private val services = ArrayList<Triple<String, String, JSONObject?>>()
    private val pending = ArrayList<(Boolean) -> Unit>()
    private val handedOff = ArrayList<Triple<String, String?, String>>()
    private val openedNatively = ArrayList<String>()
    private val wsSubs = ArrayList<(JSONObject) -> Unit>()
    private var frontend = true

    @After fun tearDown() = HaDisplay.reset()

    private fun context(store: EntityStore, components: List<String>? = null) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(), accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> services += Triple(d, s, data) },
        callServiceThen = { d, s, data, _, done ->
            services += Triple(d, s, data)
            pending += done
        },
        navigate = {}, openMoreInfo = { openedNatively += it }, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haConfig = { if (components == null) JSONObject() else JSONObject().put("components", JSONArray(components)) },
        subscribeWs = { _, onEvent, _ ->
            wsSubs += onEvent
            ({})
        },
        now = { now },
        moreInfoInFrontend = { id, view, reason ->
            if (frontend) handedOff += Triple(id, view, reason)
            frontend
        },
    )

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.map { it.text.toString() }

    private fun store(vararg states: EntityState) = EntityStore().apply { states.forEach(::putLocal) }

    private fun open(store: EntityStore, id: String): MoreInfoSheet {
        val sheet = MoreInfoSheet(context(store)) {}
        activity.setContentView(sheet)
        sheet.show(id)
        idle()
        return sheet
    }

    private fun MoreInfoSheet.row(): MoreInfoStateCard = all(this).filterIsInstance<MoreInfoStateCard>().single()

    private fun dryer(state: String = "Cotton") =
        EntityState(DRYER, state, mapOf("options" to listOf("Cotton", "Synthetics", "Wool"), "friendly_name" to "Dryer Program"))

    // ---- select, input_select ----

    @Test
    fun aSelectsDialogIsItsMenuThenTheHistoryAndTheActivity() {
        val sheet = open(store(dryer()), DRYER)
        assertEquals("select", sheet.row().shownType)
        val shown = texts(sheet)
        // state-card-select: the badge and the menu (its label hidden), no name or time.
        assertTrue(shown.toString(), "Cotton" in shown)
        assertFalse(shown.toString(), "Dryer Program" in shown.drop(1))
        assertTrue(shown.toString(), shown.indexOf("Cotton") < shown.indexOf("History"))
        assertTrue(shown.toString(), shown.indexOf("History") < shown.indexOf("Activity"))
        assertEquals(1, all(sheet).filterIsInstance<MoreInfoLogbookView>().size)
        // The old option list is gone: one control, not a row per option.
        assertFalse(shown.toString(), "Wool" in shown)
    }

    @Test
    fun pickingAnotherOptionSelectsIt() {
        val sheet = open(store(dryer()), DRYER)
        val menu = all(sheet.row()).single { it.contentDescription?.toString() == "Program, Cotton" || it.contentDescription?.toString() == "Dryer Program, Cotton" }
        menu.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        val items = (0 until popup.menu.size()).map { popup.menu.getItem(it) }
        assertEquals(listOf("Cotton", "Synthetics", "Wool"), items.map { it.title.toString() })
        assertTrue("the selected option is checked", items[0].isChecked)
        shadowOf(popup).onMenuItemClickListener.onMenuItemClick(items[2])
        assertEquals(Triple("select", "select_option", "Wool"), services.single().let { Triple(it.first, it.second, it.third?.getString("option")) })
        // The selected one again sends nothing (`_selectedOptionChanged`).
        menu.performClick()
        val again = ShadowPopupMenu.getLatestPopupMenu()
        shadowOf(again).onMenuItemClickListener.onMenuItemClick(again.menu.getItem(0))
        assertEquals(1, services.size)
    }

    @Test
    fun anInputSelectCallsItsOwnDomain() {
        val mode = EntityState("input_select.house_mode", "Day", mapOf("options" to listOf("Day", "Night")))
        val sheet = open(store(mode), mode.entityId)
        all(sheet.row()).single { it.contentDescription?.toString()?.endsWith(", Day") == true }.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.getItem(1))
        assertEquals("input_select" to "select_option", services.single().first to services.single().second)
    }

    @Test
    fun anUnavailableSelectShowsTheDisplayRowUntilItComesBack() {
        val store = store(dryer("unavailable"))
        val sheet = open(store, DRYER)
        // stateCardType: an unavailable entity's row is state-card-display.
        assertEquals("display", sheet.row().shownType)
        assertTrue(texts(sheet).toString(), "Unavailable" in texts(sheet))
        store.putLocal(dryer("Wool"))
        idle()
        assertEquals("select", sheet.row().shownType)
        assertTrue(texts(sheet).toString(), "Wool" in texts(sheet))
    }

    @Test
    fun theBadgeAndTheMenuShareTheRowAsFlexboxShrinksThem() {
        // A 40 px badge, 8 px, a menu `width: 100%`: 48 px too wide, shared by their bases. The S1's sheet: 912 px.
        val (badge, menu) = MoreInfoStateCard.shares(912f)
        assertEquals(37.98f, badge, 0.01f)
        assertEquals(912f - 8f - badge, menu, 0.01f)
        // The tablet's 580 px dialog (532 px inside).
        assertEquals(36.64f, MoreInfoStateCard.shares(532f).first, 0.01f)
    }

    // ---- number, input_number ----

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths: the name is wider than the row
    fun aLongNumberNameGivesWayToItsControl() {
        // state-info shrinks to its 120 px min-width and ellipsizes; the `.flex` keeps its content (the slider, the box).
        val long = "A very long number name that would take the whole row of the dialog and then some more"
        for (state in listOf(
            EntityState("number.volume", "40", mapOf("friendly_name" to long, "mode" to "slider", "min" to 0, "max" to 100, "step" to 1)),
            EntityState("number.volume", "40", mapOf("friendly_name" to long, "mode" to "box", "min" to 0, "max" to 100, "step" to 1)),
        )) {
            val sheet = open(store(state), "number.volume")
            val row = all(sheet).filterIsInstance<dev.glassworks.engine.vitro.cards.StateCardRow>().single()
            val w = Math.round(400 * activity.resources.displayMetrics.density)
            row.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            assertTrue("the control keeps its room: ${row.flexWidthPx}", row.flexWidthPx > 0)
            val control = row.getChildAt(2)
            val shown = all(control).filter { it.isShown && it !== control && it.width > 0 }
            assertTrue("a drawn control (${state.attributes["mode"]})", shown.isNotEmpty())
            assertTrue("the control ends at the row's end", control.right <= row.measuredWidth && control.left >= 0)
            val name = all(row).filterIsInstance<TextView>().first { it.text.toString() == long }
            assertTrue("the name alone is wider than the row", name.paint.measureText(long) > w)
            assertTrue("state-info ends where the `.flex` starts", (name.parent as View).right <= row.measuredWidth - row.flexWidthPx)
            assertTrue("the name ellipsizes", (name.layout?.getEllipsisCount(0) ?: 0) > 0)
        }
    }

    // ---- button, input_button ----

    @Test
    fun pressPressesTheButtonAndShowsItsResult() {
        val button = EntityState("button.coffee_descale", "unknown", mapOf("friendly_name" to "Descale"))
        val sheet = open(store(button), button.entityId)
        assertEquals("button", sheet.row().shownType)
        val shown = texts(sheet)
        assertTrue(shown.toString(), "Descale" in shown && "Press" in shown)
        val press = all(sheet.row()).single { it.contentDescription?.toString() == "Press Descale" }
        press.performClick()
        press.performClick() // busy: one press at a time
        assertEquals(listOf("button" to "press"), services.map { it.first to it.second })
        pending.single()(true)
        idle()
        val label = all(press).filterIsInstance<TextView>().single()
        assertEquals("the result takes the label's place", 0f, label.alpha)
        ShadowLooper.idleMainLooper(MoreInfoStateCard.RESULT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1f, label.alpha)
    }

    @Test
    fun anInputButtonPressesItsOwnDomain() {
        val bell = EntityState("input_button.ring", "unknown", mapOf("friendly_name" to "Ring"))
        val sheet = open(store(bell), bell.entityId)
        all(sheet.row()).single { it.contentDescription?.toString() == "Press Ring" }.performClick()
        assertEquals("input_button" to "press", services.single().first to services.single().second)
    }

    // ---- alert ----

    @Test
    fun anActiveAlertHasItsToggleAndATapAcknowledgesIt() {
        val alert = EntityState("alert.freezer", "on", mapOf("friendly_name" to "Freezer door open"))
        val store = store(alert)
        val sheet = open(store, alert.entityId)
        assertEquals("alert", sheet.row().shownType)
        val toggle = all(sheet.row()).filterIsInstance<HaSwitchView>().single()
        assertTrue(toggle.isShown && toggle.isOn)
        toggle.performClick()
        assertEquals("alert" to "turn_off", services.single().first to services.single().second)
        assertFalse("switched at once", toggle.isOn)
        // No new state within 2 s: back to the alert's own.
        ShadowLooper.idleMainLooper(MoreInfoStateCard.TOGGLE_RESYNC_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(toggle.isOn)
        // Acknowledged ("off") is still active: the toggle shows, off, and a tap turns it on again.
        store.putLocal(alert.copy(state = "off"))
        idle()
        assertTrue(toggle.isShown)
        assertFalse(toggle.isOn)
        toggle.performClick()
        assertEquals("turn_on", services.last().second)
    }

    @Test
    fun anIdleAlertShowsItsState() {
        val alert = EntityState("alert.garage", "idle", mapOf("friendly_name" to "Garage left open"))
        val sheet = open(store(alert), alert.entityId)
        assertFalse(all(sheet.row()).filterIsInstance<HaSwitchView>().single().isShown)
        assertTrue(texts(sheet).toString(), "Idle" in texts(sheet))
    }

    @Test
    fun anAlertsTimelineIsRedOnAndOrangeAcknowledged() {
        // stateColorProperties: --state-alert-on-color (red), --state-alert-off-color (orange), idle the inactive grey.
        val alert = EntityState("alert.garage", "idle", emptyMap())
        val ctx = context(store(alert))
        assertEquals(0xFFF44336.toInt(), HaTimelineChart.stateColor(ctx, alert.entityId, "on"))
        assertEquals(0xFFFF9800.toInt(), HaTimelineChart.stateColor(ctx, alert.entityId, "off"))
        assertEquals(ctx.theme.stateInactiveColor, HaTimelineChart.stateColor(ctx, alert.entityId, "idle"))
    }

    // ---- event ----

    @Test
    fun anEventShowsItsLastTimeOverItsType() {
        val event = EntityState("event.doorbell", "2026-11-12T15:43:00+00:00",
            mapOf("event_type" to "pressed", "event_types" to listOf("pressed"), "friendly_name" to "Doorbell Press"))
        val sheet = open(store(event), event.entityId)
        assertEquals("event", sheet.row().shownType)
        val shown = texts(sheet.row())
        assertEquals(shown.toString(), "pressed", shown.last())
        // formatEntityState of a timestamp domain: its date and time, in HA's zone.
        assertTrue(shown.toString(), shown[shown.size - 2].startsWith("November 12, 2026 at "))
    }

    // ---- types ----

    @Test
    fun theRowTypesAreHasStateCardTypes() {
        fun type(id: String, state: String) = MoreInfoStateCard.typeOf(id, EntityState(id, state, emptyMap()))
        assertEquals("select", type("select.a", "x"))
        assertEquals("select", type("input_select.a", "x"))
        assertEquals("button", type("button.a", "unknown"))
        assertEquals("button", type("input_button.a", "unknown"))
        assertEquals("alert", type("alert.a", "idle"))
        assertEquals("event", type("event.a", "unknown"))
        assertEquals("display", type("sensor.a", "1"))
        assertEquals("display", type("button.a", "unavailable"))
        assertEquals("display", MoreInfoStateCard.typeOf("select.a", null))
    }

    // ---- a logbook card's entry ----

    @Test
    fun aLogbookCardEntryOpensHasDialogWhereItsDetailIs() {
        val door = EntityState("binary_sensor.front_door", "on", mapOf("device_class" to "door"))
        val light = EntityState("light.desk", "on", emptyMap())
        val temp = EntityState("sensor.attic", "21", mapOf("unit_of_measurement" to "°C"))
        val ctx = context(store(door, light, temp))
        // The default view lists a binary sensor's Activity; a light's dialog lists it on its History view.
        HaLogbook.openEntryDetail(ctx, door.entityId)
        HaLogbook.openEntryDetail(ctx, light.entityId)
        assertEquals(listOf(door.entityId to null, light.entityId to "history"), handedOff.map { it.first to it.second })
        // HA's dialog has no Activity for a numeric sensor: Vitro's own More Info. No entity: nothing.
        HaLogbook.openEntryDetail(ctx, temp.entityId)
        HaLogbook.openEntryDetail(ctx, null)
        assertEquals(listOf(temp.entityId), openedNatively)
        // No page for HA's frontend (a test, no engine): Vitro's More Info.
        frontend = false
        HaLogbook.openEntryDetail(ctx, door.entityId)
        assertEquals(listOf(temp.entityId, door.entityId), openedNatively)
    }

    @Test
    fun aTapOnTheLogbookCardsRowHandsOff() {
        val door = EntityState("binary_sensor.front_door", "on", mapOf("device_class" to "door", "friendly_name" to "Front Door"))
        val ctx = context(store(door))
        val card = LogbookCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "logbook", "entities": ["binary_sensor.front_door"]}""")))
        activity.setContentView(card)
        idle()
        // The card subscribes again as it shows: the latest stream is the live one.
        wsSubs.last()(JSONObject().put("events", JSONArray().put(
            JSONObject().put("entity_id", door.entityId).put("state", "on").put("when", now / 1000.0 - 60))))
        idle()
        // The entry's row: the innermost view with a click listener around its value.
        all(card).filter { it.hasOnClickListeners() && texts(it).any { t -> t.startsWith("Open") } }.last().performClick()
        assertEquals(door.entityId to null, handedOff.single().let { it.first to it.second })
        assertTrue(openedNatively.isEmpty())
    }

    private companion object {
        const val DRYER = "select.dryer_program"
    }
}
