/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.FrontendLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A language or format change builds the view again in place: it stays where it was scrolled, as HA's page does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocaleRebuildScrollTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun drawAll(v: View) {
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1440, 720)
        val bmp = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        bmp.recycle()
        ShadowLooper.idleMainLooper()
    }

    /** A card whose height comes later, as an island's does once its page reports it. */
    private class LateCard(context: android.content.Context, var heightPx: Int) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightPx)
    }

    @Test
    fun theScrollPositionWaitsForCardsThatGrowLater() {
        val store = EntityStore()
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
            )
        }
        val built = ArrayList<LateCard>()
        // The first build's card is tall at once; the rebuilt one starts short and grows, as an island's page does.
        vv.testCards = { card, ctx -> if (card.type == "custom:late") LateCard(ctx.context, if (built.isEmpty()) 3000 else 100).also { built += it } else null }
        val cards = JSONArray().put(JSONObject().put("type", "custom:late"))
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", JSONArray().put(JSONObject().put("title", "Home").put("cards", cards)))), null)
        activity.setContentView(vv)
        drawAll(vv)
        vv.currentScroll()!!.scrollTo(0, 600)
        assertEquals(600, vv.currentScroll()!!.scrollY)

        vv.setLocale(FrontendLocale(language = "de"), "UTC", HaText.version)
        drawAll(vv)
        assertEquals("the rebuilt card is short for now", 2, built.size)
        // Its height arrives: the view scrolls on to where it was.
        built.last().heightPx = 3000
        built.last().requestLayout()
        drawAll(vv)
        assertEquals(600, vv.currentScroll()!!.scrollY)
        // Restored: a later change of height leaves the scroll alone.
        vv.currentScroll()!!.scrollTo(0, 100)
        built.last().heightPx = 3200
        built.last().requestLayout()
        drawAll(vv)
        assertEquals(100, vv.currentScroll()!!.scrollY)
    }

    @Test
    fun aLocaleChangeKeepsTheScrollPosition() {
        val store = EntityStore()
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
            )
        }
        val cards = JSONArray()
        for (i in 1..40) cards.put(JSONObject().put("type", "markdown").put("content", "Card $i\n\nA line\n\nAnother line"))
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", JSONArray().put(JSONObject().put("title", "Home").put("cards", cards)))), null)
        activity.setContentView(vv)
        drawAll(vv)
        val before = vv.currentScroll()!!
        before.scrollTo(0, 600)
        assertEquals("the view is tall enough to scroll", 600, before.scrollY)

        vv.setLocale(FrontendLocale(language = "de"), "UTC", HaText.version)
        drawAll(vv)
        val after = vv.currentScroll()!!
        assertTrue(vv.localeRebuilds >= 1)
        assertNotSame("the view was built again", before, after)
        assertEquals(600, after.scrollY)
    }
}
