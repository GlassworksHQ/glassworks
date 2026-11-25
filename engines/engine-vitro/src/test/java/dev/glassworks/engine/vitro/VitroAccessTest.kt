/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.VitroAccess
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
import org.robolectric.shadows.ShadowLooper

/** The roles, states and keys Vitro's controls give TalkBack and keyboards (VitroAccess and the controls). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroAccessTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun context(store: EntityStore = EntityStore()) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun View.node(): AccessibilityNodeInfo = createAccessibilityNodeInfo()

    private fun View.all(): List<View> =
        listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    @Test
    fun aPlainControlBecomesAFocusableButtonAndAWidgetKeepsItsRole() {
        val root = LinearLayout(activity)
        val plain = FrameLayout(activity).apply { isClickable = true; contentDescription = "More info" }
        val dial = ThermostatDialView(context(), "°C").apply { isClickable = true }
        val decor = View(activity) // not a control
        val scrim = FrameLayout(activity).apply { isClickable = true }
        VitroAccess.notAControl(scrim)
        listOf(plain, dial, decor, scrim).forEach(root::addView)
        VitroAccess.applyDefaults(root)
        // Focus of its own, so Android 6 and 7 reach it with keys too (no automatic focus there).
        assertEquals(View.FOCUSABLE, plain.focusable)
        assertEquals("android.widget.Button", plain.node().className)
        // A view that describes itself keeps its own role.
        dial.bind(10.0, 30.0, 0.5, 20.0, 21.0, "heat", "idle")
        assertEquals("android.widget.SeekBar", dial.node().className)
        assertFalse(decor.isFocusable)
        // A touch-swallowing container is no control at all.
        assertFalse(scrim.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, scrim.importantForAccessibility)
    }

    @Test
    fun aDeclaredRoleReadsItsStateWhenAsked() {
        var on = false
        val root = LinearLayout(activity)
        // A node gets its view's description only on screen.
        activity.setContentView(root)
        val v = FrameLayout(activity).apply { isClickable = true }
        root.addView(v)
        VitroAccess.role(v, A11yRole.SWITCH, label = "Toggle Desk lamp") { on }
        assertEquals("android.widget.Switch", v.node().className)
        assertTrue(v.node().isCheckable)
        assertFalse(v.node().isChecked)
        on = true
        assertTrue(v.node().isChecked)
        assertEquals("Toggle Desk lamp", v.node().contentDescription.toString())
        // A name read when asked, for a view with none of its own.
        var title = "AC"
        val avatar = FrameLayout(activity).apply { isClickable = true }
        root.addView(avatar)
        VitroAccess.role(avatar, A11yRole.BUTTON, name = { "Toggle $title" })
        title = "Heat pump"
        assertEquals("Toggle Heat pump", avatar.node().contentDescription.toString())
    }

    @Test
    fun anEntitiesRowToggleIsANamedSwitch() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk lamp")))
        val card = EntitiesCardView(context(store), JSONObject("""{"type": "entities", "entities": ["light.desk"]}""").let { CardConfig(it.getString("type"), it) })
        activity.setContentView(card)
        ShadowLooper.idleMainLooper()
        val switch = card.all().single { it.contentDescription?.toString() == "Toggle Desk lamp" }
        assertEquals("android.widget.Switch", switch.node().className)
        assertTrue(switch.node().isChecked)
    }

    @Test
    fun moreInfoSlidersMoveWithTalkBackAndKeys() {
        val released = ArrayList<Int>()
        val pill = dev.glassworks.engine.vitro.cards.VerticalSliderPillView(activity, 0xFFFFA000.toInt()).apply {
            onRelease = { released += it }
            setPercent(40)
        }
        activity.setContentView(pill)
        val info = pill.node()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(40f, info.rangeInfo.current, 0f)
        // A TalkBack swipe moves it a tenth and sends it.
        assertTrue(pill.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(50), released)
        // Right moves it one step, sent on release; down is left for moving focus.
        assertTrue(pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(51, released.last())
        assertFalse(pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        val cover = dev.glassworks.engine.vitro.cards.VerticalCoverSliderView(activity).apply { setPercent(100) }
        // At the end an arrow isn't taken, so a D-pad moves on.
        assertFalse(cover.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test
    fun moreInfoIsModalForTalkBackAndKeys() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk lamp", "supported_color_modes" to listOf("onoff"))))
        val view = dev.glassworks.engine.vitro.ui.VitroView(activity, store, android.graphics.Color.BLACK)
        view.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        activity.setContentView(view)
        view.openMoreInfo("light.desk")
        ShadowLooper.idleMainLooper()
        // The dashboard behind the sheet is hidden from TalkBack and takes no focus, as behind HA's dialog.
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.overlayLayer.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, view.overlayLayer.descendantFocusability)
        view.closeMoreInfo()
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.overlayLayer.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, view.overlayLayer.descendantFocusability)
    }

    @Test
    fun aDetachDuringTheExitLeavesNoSheetAndAFreeDashboard() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk lamp", "supported_color_modes" to listOf("onoff"))))
        val view = dev.glassworks.engine.vitro.ui.VitroView(activity, store, android.graphics.Color.BLACK)
        view.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        activity.setContentView(view)
        view.openMoreInfo("light.desk")
        ShadowLooper.idleMainLooper()
        view.closeMoreInfo()
        // Detached while the exit runs, then back: no sheet left over, the dashboard reachable again.
        activity.setContentView(View(activity))
        activity.setContentView(view)
        ShadowLooper.idleMainLooper()
        assertTrue(view.all().none { it is MoreInfoSheet })
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.overlayLayer.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, view.overlayLayer.descendantFocusability)
    }

    @Test
    fun aKeyEditKeepsItsValueUntilItsKeyComesUp() {
        val released = ArrayList<Int>()
        val pill = dev.glassworks.engine.vitro.cards.VerticalSliderPillView(activity, 0xFFFFA000.toInt()).apply {
            onRelease = { released += it }
            setPercent(40)
        }
        activity.setContentView(pill)
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        // HA's value arrives mid-edit, and a Shift is let go: the edit holds.
        pill.setPercent(40)
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertTrue(released.isEmpty())
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(41), released)
        // Held at the end, the key stays with its edit (a repeat that moved focus would drop it).
        pill.setPercent(99)
        assertTrue(pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(pill.dispatchKeyEvent(KeyEvent.changeTimeRepeat(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), 0L, 1)))
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(100, released.last())
        // A cancelled release puts HA's value back, caption included.
        val captions = ArrayList<Int>()
        pill.onChange = { captions += it }
        pill.setPercent(60)
        pill.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        pill.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT), KeyEvent.FLAG_CANCELED))
        assertEquals(listOf(59, 60), captions)
    }

    @Test
    fun backAndEscapeCloseMoreInfo() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk lamp", "supported_color_modes" to listOf("onoff"))))
        var closed = 0
        val sheet = MoreInfoSheet(context(store)) { closed++ }
        activity.setContentView(sheet)
        sheet.show("light.desk")
        ShadowLooper.idleMainLooper()
        // Close takes keyboard focus (it starts there once a key leaves touch mode).
        assertTrue(sheet.all().single { it.contentDescription?.toString() == "Close" }.isFocusable)
        // The scrim and the panel aren't controls: TalkBack doesn't stop on them.
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, sheet.importantForAccessibility)
        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, closed)
    }
}
