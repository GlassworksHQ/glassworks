/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.PictureElementsCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HA's picture-elements overlays: a bare 40 px state-badge for `state-icon`, a padded 14 px `state-label`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PictureElementsLookTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @Test
    fun stateIconAndStateLabelHaveNothingBehindThem() {
        val store = EntityStore()
        store.putLocal(EntityState("light.a", "off", mapOf("friendly_name" to "A")))
        store.putLocal(EntityState("sensor.ink", "74", mapOf("unit_of_measurement" to "%")))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val card = PictureElementsCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "picture-elements", "image": "/x.png", "elements": [
            {"type": "state-icon", "entity": "light.a", "style": {"top": "30%", "left": "30%"}},
            {"type": "state-label", "entity": "sensor.ink", "style": {"top": "70%", "left": "70%"}}]}""")))
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val dp = activity.resources.displayMetrics.density
        val icon = all(card).filterIsInstance<MdiIconView>().single()
        val badge = icon.parent as View
        assertNull(badge.background)
        assertEquals((40 * dp).toInt(), badge.layoutParams.width)
        assertEquals((24 * dp).toInt(), icon.layoutParams.width)
        val label = all(card).filterIsInstance<TextView>().single { it.text.toString() == "74%" }
        assertNull(label.background)
        assertEquals(14f, label.textSize / dp, 0.5f)
        assertEquals((8 * dp).toInt(), label.paddingLeft)
    }
}
