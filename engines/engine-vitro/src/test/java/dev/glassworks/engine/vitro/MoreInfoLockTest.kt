/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.VerticalTogglePillView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** More Info's lock control: one write at a time, settled by HA's answer or a deadline, and never by a stale one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoLockTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** The lock writes the sheet sent, with the callback that answers each. */
    private val writes = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()

    private val store = EntityStore().apply { putLocal(EntityState(LOCK, "locked", emptyMap())) }

    private fun sheet(): MoreInfoSheet {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            callWs = { msg, done -> writes += msg to done },
        )
        return MoreInfoSheet(ctx) {}.also { it.show(LOCK) }
    }

    private fun View.pill(): VerticalTogglePillView {
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        return all(this).filterIsInstance<VerticalTogglePillView>().single()
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test
    fun aLostConnectionKeepsTheFlipAndARefusalRollsItBack() {
        val pill = sheet().pill()
        pill.performClick()
        assertEquals("unlock", writes.last().first.getString("service"))
        assertEquals("unlocked", store[LOCK]!!.state)
        // The connection went: HA may have unlocked it. The flip stays (the reconnect's snapshot corrects it).
        writes.last().second(false, null, JSONObject().put("code", "disconnected"))
        idle()
        assertEquals("unlocked", store[LOCK]!!.state)

        // The guard is released: the next write goes out, and HA refuses it.
        pill.performClick()
        assertEquals(2, writes.size)
        assertEquals("locked", store[LOCK]!!.state)
        writes.last().second(false, null, JSONObject().put("code", "unauthorized"))
        idle()
        assertEquals("unlocked", store[LOCK]!!.state)
    }

    @Test
    fun anUnansweredWriteIsReleasedAndALateAnswerSettlesOnlyItself() {
        val pill = sheet().pill()
        pill.performClick()
        val first = writes.single()
        // A second tap while it's out sends nothing.
        pill.performClick()
        assertEquals(1, writes.size)
        // No answer at all: after the deadline the guard goes and the flip is given up.
        ShadowLooper.idleMainLooper(31, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("locked", store[LOCK]!!.state)

        pill.performClick()
        assertEquals(2, writes.size)
        assertEquals("unlocked", store[LOCK]!!.state)
        // The first write's late refusal touches nothing of the second's.
        first.second(false, null, JSONObject().put("code", "unauthorized"))
        idle()
        assertEquals("unlocked", store[LOCK]!!.state)
        pill.performClick()
        assertEquals("the second write still holds the guard", 2, writes.size)
        // Settled, so the shared guard is free for the next test.
        writes.last().second(true, null, null)
        idle()
    }

    @Test
    fun aCoverOffersOnlyWhatItSupports() {
        // Open and close only (no STOP, no SET_POSITION), though it reports a position: two buttons, no slider.
        store.putLocal(EntityState("cover.garage", "open", mapOf("supported_features" to 3, "current_position" to 100)))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val sheet = MoreInfoSheet(ctx) {}.also { it.show("cover.garage") }
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val described = all(sheet).mapNotNull { it.contentDescription?.toString() }
        assertEquals(true, "Open" in described && "Close" in described)
        assertEquals(false, "Stop" in described)
        assertEquals(false, all(sheet).any { it is dev.glassworks.engine.vitro.cards.VerticalCoverSliderView })
    }

    private companion object {
        const val LOCK = "lock.front_door"
    }
}
