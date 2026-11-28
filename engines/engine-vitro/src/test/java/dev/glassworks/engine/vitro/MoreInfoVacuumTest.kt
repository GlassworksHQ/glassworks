/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaControlButton
import dev.glassworks.engine.vitro.cards.HaControlSelectMenu
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MoreInfoVacuumView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
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

/** More Info of a vacuum: HA's buttons for what it supports, the services they call, and the fan speed menu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoVacuumTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val calls = ArrayList<Pair<String, JSONObject?>>()
    private val store = EntityStore()

    private fun sheet(state: String, features: Int, vararg attrs: Pair<String, Any?>): MoreInfoSheet {
        store.putLocal(EntityState(ID, state, mapOf("supported_features" to features, "friendly_name" to "Robot") + attrs))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { d, s, data, _ -> calls += "$d.$s" to data },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return MoreInfoSheet(ctx) {}.also { it.show(ID) }
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun MoreInfoSheet.buttons() = all(this).filterIsInstance<HaControlButton>().filter { it.parent != null && (it.parent as View).visibility == View.VISIBLE && it.isAttachedToWindowOrParented() }
    private fun View.isAttachedToWindowOrParented() = parent is ViewGroup && (parent as ViewGroup).indexOfChild(this) >= 0
    private fun MoreInfoSheet.button(label: String) = buttons().single { it.contentDescription == label }
    private fun MoreInfoSheet.texts() = all(this).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }

    @Test
    fun theButtonsAreWhatTheVacuumSupportsAndCallItsServices() {
        val s = sheet("docked", PAUSE or STOP or RETURN_HOME or LOCATE or CLEAN_SPOT or STATE or START)
        assertEquals(listOf("Start", "Stop", "Return home", "Locate", "Clean spot"), s.buttons().map { it.contentDescription.toString() })
        // Docked: stop is off, the rest on.
        assertFalse(s.button("Stop").isEnabled)
        assertTrue(s.button("Return home").isEnabled)
        s.button("Start").performClick()
        s.button("Return home").performClick()
        s.button("Locate").performClick()
        s.button("Clean spot").performClick()
        assertEquals(listOf("vacuum.start", "vacuum.return_to_base", "vacuum.locate", "vacuum.clean_spot"), calls.map { it.first })
        assertTrue(calls.all { it.second?.getString("entity_id") == ID })
    }

    @Test
    fun cleaningItPausesAndShowsItsStatus() {
        val s = sheet("cleaning", PAUSE or STOP or STATE or START or STATUS, "status" to "Cleaning the kitchen")
        assertTrue("the status in the header", "Cleaning the kitchen" in s.texts())
        s.button("Pause").performClick()
        s.button("Stop").performClick()
        assertEquals(listOf("vacuum.pause", "vacuum.stop"), calls.map { it.first })
    }

    @Test
    fun anOldVacuumWithoutStartOrStateGetsStartPause() {
        val s = sheet("idle", PAUSE)
        assertEquals(listOf("Start/pause"), s.buttons().map { it.contentDescription.toString() })
        s.buttons().single().performClick()
        assertEquals("vacuum.start_pause", calls.single().first)
    }

    @Test
    fun unavailableEverythingIsOff() {
        val s = sheet("unavailable", PAUSE or STOP or RETURN_HOME or LOCATE or STATE or START or FAN_SPEED, "fan_speed_list" to listOf("quiet", "max"))
        assertTrue(s.buttons().none { it.isEnabled })
        assertFalse(all(s).filterIsInstance<HaControlSelectMenu>().single().isEnabled)
    }

    @Test
    fun theFanSpeedMenuShowsOnlyWithTheFeatureAndAList() {
        assertTrue(all(sheet("docked", START or STATE, "fan_speed_list" to listOf("quiet"))).filterIsInstance<HaControlSelectMenu>().none { it.visibility == View.VISIBLE })
        val s = sheet("docked", START or STATE or FAN_SPEED, "fan_speed" to "quiet", "fan_speed_list" to listOf("quiet", "max"))
        val menu = all(s).filterIsInstance<HaControlSelectMenu>().single()
        assertEquals(View.VISIBLE, menu.visibility)
        assertTrue("Fan speed" in s.texts() && "quiet" in s.texts())
        menu.onPick!!("max")
        menu.onPick!!("quiet") // the speed it already has: nothing sent
        assertEquals(listOf("vacuum.set_fan_speed"), calls.map { it.first })
        assertEquals("max", calls.single().second?.getString("fan_speed"))
    }

    @Test
    fun theBatteryIconFollowsHasLevelAndCharging() {
        assertEquals("mdi:battery-90", MoreInfoVacuumView.batteryIconName("87", false))
        assertEquals("mdi:battery-charging-90", MoreInfoVacuumView.batteryIconName("87", true))
        assertEquals("mdi:battery-charging", MoreInfoVacuumView.batteryIconName("100", true))
        assertEquals("mdi:battery-charging-outline", MoreInfoVacuumView.batteryIconName("4", true))
        assertEquals("mdi:battery-alert-variant-outline", MoreInfoVacuumView.batteryIconName("4", false))
        assertEquals("mdi:battery-alert", MoreInfoVacuumView.batteryIconName("on", false))
    }

    private companion object {
        const val ID = "vacuum.robot"
        const val PAUSE = 4
        const val STOP = 8
        const val RETURN_HOME = 16
        const val FAN_SPEED = 32
        const val STATUS = 128
        const val LOCATE = 512
        const val CLEAN_SPOT = 1024
        const val STATE = 4096
        const val START = 8192
    }
}
