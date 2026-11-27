/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.LocalRelayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * A live value that changes width (a 5 Hz power sensor) lays out its own row, not the dashboard ([LocalRelayout]), and
 * the row it leaves is laid out exactly as a full pass would lay it out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class LiveUpdateLayoutTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After fun forget() = HaDisplay.reset()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { NOW },
    )

    /** The window's root: counts the layout requests that reach it. */
    private class Root(context: Context) : FrameLayout(context) {
        var requests = 0
        override fun requestLayout() {
            requests++
            super.requestLayout()
        }
    }

    private fun power(value: String) = EntityState(
        entityId = "sensor.power", state = value,
        attributes = mapOf("friendly_name" to "Desk power", "unit_of_measurement" to "W", "device_class" to "power"),
        lastChanged = NOW / 1000.0 - 60, lastUpdated = NOW / 1000.0 - 60,
    )

    private fun other() = EntityState(
        entityId = "sensor.other", state = "21.5",
        attributes = mapOf("friendly_name" to "Hall temperature", "unit_of_measurement" to "°C"),
        lastChanged = NOW / 1000.0 - 60, lastUpdated = NOW / 1000.0 - 60,
    )

    private fun card() = EntitiesCardView(ctx(), LovelaceParser.parseCard(
        JSONObject("""{"type": "entities", "title": "Power", "entities": ["sensor.power", "sensor.other"]}"""),
    ))

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun layOut(v: View, w: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    /** Every view's box under [v], in tree order. */
    private fun boxes(v: View, out: MutableList<String> = ArrayList(), path: String = "0"): List<String> {
        out += "$path ${v.javaClass.simpleName} ${v.left},${v.top},${v.right},${v.bottom} ${v.visibility}"
        if (v is ViewGroup) for (i in 0 until v.childCount) boxes(v.getChildAt(i), out, "$path.$i")
        return out
    }

    private fun pixels(v: View): IntArray {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    private fun texts(v: View): List<String> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull((v as? TextView)?.text?.toString())

    @Test
    fun aWiderValueLaysOutItsRowAsAFullPassWould() {
        store.putLocal(power("5"))
        store.putLocal(other())
        val root = Root(activity)
        activity.setContentView(root)
        val card = card()
        root.addView(card, FrameLayout.LayoutParams(ViewGroupWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        assertTrue(card.isLaidOut)
        val before = boxes(card)
        root.requests = 0

        // The value widens: "5 W" to "12,345.7 W". The row's name gives it the room, the row's height stays.
        store.putLocal(power("12345.678"))
        idle()
        assertTrue(texts(card).toString(), texts(card).any { "12" in it && "W" in it })
        assertEquals("the request stopped at the row", 0, root.requests)
        assertFalse(root.isLayoutRequested)
        val after = boxes(card)
        assertNotEquals("the value moved", before, after)

        // A card built with that value and laid out whole: the same boxes, the same pixels.
        val fresh = card()
        val other = Root(activity)
        root.addView(other, FrameLayout.LayoutParams(ViewGroupWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        other.addView(fresh, FrameLayout.LayoutParams(ViewGroupWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        layOut(fresh, card.width)
        assertEquals(boxes(fresh), after)
        assertTrue("the same pixels", pixels(fresh).contentEquals(pixels(card)))
    }

    /** A host that keeps its content's layout requests while [LocalRelayout.run] runs. */
    private class Host(context: Context) : FrameLayout(context) {
        private var local: LocalRelayout? = LocalRelayout(this)
        val relayout: LocalRelayout get() = local!!
        override fun requestLayout() {
            if (local?.take() != true) super.requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            local?.onMeasure(widthMeasureSpec, heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    @Test
    fun aHostThatGrowsAsksTheWindowAfterAll() {
        val root = Root(activity)
        activity.setContentView(root)
        val host = Host(activity)
        val text = TextView(activity).apply { text = "one line" }
        host.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        root.requests = 0

        // Wider only: kept in the host, and laid out there.
        host.relayout.run { text.text = "one longer line" }
        assertEquals(0, root.requests)
        assertEquals(text.measuredWidth, text.width)
        assertFalse(host.isLayoutRequested)

        // Taller: the host's height changes, so its parent has to lay it out.
        host.relayout.run { text.text = "three\nlines\nnow" }
        assertEquals(1, root.requests)
        assertTrue(root.isLayoutRequested)
        idle()

        // Outside run, a request goes up as always.
        root.requests = 0
        text.text = "back to one"
        assertEquals(1, root.requests)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ViewGroupWidth = 480
    }
}
