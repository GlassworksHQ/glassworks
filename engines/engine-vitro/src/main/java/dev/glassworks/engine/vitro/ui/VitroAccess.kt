/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import dev.glassworks.platform.Platform
import java.util.Collections
import java.util.WeakHashMap

/**
 * What a control is, for TalkBack: the role HA's page gives the element (`role`, `aria-checked`, `aria-pressed`),
 * as the Android widget class TalkBack announces it by.
 */
internal enum class A11yRole(val className: String) {
    /** `role="button"`, HA's default for anything that acts on a press (cards, chips, icon buttons, keypads). */
    BUTTON("android.widget.Button"),

    /** `role="switch"` with `aria-checked`: `ha-switch`, `ha-control-switch`, `ha-entity-toggle`. */
    SWITCH("android.widget.Switch"),

    /** `role="checkbox"` with `aria-checked`: a to-do item's check box. */
    CHECKBOX("android.widget.CheckBox"),

    /** `role="radio"` with `aria-checked`: one choice of `ha-control-select` (the HVAC modes). */
    RADIO("android.widget.RadioButton"),

    /** A button that stays pressed (`aria-pressed`): the light's mode buttons, a calendar's view buttons. */
    TOGGLE_BUTTON("android.widget.ToggleButton"),
}

/**
 * Accessibility for Vitro's controls, the way HA's page exposes them.
 *
 * Controls name themselves where they're built ([role], `contentDescription`). [applyDefaults] does what every control
 * needs on top: keyboard focus, so DPAD and Enter reach it on Android 6 and 7 too (which have no automatic focus for
 * clickable views), and HA's default `role="button"` for a control that doesn't declare another role. [install] runs
 * it on a view tree while TalkBack is on or a keyboard or D-pad is attached, and never otherwise.
 */
internal object VitroAccess {
    /** Views whose role was set where they were built: [applyDefaults] leaves them alone. */
    private val declared: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** Classes that describe themselves (`onInitializeAccessibilityNodeInfo` of their own), by class. */
    private val selfDescribing = HashMap<Class<*>, Boolean>()

    /**
     * Gives [view] the role [role]; with [checked], a checkable state read when TalkBack asks (`aria-checked`, or for
     * [A11yRole.TOGGLE_BUTTON] `aria-pressed`). [label], when given, is its name; [name] is one read when TalkBack
     * asks, for a view with no description of its own (a name that follows a template, say). It also takes keyboard
     * focus.
     */
    fun role(
        view: View,
        role: A11yRole,
        label: CharSequence? = null,
        name: (() -> CharSequence?)? = null,
        checked: (() -> Boolean)? = null,
    ) {
        if (label != null) view.contentDescription = label
        view.isFocusable = true
        declared += view
        view.accessibilityDelegate = RoleDelegate(role, checked, name)
    }

    /**
     * A container that's clickable only to keep taps from reaching what's under it (a sheet's panel, its scrim):
     * not a control for TalkBack or the keyboard.
     */
    fun notAControl(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.isFocusable = false
        declared += view
    }

    /**
     * Tells TalkBack a slider's value changed (an edit, or a new value from HA), as Android's own SeekBar does with
     * `TYPE_VIEW_SELECTED`, so it reads the new value. Nothing while no accessibility service is on.
     */
    fun valueChanged(view: View) {
        val am = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true && view.isAttachedToWindow) view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /** Whether controls need [applyDefaults] now: TalkBack (or another service) is on, or there are keys to press. */
    fun wanted(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true) return true
        val c = context.resources.configuration
        val keyboard = c.keyboard != Configuration.KEYBOARD_NOKEYS && c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        val dpad = c.navigation == Configuration.NAVIGATION_DPAD || c.navigation == Configuration.NAVIGATION_TRACKBALL
        return keyboard || dpad
    }

    /**
     * Applies [applyDefaults] to [root] after each layout while [wanted], and once more when TalkBack turns on (the
     * next layout picks up views built since).
     */
    fun install(root: View) {
        val onLayout = ViewTreeObserver.OnGlobalLayoutListener { if (wanted(root.context)) applyDefaults(root) }
        val am = root.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val onState = AccessibilityManager.AccessibilityStateChangeListener { on -> if (on) root.requestLayout() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
                am?.addAccessibilityStateChangeListener(onState)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
                am?.removeAccessibilityStateChangeListener(onState)
            }
        })
    }

    /** Every exposed control under [root] takes keyboard focus, and is a button unless it says what else it is. */
    fun applyDefaults(root: View) {
        if (root.visibility != View.VISIBLE) return
        if (isControl(root)) {
            if (!explicitlyFocusable(root)) root.isFocusable = true
            if (root !in declared && !hasOwnRole(root)) {
                declared += root
                root.accessibilityDelegate = BUTTON
            }
        }
        val g = root as? ViewGroup ?: return
        for (i in 0 until g.childCount) applyDefaults(g.getChildAt(i))
    }

    /** A view TalkBack and the keyboard treat as a control: it acts on a press and isn't hidden from them. */
    fun isControl(v: View): Boolean =
        (v.isClickable || v.isLongClickable) && v.isEnabled && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO

    /** Focusable for keys on every Android version: Android 8's automatic focus doesn't count before it. */
    fun explicitlyFocusable(v: View): Boolean =
        if (Platform.hasFocusableAuto) v.focusable == View.FOCUSABLE else v.isFocusable

    /** A widget with a role of its own, virtual nodes, or a class that describes itself (a dial, a slider). */
    private fun hasOwnRole(v: View): Boolean =
        v is CompoundButton || v is SeekBar || v is EditText || v.accessibilityNodeProvider != null ||
            selfDescribing.getOrPut(v.javaClass) { describesItself(v.javaClass) }

    private fun describesItself(cls: Class<*>): Boolean {
        var c: Class<*>? = cls
        while (c != null && !c.name.startsWith("android.")) {
            if (c.declaredMethods.any { it.name == "onInitializeAccessibilityNodeInfo" }) return true
            c = c.superclass
        }
        return false
    }

    private val BUTTON = RoleDelegate(A11yRole.BUTTON, null, null)

    private class RoleDelegate(
        val role: A11yRole,
        val checked: (() -> Boolean)?,
        val name: (() -> CharSequence?)?,
    ) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = role.className
            if (host.contentDescription.isNullOrBlank()) name?.invoke()?.takeIf { it.isNotBlank() }?.let { info.contentDescription = it }
            val on = checked?.invoke() ?: return
            info.isCheckable = true
            info.isChecked = on
        }
    }
}

/**
 * A slider TalkBack and keys can move, as HA's `role="slider"` controls are: its value and range for TalkBack, whose
 * swipe up and down move it a tenth; with keys, the left and right arrows move it one [step], the page keys a tenth,
 * Home and End to the ends, committed on release (HA's `keyup`). Up and down are left for moving between controls, and
 * an arrow at an end is too, as Android's SeekBar does, so a D-pad never gets stuck on a slider.
 *
 * [preview] shows a value (and tells the slider's listeners), [commit] sends it.
 */
internal class AdjustableAccess(
    private val min: () -> Int,
    private val max: () -> Int,
    private val step: Int,
    private val value: () -> Int,
    private val preview: (Int) -> Unit,
    private val commit: (Int) -> Unit,
    /** An edit dropped (a cancelled key, focus gone): show HA's value again. */
    private val restore: () -> Unit = {},
) {
    private var pending = false

    /** The key whose release ends the edit (a Shift let go meanwhile doesn't). */
    private var pendingKey = 0

    /** A key edit is showing: a value bound from HA waits until its key comes up. */
    val editing: Boolean get() = pending

    private val tenth: Int get() = maxOf(step, (max() - min()) / 10)

    fun onInitialize(info: AccessibilityNodeInfo) {
        val v = value()
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min().toFloat(), max().toFloat(), v.toFloat(),
        )
        if (v < max()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (v > min()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    /** TalkBack's scroll actions: a tenth, sent at once. Null for an action this doesn't take. */
    fun perform(action: Int): Boolean? {
        val sign = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return null
        }
        val next = (value() + sign * tenth).coerceIn(min(), max())
        if (next == value()) return false
        preview(next)
        commit(next)
        return true
    }

    /** A key: true when it moved the value (the view keeps the key), false to let Android move focus. */
    fun keyDown(keyCode: Int): Boolean {
        val v = value()
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> v + step
            KeyEvent.KEYCODE_DPAD_LEFT -> v - step
            KeyEvent.KEYCODE_PAGE_UP -> v + tenth
            KeyEvent.KEYCODE_PAGE_DOWN -> v - tenth
            KeyEvent.KEYCODE_MOVE_HOME -> min()
            KeyEvent.KEYCODE_MOVE_END -> max()
            else -> return false
        }.coerceIn(min(), max())
        // At an end: a new press moves focus on, but the held key of an edit under way stays with it (a repeat
        // that moves focus would drop the edit before its key comes up).
        if (next == v) return pending && keyCode == pendingKey
        preview(next)
        pending = true
        pendingKey = keyCode
        return true
    }

    /** The release of a key that moved the value: sends it (a cancelled one doesn't). */
    fun keyUp(event: KeyEvent): Boolean {
        if (!pending || event.keyCode != pendingKey) return false
        pending = false
        if (event.isCanceled) restore() else commit(value())
        return true
    }

    /** Focus left mid-edit: the edit is dropped, nothing is sent. */
    fun cancel() {
        if (!pending) return
        pending = false
        restore()
    }
}

