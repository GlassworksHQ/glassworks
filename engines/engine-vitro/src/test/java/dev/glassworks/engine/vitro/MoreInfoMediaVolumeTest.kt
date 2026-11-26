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
import android.widget.SeekBar
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** More Info's media volume: a held key is one `volume_set` when it comes up, as HA's slider sends on `keyup`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoMediaVolumeTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val levels = ArrayList<Double>()
    private val store = EntityStore().apply {
        putLocal(EntityState(PLAYER, "playing", mapOf("media_title" to "Song", "volume_level" to 0.4, "supported_features" to 4)))
    }

    private fun volume(): SeekBar {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN },
            callService = { _, service, data, _ -> if (service == "volume_set") levels += (data as JSONObject).getDouble("volume_level") },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val sheet = MoreInfoSheet(ctx) {}.also { it.show(PLAYER) }
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        return all(sheet).filterIsInstance<SeekBar>().single()
    }

    @Test
    fun aHeldArrowSetsTheVolumeOnceWhenItComesUp() {
        val bar = volume()
        assertEquals(40, bar.progress)
        val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        assertTrue(bar.dispatchKeyEvent(down))
        bar.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 50L, 1))
        bar.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 100L, 2))
        assertTrue("nothing while the key is held: $levels", levels.isEmpty())
        val moved = bar.progress
        assertTrue(moved > 40)
        bar.dispatchKeyEvent(KeyEvent(0L, 150L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertEquals(listOf(moved / 100.0), levels)
        // TalkBack's step still goes at once.
        levels.clear()
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(1, levels.size)
    }

    @Test
    fun aCancelledKeyEditShowsHasLatestVolume() {
        val bar = volume()
        val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        assertTrue(bar.dispatchKeyEvent(down))
        val edit = bar.progress
        // HA's volume changes while the key is held (another remote): the bar keeps the edit meanwhile.
        store.putLocal(EntityState(PLAYER, "playing", mapOf("media_title" to "Song", "volume_level" to 0.6, "supported_features" to 4)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(edit, bar.progress)
        // The key is cancelled: nothing is sent, and the bar shows HA's volume now, not the one before the edit.
        bar.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(0L, 50L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0), KeyEvent.FLAG_CANCELED))
        assertTrue(levels.toString(), levels.isEmpty())
        assertEquals(60, bar.progress)
    }

    private companion object {
        const val PLAYER = "media_player.lounge"
    }
}
