/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.HaHorizontalSliderView
import dev.glassworks.engine.vitro.cards.MushroomCoverCardView
import dev.glassworks.engine.vitro.compat.NativeOptions
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Mushroom 5.3.0's cover card with `show_tilt_position_control`: the tilt slider (`mushroom-cover-tilt-position-control`)
 * and the one button that moves to the next control (`renderNextControlButton`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MushroomCoverTiltTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val calls = ArrayList<Pair<String, JSONObject>>()

    private fun context(store: EntityStore) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(), accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += "$domain.$service" to (data ?: JSONObject()) },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun cover(state: String, features: Int, vararg attrs: Pair<String, Any?>) =
        EntityState("cover.blind", state, mapOf("friendly_name" to "Blind", "supported_features" to features) + attrs)

    private fun View.all(): List<View> = listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    private fun View.shownSliders() = all().filterIsInstance<HaHorizontalSliderView>().filter { it.visibility == View.VISIBLE }

    /** The control buttons shown, by description (Mushroom's next-control button names the control it moves to). */
    private fun View.shownSwitches() = all().filter { it.visibility == View.VISIBLE }
        .mapNotNull { it.contentDescription?.toString() }.filter { it.endsWith("_control") }

    private fun View.click(description: String) {
        val v = all().firstOrNull { it.contentDescription?.toString() == description && it.visibility == View.VISIBLE }
        assertNotNull("no visible '$description' in ${all().mapNotNull { it.contentDescription }}", v)
        assertTrue(v!!.performClick())
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private val all3 = """{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true,
        "show_position_control": true, "show_tilt_position_control": true}"""

    @Test
    fun theTiltControlRoutesNativelyNow() {
        assertTrue(NativeOptions.cardReasons("custom:mushroom-cover-card", JSONObject(all3)).isEmpty())
        assertTrue(NativeOptions.cardReasons("custom:mushroom-cover-card", JSONObject(all3).put("layout", "horizontal")).isEmpty())
        // The vertical layout still has no controls in Vitro: it falls back with any of them.
        val vertical = JSONObject(all3).put("layout", "vertical").put("show_buttons_control", false).put("show_position_control", false)
        assertTrue(NativeOptions.cardReasons("custom:mushroom-cover-card", vertical).joinToString().contains("show_tilt_position_control"))
    }

    @Test
    fun oneButtonMovesThroughTheControlsInMushroomsOrder() {
        // SET_TILT_POSITION 128 with open, close, stop and set position (15).
        val st = EntityStore().apply { putLocal(cover("open", 143, "current_position" to 40, "current_tilt_position" to 30)) }
        val card = MushroomCoverCardView(context(st), LovelaceParser.parseCard(JSONObject(all3)))
        idle()
        // The buttons first, and one button: to the position slider.
        assertTrue(card.all().any { it.contentDescription == "Open" && it.visibility == View.VISIBLE })
        assertEquals(listOf("position_control"), card.shownSwitches())
        assertEquals("mdi:gesture-swipe-horizontal", nextIcon(card))
        card.click("position_control")
        assertEquals(listOf("tilt_position_control"), card.shownSwitches())
        assertEquals("mdi:rotate-right", nextIcon(card))
        assertFalse(card.shownSliders().single().tiltStripes)
        card.click("tilt_position_control")
        val tilt = card.shownSliders().single()
        assertTrue("the tilt slider", tilt.tiltStripes)
        assertEquals(0.30f, tilt.value, 0.001f)
        // From the last control, back to the first.
        assertEquals(listOf("buttons_control"), card.shownSwitches())
        card.click("buttons_control")
        assertTrue(card.shownSliders().isEmpty())

        // A cover without SET_TILT_POSITION (15): two controls, and no way to the tilt slider.
        st.putLocal(cover("open", 15, "current_position" to 40))
        idle()
        assertEquals(listOf("position_control"), card.shownSwitches())
        card.click("position_control")
        assertEquals(listOf("buttons_control"), card.shownSwitches())

        // Tilt only (128): the tilt slider, nothing to switch to.
        st.putLocal(cover("closed", 128, "current_tilt_position" to 0))
        idle()
        assertTrue(card.shownSliders().single().tiltStripes)
        assertTrue(card.shownSwitches().isEmpty())
    }

    private fun nextIcon(card: View) = (card.all().single {
        it.visibility == View.VISIBLE && it.contentDescription?.toString()?.endsWith("_control") == true
    } as ViewGroup).getChildAt(0).let { (it as MdiIconView).icon }

    @Test
    fun theTiltSliderSetsTheTiltPositionAndTakesNoTouchWhileUnavailable() {
        val st = EntityStore().apply { putLocal(cover("open", 128, "current_tilt_position" to 30)) }
        val card = MushroomCoverCardView(context(st), LovelaceParser.parseCard(JSONObject(
            """{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_tilt_position_control": true}""",
        )))
        idle()
        val tilt = card.shownSliders().single()
        tilt.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(42, View.MeasureSpec.EXACTLY))
        tilt.layout(0, 0, 200, 42)
        drag(tilt, 150f)
        assertEquals("cover.set_cover_tilt_position", calls.last().first)
        assertEquals(75, calls.last().second.getInt("tilt_position"))
        assertEquals("cover.blind", calls.last().second.getString("entity_id"))

        st.putLocal(cover("unavailable", 128))
        idle()
        assertFalse(tilt.isEnabled)
        val before = calls.size
        drag(tilt, 50f)
        assertEquals("nothing sent", before, calls.size)
    }

    private fun drag(v: View, x: Float) {
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { a ->
            val e = MotionEvent.obtain(t, t, a, x, 21f, 0)
            v.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    @Test
    fun theTiltSliderIsStripedWithTheIndicatorAtItsValue() {
        val ctx = context(EntityStore())
        val blue = 0xFF2196F3.toInt()
        fun draw(enabled: Boolean): Bitmap {
            val s = HaHorizontalSliderView(ctx, blue) {}.apply {
                mushroom = true
                tiltStripes = true
                isEnabled = enabled
                setValue(0.5f, active = true)
            }
            val w = (240 * ctx.dp).toInt()
            val h = (42 * ctx.dp).toInt()
            s.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            s.layout(0, 0, w, h)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { s.draw(Canvas(it)) }
        }
        fun Bitmap.at(fx: Float) = getPixel((width * fx).toInt(), height / 2)
        val bmp = draw(enabled = true)
        // The indicator: a white 10 px pill at value · (W - 10 px), with the 2 px bar in the slider's color at its middle.
        val pillLeft = 0.5f * (bmp.width - 10 * ctx.dp)
        val pillMid = ((pillLeft + 5 * ctx.dp)).toInt()
        assertEquals("the bar", blue, bmp.getPixel(pillMid, bmp.height / 2) or 0xFF000000.toInt())
        assertEquals("the pill", Color.WHITE, bmp.getPixel((pillLeft + 2 * ctx.dp).toInt(), (4 * ctx.dp).toInt()))
        // Stripe 10 runs from 1 - 0.43889 to 1 - 0.41667 of the width; the gap before stripe 9 from 0.39583 to 0.41667.
        val stripe = Color.alpha(bmp.at(1f - 0.4278f))
        val gap = Color.alpha(bmp.at(1f - 0.4062f))
        assertTrue("a stripe ($stripe) is the 20 % color twice over the gap ($gap)", stripe > gap + 20)
        // No fill left of the indicator: the track there is far from the solid color.
        assertTrue("no fill", Color.alpha(bmp.at(0.30f)) < 128)
        // Unavailable (disabled): Mushroom's `.inactive` drops the stripes.
        val off = draw(enabled = false)
        assertEquals(Color.alpha(off.at(1f - 0.4278f)), Color.alpha(off.at(1f - 0.4062f)))
    }
}
