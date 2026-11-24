/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reactive entity store and the state formatting the cards share. Pure JVM. */
class EntityStoreTest {

    private fun event(json: String) = JSONObject(json)

    private fun entity(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        EntityState(id, state, attrs.toMap())

    private fun storeWithLamp(): EntityStore = EntityStore().also {
        it.applyCompressedEvent(
            event(
                """{"a": {"light.lounge_lamp": {
                     "s": "on",
                     "a": {"friendly_name": "Lounge Lamp", "brightness": 128, "rgb_color": [255, 180, 60], "effect_list": ["none", "colorloop"]},
                     "lc": 1700000000.5, "lu": 1700000001.25}}}""",
            ),
        )
    }

    // ---- Compressed events ----

    @Test
    fun addedEntitiesCarryStateAttributesAndTimestamps() {
        val store = storeWithLamp()
        val lamp = store["light.lounge_lamp"]!!
        assertEquals("on", lamp.state)
        assertEquals("light", lamp.domain)
        assertEquals("Lounge Lamp", lamp.friendlyName)
        assertEquals(1700000000.5, lamp.lastChanged, 0.0)
        assertEquals(1700000001.25, lamp.lastUpdated, 0.0)
        assertEquals(128, (lamp.attributes["brightness"] as Number).toInt())
        // Arrays come out as lists, not JSONArrays, so cards need no JSON types.
        assertEquals(listOf<Any?>(255, 180, 60), (lamp.attributes["rgb_color"] as List<*>).map { (it as Number).toInt() })
        assertEquals(listOf("none", "colorloop"), lamp.attributes["effect_list"])
        assertEquals(1, store.size())
    }

    @Test
    fun addedEventReturnsAndDispatchesTheChangedIds() {
        val store = EntityStore()
        val batches = ArrayList<Set<String>>()
        store.subscribeAny { batches += it }
        val changed = store.applyCompressedEvent(event("""{"a": {"light.a": {"s": "on"}, "light.b": {"s": "off"}, "light.skipped": "not an object"}}"""))
        assertEquals(setOf("light.a", "light.b"), changed)
        assertEquals(listOf(setOf("light.a", "light.b")), batches)
        assertEquals(2, store.updatesReceived.get())
        assertNull(store["light.skipped"])
    }

    @Test
    fun diffsApplyAddedAndChangedFieldsAndKeepTheRest() {
        val store = storeWithLamp()
        store.applyCompressedEvent(
            event(
                """{"c": {"light.lounge_lamp": {
                     "+": {"s": "off", "a": {"brightness": 0, "color_mode": "onoff", "nested": {"k": [1, 2]}}, "lu": 1700000005.0},
                     "-": {"a": ["rgb_color"]}}}}""",
            ),
        )
        val lamp = store["light.lounge_lamp"]!!
        assertEquals("off", lamp.state)
        assertEquals("Lounge Lamp", lamp.friendlyName) // untouched attribute stays
        assertEquals(0, (lamp.attributes["brightness"] as Number).toInt())
        assertEquals("onoff", lamp.attributes["color_mode"])
        assertFalse("rgb_color was removed", "rgb_color" in lamp.attributes)
        assertEquals(mapOf("k" to listOf<Any?>(1, 2)).keys, (lamp.attributes["nested"] as Map<*, *>).keys)
        assertEquals(1700000000.5, lamp.lastChanged, 0.0) // lc not in the diff
        assertEquals(1700000005.0, lamp.lastUpdated, 0.0)
    }

    @Test
    fun diffWithOnlyStateLeavesAttributesAlone() {
        val store = storeWithLamp()
        store.applyCompressedEvent(event("""{"c": {"light.lounge_lamp": {"+": {"s": "off"}}}}"""))
        assertEquals("off", store["light.lounge_lamp"]!!.state)
        assertEquals(128, (store["light.lounge_lamp"]!!.attributes["brightness"] as Number).toInt())
    }

    @Test
    fun diffForAnEntityWeNeverSawCreatesIt() {
        val store = EntityStore()
        store.applyCompressedEvent(event("""{"c": {"sensor.kitchen_temp": {"+": {"s": "21.5", "a": {"unit_of_measurement": "°C"}}}}}"""))
        val s = store["sensor.kitchen_temp"]!!
        assertEquals("21.5", s.state)
        assertEquals("°C", s.unit)
    }

    @Test
    fun removalsDropEntitiesAndOnlyReportRealOnes() {
        val store = storeWithLamp()
        store.applyCompressedEvent(event("""{"a": {"sensor.kitchen_temp": {"s": "21.5"}}}"""))
        val changed = store.applyCompressedEvent(event("""{"r": ["light.lounge_lamp", "light.never_existed"]}"""))
        assertEquals(setOf("light.lounge_lamp"), changed)
        assertNull(store["light.lounge_lamp"])
        assertNotNull(store["sensor.kitchen_temp"])
        assertEquals(1, store.size())
        assertEquals(setOf("sensor.kitchen_temp"), store.all().keys)
    }

    @Test
    fun oneEventCanAddChangeAndRemoveTogether() {
        val store = storeWithLamp()
        store.applyCompressedEvent(event("""{"a": {"switch.fan": {"s": "off"}}}"""))
        val batches = ArrayList<Set<String>>()
        store.subscribeAny { batches += it }
        store.applyCompressedEvent(
            event(
                """{"a": {"sensor.new": {"s": "1"}}, "c": {"light.lounge_lamp": {"+": {"s": "off"}}}, "r": ["switch.fan"]}""",
            ),
        )
        assertEquals(1, batches.size) // one dispatch for the whole event
        assertEquals(setOf("sensor.new", "light.lounge_lamp", "switch.fan"), batches[0])
        assertNull(store["switch.fan"])
        assertEquals("1", store["sensor.new"]!!.state)
    }

    @Test
    fun anEmptyEventChangesAndDispatchesNothing() {
        val store = storeWithLamp()
        var calls = 0
        store.subscribeAny { calls++ }
        val before = store.updatesReceived.get()
        assertTrue(store.applyCompressedEvent(event("{}")).isEmpty())
        assertTrue(store.applyCompressedEvent(event("""{"a": {}, "c": {}, "r": []}""")).isEmpty())
        assertEquals(0, calls)
        assertEquals(before, store.updatesReceived.get())
    }

    @Test
    fun statesArrayLoadsAFullSnapshot() {
        val store = EntityStore()
        val changed = store.applyStatesArray(
            JSONArray(
                """[
                  {"entity_id": "sensor.kitchen_temp", "state": "21.5", "attributes": {"unit_of_measurement": "°C", "friendly_name": "Kitchen"}},
                  {"state": "on"},
                  "junk",
                  {"entity_id": "light.lounge_lamp", "state": "on"}
                ]""",
            ),
        )
        assertEquals(setOf("sensor.kitchen_temp", "light.lounge_lamp"), changed)
        assertEquals("°C", store["sensor.kitchen_temp"]!!.unit)
        assertEquals(emptyMap<String, Any?>(), store["light.lounge_lamp"]!!.attributes)
    }

    @Test
    fun putLocalIsAnOptimisticUpdateThatNotifiesSubscribers() {
        val store = storeWithLamp()
        val seen = ArrayList<String?>()
        store.subscribe("light.lounge_lamp") { seen += it?.state }
        store.putLocal(store["light.lounge_lamp"]!!.copy(state = "off"))
        assertEquals(listOf<String?>("off"), seen)
        assertEquals("off", store["light.lounge_lamp"]!!.state)
    }

    // ---- Selective dispatch ----

    @Test
    fun aChangeWakesOnlyTheSubscribersOfThatEntity() {
        val store = EntityStore()
        val initial = StringBuilder("{\"a\": {")
        for (i in 0 until 100) {
            if (i > 0) initial.append(',')
            initial.append("\"sensor.bulk_$i\": {\"s\": \"0\"}")
        }
        initial.append("}}")
        store.applyCompressedEvent(JSONObject(initial.toString()))
        assertEquals(100, store.size())

        val woken = IntArray(100)
        for (i in 0 until 100) store.subscribe("sensor.bulk_$i") { woken[i]++ }

        val dispatchedBefore = store.notificationsDispatched.get()
        val updatesBefore = store.updatesReceived.get()
        store.applyCompressedEvent(event("""{"c": {"sensor.bulk_42": {"+": {"s": "7"}}}}"""))

        assertEquals("exactly one notification, not 100", 1L, store.notificationsDispatched.get() - dispatchedBefore)
        assertEquals(1L, store.updatesReceived.get() - updatesBefore)
        assertEquals(1, woken[42])
        assertEquals("the other 99 were never woken", 1, woken.sum())
    }

    @Test
    fun subscribersReceiveTheNewStateAndRemovalsAsNull() {
        val store = storeWithLamp()
        val seen = ArrayList<EntityState?>()
        store.subscribe("light.lounge_lamp") { seen += it }
        store.applyCompressedEvent(event("""{"c": {"light.lounge_lamp": {"+": {"s": "off"}}}}"""))
        store.applyCompressedEvent(event("""{"r": ["light.lounge_lamp"]}"""))
        assertEquals(2, seen.size)
        assertEquals("off", seen[0]!!.state)
        assertNull(seen[1])
    }

    @Test
    fun everySubscriberOfTheSameEntityIsWokenOnce() {
        val store = storeWithLamp()
        var a = 0
        var b = 0
        store.subscribe("light.lounge_lamp") { a++ }
        store.subscribe("light.lounge_lamp") { b++ }
        val before = store.notificationsDispatched.get()
        store.applyCompressedEvent(event("""{"c": {"light.lounge_lamp": {"+": {"s": "off"}}}}"""))
        assertEquals(1, a)
        assertEquals(1, b)
        assertEquals(2L, store.notificationsDispatched.get() - before)
    }

    @Test
    fun unsubscribingStopsNotificationsAndTheCount() {
        val store = storeWithLamp()
        var n = 0
        val unsubscribe = store.subscribe("light.lounge_lamp") { n++ }
        store.applyCompressedEvent(event("""{"c": {"light.lounge_lamp": {"+": {"s": "off"}}}}"""))
        unsubscribe()
        val before = store.notificationsDispatched.get()
        store.applyCompressedEvent(event("""{"c": {"light.lounge_lamp": {"+": {"s": "on"}}}}"""))
        assertEquals(1, n)
        assertEquals(0L, store.notificationsDispatched.get() - before)
    }

    @Test
    fun aChangeNobodyWatchesStillCountsAsAnUpdateButNotANotification() {
        val store = EntityStore()
        val notificationsBefore = store.notificationsDispatched.get()
        store.applyCompressedEvent(event("""{"a": {"sensor.unwatched": {"s": "1"}}}"""))
        assertEquals(1L, store.updatesReceived.get())
        assertEquals(notificationsBefore, store.notificationsDispatched.get())
    }

    @Test
    fun aBatchNotifiesEachWatchedEntityOnceAndTheAnyListenersOnce() {
        val store = EntityStore()
        store.applyCompressedEvent(event("""{"a": {"light.a": {"s": "off"}, "light.b": {"s": "off"}, "light.c": {"s": "off"}}}"""))
        var a = 0
        var c = 0
        var any = 0
        var anySeen: Set<String> = emptySet()
        store.subscribe("light.a") { a++ }
        store.subscribe("light.c") { c++ }
        store.subscribeAny { any++; anySeen = it }
        val before = store.notificationsDispatched.get()
        store.applyCompressedEvent(event("""{"c": {"light.a": {"+": {"s": "on"}}, "light.b": {"+": {"s": "on"}}, "light.c": {"+": {"s": "on"}}}}"""))
        assertEquals(2L, store.notificationsDispatched.get() - before) // a and c; b has no subscriber
        assertEquals(1, a)
        assertEquals(1, c)
        assertEquals(1, any)
        assertEquals(setOf("light.a", "light.b", "light.c"), anySeen)
    }

    @Test
    fun unsubscribingFromAnyStopsBatches() {
        val store = EntityStore()
        var calls = 0
        val off = store.subscribeAny { calls++ }
        store.applyCompressedEvent(event("""{"a": {"light.a": {"s": "on"}}}"""))
        off()
        store.applyCompressedEvent(event("""{"a": {"light.b": {"s": "on"}}}"""))
        assertEquals(1, calls)
    }

    // ---- Entity state ----

    @Test
    fun friendlyNameFallsBackToTheEntityId() {
        assertEquals("Lounge Lamp", entity("light.lounge_lamp", "on", "friendly_name" to "Lounge Lamp").friendlyName)
        assertEquals("Kitchen temp", entity("sensor.kitchen_temp", "1").friendlyName)
        assertEquals("Kitchen temp", entity("sensor.kitchen_temp", "1", "friendly_name" to "  ").friendlyName)
    }

    @Test
    fun attributeShortcutsIgnoreEmptyValues() {
        val s = entity("sensor.kitchen_temp", "21", "unit_of_measurement" to "", "device_class" to "temperature", "icon" to "mdi:thermometer", "entity_picture" to "/local/k.png")
        assertNull(s.unit)
        assertEquals("temperature", s.deviceClass)
        assertEquals("mdi:thermometer", s.icon)
        assertEquals("/local/k.png", s.entityPicture)
        assertNull(entity("sensor.x", "1").deviceClass)
    }

    @Test
    fun unavailableAndUnknownAreFlagged() {
        assertTrue(entity("light.a", "unavailable").isUnavailable)
        assertTrue(entity("light.a", "unknown").isUnavailable)
        assertFalse(entity("light.a", "off").isUnavailable)
    }

    @Test
    fun brightnessPercentIsClampedAndOptional() {
        assertEquals(100, entity("light.a", "on", "brightness" to 255).brightnessPct)
        assertEquals(50, entity("light.a", "on", "brightness" to 128).brightnessPct)
        assertEquals(0, entity("light.a", "on", "brightness" to 0).brightnessPct)
        assertEquals(100, entity("light.a", "on", "brightness" to 300.0).brightnessPct)
        assertNull(entity("light.a", "on").brightnessPct)
        assertNull(entity("light.a", "on", "brightness" to null).brightnessPct)
    }

    // ---- formattedState ----

    @Test
    fun lightFormatsBrightnessWhenOn() {
        assertEquals("50%", entity("light.a", "on", "brightness" to 128).formattedState())
        assertEquals("On", entity("light.a", "on").formattedState())
        assertEquals("Off", entity("light.a", "off", "brightness" to 128).formattedState())
    }

    @Test
    fun unavailableStatesAreTitleCased() {
        assertEquals("Unavailable", entity("light.a", "unavailable").formattedState())
        assertEquals("Unknown", entity("sensor.a", "unknown", "unit_of_measurement" to "°C").formattedState())
    }

    @Test
    fun doorAndWindowSensorsReadOpenOrClosed() {
        for (dc in listOf("door", "window", "garage_door", "opening")) {
            assertEquals("Open", entity("binary_sensor.x", "on", "device_class" to dc).formattedState())
            assertEquals("Closed", entity("binary_sensor.x", "off", "device_class" to dc).formattedState())
        }
    }

    @Test
    fun motionAndSimilarSensorsReadDetectedOrClear() {
        for (dc in listOf("motion", "occupancy", "presence", "smoke", "gas", "problem")) {
            assertEquals("Detected", entity("binary_sensor.x", "on", "device_class" to dc).formattedState())
            assertEquals("Clear", entity("binary_sensor.x", "off", "device_class" to dc).formattedState())
        }
    }

    @Test
    fun lockAndMoistureSensorsHaveTheirOwnWords() {
        assertEquals("Unlocked", entity("binary_sensor.x", "on", "device_class" to "lock").formattedState())
        assertEquals("Locked", entity("binary_sensor.x", "off", "device_class" to "lock").formattedState())
        assertEquals("Wet", entity("binary_sensor.x", "on", "device_class" to "moisture").formattedState())
        assertEquals("Dry", entity("binary_sensor.x", "off", "device_class" to "moisture").formattedState())
    }

    @Test
    fun binarySensorWithoutAKnownClassReadsOnOrOff() {
        assertEquals("On", entity("binary_sensor.x", "on").formattedState())
        assertEquals("Off", entity("binary_sensor.x", "off").formattedState())
        assertEquals("On", entity("binary_sensor.x", "on", "device_class" to "battery_charging").formattedState())
    }

    @Test
    fun sensorWithAUnitAppendsIt() {
        assertEquals("21.5 °C", entity("sensor.kitchen_temp", "21.5", "unit_of_measurement" to "°C").formattedState())
        assertEquals("340 W", entity("sensor.power", "340", "unit_of_measurement" to "W").formattedState())
        // A unit wins over the domain rules; HA writes `%` without a space.
        assertEquals("12%", entity("light.a", "12", "unit_of_measurement" to "%").formattedState())
    }

    @Test
    fun otherStatesAreHumanized() {
        assertEquals("Partly cloudy", entity("weather.home", "partly_cloudy").formattedState())
        assertEquals("Not home", entity("person.alex", "not_home").formattedState())
        assertEquals("Closed", entity("cover.garage", "closed").formattedState())
        assertEquals("", entity("sensor.empty", "").formattedState())
    }

    @Test
    fun anOpenCoverShowsItsPosition() {
        assertEquals("Open · 60%", entity("cover.blind", "open", "current_position" to 60).formattedState())
        assertEquals("Closed", entity("cover.blind", "closed", "current_position" to 0).formattedState())
        assertEquals("Open", entity("cover.blind", "open").formattedState())
    }

    @Test
    fun climateShowsTheActionAndTheTargetOrCurrentTemperature() {
        assertEquals(
            "Heating · 21.5°",
            entity("climate.hall", "heat", "hvac_action" to "heating", "temperature" to 21.5, "current_temperature" to 20.1).formattedState(),
        )
        assertEquals("Heat · 21°", entity("climate.hall", "heat", "temperature" to 21).formattedState())
        assertEquals("Heat · 20.1°", entity("climate.hall", "heat", "current_temperature" to 20.1).formattedState())
        assertEquals("Cool", entity("climate.hall", "cool").formattedState())
        assertEquals("Off", entity("climate.hall", "off").formattedState())
        assertEquals("Idle · 19°", entity("climate.hall", "heat", "hvac_action" to "idle", "temperature" to 19).formattedState())
    }

    // ---- isActive ----

    @Test
    fun onOffDomainsAreActiveWhenOn() {
        for (d in listOf("light", "switch", "fan", "input_boolean", "automation", "script", "binary_sensor")) {
            assertTrue("$d on", entity("$d.x", "on").isActive)
            assertFalse("$d off", entity("$d.x", "off").isActive)
            assertFalse("$d unavailable", entity("$d.x", "unavailable").isActive)
        }
    }

    @Test
    fun binarySensorsAreActiveForDoorAndMotionToo() {
        assertTrue(entity("binary_sensor.front_door", "on", "device_class" to "door").isActive)
        assertFalse(entity("binary_sensor.front_door", "off", "device_class" to "door").isActive)
        assertTrue(entity("binary_sensor.hall_motion", "on", "device_class" to "motion").isActive)
        assertFalse(entity("binary_sensor.hall_motion", "off", "device_class" to "motion").isActive)
    }

    @Test
    fun sensorsAreActiveOnlyForOnActiveOrOpen() {
        assertFalse(entity("sensor.kitchen_temp", "21.5", "unit_of_measurement" to "°C").isActive)
        assertTrue(entity("sensor.x", "on").isActive)
        assertTrue(entity("sensor.x", "active").isActive)
        assertTrue(entity("sensor.x", "open").isActive)
        assertFalse(entity("sensor.x", "off").isActive)
    }

    @Test
    fun climateIsActiveUnlessOffOrUnavailable() {
        assertTrue(entity("climate.hall", "heat").isActive)
        assertTrue(entity("climate.hall", "cool").isActive)
        assertTrue(entity("climate.hall", "auto").isActive)
        assertFalse(entity("climate.hall", "off").isActive)
        assertFalse(entity("climate.hall", "unavailable").isActive)
        assertFalse(entity("climate.hall", "unknown").isActive)
    }

    @Test
    fun otherDomainsHaveTheirOwnActiveStates() {
        assertTrue(entity("lock.front", "unlocked").isActive)
        assertFalse(entity("lock.front", "locked").isActive)
        assertTrue(entity("cover.garage", "open").isActive)
        assertTrue(entity("cover.garage", "opening").isActive)
        assertFalse(entity("cover.garage", "closed").isActive)
        assertFalse(entity("cover.garage", "closing").isActive)
        assertTrue(entity("media_player.lounge", "playing").isActive)
        assertTrue(entity("media_player.lounge", "buffering").isActive)
        assertFalse(entity("media_player.lounge", "paused").isActive)
        assertTrue(entity("person.alex", "home").isActive)
        assertFalse(entity("person.alex", "not_home").isActive)
        assertTrue(entity("device_tracker.phone", "home").isActive)
        assertTrue(entity("vacuum.hall", "cleaning").isActive)
        assertTrue(entity("vacuum.hall", "returning").isActive)
        assertFalse(entity("vacuum.hall", "docked").isActive)
    }

    // ---- Relative last-changed text ----

    private fun changedAt(lastChanged: Double) = EntityState("sensor.a", "1", emptyMap(), lastChanged = lastChanged)

    @Test
    fun relativeLastChangedIsNullWhenTheTimeIsUnknown() {
        assertNull(changedAt(0.0).relativeLastChanged(1_000_000.0))
        assertNull(changedAt(-5.0).relativeLastChanged(1_000_000.0))
    }

    @Test
    fun relativeLastChangedSpeaksInTheLargestWholeUnit() {
        val now = 2_000_000_000.0
        fun text(agoSec: Long) = changedAt(now - agoSec).relativeLastChanged(now)
        assertEquals("Just now", text(0))
        assertEquals("Just now", text(59))
        assertEquals("1 minute ago", text(60))
        assertEquals("2 minutes ago", text(150))
        assertEquals("59 minutes ago", text(3_599))
        assertEquals("1 hour ago", text(3_600))
        assertEquals("23 hours ago", text(23 * 3_600L + 59))
        assertEquals("1 day ago", text(86_400))
        assertEquals("6 days ago", text(6 * 86_400L + 100))
        assertEquals("1 week ago", text(7 * 86_400L))
        assertEquals("4 weeks ago", text(29 * 86_400L))
        assertEquals("1 month ago", text(30 * 86_400L))
        assertEquals("3 months ago", text(100 * 86_400L))
    }

    @Test
    fun relativeLastChangedInTheFutureIsJustNow() {
        assertEquals("Just now", changedAt(1_000_100.0).relativeLastChanged(1_000_000.0))
    }

    @Test
    fun relativeLastChangedDefaultsToTheCurrentClock() {
        val now = System.currentTimeMillis() / 1000.0
        assertEquals("5 minutes ago", changedAt(now - 300).relativeLastChanged())
    }

    // ---- Full get_states arrays ----

    @Test
    fun statesArrayParsesIsoLastChangedAndLastUpdated() {
        val store = EntityStore()
        store.applyStatesArray(
            JSONArray(
                """[{"entity_id": "light.a", "state": "on", "attributes": {"friendly_name": "A"},
                     "last_changed": "2026-11-23T17:00:00+00:00", "last_updated": "2026-11-23T17:00:30.500000Z"},
                    {"entity_id": "light.b", "state": "off", "attributes": {},
                     "last_changed": "2026-11-23T19:00:00+02:00", "last_updated": 1700000000.5},
                    {"entity_id": "light.c", "state": "off", "attributes": {}, "last_changed": "not a date"},
                    {"entity_id": "light.d", "state": "off"}]""",
            ),
        )
        val noon = 1795453200.0 // 2026-11-23T17:00:00Z
        val a = store["light.a"]!!
        assertEquals(noon, a.lastChanged, 0.0)
        assertEquals(noon + 30.5, a.lastUpdated, 0.0)
        assertEquals("an offset is applied", noon, store["light.b"]!!.lastChanged, 0.0)
        assertEquals("numbers pass through", 1700000000.5, store["light.b"]!!.lastUpdated, 0.0)
        assertEquals(0.0, store["light.c"]!!.lastChanged, 0.0)
        assertEquals(0.0, store["light.d"]!!.lastChanged, 0.0)
        assertEquals("5 minutes ago", EntityState("x", "on", emptyMap(), lastChanged = noon).relativeLastChanged(noon + 300))
    }

    @Test
    fun isoTimestampParsingHandlesZonesAndRejectsJunk() {
        assertEquals(0.0, EntityStore.parseIsoEpochSeconds("1970-01-01T00:00:00Z")!!, 0.0)
        assertEquals(0.0, EntityStore.parseIsoEpochSeconds("1970-01-01T05:30:00+05:30")!!, 0.0)
        assertEquals(3600.0, EntityStore.parseIsoEpochSeconds("1970-01-01T00:00:00-0100")!!, 0.0)
        assertEquals(0.25, EntityStore.parseIsoEpochSeconds("1970-01-01T00:00:00.25")!!, 0.0) // no zone = UTC
        assertNull(EntityStore.parseIsoEpochSeconds("yesterday"))
        assertNull(EntityStore.parseIsoEpochSeconds("2026-13-45T25:61:61Z"))
    }

    // ---- hass.states shape for Tier 3 ----

    @Test
    fun hassJsonMatchesTheFrontendStateObject() {
        val json = entity("light.lounge_lamp", "on", "friendly_name" to "Lounge Lamp", "brightness" to 128, "rgb_color" to listOf(255, 180, 60), "gone" to null).toHassJson()
        assertEquals("light.lounge_lamp", json.getString("entity_id"))
        assertEquals("on", json.getString("state"))
        val attrs = json.getJSONObject("attributes")
        assertEquals("Lounge Lamp", attrs.getString("friendly_name"))
        assertEquals(128, attrs.getInt("brightness"))
        assertTrue(attrs.isNull("gone"))
    }
}
