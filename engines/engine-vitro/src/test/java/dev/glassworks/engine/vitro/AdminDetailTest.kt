/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaErrorCardView
import dev.glassworks.engine.vitro.cards.PlantStatusCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.ErrorCardView
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * What HA's frontend (20261028) shows an administrator and anyone else (#884): hui-error-card, the card behind HA's
 * error cards and hui-warning, titles itself only for `hass.user.is_admin` (`auth/current_user`). Everyone else sees the
 * icon alone, 16 px in, where it is for an administrator. A user HA hasn't named yet counts as no administrator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdminDetailTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    private fun ctx(user: JSONObject) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haUser = { user },
    )

    private val admin = JSONObject().put("id", "u-admin").put("name", "Admin").put("is_admin", true)
    private val member = JSONObject().put("id", "u-wall").put("name", "Wall").put("is_admin", false)

    /** The users that see the icon alone: not an administrator, and not known yet (`auth/current_user` unanswered). */
    private val notAdmins = listOf(member, JSONObject(), JSONObject().put("id", "u-x").put("name", "No flag"))

    private fun card(json: String): CardConfig = LovelaceParser.parseCard(JSONObject(json))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun View.shownIn(root: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent as? View
        }
        return true
    }

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.shownIn(v) && it.text.isNotEmpty() }.map { it.text.toString() }
    private fun icons(v: View) = all(v).filterIsInstance<MdiIconView>().filter { it.shownIn(v) }
    private fun px(dp: Float) = (dp * activity.resources.displayMetrics.density).roundToInt()

    private fun layout(v: View, wDp: Int = 300) {
        v.measure(View.MeasureSpec.makeMeasureSpec(px(wDp.toFloat()), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    /** The one icon of a hui-error-card: [icon], 16 px in from the card's start. */
    private fun assertIconAtStart(v: View, icon: String) {
        layout(v)
        val shown = icons(v).single()
        assertEquals(icon, shown.icon)
        var left = shown.left
        var p = shown.parent as View
        while (p !== v) { left += p.left; p = p.parent as View }
        assertEquals("the icon starts 16 px in", px(16f), left)
    }

    /** HA marks [ids] missing: the subscription for them answered without them. */
    private fun markMissing(vararg ids: String) {
        val owner = Any()
        store.resetSnapshot(owner)
        store.markSnapshot(ids.toSet(), owner)
    }

    // ---- hui-warning: a tile whose entity HA doesn't have ----

    @Test fun anAdministratorSeesTheTilesMissingEntityWarningTitled() {
        markMissing("text.night_window")
        val tile = TileCardView(ctx(admin), card("""{"type": "tile", "entity": "text.night_window"}"""))
        idle()
        assertNotNull(tile.missingWarning)
        assertEquals(listOf("Entity not found"), texts(tile))
        assertIconAtStart(tile, "mdi:alert-outline")
        assertEquals("Entity not found", tile.missingWarning!!.contentDescription)
    }

    @Test fun anyoneElseSeesTheTilesMissingEntityIconAlone() {
        for (user in notAdmins) {
            markMissing("text.night_window")
            val tile = TileCardView(ctx(user), card("""{"type": "tile", "entity": "text.night_window"}"""))
            idle()
            assertNotNull(tile.missingWarning)
            assertTrue("no text for $user: ${texts(tile)}", texts(tile).isEmpty())
            assertIconAtStart(tile, "mdi:alert-outline")
            // TalkBack reads the card's generic name, not the detail HA keeps for administrators.
            assertEquals(ErrorCardView.WARNING_TITLE, tile.missingWarning!!.contentDescription)
        }
    }

    @Test fun theWarningWashesTheTileWithTheWarningColor() {
        markMissing("text.night_window")
        val tile = TileCardView(ctx(member), card("""{"type": "tile", "entity": "text.night_window"}"""))
        idle()
        layout(tile)
        val wash = (tile.missingWarning!!.background as GradientDrawable).color!!.defaultColor
        // HA's --warning-color (#ffa600) at 12% over the card's background, opaque: it covers the tile's border, as
        // hui-error-card has none.
        val bg = ctx(member).theme.cardBackgroundColor
        fun mix(u: Int, o: Int) = (u * 0.88f + o * 0.12f).roundToInt()
        assertEquals(android.graphics.Color.argb(android.graphics.Color.alpha(bg), mix(android.graphics.Color.red(bg), 0xFF),
            mix(android.graphics.Color.green(bg), 0xA6), mix(android.graphics.Color.blue(bg), 0x00)), wash)
        assertEquals(0, tile.missingWarning!!.left)
        assertEquals(tile.width, tile.missingWarning!!.width)
        // The entity arriving brings the tile back.
        store.putLocal(EntityState("text.night_window", "22:00", mapOf("friendly_name" to "Night window")))
        idle()
        assertEquals(View.GONE, tile.missingWarning!!.visibility)
    }

    @Test fun theWashIsCompositedOnceOverATranslucentCard() {
        val orange = 0xFFFFA600.toInt()
        // Opaque: the background with 12% of the color, opaque.
        assertEquals(android.graphics.Color.argb(255, 255, 187, 30), dev.glassworks.engine.vitro.ui.washOver(0xFFFFBE22.toInt(), orange))
        // Transparent (a theme's `ha-card-background: transparent`): the color itself at 12%, as HA's ::after draws it.
        assertEquals(android.graphics.Color.argb(31, 255, 166, 0), dev.glassworks.engine.vitro.ui.washOver(android.graphics.Color.TRANSPARENT, orange))
        // Half transparent black: alpha 0.12 + 0.5 * 0.88 = 0.56, color (0.12 * c) / 0.56.
        val half = dev.glassworks.engine.vitro.ui.washOver(android.graphics.Color.argb(128, 0, 0, 0), orange)
        assertEquals(143, android.graphics.Color.alpha(half))
        assertEquals((255 * 0.12f / (0.12f + 128 / 255f * 0.88f)).roundToInt(), android.graphics.Color.red(half))
    }

    // ---- hui-warning: a plant card whose entity HA doesn't have ----

    @Test fun thePlantCardsMissingEntityIsHasWarningTitledForAnAdministratorOnly() {
        val forAdmin = PlantStatusCardView(ctx(admin), card("""{"type": "plant-status", "entity": "plant.gone"}"""))
        idle()
        assertEquals(listOf("Entity not found"), texts(forAdmin))
        assertIconAtStart(forAdmin, "mdi:alert-outline")
        for (user in notAdmins) {
            val v = PlantStatusCardView(ctx(user), card("""{"type": "plant-status", "entity": "plant.gone"}"""))
            idle()
            assertTrue("no text for $user: ${texts(v)}", texts(v).isEmpty())
            assertIconAtStart(v, "mdi:alert-outline")
        }
    }

    @Test fun thePlantCardsWarningGoesWhenThePlantArrives() {
        val v = PlantStatusCardView(ctx(admin), card("""{"type": "plant-status", "entity": "plant.gone"}"""))
        idle()
        assertEquals(listOf("Entity not found"), texts(v))
        store.putLocal(EntityState("plant.gone", "ok", mapOf("friendly_name" to "Fern", "moisture" to 40, "problem" to "none")))
        idle()
        assertTrue("the warning is gone: ${texts(v)}", "Entity not found" !in texts(v))
        assertTrue(texts(v).contains("Fern"))
        assertTrue("no warning icon left", icons(v).none { it.icon == "mdi:alert-outline" })
    }

    // ---- hui-error-card: a config HA's card rejects, and a card that fails ----

    @Test fun aRejectedConfigIsTitledConfigurationErrorForAnAdministratorOnly() {
        val err = HaErrorCardView(ctx(admin), "Entity must be specified")
        // HA's title, not the message: hui-card passes the message, which only the editor's preview shows.
        assertEquals(listOf(ErrorCardView.TITLE), texts(err))
        assertIconAtStart(err, "mdi:alert-circle-outline")
        assertEquals("Entity must be specified", err.message)
        for (user in notAdmins) {
            val v = HaErrorCardView(ctx(user), "Entity must be specified")
            assertTrue("no text for $user", texts(v).isEmpty())
            assertIconAtStart(v, "mdi:alert-circle-outline")
            assertEquals(ErrorCardView.TITLE, all(v).firstNotNullOfOrNull { it.contentDescription }?.toString())
        }
    }

    @Test fun aCardThatFailedIsTitledForAnAdministratorOnly() {
        val forAdmin = ErrorCardView(ctx(admin), card("""{"type": "tile"}"""))
        assertEquals(listOf(ErrorCardView.TITLE), texts(forAdmin))
        assertIconAtStart(forAdmin, "mdi:alert-circle-outline")
        for (user in notAdmins) {
            val v = ErrorCardView(ctx(user), card("""{"type": "tile"}"""))
            assertTrue("no text for $user", texts(v).isEmpty())
            assertIconAtStart(v, "mdi:alert-circle-outline")
        }
    }
}
