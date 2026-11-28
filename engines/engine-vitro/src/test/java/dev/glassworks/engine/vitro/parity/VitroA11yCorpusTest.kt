/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroView
import dev.glassworks.testing.RepoFiles
import dev.glassworks.ui.Inter
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Locale

/**
 * Every control Vitro draws can be found and used without sight or touch, as HA's page can: the parity corpus
 * rendered on the S1 with TalkBack on, and the More Info sheet of every entity it shows, walked view by view. A
 * control (a clickable or long-clickable view on screen that TalkBack sees) fails without a name (its own description
 * or text, the text inside it, or virtual nodes of its own), without a role (a button, a switch…, as HA's `role`), or
 * without keyboard focus of its own (Android 6 and 7 have no automatic focus, so DPAD and Enter wouldn't reach it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VitroA11yCorpusTest {
    @Test
    fun everyControlInTheCorpusHasANameAndTakesFocus() {
        val fontFile = File(RepoFiles.root, "core/src/main/res/font/inter.ttf")
        Inter.load = { fontFile.readBytes() }
        val root = ParityFiles.committed
        val shared = ParityShared.load(root)
        val frame = ParityFrame.load().first { it.id == "s1" }
        val cases = ParityCase.loadAll(root).filter { it.runsOn(frame) && (ParityFiles.onlyCases == null || it.id in ParityFiles.onlyCases!!) }
        val problems = LinkedHashSet<String>()
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            RuntimeEnvironment.setQualifiers(frame.qualifiers)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            // TalkBack on: VitroView gives its controls their focus and roles as it would on a panel.
            shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            val renderer = ParityRenderer(activity, frame, root, shared)
            for (case in cases) {
                val ref = ParityReference.find(root, frame, case)
                val render = renderer.render(case, ref)
                val view = render.root as VitroView
                problems += A11yWalk.problems(view).map { "${case.id}: $it" }
                // Each entity's More Info sheet, over the same view.
                val ids = ref?.states?.keys()?.asSequence()?.toList().orEmpty()
                for (id in ids) {
                    view.openMoreInfo(id)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
                    view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.width, view.height)
                    problems += A11yWalk.problems(view).map { "${case.id} more-info ${id.substringBefore('.')}: $it" }
                }
            }
            activity.setContentView(View(activity))
        } finally {
            Locale.setDefault(locale)
        }
        if (problems.isNotEmpty()) fail("${problems.size} control(s) TalkBack or a keyboard can't use:\n" + problems.joinToString("\n"))
    }
}

/** The walk: what makes a control usable without sight or touch. */
internal object A11yWalk {
    fun problems(root: View): List<String> {
        val out = ArrayList<String>()
        fun visit(v: View, path: String) {
            if (v.visibility != View.VISIBLE) return
            val here = "$path/${v.javaClass.simpleName}"
            if (VitroAccess.isControl(v)) {
                if (!named(v)) out += "$here has no name"
                if (!VitroAccess.explicitlyFocusable(v)) out += "$here (${name(v)}) isn't focusable"
                val role = v.createAccessibilityNodeInfo().className?.toString()
                if (role !in ROLES) out += "$here (${name(v)}) has no role ($role)"
            }
            (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) visit(g.getChildAt(i), here) }
        }
        visit(root, "")
        return out
    }

    /** The roles a control may have: HA's (A11yRole) and the widgets that bring their own. */
    private val ROLES = A11yRole.values().map { it.className }.toSet() + setOf("android.widget.SeekBar", "android.widget.EditText")

    private fun name(v: View): String = v.contentDescription?.toString() ?: (v as? android.widget.TextView)?.text?.toString() ?: "?"

    /** A name TalkBack reads: the view's own, a text inside it that isn't a control of its own, or virtual nodes. */
    fun named(v: View): Boolean {
        // What TalkBack reads: the node's description or text (a view may name itself only there).
        val node = v.createAccessibilityNodeInfo()
        if (!node.contentDescription.isNullOrBlank() || !node.text.isNullOrBlank()) return true
        if (v.accessibilityNodeProvider != null) return true
        val g = v as? ViewGroup ?: return false
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE || c.isClickable || c.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) continue
            if (named(c)) return true
        }
        return false
    }
}
