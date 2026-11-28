/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import dev.glassworks.engine.vitro.cards.ClimateHandle
import dev.glassworks.engine.vitro.cards.ClimateTargets
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MushroomClimateCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Heat/cool target ranges (#814): `target_temp_low` / `target_temp_high` on HA's thermostat card, More Info, the tile's
 * target-temperature feature and Mushroom's climate card. The reference Home Assistant has no climate entity in
 * heat_cool with a range, so these stand in for parity cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClimateRangeTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private class Call(val domain: String, val service: String, val data: JSONObject) {
        override fun toString() = "$domain.$service $data"
    }

    private val calls = ArrayList<Call>()

    private fun context(store: EntityStore = EntityStore()) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += Call(domain, service, data ?: JSONObject()) },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    /** A heat_cool thermostat (TARGET_TEMPERATURE_RANGE in its features) holding 20–24 °C. */
    private fun heatCool(vararg extra: Pair<String, Any?>) = EntityState(
        "climate.hall", "heat_cool",
        mapOf(
            "friendly_name" to "Hall", "hvac_modes" to listOf("off", "heat_cool"), "supported_features" to 386,
            "min_temp" to 10.0, "max_temp" to 30.0, "target_temp_step" to 0.5, "current_temperature" to 22.0,
            "target_temp_low" to 20.0, "target_temp_high" to 24.0, "hvac_action" to "idle",
        ) + extra,
    )

    private fun store(s: EntityState) = EntityStore().also { it.putLocal(s) }

    private fun card(json: String) = JSONObject(json).let { CardConfig(it.getString("type"), it) }

    private fun View.all(): List<View> =
        listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    /** Visible here and in every parent (the cards aren't attached to a window, so `isShown` is always false). */
    private fun View.visible(): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }

    private fun View.byDescription(d: String): View = all().single { it.contentDescription?.toString() == d }

    private fun View.dial(): ThermostatDialView = all().filterIsInstance<ThermostatDialView>().single()

    private fun assertRange(call: Call, low: Double, high: Double) {
        assertEquals("climate.set_temperature", "${call.domain}.${call.service}")
        assertEquals(call.toString(), low, call.data.getDouble("target_temp_low"), 0.0)
        assertEquals(call.toString(), high, call.data.getDouble("target_temp_high"), 0.0)
        assertFalse(call.toString(), call.data.has("temperature"))
    }

    // ---- The rule ----

    @Test
    fun aRangeIsReadOnlyWithoutASingleTargetAndWithTheRangeFeature() {
        assertTrue(ClimateTargets.of(heatCool().attributes).isRange)
        // HA prefers the single target when both are there.
        assertFalse(ClimateTargets.of(heatCool("temperature" to 21.0).attributes).isRange)
        // An entity whose features leave out TARGET_TEMPERATURE_RANGE has no range, whatever its attributes say.
        val single = ClimateTargets.of(heatCool("supported_features" to 1).attributes)
        assertFalse(single.isRange)
        assertNull(single.low)
        // No supported_features at all is no range, as HA's supportsFeature reads it.
        assertFalse(ClimateTargets.of(mapOf("target_temp_low" to 20.0, "target_temp_high" to 24.0)).isRange)
        // An off thermostat with no ends (the reference HA's climate.ac) has none either.
        assertFalse(ClimateTargets.of(mapOf("supported_features" to 395, "target_temp_low" to null, "target_temp_high" to null)).isRange)
    }

    @Test
    fun theEndsStayInTheDevicesLimitsAndNeverCross() {
        // A step up from the low end, then one too many: it meets the high end and stops there.
        assertEquals(20.5 to 24.0, ClimateTargets.stepped(ClimateHandle.LOW, 0.5, 20.0, 24.0, 10.0, 30.0))
        assertEquals(24.0 to 24.0, ClimateTargets.stepped(ClimateHandle.LOW, 0.5, 23.5, 24.0, 10.0, 30.0))
        assertEquals(24.0 to 24.0, ClimateTargets.stepped(ClimateHandle.LOW, 0.5, 24.0, 24.0, 10.0, 30.0))
        // The high end can't go under the low one, nor past the device's max; the low end not under its min.
        assertEquals(20.0 to 20.0, ClimateTargets.stepped(ClimateHandle.HIGH, -1.0, 20.0, 20.5, 10.0, 30.0))
        assertEquals(20.0 to 30.0, ClimateTargets.stepped(ClimateHandle.HIGH, 0.5, 20.0, 30.0, 10.0, 30.0))
        assertEquals(10.0 to 24.0, ClimateTargets.stepped(ClimateHandle.LOW, -0.5, 10.0, 24.0, 10.0, 30.0))
        // A fine step keeps its decimals, on the wire and on screen (HA's `digits`).
        assertEquals(20.125 to 24.0, ClimateTargets.stepped(ClimateHandle.LOW, 0.125, 20.0, 24.0, 10.0, 30.0))
        assertEquals("20" to ".25", ThermostatDialView.splitTemp(20.25, 0.25))
        assertEquals("21" to "", ThermostatDialView.splitTemp(21.0, 1.0))
        // A setpoint off the step grid (a 30.25 maximum on a 0.5 step) shows as it is; on-grid ones keep the step's.
        assertEquals("30" to ".25", ThermostatDialView.splitTemp(30.25, 0.5))
        assertEquals("30" to ".0", ThermostatDialView.splitTemp(30.0, 0.5))
        assertEquals(20.2, ClimateTargets.roundToStep(20.0 + 0.1 + 0.1, 0.1), 0.0)
        // A release is finished against the entity as the store holds it now: HA's newer other end wins, and an
        // entity that no longer has a range gets nothing.
        assertEquals(21.0 to 26.0, ClimateTargets.rangeToSend(ClimateHandle.LOW, 21.0, 24.0, heatCool("target_temp_high" to 26.0)))
        assertEquals(26.0 to 26.0, ClimateTargets.rangeToSend(ClimateHandle.LOW, 27.0, 24.0, heatCool("target_temp_high" to 26.0)))
        assertNull(ClimateTargets.rangeToSend(ClimateHandle.LOW, 21.0, 24.0, heatCool("temperature" to 22.0)))
        assertNull(ClimateTargets.rangeToSend(ClimateHandle.LOW, 21.0, 24.0, null))
        // An untouched end HA reports outside the device's limits is brought within them, and the pair stays in order.
        assertEquals(21.0 to 25.0, ClimateTargets.rangeToSend(ClimateHandle.LOW, 21.0, 24.0, heatCool("max_temp" to 25.0, "target_temp_high" to 26.0)))
        assertEquals(10.0 to 10.0, ClimateTargets.rangeToSend(ClimateHandle.LOW, 12.0, 24.0, heatCool("target_temp_low" to 9.0, "target_temp_high" to 8.0)))
        // °F steps by one when the entity doesn't say.
        assertEquals(1.0, ClimateTargets.stepOf(emptyMap(), "°F"), 0.0)
        assertEquals(0.5, ClimateTargets.stepOf(emptyMap(), "°C"), 0.0)
    }

    // ---- The dial ----

    private fun rangeDial(showCurrent: Boolean = true): Pair<ThermostatDialView, MutableList<Pair<Double, Double>>> {
        val commits = ArrayList<Pair<Double, Double>>()
        val dial = ThermostatDialView(context(), "°C", showCurrent)
        // As the cards do: the moved end bounded against the other end the entity reports (here the dial's own).
        dial.onRangeCommit = { h, lo, hi ->
            ClimateTargets.rangeToSend(h, lo, hi, heatCool("target_temp_low" to lo, "target_temp_high" to hi))?.let { commits += it }
        }
        dial.bind(10.0, 30.0, 0.5, current = 22.0, target = null, mode = "heat_cool", action = "idle", stateLabel = "Heat/Cool", low = 20.0, high = 24.0)
        dial.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        dial.layout(0, 0, 320, 320)
        return dial to commits
    }

    @Test
    fun theDialShowsBothEndsAndTheButtonsMoveTheSelectedOne() {
        val (dial, commits) = rangeDial()
        assertTrue(dial.isRange)
        // HA's label, the two targets, the current temperature.
        assertEquals("Idle, 20.0 °C, 24.0 °C, 22.0 °C", dial.contentDescription.toString())
        assertEquals(ClimateHandle.LOW, dial.selected) // HA selects the low end first
        dial.nudge(1)
        assertEquals(listOf(20.5 to 24.0), commits)
        dial.select(ClimateHandle.HIGH)
        dial.nudge(-1)
        assertEquals(20.5 to 23.5, commits.last())
        assertEquals("Idle, 20.5 °C, 23.5 °C, 22.0 °C", dial.contentDescription.toString())
        // HA's next state lands; a press never moves the high end under the low one.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 23.5, high = 23.5)
        val before = commits.size
        dial.nudge(-1)
        assertEquals(before, commits.size)
    }

    private fun touch(dial: View, action: Int, value: Double) {
        // The ring runs 270° from 135°, radius 145 / 320 of the dial, between the device's min (10) and max (30).
        val angle = Math.toRadians(135.0 + 270.0 * (value - 10.0) / 20.0)
        val x = (160 + 145 * cos(angle)).toFloat()
        val y = (160 + 145 * sin(angle)).toFloat()
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        dial.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test
    fun aFingerMovesTheNearerThumbAndStopsAtTheOther() {
        val (dial, commits) = rangeDial()
        // Near the high end: it moves the high thumb, selects it, and commits both ends on release.
        touch(dial, MotionEvent.ACTION_DOWN, 25.0)
        assertEquals(ClimateHandle.HIGH, dial.selected)
        touch(dial, MotionEvent.ACTION_MOVE, 26.0)
        touch(dial, MotionEvent.ACTION_UP, 26.0)
        assertEquals(listOf(20.0 to 26.0), commits)
        // Below the low end: the low thumb, dragged up past the high end, stops on it.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 26.0)
        touch(dial, MotionEvent.ACTION_DOWN, 15.0)
        assertEquals(ClimateHandle.LOW, dial.selected)
        touch(dial, MotionEvent.ACTION_MOVE, 28.0)
        touch(dial, MotionEvent.ACTION_UP, 28.0)
        assertEquals(26.0 to 26.0, commits.last())
        // HA moves the high end while a finger moves the low one: the release keeps HA's high end.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 24.0)
        touch(dial, MotionEvent.ACTION_DOWN, 18.0)
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 26.0)
        touch(dial, MotionEvent.ACTION_UP, 19.0)
        assertEquals(19.0 to 26.0, commits.last())
        assertEquals(3, commits.size)
        // HA raises the high end mid-drag: the release is bounded by HA's new end, not the one shown at the start.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 24.0)
        touch(dial, MotionEvent.ACTION_DOWN, 18.0)
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 26.0)
        touch(dial, MotionEvent.ACTION_UP, 25.5)
        assertEquals(25.5 to 26.0, commits.last())
        commits.removeAt(commits.lastIndex)
        // A mode change to a single target mid-drag: the range edit is dropped.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 26.0)
        touch(dial, MotionEvent.ACTION_DOWN, 18.0)
        dial.bind(10.0, 30.0, 0.5, 22.0, 21.0, "heat", "idle")
        touch(dial, MotionEvent.ACTION_UP, 18.0)
        assertEquals(3, commits.size)
        // A cancelled drag sends nothing and goes back to HA's range.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 26.0)
        touch(dial, MotionEvent.ACTION_DOWN, 18.0)
        touch(dial, MotionEvent.ACTION_CANCEL, 18.0)
        assertEquals(3, commits.size)
        assertTrue(dial.contentDescription.toString(), "20.0 °C, 26.0 °C" in dial.contentDescription.toString())
    }

    @Test
    fun keysMoveTheSelectedEndAndCommitOnRelease() {
        val (dial, commits) = rangeDial()
        fun key(code: Int) {
            dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        assertTrue(dial.isFocusable)
        // Right and left move it, as on a SeekBar; up and down are left for moving between controls.
        assertFalse(dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(20.5 to 24.0), commits)
        // The optimistic write's state comes back, as the cards bind it.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.5, high = 24.0)
        // Enter switches to the other end; End takes it to the device's max.
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(ClimateHandle.HIGH, dial.selected)
        key(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(20.5 to 30.0, commits.last())
        // Only the key that made the edit ends it: a Shift let go meanwhile sends nothing. And Enter mid-edit doesn't
        // move the edit to the other end.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.5, high = 30.0)
        dial.select(ClimateHandle.LOW)
        val n = commits.size
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(n, commits.size)
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(21.0 to 30.0, commits.last())
        dial.select(ClimateHandle.HIGH)
        // At an end the arrow isn't taken, so a D-pad moves on to the next control.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.5, high = 30.0)
        assertFalse(dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        // A cancelled release sends nothing and shows HA's range again.
        val before = commits.size
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        dial.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT), KeyEvent.FLAG_CANCELED))
        assertEquals(before, commits.size)
        assertTrue(dial.contentDescription.toString(), "20.5 °C, 30.0 °C" in dial.contentDescription.toString())
    }

    @Test
    fun roundedTargetsStayWithinTheDevicesLimits() {
        // A 30.25 maximum with a 0.5 step: End sends 30.25, not 30.3.
        val sent = ArrayList<Double>()
        val dial = ThermostatDialView(context(), "°C").apply { onCommit = { sent += it } }
        dial.bind(10.25, 30.25, 0.5, 22.0, 21.25, "heat", "idle")
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
        assertEquals(listOf(30.25), sent)
        // A maximum off the step grid: End reaches it exactly (30.2), not the step below it.
        sent.clear()
        dial.bind(10.0, 30.2, 0.5, 22.0, 21.0, "heat", "idle")
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
        assertEquals(listOf(30.2), sent)
    }

    @Test
    fun theDevicesExactMinimumIsReachableByTouch() {
        // A 7.3 minimum on a 0.5 step: the ring's start is 7.3, not the 7.5 a zero-based step would make it.
        val sent = ArrayList<Double>()
        val dial = ThermostatDialView(context(), "°C").apply { onCommit = { sent += it } }
        dial.bind(7.3, 35.0, 0.5, 22.0, 21.0, "heat", "idle")
        dial.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        dial.layout(0, 0, 320, 320)
        touch(dial, MotionEvent.ACTION_DOWN, 10.0) // the helper's 10 is the ring's start
        touch(dial, MotionEvent.ACTION_UP, 10.0)
        assertEquals(listOf(7.3), sent)
    }

    @Test
    fun aKeyReleaseThatSendsNothingStillSettlesThePreview() {
        // 59 held up to 61; HA then reports a 60 maximum and a 60 target. The release sends nothing (HA has 60), and
        // the dial reads 60, not the 61 it previewed.
        val sent = ArrayList<Double>()
        val dial = ThermostatDialView(context(), "°C").apply { onCommit = { sent += it } }
        dial.bind(10.0, 70.0, 1.0, null, 59.0, "heat", "idle")
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        dial.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
        assertTrue(dial.contentDescription.toString(), "61" in dial.contentDescription.toString())
        dial.bind(10.0, 60.0, 1.0, null, 60.0, "heat", "idle")
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(sent.toString(), sent.isEmpty())
        assertTrue(dial.contentDescription.toString(), "60" in dial.contentDescription.toString() && "61" !in dial.contentDescription.toString())
    }

    @Test
    fun aCancelledSingleDragShowsTheRangeHaMovedTo() {
        val sent = ArrayList<Double>()
        val dial = ThermostatDialView(context(), "°C").apply { onCommit = { sent += it } }
        dial.bind(10.0, 30.0, 0.5, 22.0, 21.0, "heat", "idle")
        dial.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        dial.layout(0, 0, 320, 320)
        touch(dial, MotionEvent.ACTION_DOWN, 25.0)
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 20.0, high = 24.0)
        touch(dial, MotionEvent.ACTION_CANCEL, 25.0)
        assertTrue(dial.isRange)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun talkBackReadsTwoSlidersNamedAfterTheTargets() {
        val (dial, commits) = rangeDial()
        val provider: AccessibilityNodeProvider = dial.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertEquals(2, host.childCount)
        val low = provider.createAccessibilityNodeInfo(1)!!
        val high = provider.createAccessibilityNodeInfo(2)!!
        assertEquals("Lower target temperature, 20.0 °C", low.contentDescription.toString())
        assertEquals("Upper target temperature, 24.0 °C", high.contentDescription.toString())
        assertEquals(20f, low.rangeInfo.current, 0f)
        assertEquals(10f, low.rangeInfo.min, 0f)
        assertEquals(30f, high.rangeInfo.max, 0f)
        assertTrue(low.isSelected)
        assertFalse(high.isSelected)
        assertTrue(high.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
        // A swipe on the upper slider selects it and steps it; a double tap on it selects it.
        assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(20.0 to 24.5), commits)
        assertEquals(ClimateHandle.HIGH, dial.selected)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(ClimateHandle.LOW, dial.selected)
        // A single target is one slider on the dial itself.
        dial.bind(10.0, 30.0, 0.5, 22.0, 21.0, "heat", "idle")
        assertNull(dial.accessibilityNodeProvider)
        val info = AccessibilityNodeInfo.obtain()
        dial.onInitializeAccessibilityNodeInfo(info)
        assertEquals(21f, info.rangeInfo.current, 0f)
    }

    // ---- The cards ----

    @Test
    fun theThermostatCardSetsTheRangeOptimistically() {
        val st = store(heatCool())
        val card = ThermostatCardView(context(st), card("""{"type": "thermostat", "entity": "climate.hall"}"""))
        ShadowLooper.idleMainLooper()
        assertTrue(card.dial().isRange)
        // HA's − and + name the end they move, and follow the selection.
        card.byDescription("Increase lower target temperature").performClick()
        assertRange(calls.last(), 20.5, 24.0)
        assertEquals(20.5, st["climate.hall"]!!.attributes["target_temp_low"]) // shown before HA answers
        card.dial().select(ClimateHandle.HIGH)
        card.byDescription("Decrease upper target temperature").performClick()
        assertRange(calls.last(), 20.5, 23.5)
        assertEquals(View.VISIBLE, (card.byDescription("Increase upper target temperature").parent as View).visibility)
    }

    @Test
    fun noWriteForAnEntityTheStoreNoLongerHasOrThatMovedToARange() {
        val single = EntityState("climate.hall", "heat", mapOf(
            "friendly_name" to "Hall", "hvac_modes" to listOf("off", "heat"), "supported_features" to 1,
            "min_temp" to 10.0, "max_temp" to 30.0, "target_temp_step" to 0.5, "temperature" to 21.0,
        ))
        val st = store(single)
        val card = ThermostatCardView(context(st), card("""{"type": "thermostat", "entity": "climate.hall"}"""))
        ShadowLooper.idleMainLooper()
        card.byDescription("Increase temperature").performClick()
        assertEquals(21.5, calls.single().data.getDouble("temperature"), 0.0)
        // The entity leaves the store while the dial still shows its old target (as before the posted rebind):
        // a press sends nothing.
        st.applyCompressedEvent(JSONObject().put("r", org.json.JSONArray().put("climate.hall")))
        card.dial().bind(10.0, 30.0, 0.5, 22.0, 21.5, "heat", "idle")
        card.dial().nudge(1)
        assertEquals(1, calls.size)
        // Back, but in a range now, with the dial still on the single target: nothing either.
        st.putLocal(heatCool())
        card.dial().bind(10.0, 30.0, 0.5, 22.0, 21.5, "heat", "idle")
        card.dial().nudge(1)
        assertEquals(1, calls.size)
        // More Info: an entity with no state at all sends nothing.
        val orphan = MoreInfoSheet(context(EntityStore())) {}
        orphan.show("climate.gone")
        ShadowLooper.idleMainLooper()
        orphan.dial().bind(10.0, 30.0, 0.5, 22.0, 21.0, "heat", "idle")
        orphan.dial().nudge(1)
        assertEquals(1, calls.size)
    }

    @Test
    fun aKeyReleaseUsesTheStoresNewerOtherEnd() {
        // End held on the low end (shown clipped at 24), HA raises the high end to 26 before the key comes up and
        // before the card rebinds: the card sends 26–26, from the key's own proposal and the store's high end.
        val st = store(heatCool())
        val card = ThermostatCardView(context(st), card("""{"type": "thermostat", "entity": "climate.hall"}"""))
        ShadowLooper.idleMainLooper()
        val dial = card.dial()
        assertEquals(ClimateHandle.LOW, dial.selected)
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
        assertTrue(calls.isEmpty()) // nothing on key-down
        st.putLocal(heatCool("target_temp_high" to 26.0))
        dial.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
        assertEquals(1, calls.size)
        assertRange(calls.single(), 26.0, 26.0)
    }

    @Test
    fun moreInfoSetsTheRange() {
        val st = store(heatCool())
        val sheet = MoreInfoSheet(context(st)) {}
        sheet.show("climate.hall")
        ShadowLooper.idleMainLooper()
        val dial = sheet.dial()
        assertTrue(dial.isRange)
        sheet.byDescription("Increase lower target temperature").performClick()
        assertRange(calls.last(), 20.5, 24.0)
    }

    @Test
    fun mushroomShowsTwoInputNumbersForARange() {
        val st = store(heatCool())
        val card = MushroomClimateCardView(context(st),
            card("""{"type": "custom:mushroom-climate-card", "entity": "climate.hall", "show_temperature_control": true}"""))
        ShadowLooper.idleMainLooper()
        val texts = card.all().filterIsInstance<android.widget.TextView>().filter { it.visible() }.map { it.text.toString() }
        assertTrue(texts.toString(), "20.0" in texts && "24.0" in texts)
        // Mushroom sends the end it moved with the other one as it is.
        card.byDescription("Increase upper target temperature").performClick()
        assertRange(calls.last(), 20.0, 24.5)
        card.byDescription("Decrease lower target temperature").performClick()
        assertRange(calls.last(), 19.5, 24.5)
        // The low end stops at the high one.
        st.putLocal(heatCool("target_temp_low" to 24.0, "target_temp_high" to 24.0))
        ShadowLooper.idleMainLooper()
        val before = calls.size
        card.byDescription("Increase lower target temperature").performClick()
        assertEquals(before, calls.size)
    }

    @Test
    fun theTilesTargetTemperatureFeatureStepsEachEnd() {
        val st = store(heatCool())
        val tile = TileCardView(context(st), card(
            """{"type": "tile", "entity": "climate.hall", "features": [{"type": "target-temperature"}]}""",
        ))
        ShadowLooper.idleMainLooper()
        assertTrue(tile.all().any { it.visible() && it.contentDescription == "Lower target temperature, 20.0 °C" })
        tile.byDescription("Decrease upper target temperature").performClick()
        assertRange(calls.last(), 20.0, 23.5)
        // Through the shared optimistic write: the store shows it at once, and a second press steps from it.
        assertEquals(23.5, st["climate.hall"]!!.attributes["target_temp_high"])
        tile.byDescription("Decrease upper target temperature").performClick()
        assertRange(calls.last(), 20.0, 23.0)
    }
}
