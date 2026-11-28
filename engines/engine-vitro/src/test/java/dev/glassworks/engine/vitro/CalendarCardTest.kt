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
import dev.glassworks.engine.vitro.cards.CalendarCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.TimeZone

/** HA 2026's calendar card: the ranges it subscribes to, the month grid's overflow, the list and the toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val tz = TimeZone.getDefault()
    private val now = 1_795_543_680_000L // 2026-11-24 10:08 in Los Angeles
    private val asked = ArrayList<JSONObject>()

    @Before
    fun zone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null) // as the device's broadcast says it
    }

    @After
    fun back() {
        TimeZone.setDefault(tz)
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
    }

    private fun card(config: String): CalendarCardView {
        val store = EntityStore()
        store.putLocal(EntityState("calendar.maintenance", "off", mapOf("friendly_name" to "Maintenance")))
        val events = JSONArray("""[
            {"start": "2026-11-26", "end": "2026-11-27", "summary": "Pre Filter Clean"},
            {"start": "2026-11-24", "end": "2026-11-25", "summary": "Oil Boards"}
        ]""")
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { msg, onEvent, _ -> asked += msg; onEvent(JSONObject().put("events", events)); {} },
            now = { now },
        )
        return CalendarCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }

    private fun show(c: View) {
        activity.setContentView(c)
        val dp = activity.resources.displayMetrics.density
        c.measure(View.MeasureSpec.makeMeasureSpec((500 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((376 * dp).toInt(), View.MeasureSpec.EXACTLY))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        c.measure(View.MeasureSpec.makeMeasureSpec((500 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((376 * dp).toInt(), View.MeasureSpec.EXACTLY))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
    }

    @Test
    fun theMonthAsksHasRangeAndOverflowsShortDaysIntoMoreLinks() {
        val c = card("""{"type": "calendar", "entities": ["calendar.maintenance"]}""")
        show(c)
        val msg = asked.last()
        assertEquals("calendar/event/subscribe", msg.getString("type"))
        assertEquals("calendar.maintenance", msg.getString("entity_id"))
        assertEquals("2026-11-01T07:00:00.000Z", msg.getString("start"))
        assertEquals("2026-12-13T08:00:00.000Z", msg.getString("end"))
        val shown = texts(c)
        assertTrue(shown.toString(), "November 2026" in shown)
        // 6 weeks: November's 30 days and 12 of December's; the 376 px card's rows are too short for an event.
        assertEquals(2, shown.count { it == "+1 more" })
        assertTrue(shown.none { it == "Oil Boards" })
    }

    @Test
    fun theListShowsSevenDaysFromTodayByDay() {
        val c = card("""{"type": "calendar", "entities": ["calendar.maintenance"], "initial_view": "listWeek"}""")
        show(c)
        assertEquals("2026-11-24T08:00:00.000Z", asked.last().getString("start"))
        assertEquals("2026-12-01T08:00:00.000Z", asked.last().getString("end"))
        val shown = texts(c)
        assertTrue(shown.toString(), shown.containsAll(listOf("Nov 24 – 30, 2026", "Tuesday", "November 24, 2026", "All-day", "Oil Boards", "Thursday", "Pre Filter Clean")))
        assertTrue(shown.indexOf("Oil Boards") < shown.indexOf("Pre Filter Clean"))
    }

    @Test
    fun theToggleAndNextMoveTheView() {
        val c = card("""{"type": "calendar", "entities": ["calendar.maintenance"]}""")
        show(c)
        all(c).first { it.contentDescription == "List (7 days)" }.performClick()
        show(c)
        assertTrue(texts(c).contains("Nov 24 – 30, 2026"))
        assertEquals("2026-11-24T08:00:00.000Z", asked.last().getString("start"))
        all(c).first { it.contentDescription == "Next" }.performClick()
        show(c)
        assertTrue(texts(c).contains("Dec 1 – 7, 2026"))
        assertEquals("2026-12-01T08:00:00.000Z", asked.last().getString("start"))
    }
}
