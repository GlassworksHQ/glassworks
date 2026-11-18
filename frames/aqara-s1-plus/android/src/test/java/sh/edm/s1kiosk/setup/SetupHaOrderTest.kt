package sh.edm.s1kiosk.setup

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Frame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.frame.TestFrames
import sh.edm.s1kiosk.ui.GroupedRail

/**
 * The Home Assistant step lists Sign in, then Add it, in every state, on a touch frame and on one without, and the
 * marker sits on the sub-step whose card shows: the card's label names it (the Mirror on 2026-11-18 had the marker
 * on Add it under a sign-in card, and Sign in went green while Add it stayed current).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupHaOrderTest {

    /** Per case: the current step, the card's label, its title. */
    private val s1 = mapOf(
        "neither" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to sign in."),
        "neither-page-loading" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to sign in."),
        "signed-in-not-added" to Triple(SetupFlow.HA, "Home Assistant · Add it", "Add it in Home Assistant."),
        "added-not-signed-in" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to sign in."),
        "both" to Triple(SetupFlow.PANES, "Panes · Choose", "What should it do?"),
        "moved" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to sign in."),
        "not-found" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Find Home Assistant."),
    )

    /** Without touch, Sign in is the setup page's until Home Assistant added the panel. */
    private val mirror = s1 + mapOf(
        "neither" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to set it up."),
        "neither-page-loading" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to set it up."),
        "not-found" to Triple(SetupFlow.SIGNIN, "Home Assistant · Sign in", "Scan to set it up."),
        "both" to Triple(SetupFlow.PANES, "Panes · Choose", "Scan to set it up."),
    )

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun texts(v: View) = views(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun check(frame: (android.content.Context) -> Frame, expected: Map<String, Triple<String, String, String>>) {
        for ((name, i) in HaOrderCases.all) {
            val (step, label, title) = expected.getValue(name)
            val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val f = frame(a)
            val s = HaOrderCases.screen(a, f, i)
            val decided = SetupState.decide(HaOrderCases.inputs(i, f.input))
            assertEquals(name, step, decided.step)
            val rows = views(s).filterIsInstance<GroupedRail>().single().rows
            assertEquals("$name: the order", listOf("Sign in", "Add it"), rows.filter { it.group == SetupFlow.HA }.map { it.title })
            assertEquals("$name: one marker, on the current step", listOf(step), rows.filter { it.mark == GroupedRail.Mark.CURRENT }.map { it.id })
            val marks = rows.filter { it.group == SetupFlow.HA }.associate { it.id to it.mark }
            for (id in listOf(SetupFlow.SIGNIN, SetupFlow.HA)) {
                val want = when (id) { step -> GroupedRail.Mark.CURRENT; in decided.done -> GroupedRail.Mark.DONE; else -> GroupedRail.Mark.PENDING }
                assertEquals("$name: $id", want, marks[id])
            }
            val t = texts(s)
            assertEquals("$name: the card's label names the current step: $t", 1, t.count { it.equals(label, ignoreCase = true) })
            assertEquals("$name: the card: $t", true, title in t)
        }
    }

    @Test fun aTouchFrameSignsInThenAdds() = check({ TestFrames.s1() }, s1)

    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun theMirrorSignsInThenAdds() = check({ TestFrames.mirror(RuntimeEnvironment.getApplication()) }, mirror)
}
