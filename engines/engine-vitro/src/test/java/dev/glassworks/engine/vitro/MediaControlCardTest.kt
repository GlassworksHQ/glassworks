/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.ArtworkColors
import dev.glassworks.engine.vitro.cards.MediaControlCardView
import dev.glassworks.engine.vitro.cards.MediaControlCardView.Control
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HA 2026's media control card: its controls, its text, and the artwork colors (node-vibrant's MMCQ + HA's pick). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaControlCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    private fun player(state: String, features: Int, attrs: Map<String, Any?> = emptyMap()) =
        EntityState("media_player.lounge", state, mapOf("friendly_name" to "Lounge", "supported_features" to features) + attrs)

    private fun actions(s: EntityState) =
        MediaControlCardView.removeSecondaryStop(s, MediaControlCardView.mediaControls(s))?.map(Control::action)

    @Test
    fun theControlsFollowHasRules() {
        val all = MediaControlCardView.PAUSE or MediaControlCardView.PREVIOUS_TRACK or MediaControlCardView.NEXT_TRACK or
            MediaControlCardView.STOP or MediaControlCardView.PLAY
        assertEquals(listOf("media_play"), actions(player("idle", all)))
        // Stop goes while pause is there; previous and next only while playing or paused.
        assertEquals(listOf("media_previous_track", "media_pause", "media_next_track"), actions(player("playing", all)))
        assertEquals(listOf("media_previous_track", "media_play", "media_next_track"), actions(player("paused", all)))
        assertEquals(listOf("media_previous_track", "media_stop", "media_next_track"),
            actions(player("playing", all and MediaControlCardView.PAUSE.inv())))
        // `on`: one play-pause button.
        assertEquals(listOf("media_play"), actions(player("on", all)))
        assertEquals("mdi:play-pause", MediaControlCardView.mediaControls(player("on", all))!!.single().icon)
        // Off: the power button, or nothing without turn_on; unavailable: nothing.
        assertEquals(listOf("turn_on"), actions(player("off", all or MediaControlCardView.TURN_ON)))
        assertNull(actions(player("off", all)))
        assertNull(actions(player("unavailable", all or MediaControlCardView.TURN_ON)))
        // turn_off comes first while on.
        assertEquals("turn_off", actions(player("playing", all or MediaControlCardView.TURN_OFF))!!.first())
    }

    @Test
    fun theTitleAndDescriptionFollowHa() {
        val music = player("playing", 0, mapOf("media_content_type" to "music", "media_title" to "Song", "media_artist" to "Band"))
        assertEquals("Band", MediaControlCardView.mediaDescription(music))
        val tv = player("playing", 0, mapOf("media_content_type" to "tvshow", "media_series_title" to "Show", "media_season" to 2, "media_episode" to 5))
        assertEquals("Show S2E5", MediaControlCardView.mediaDescription(tv))
        assertEquals("App", MediaControlCardView.mediaDescription(player("playing", 0, mapOf("app_name" to "App"))))
        assertEquals("a song.mp3", MediaControlCardView.cleanupMediaTitle("https://example.com/files/a%20song.mp3?authSig=x"))
        assertNull(MediaControlCardView.cleanupMediaTitle(""))
    }

    /** node-vibrant 4.0.4's MMCQ on the same picture (tools: its `MMCQ` with `colorCount: 16`), in its order. */
    @Test
    fun mmcqMatchesNodeVibrant() {
        val w = 40
        val h = 30
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var r = 18; var g = 20; var b = 24; var a = 255
            if (x >= 24 && y >= 6 && y < 22) { r = 200; g = 120 + (x - 24) * 3; b = 40 }
            if (y >= 24) { r = 30 + x * 5; g = 60 + x * 3; b = 140 }
            if (x < 3 && y < 3) { r = 255; g = 255; b = 255 }
            if (x == 10 && y < 10) { a = 60; r = 0; g = 255; b = 0 }
            Color.argb(a, r, g, b)
        }
        val swatches = ArtworkColors.quantize(px).map { it.rgb.toList() to it.population }
        assertEquals(listOf(
            listOf(204, 147, 44) to 208, listOf(65, 81, 140) to 90, listOf(204, 124, 44) to 48, listOf(116, 111, 140) to 30,
            listOf(20, 20, 28) to 685, listOf(185, 153, 140) to 102, listOf(134, 124, 140) to 18,
        ), swatches)
        assertEquals(Pair(Color.rgb(20, 20, 28), Color.rgb(204, 147, 44)), ArtworkColors.fromPixels(px))
    }

    /**
     * HA's pick on the swatches Chrome's canvas gave for a real album cover (HA drew #0c0c0c behind #7c7c7c): the
     * second color fails the 4.5 contrast, so a similar one that passes is taken instead.
     */
    @Test
    fun theForegroundFallsBackToASimilarColorWithContrast() {
        fun sw(v: Int, n: Int) = ArtworkColors.Swatch(intArrayOf(v, v, v), n)
        val swatches = listOf(sw(12, 11580), sw(98, 956), sw(167, 856), sw(20, 596), sw(47, 356), sw(28, 350), sw(36, 221),
            sw(60, 128), sw(68, 118), sw(116, 108), sw(124, 107))
        assertEquals(Pair(Color.rgb(12, 12, 12), Color.rgb(124, 124, 124)), ArtworkColors.pick(swatches))
        // Nothing with contrast: white on a dark background, black on a light one (YIQ 200).
        assertEquals(Color.WHITE, ArtworkColors.pick(listOf(sw(40, 10), sw(50, 5))).second)
        assertEquals(Color.BLACK, ArtworkColors.pick(listOf(sw(230, 10), sw(220, 5))).second)
    }

    @Test
    fun aFifthTheSizeThroughTwoHalvingsAndBilinear() {
        val (px, w, h) = ArtworkColors.downscale(IntArray(640 * 480) { Color.rgb(10, 20, 30) }, 640, 480)
        assertEquals(128 to 96, w to h)
        assertEquals(Color.rgb(10, 20, 30), px.toSet().single())
    }

    private val calls = ArrayList<String>()

    /** The progress bar (HA's ha-slider) and the share of it filled. */
    private fun progressOf(card: View): View = all(card).single { it.javaClass.simpleName == "MediaProgressBar" }
    private fun fractionOf(bar: View): Float = bar.javaClass.getDeclaredMethod("getFraction").apply { isAccessible = true }.invoke(bar) as Float

    private fun card(s: EntityState?): MediaControlCardView {
        s?.let(store::putLocal)
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { d, sv, data, _ -> calls += "$d.$sv ${data ?: ""}" }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return MediaControlCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "media-control", "entity": "media_player.lounge"}""")))
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun shown(root: View, v: View): Boolean {
        var c: View? = v
        while (c != null && c !== root) { if (c.visibility != View.VISIBLE) return false; c = c.parent as? View }
        return true
    }
    private fun texts(card: View) = all(card).filterIsInstance<TextView>().filter { shown(card, it) && it.text.isNotEmpty() }.map { it.text.toString() }
    private fun buttons(card: View) = all(card).filter { shown(card, it) && it.isClickable && it.contentDescription != null }.map { it.contentDescription.toString() }

    @Test
    fun anIdleSonosShowsPlayBrowseAndJoin() {
        // The corpus's speaker: idle, every feature but power.
        val c = card(player("idle", 8321599, mapOf("media_content_type" to "music")))
        assertEquals(listOf("Lounge"), texts(c))
        assertEquals(listOf("More info", "Play", "Browse media", "Join"), buttons(c))
    }

    @Test
    fun aPausedTrackShowsItsTitleArtistAndProgress() {
        val c = card(player("paused", 8321599, mapOf(
            "media_content_type" to "music", "media_title" to "Song", "media_artist" to "Band",
            "media_duration" to 200, "media_position" to 50,
        )))
        c.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(listOf("Lounge", "Song", "Band"), texts(c))
        assertEquals(listOf("More info", "Previous track", "Play", "Next track", "Browse media", "Join"), buttons(c))
        // 50 s of 200: a quarter of the bar.
        val bar = progressOf(c)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(0.25f, fractionOf(bar), 0.001f)
    }

    @Test
    fun theProgressBarSeeksOnAPlayerThatCan() {
        val c = card(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 10)))
        c.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        val bar = progressOf(c)
        val mid = bar.width / 2f
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, mid, 1f, 0))
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, 10L, android.view.MotionEvent.ACTION_UP, mid, 1f, 0))
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("media_player.media_seek"))
        assertTrue(calls.single(), calls.single().contains("\"seek_position\":60"))
        // Without SEEK the bar only shows the position.
        calls.clear()
        val noSeek = card(player("paused", 8321599 and 2.inv(), mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 10)))
        noSeek.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        noSeek.layout(0, 0, noSeek.measuredWidth, noSeek.measuredHeight)
        val still = progressOf(noSeek)
        still.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, still.width / 2f, 1f, 0))
        still.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, 10L, android.view.MotionEvent.ACTION_UP, still.width / 2f, 1f, 0))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theSeekBarIsASliderForTalkBackAndKeys() {
        // HA's ha-slider: 0 to media_duration in seconds, step 1; a change seeks.
        val c = card(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 10)))
        c.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        val bar = progressOf(c)
        assertTrue(bar.isFocusable)
        val info = bar.createAccessibilityNodeInfo()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(120f, info.rangeInfo.max, 0f)
        assertEquals(10f, info.rangeInfo.current, 0f)
        // TalkBack: a tenth (12 s), sent at once.
        assertTrue(bar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertTrue(calls.toString(), calls.single().contains("\"seek_position\":22"))
        calls.clear()
        val args = android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 90f) }
        assertTrue(bar.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertTrue(calls.toString(), calls.single().contains("\"seek_position\":90"))
        calls.clear()
        // A key: shown on the press, sent when it comes up, and HA's position meanwhile doesn't move it back.
        assertTrue(bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(calls.isEmpty())
        store.putLocal(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 30)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(91f / 120f, fractionOf(bar), 0.001f)
        bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(calls.toString(), calls.single().contains("\"seek_position\":91"))
        // Then HA's position shows again.
        store.putLocal(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 60)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0.5f, fractionOf(bar), 0.001f)
        // Without SEEK: no slider, nothing to move.
        val noSeek = card(player("paused", 8321599 and 2.inv(), mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 10)))
        val still = progressOf(noSeek)
        assertFalse(still.isFocusable)
        assertFalse(still.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
    }

    @Test
    fun aKeyEditKeepsItsPositionWhenTheDurationChanges() {
        val c = card(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 120, "media_position" to 10)))
        c.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        val bar = progressOf(c)
        assertTrue(bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
        // HA reports a longer track while the key is held: the edit still means second 11, drawn on the new length.
        store.putLocal(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 240, "media_position" to 10)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(11f / 240f, fractionOf(bar), 0.001f)
        bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(calls.toString(), calls.single().contains("\"seek_position\":11"))
        // A shorter one: the edit is clamped to its end.
        calls.clear()
        assertTrue(bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_END)))
        store.putLocal(player("paused", 8321599, mapOf("media_title" to "Song", "media_duration" to 100, "media_position" to 10)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // TalkBack reads the clamped value meanwhile, never one past the new end.
        val held = bar.createAccessibilityNodeInfo().rangeInfo
        assertEquals(100f, held.max, 0f)
        assertEquals(100f, held.current, 0f)
        bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MOVE_END))
        assertTrue(calls.toString(), calls.single().contains("\"seek_position\":100"))
    }

    @Test
    fun anUnavailablePlayerShowsOnlyItsName() {
        val c = card(player("unavailable", 8321599))
        assertEquals(listOf("Lounge"), texts(c))
        assertEquals(listOf("More info"), buttons(c))
    }
}
