/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.glassworks.engine.vitro.cards.BrightnessDialView
import dev.glassworks.engine.vitro.cards.ClimateHandle
import dev.glassworks.engine.vitro.cards.ColorTempSliderView
import dev.glassworks.engine.vitro.cards.HaColorTempBarView
import dev.glassworks.engine.vitro.cards.HaHorizontalSliderView
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.cards.VerticalCoverSliderView
import dev.glassworks.engine.vitro.cards.VerticalSliderPillView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
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
 * Every native range control (the thermostat dial, the light and humidifier dials, the tile sliders, More Info's
 * sliders) for TalkBack and keyboards: a value TalkBack can read and step, a key edit sent when its key comes up as a
 * drag is when it's let go, and TalkBack told the value a drag left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RangeControlsA11yTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun context() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun talkBackOn() = shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)

    /** The accessibility events [view] sends, by type. */
    private fun events(view: View): MutableList<Int> {
        val sent = ArrayList<Int>()
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                sent += eventType
                super.sendAccessibilityEvent(host, eventType)
            }
        }
        return sent
    }

    private fun attach(view: View, w: Int, h: Int) {
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun down(view: View, code: Int, repeat: Int = 0): Boolean =
        view.dispatchKeyEvent(KeyEvent.changeTimeRepeat(KeyEvent(KeyEvent.ACTION_DOWN, code), 0L, repeat))

    private fun up(view: View, code: Int): Boolean = view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))

    private fun View.node(): AccessibilityNodeInfo = createAccessibilityNodeInfo()

    private fun AccessibilityNodeInfo.has(action: AccessibilityNodeInfo.AccessibilityAction) = actionList.any { it.id == action.id }

    // D08: the dial's held key keeps asking past the other end.

    @Test
    fun aHeldKeyAgainstTheOtherEndKeepsWhatItAskedFor() {
        val sent = ArrayList<Triple<ClimateHandle, Double, Double>>()
        val dial = ThermostatDialView(context(), "°C").apply { onRangeCommit = { h, lo, hi -> sent += Triple(h, lo, hi) } }
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 23.5, high = 24.0)
        attach(dial, 320, 320)
        assertEquals(ClimateHandle.LOW, dial.selected)
        // Right held on the low end: the first press reaches the high end, the repeat asks a step past it.
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1))
        // HA raises the high end before the key comes up: the release sends what the repeat asked for.
        dial.bind(10.0, 30.0, 0.5, 22.0, null, "heat_cool", "idle", "Heat/Cool", low = 23.5, high = 26.0)
        assertTrue(up(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(Triple(ClimateHandle.LOW, 24.5, 26.0)), sent)
    }

    @Test
    fun theSingleTargetDialTellsTalkBackItsNewValue() {
        talkBackOn()
        val sent = ArrayList<Double>()
        val dial = ThermostatDialView(context(), "°C").apply { onCommit = { sent += it } }
        dial.bind(10.0, 30.0, 0.5, 22.0, 21.0, "heat", "idle")
        attach(dial, 320, 320)
        val events = events(dial)
        assertTrue(dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(21.5), sent)
        assertTrue(events.toString(), AccessibilityEvent.TYPE_VIEW_SELECTED in events)
        // A key edit: TalkBack hears each step, HA gets one call when the key comes up.
        events.clear()
        down(dial, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(events.toString(), AccessibilityEvent.TYPE_VIEW_SELECTED in events)
        assertEquals(1, sent.size)
        up(dial, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(21.5, 22.0), sent)
    }

    // D09: More Info's three sliders tell TalkBack the value a drag left.

    @Test
    fun moreInfoSlidersAnnounceTheValueADragLeft() {
        talkBackOn()
        val pill = VerticalSliderPillView(activity, 0xFFFFA000.toInt()).apply { setPercent(40) }
        val kelvin = ColorTempSliderView(activity, 2000, 6500).apply { setKelvin(3000) }
        val cover = VerticalCoverSliderView(activity).apply { setPercent(40) }
        for (slider in listOf(pill, kelvin, cover)) {
            attach(slider, 100, 300)
            val events = events(slider)
            touch(slider, MotionEvent.ACTION_DOWN, 50f, 150f)
            touch(slider, MotionEvent.ACTION_MOVE, 50f, 60f)
            assertFalse("${slider.javaClass.simpleName}: $events", AccessibilityEvent.TYPE_VIEW_SELECTED in events)
            touch(slider, MotionEvent.ACTION_UP, 50f, 60f)
            assertTrue("${slider.javaClass.simpleName}: $events", AccessibilityEvent.TYPE_VIEW_SELECTED in events)
        }
    }

    @Test
    fun kelvinSlidersReadInKelvin() {
        val kelvin = ColorTempSliderView(activity, 2000, 6500).apply { setKelvin(2700) }
        attach(kelvin, 100, 300)
        assertEquals("2700 K", kelvin.node().stateDescription?.toString())
        val bar = HaColorTempBarView(context()) {}.apply { bind(2000, 6500, 2700, on = true) }
        attach(bar, 300, 42)
        assertEquals("2700 K", bar.node().stateDescription?.toString())
    }

    @Test
    fun setProgressSetsAMoreInfoSlider() {
        val released = ArrayList<Int>()
        val pill = VerticalSliderPillView(activity, 0xFFFFA000.toInt()).apply {
            onRelease = { released += it }
            setPercent(40)
        }
        attach(pill, 100, 300)
        assertTrue(pill.node().has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 73f) }
        assertTrue(pill.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertEquals(listOf(73), released)
    }

    // The tile sliders: what More Info's do, on a dashboard.

    @Test
    fun aTileSliderSendsAKeyEditWhenItsKeyComesUp() {
        talkBackOn()
        val sent = ArrayList<Float>()
        val slider = HaHorizontalSliderView(context(), 0xFFFFA000.toInt()) { sent += it }.apply { setValue(0.4f, true) }
        attach(slider, 300, 42)
        val info = slider.node()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(40f, info.rangeInfo.current, 0f)
        // Held right: each repeat moves it, nothing is sent until the key comes up, then once.
        assertTrue(down(slider, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(down(slider, KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1))
        assertTrue(sent.isEmpty())
        // HA's value arriving mid-edit doesn't move it under the key.
        slider.setValue(0.4f, true)
        assertEquals(0.42f, slider.value, 0.001f)
        assertTrue(up(slider, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, sent.size)
        assertEquals(0.42f, sent.single(), 0.001f)
        // Up and down move focus, as on a SeekBar: a D-pad isn't stuck on the slider.
        assertFalse(down(slider, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(down(slider, KeyEvent.KEYCODE_DPAD_UP))
        // A cancelled release sends nothing and shows HA's value again.
        slider.setValue(0.42f, true)
        down(slider, KeyEvent.KEYCODE_DPAD_LEFT)
        slider.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT), KeyEvent.FLAG_CANCELED))
        assertEquals(1, sent.size)
        assertEquals(0.42f, slider.value, 0.001f)
        // At the end an arrow isn't taken, so focus moves on.
        slider.setValue(1f, true)
        assertFalse(down(slider, KeyEvent.KEYCODE_DPAD_RIGHT))
        // TalkBack's swipe is sent at once; a drag's release is read out.
        slider.setValue(0.5f, true)
        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(0.6f, sent.last(), 0.001f)
        val events = events(slider)
        touch(slider, MotionEvent.ACTION_DOWN, 30f, 20f)
        touch(slider, MotionEvent.ACTION_UP, 30f, 20f)
        assertTrue(events.toString(), AccessibilityEvent.TYPE_VIEW_SELECTED in events)
    }

    @Test
    fun aTileColorTempBarSendsAKeyEditWhenItsKeyComesUp() {
        val sent = ArrayList<Int>()
        val bar = HaColorTempBarView(context()) { sent += it }.apply { bind(2000, 6500, 2700, on = true) }
        attach(bar, 300, 42)
        assertTrue(down(bar, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(sent.isEmpty())
        assertTrue(up(bar, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(2745), sent)
        assertFalse(down(bar, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun aKelvinKeyEditTakesTheLimitsHaReportsMeanwhile() {
        val sent = ArrayList<Int>()
        val bar = HaColorTempBarView(context()) { sent += it }.apply { bind(2000, 6500, 6000, on = true) }
        attach(bar, 300, 42)
        assertTrue(down(bar, KeyEvent.KEYCODE_DPAD_RIGHT))
        // HA narrows the range while the key is held: the release sends a value inside the new one.
        bar.bind(2000, 5000, 4800, on = true)
        assertTrue(up(bar, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(5000), sent)
        assertEquals(5000f, bar.node().rangeInfo.max, 0f)
    }

    // The light card's and the humidifier card's dials.

    @Test
    fun theLightDialIsASliderThatStillToggles() {
        val brightness = ArrayList<Int>()
        var toggles = 0
        val dial = BrightnessDialView(activity, 0xFFFFA000.toInt(), 0xFF888888.toInt(), 0xFF888888.toInt(), 0xFF03A9F4.toInt()).apply {
            onBrightness = { brightness += it }
            onToggle = { toggles++ }
            // The light reports 40 % (HA's slider runs 1–100: 40 % is (40 - 1) / 99 of the arc).
            update(true, BrightnessDialView.fractionOf(40), "mdi:lightbulb")
        }
        attach(dial, 200, 200)
        val info = dial.node()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(40f, info.rangeInfo.current, 0f)
        assertTrue(info.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
        // Keys: sent on release.
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(brightness.isEmpty())
        up(dial, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(41), brightness)
        // TalkBack: a tenth, at once.
        dial.update(true, BrightnessDialView.fractionOf(41), "mdi:lightbulb")
        assertTrue(dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(51, brightness.last())
        // Two TalkBack steps before the optimistic render: the second goes on from the first.
        dial.update(true, BrightnessDialView.fractionOf(50), "mdi:lightbulb")
        brightness.clear()
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(listOf(60, 70), brightness)
        // A write turned down: HA's value comes back, and the next step starts from it.
        dial.update(true, BrightnessDialView.fractionOf(50), "mdi:lightbulb")
        assertEquals(50f, dial.node().rangeInfo.current, 0f)
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(60, brightness.last())
        // Enter toggles, as a tap on the icon does.
        down(dial, KeyEvent.KEYCODE_ENTER)
        up(dial, KeyEvent.KEYCODE_ENTER)
        assertEquals(1, toggles)
        // An on/off light: a button, and arrows move focus.
        dial.update(true, 1f, "mdi:lightbulb", dimmable = false)
        assertEquals("android.widget.Button", dial.node().className)
        assertFalse(down(dial, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun aLightDialDisabledMidKeyEditSendsNothingOnRelease() {
        val brightness = ArrayList<Int>()
        val dial = BrightnessDialView(activity, 0xFFFFA000.toInt(), 0xFF888888.toInt(), 0xFF888888.toInt(), 0xFF03A9F4.toInt()).apply {
            onBrightness = { brightness += it }
            update(true, BrightnessDialView.fractionOf(40), "mdi:lightbulb")
        }
        attach(dial, 200, 200)
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
        // Unavailable while the key is held, then back as off: the release must not turn the light on at 1 %.
        dial.update(false, 0f, "mdi:lightbulb", enabled = false)
        dial.update(false, 0f, "mdi:lightbulb", enabled = true)
        up(dial, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(brightness.toString(), brightness.isEmpty())
    }

    /** The humidifier card's dial: the thermostat's, with HA's humidity control ([ThermostatDialView.humidifier]). */
    private fun humidityDial(sent: MutableList<Double>, min: Double, max: Double, target: Double?) =
        ThermostatDialView(context(), "%").apply {
            humidifier = true
            onCommit = { sent += it }
            bind(min, max, 1.0, current = 45.0, target = target, mode = "on", action = "humidifying", stateLabel = "On")
            attach(this, 300, 300)
        }

    @Test
    fun theHumidifierDialIsASlider() {
        val sent = ArrayList<Double>()
        val dial = humidityDial(sent, 30.0, 80.0, 50.0)
        assertTrue(dial.isFocusable)
        val info = dial.node()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(30f, info.rangeInfo.min, 0f)
        assertEquals(80f, info.rangeInfo.max, 0f)
        assertEquals(50f, info.rangeInfo.current, 0f)
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(sent.isEmpty())
        assertTrue(dial.contentDescription.toString(), "49%" in dial.contentDescription.toString())
        up(dial, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(49.0), sent)
        // No target (off with no humidity): nothing to move.
        dial.bind(30.0, 80.0, 1.0, current = 45.0, target = null, mode = "off", action = "off", stateLabel = "Off")
        assertFalse(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun aHumidityKeyEditIsDroppedWhenTheTargetGoes() {
        val sent = ArrayList<Double>()
        val dial = humidityDial(sent, 30.0, 80.0, 50.0)
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_LEFT))
        // Turned off while the key is held: nothing to set, nothing sent.
        dial.bind(30.0, 80.0, 1.0, current = 45.0, target = null, mode = "off", action = "off", stateLabel = "Off")
        up(dial, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test
    fun aHumidityKeyEditIsDroppedWhenTheTargetGoesAndComesBack() {
        val sent = ArrayList<Double>()
        val dial = humidityDial(sent, 30.0, 80.0, 50.0)
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_LEFT))
        // Off and on again while the key is held: the release has no edit left to send.
        dial.bind(30.0, 80.0, 1.0, current = 45.0, target = null, mode = "off", action = "off", stateLabel = "Off")
        dial.bind(30.0, 80.0, 1.0, current = 45.0, target = 55.0, mode = "on", action = "humidifying", stateLabel = "On")
        up(dial, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(sent.toString(), sent.isEmpty())
        assertEquals(55f, dial.node().rangeInfo.current, 0f)
    }

    @Test
    fun talkBackSetsAndStepsTheHumidityByATenth() {
        val sent = ArrayList<Double>()
        val dial = humidityDial(sent, 30.0, 80.0, 50.0)
        assertTrue(dial.node().has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
        // HA's _tenPercentStep: a tenth of 30–80 is 5.
        assertTrue(dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(55.0), sent)
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 65f) }
        assertTrue(dial.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertEquals(listOf(55.0, 65.0), sent)
        // Outside the limits: the nearest end.
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 95f)
        assertTrue(dial.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertEquals(80.0, sent.last(), 0.0)
        // A thermostat keeps its step per swipe and takes no set-progress.
        val temps = ArrayList<Double>()
        val thermostat = ThermostatDialView(context(), "°C").apply {
            onCommit = { temps += it }
            bind(7.0, 35.0, 0.5, current = 20.0, target = 21.0, mode = "heat", action = "heating", stateLabel = "Heat")
            attach(this, 300, 300)
        }
        assertFalse(thermostat.node().has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
        thermostat.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(listOf(21.5), temps)
    }

    @Test
    fun aHumidityKeyEditTakesTheLimitsHaReportsMeanwhile() {
        val sent = ArrayList<Double>()
        val dial = humidityDial(sent, 20.0, 80.0, 79.0)
        assertTrue(down(dial, KeyEvent.KEYCODE_DPAD_RIGHT))
        // HA narrows the range while the key is held: the release sends a value inside the new one.
        dial.bind(20.0, 60.0, 1.0, current = 45.0, target = 79.0, mode = "on", action = "humidifying", stateLabel = "On")
        up(dial, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(60.0), sent)
    }
}
