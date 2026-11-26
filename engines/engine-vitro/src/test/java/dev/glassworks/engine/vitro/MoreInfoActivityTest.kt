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
import dev.glassworks.engine.vitro.cards.LogbookCardView
import dev.glassworks.engine.vitro.cards.LogbookEntry
import dev.glassworks.engine.vitro.cards.MoreInfoLogbookView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * HA's Activity section in Vitro's More Info sheet (`ha-more-info-logbook`, frontend 20261028.5): where it shows, the
 * `logbook/event_stream` subscription and how its messages page in, what a row says, its cause, and what a tap does.
 * The parity cases `more-info-activity-*` compare the drawn section with HA's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoActivityTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** 2026-11-12 10:08 in Chicago, as the parity harness's fixed instant. */
    private val now = 1_794_499_680_000L
    private val nowSec = now / 1000.0

    private class Sub(val message: () -> JSONObject, val onEvent: (JSONObject, Boolean) -> Unit, val onError: (JSONObject?) -> Unit) {
        var closed = false
    }

    private val subs = ArrayList<Sub>()
    private val calls = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()
    private val handedOff = ArrayList<Triple<String, String, String>>()
    private val navigated = ArrayList<String>()
    private val watched = ArrayList<String>()

    @After fun tearDown() = HaDisplay.reset()

    private fun context(store: EntityStore, admin: Boolean = false, components: List<String>? = null) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(), accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> },
        navigate = { navigated += it }, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haConfig = { if (components == null) JSONObject() else JSONObject().put("components", JSONArray(components)) },
        haUser = { JSONObject().put("is_admin", admin) },
        watchEntity = { watched += it },
        callWs = { msg, done -> calls += msg to done },
        subscribeWsFresh = { message, onEvent, onError ->
            val sub = Sub(message, onEvent, onError)
            subs += sub
            ({ sub.closed = true })
        },
        now = { now },
    )

    private fun idle() = ShadowLooper.idleMainLooper()

    /** The Activity section's subscription (the history section has its own). */
    private fun logbook(): Sub = subs.single { it.message().getString("type") == "logbook/event_stream" }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.map { it.text.toString() }

    private fun store(vararg states: EntityState) = EntityStore().apply { states.forEach(::putLocal) }

    private fun door(state: String = "off") = EntityState(DOOR, state, mapOf("device_class" to "door", "friendly_name" to "Front Door"))

    private fun open(store: EntityStore, id: String, admin: Boolean = false, components: List<String>? = null): MoreInfoSheet {
        val sheet = MoreInfoSheet(context(store, admin, components)) {}
        sheet.inFrontend = { entity, view, reason -> handedOff += Triple(entity, view, reason) }
        activity.setContentView(sheet)
        sheet.show(id)
        idle()
        return sheet
    }

    private fun row(entityId: String, state: String?, agoSec: Double, vararg extra: Pair<String, Any?>) =
        JSONObject().put("entity_id", entityId).put("when", nowSec - agoSec).apply {
            if (state != null) put("state", state)
            extra.forEach { (k, v) -> put(k, v) }
        }

    private fun message(vararg rows: JSONObject, partial: Boolean = true) =
        JSONObject().put("events", JSONArray(rows.toList())).put("start_time", nowSec - 86_400).put("end_time", nowSec).apply {
            if (partial) put("partial", true)
        }

    private fun MoreInfoSheet.activityView() = all(this).filterIsInstance<MoreInfoLogbookView>().single()

    // ---- Where it shows ----

    @Test
    fun aBinarySensorsDefaultDialogHasTheActivitySectionAfterItsHistory() {
        val sheet = open(store(door()), DOOR)
        val shown = texts(sheet)
        assertTrue(shown.toString(), shown.indexOf("History") in 0 until shown.indexOf("Activity"))
        assertEquals("logbook/event_stream", logbook().message().getString("type"))
    }

    @Test
    fun aNumericSensorAndACounterHaveNoActivitySection() {
        // computeShowLogBookComponent: continuous domains and numeric sensors log nothing worth reading.
        val temp = EntityState("sensor.attic", "21", mapOf("unit_of_measurement" to "°C"))
        val power = EntityState("sensor.oven", "0", mapOf("device_class" to "power"))
        val counter = EntityState("counter.visits", "3", emptyMap())
        val config = JSONObject()
        assertFalse(HaLogbook.shows(temp.entityId, temp, config))
        assertFalse(HaLogbook.shows(power.entityId, power, config))
        assertFalse(HaLogbook.shows(counter.entityId, counter, config))
        assertFalse("a camera", HaLogbook.shows("camera.door", EntityState("camera.door", "idle", emptyMap()), config))
        assertFalse("no state yet", HaLogbook.shows(DOOR, null, config))
        // An enum or text sensor, a tracker, a number: logged.
        assertTrue(HaLogbook.shows("sensor.washer", EntityState("sensor.washer", "idle", mapOf("device_class" to "enum")), config))
        assertTrue(HaLogbook.shows("device_tracker.phone", EntityState("device_tracker.phone", "home", emptyMap()), config))
        assertTrue(HaLogbook.shows("number.volume", EntityState("number.volume", "5", mapOf("unit_of_measurement" to "%")), config))
        val sheet = open(store(temp), temp.entityId)
        assertFalse("Activity" in texts(sheet))
        assertTrue(subs.none { it.message().getString("type") == "logbook/event_stream" })
    }

    @Test
    fun withoutHasLogbookIntegrationThereIsNoActivitySection() {
        val sheet = open(store(door()), DOOR, components = listOf("history", "binary_sensor"))
        assertFalse("Activity" in texts(sheet))
        assertTrue(subs.none { it.message().getString("type") == "logbook/event_stream" })
    }

    @Test
    fun theHistoryTabOfALightShowsItsActivityToo() {
        // ha-more-info-history-and-logbook: a domain with a dialog of its own has both behind its History button.
        val light = EntityState("light.desk", "on", mapOf("friendly_name" to "Desk", "supported_color_modes" to listOf("onoff"), "color_mode" to "onoff"))
        val sheet = open(store(light), light.entityId)
        assertFalse("the controls have no Activity", "Activity" in texts(sheet))
        all(sheet).single { it.contentDescription == "History" }.performClick()
        idle()
        assertTrue(texts(sheet).containsAll(listOf("History", "Activity")))
        assertEquals("light.desk", logbook().message().getJSONArray("entity_ids").getString(0))
    }

    // ---- The subscription ----

    @Test
    fun itSubscribesForTheLast24HoursToAYearAheadAsHasLogbookDoes() {
        open(store(door()), DOOR)
        val msg = logbook().message()
        assertEquals("2026-11-11T16:08:00.000Z", msg.getString("start_time"))
        assertEquals("2027-11-12T16:08:00.000Z", msg.getString("end_time"))
        assertEquals(JSONArray(listOf(DOOR)).toString(), msg.getJSONArray("entity_ids").toString())
        // A non-administrator's page reads no user list.
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anAdministratorsSectionReadsHasUsersForTheirNames() {
        open(store(door()), DOOR, admin = true)
        assertEquals("config/auth/list", calls.single().first.getString("type"))
    }

    @Test
    fun itSpinsUntilTheFirstMessageThenSaysNoActivityFound() {
        val sheet = open(store(door()), DOOR)
        assertFalse("No activity found." in texts(sheet))
        logbook().onEvent(message(), true)
        idle()
        assertTrue("No activity found." in texts(sheet))
    }

    @Test
    fun historyInPartsThenLiveRowsStayNewestFirst() {
        val sheet = open(store(door()), DOOR)
        val sub = logbook()
        // The window's history comes oldest first, in parts (`partial`), then an empty message ends it.
        sub.onEvent(message(row(DOOR, "on", 3 * 3600.0), row(DOOR, "off", 3 * 3600.0 - 300)), true)
        sub.onEvent(message(row(DOOR, "on", 3600.0)), false)
        sub.onEvent(message(partial = false), false)
        // A live row, newer than all.
        sub.onEvent(message(row(DOOR, "off", 60.0), partial = false), false)
        idle()
        val shown = sheet.activityView().rows.map { it.second.state }
        assertEquals(listOf("off", "on", "off", "on"), shown)
        assertEquals(listOf("Closed", "Open", "Closed", "Open"), texts(sheet.activityView()).filter { it == "Open" || it == "Closed" })
    }

    @Test
    fun mergeFollowsHasRulesForNewerOlderAndInBetweenRows() {
        fun e(ago: Double) = LogbookEntry(DOOR, null, null, "on", nowSec - ago)
        val current = listOf(e(100.0), e(200.0), e(300.0))
        // An empty message changes nothing.
        assertEquals(current, HaLogbook.merge(current, emptyList(), null))
        // Older ones (oldest first as sent) go under.
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0, 500.0), HaLogbook.merge(current, listOf(e(500.0), e(400.0)), null).map { nowSec - it.whenSec })
        // Rows in between are sorted in.
        assertEquals(listOf(100.0, 150.0, 200.0, 300.0), HaLogbook.merge(current, listOf(e(150.0)), null).map { nowSec - it.whenSec })
        // Rows older than the window go when new ones come.
        assertEquals(listOf(50.0, 100.0), HaLogbook.merge(current, listOf(e(50.0)), nowSec - 150).map { nowSec - it.whenSec })
    }

    @Test
    fun aReconnectStartsTheListOverFromTheNewWindow() {
        val sheet = open(store(door()), DOOR)
        val sub = logbook()
        sub.onEvent(message(row(DOOR, "on", 600.0)), true)
        idle()
        assertEquals(1, sheet.activityView().rows.size)
        // The new connection replays the window: its first message replaces the list rather than doubling it.
        sub.onEvent(message(row(DOOR, "on", 600.0)), true)
        idle()
        assertEquals(1, sheet.activityView().rows.size)
    }

    @Test
    fun atMostAHundredRowsShow() {
        val sheet = open(store(door()), DOOR)
        val rows = (0 until 120).map { i -> row(DOOR, if (i % 2 == 0) "on" else "off", 80_000.0 - i * 600) }
        logbook().onEvent(message(*rows.toTypedArray()), true)
        idle()
        assertEquals(MoreInfoLogbookView.MAX_ENTRIES, sheet.activityView().rows.size)
    }

    @Test
    fun aRejectedSubscriptionSaysTheLogbookCouldntLoad() {
        val sheet = open(store(door()), DOOR)
        logbook().onError(JSONObject().put("code", "unknown_error").put("message", "x"))
        idle()
        assertTrue(texts(sheet).toString(), texts(sheet).any { it.startsWith("Could not load activity") })
    }

    @Test
    fun closingTheSheetEndsTheSubscription() {
        val sheet = open(store(door()), DOOR)
        sheet.dismiss()
        idle()
        activity.setContentView(View(activity))
        idle()
        assertTrue(logbook().closed)
    }

    // ---- What a row says ----

    @Test
    fun aRowSaysTheStateAsHaFormatsIt() {
        val s = store(door(), EntityState("button.bell", "2026-11-12T16:00:00+00:00", emptyMap()),
            EntityState("event.doorbell", "2026-11-12T16:00:00+00:00", mapOf("event_types" to listOf("ring"))))
        fun value(e: LogbookEntry) = HaLogbook.value(e, s::get)?.text
        assertEquals("Open", value(LogbookEntry(DOOR, null, null, "on", nowSec)))
        // A button pressed at the row's time; a restored button's timestamp isn't a press.
        val pressed = EntityStore.parseIsoEpochSeconds("2026-11-12T16:00:00+00:00")!!
        assertEquals("Pressed", value(LogbookEntry("button.bell", null, null, "2026-11-12T16:00:00+00:00", pressed)))
        assertEquals("Event detected", value(LogbookEntry("event.doorbell", null, null, "2026-11-12T16:00:00+00:00", pressed)))
        assertEquals("ring", value(LogbookEntry("event.doorbell", null, null, "2026-11-12T16:00:00+00:00", pressed, eventType = "ring")))
        // An automation's run row: HA's own word, not core's English.
        assertEquals("Triggered", value(LogbookEntry("automation.night", null, "triggered by time", null, nowSec, domain = "automation")))
        assertEquals("Ran", value(LogbookEntry("script.bed", null, "started", null, nowSec, domain = "script")))
    }

    @Test
    fun aMessageNamingAnEntityShowsItsName() {
        val s = store(EntityState("light.porch", "on", mapOf("friendly_name" to "Porch")))
        assertEquals("turned on by Porch", HaLogbook.messageText("turned on by light.porch", s::get))
        assertEquals("was unplugged", HaLogbook.messageText("was unplugged", s::get))
    }

    // ---- Causes ----

    @Test
    fun aRowsCauseIsTheRunOverTheUserThenTheUserThenTheContext() {
        val users = HaLogbook.Users.of(listOf(EntityState("person.robin", "home", mapOf("user_id" to "u1", "friendly_name" to "Robin"))),
            JSONArray(listOf(JSONObject().put("id", "u2").put("name", "Node-RED").put("system_generated", false),
                JSONObject().put("id", "u3").put("name", "Home Assistant Cloud").put("system_generated", true))))
        val s = store()
        fun cause(e: LogbookEntry) = HaLogbook.rowCause(e, users, s::get)
        // A person's user: their initials badge.
        val byRobin = cause(LogbookEntry(DOOR, null, null, "on", nowSec, contextUserId = "u1", contextEventType = "call_service", contextDomain = "light"))!!
        assertEquals("user" to "Robin", byRobin.type to byRobin.name)
        assertNull(HaLogbook.causeIcon(byRobin))
        assertEquals("R", HaLogbook.initials(byRobin.name))
        // HA's cloud user: its cloud icon.
        val cloud = cause(LogbookEntry(DOOR, null, null, "on", nowSec, contextUserId = "u3"))!!
        assertEquals("mdi:cloud", HaLogbook.causeIcon(cloud))
        // An automation that ran on a user's behalf wins over the user.
        val run = cause(LogbookEntry(DOOR, null, null, "on", nowSec, contextUserId = "u1", contextEventType = "automation_triggered",
            contextEntityId = "automation.morning", contextName = "Morning"))!!
        assertEquals("automation" to "Morning", run.type to run.name)
        assertEquals("mdi:robot", HaLogbook.causeIcon(run))
        // An automation's own row, triggered on a schedule.
        val timed = cause(LogbookEntry("automation.morning", null, "triggered by time", null, nowSec, domain = "automation", source = "time"))!!
        assertEquals("mdi:clock-outline", HaLogbook.causeIcon(timed))
        // A state change of another entity.
        val state = cause(LogbookEntry("automation.morning", null, "triggered", null, nowSec, domain = "automation", source = "state of binary_sensor.front"))!!
        assertEquals("state" to "binary_sensor.front", state.type to state.entityId)
        assertEquals("mdi:state-machine", HaLogbook.causeIcon(state))
        // No cause at all.
        assertNull(cause(LogbookEntry(DOOR, null, null, "on", nowSec)))
    }

    @Test
    fun aNonAdminSeesThePersonsNameEvenWhenNoCardFollowsThePerson() {
        // HA's frontend names the user from `hass.states`, which holds every person; the dashboard follows only the
        // door. The section follows the persons HA's registry lists, and names the row once Robin's state lands.
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "person.robin"))
            .put(JSONObject().put("ei", DOOR))))
        val s = store(door())
        val sheet = open(s, DOOR)
        assertEquals(listOf("person.robin"), watched)
        logbook().onEvent(message(row(DOOR, "on", 60.0, "context_user_id" to "u1", "context_event_type" to "call_service",
            "context_domain" to "cover", "context_service" to "open_cover")), true)
        idle()
        // No user known yet: the row has no cause badge.
        assertFalse(texts(sheet.activityView()).toString(), "R" in texts(sheet.activityView()))
        s.putLocal(EntityState("person.robin", "home", mapOf("user_id" to "u1", "friendly_name" to "Robin")))
        idle()
        // Robin's `ha-user-badge`: their initials.
        assertTrue(texts(sheet.activityView()).toString(), "R" in texts(sheet.activityView()))
        // HA's registry again (its display data re-sent): a person followed already isn't asked for twice.
        s.renotifyAll()
        idle()
        assertEquals(listOf("person.robin"), watched)
    }

    @Test
    fun anIntegrationCauseHaWouldDrawAsABrandImageIsUnsupported() {
        val s = store()
        val viaHue = HaLogbook.rowCause(LogbookEntry(DOOR, null, null, "on", nowSec, contextEventType = "call_service", contextDomain = "hue"),
            HaLogbook.Users.NONE, s::get)!!
        assertEquals(HaLogbook.UNSUPPORTED, HaLogbook.causeIcon(viaHue))
        // A domain HA has a fallback icon for draws it.
        val viaLight = HaLogbook.rowCause(LogbookEntry(DOOR, null, null, "on", nowSec, contextEventType = "call_service", contextDomain = "light"),
            HaLogbook.Users.NONE, s::get)!!
        assertEquals("mdi:lightbulb", HaLogbook.causeIcon(viaLight))
    }

    @Test
    fun aRowVitroCantDrawAsHaDoesHandsTheDialogToHasFrontend() {
        val sheet = open(store(door()), DOOR)
        logbook().onEvent(message(row(DOOR, "on", 60.0, "context_event_type" to "call_service", "context_domain" to "hue")), true)
        idle()
        assertEquals(DOOR to "info", handedOff.single().first to handedOff.single().second)
        assertTrue(sheet.activityView().rows.isEmpty())
    }

    // ---- Taps ----

    @Test
    fun aRowsTapOpensHasDialogForTheDetailOnTheViewItIsOn() {
        val sheet = open(store(door()), DOOR)
        logbook().onEvent(message(row(DOOR, "on", 60.0)), true)
        idle()
        val (row, _) = sheet.activityView().rows.single()
        assertTrue(row.isClickable)
        // TalkBack: the value and the time (HA's formatTimeWithSeconds, in HA's zone).
        assertEquals("Open, " + LogbookCardView.timeText(now - 60_000), row.contentDescription.toString())
        row.performClick()
        assertEquals(DOOR, handedOff.single().first)
        assertEquals("info", handedOff.single().second)
    }

    @Test
    fun onTheHistoryTabTheDetailOpensOnHasHistoryView() {
        val light = EntityState("light.desk", "on", mapOf("friendly_name" to "Desk", "supported_color_modes" to listOf("onoff"), "color_mode" to "onoff"))
        val sheet = open(store(light), light.entityId)
        all(sheet).single { it.contentDescription == "History" }.performClick()
        idle()
        logbook().onEvent(message(row("light.desk", "on", 60.0)), true)
        idle()
        sheet.activityView().rows.single().first.performClick()
        assertEquals("light.desk" to "history", handedOff.single().first to handedOff.single().second)
    }

    @Test
    fun showMoreGoesToTheLogbookFromTheStartOfYesterday() {
        val sheet = open(store(door()), DOOR)
        all(sheet.activityView()).filterIsInstance<TextView>().single { it.text.toString() == "Show more" }.performClick()
        assertEquals(1, navigated.size)
        assertTrue(navigated.single(), navigated.single().startsWith("/logbook?entity_id=binary_sensor.front_door&start_date="))
        assertTrue(navigated.single().endsWith("&back=1"))
    }

    @Test
    fun theStreamRequestIsTheCardsAndHasTheSameShape() {
        // One request builder for the card and the section: HA's subscribeLogbook.
        val msg = LogbookCardView.streamRequest(listOf(DOOR), 24, now)
        assertEquals(setOf("type", "start_time", "end_time", "entity_ids"), msg.keys().asSequence().toSet())
    }

    private companion object {
        const val DOOR = "binary_sensor.front_door"
    }
}
