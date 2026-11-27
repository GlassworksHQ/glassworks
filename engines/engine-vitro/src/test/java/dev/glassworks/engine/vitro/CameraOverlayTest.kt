/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.glassworks.engine.vitro.camera.CameraOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The view's one SurfaceView camera: its largest on screen, chosen once, and never one a layer would hide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w960dp-h480dp-mdpi")
class CameraOverlayTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After
    fun reset() = CameraOverlay.reset()

    /** A dashboard view: a ScrollView over a column of camera hosts, [sizes] in pixels. */
    private fun view(vararg sizes: Pair<Int, Int>): Pair<ScrollView, List<FrameLayout>> {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val hosts = sizes.map { (w, h) -> FrameLayout(activity).also { column.addView(it, LinearLayout.LayoutParams(w, h)) } }
        val scroll = ScrollView(activity).apply { addView(column) }
        activity.setContentView(scroll)
        layout(scroll)
        return scroll to hosts
    }

    private fun layout(v: View) {
        val root = v.rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 480)
    }

    /** Each host's owner: an object of its own, as a camera's feed is (the choice compares owners by identity). */
    private fun owners(hosts: List<View>) = hosts.mapIndexed { i, h -> Any() to h }

    @Test
    fun `the largest camera on screen is the view's overlay, once, whatever changes afterwards`() {
        val (_, hosts) = view(300 to 100, 600 to 300, 400 to 200)
        val cams = owners(hosts)
        // The third is partly below the fold: 80 px of its 200 show, smaller than the second's 300.
        assertEquals(listOf(false, true, false), cams.map { (o, h) -> CameraOverlay.choose(o, h) { cams } })
        // The first grows past it: the choice stays, so no camera's player opens again for it.
        hosts[0].layoutParams = LinearLayout.LayoutParams(900, 400)
        layout(hosts[0])
        assertEquals(true, CameraOverlay.choose(cams[1].first, hosts[1]) { cams })
        assertEquals(false, CameraOverlay.choose(cams[0].first, hosts[0]) { cams })
    }

    @Test
    fun `a camera not laid out yet waits, and one drawn through a layer or a fade is never chosen`() {
        val unattached = FrameLayout(activity)
        assertNull(CameraOverlay.choose("loose", unattached) { emptyList() })
        val (_, hosts) = view(600 to 300, 300 to 100)
        // HA's state_filter draws the stream host through a layer: a SurfaceView's hole wouldn't open there.
        hosts[0].setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val cams = owners(hosts)
        assertEquals(true, CameraOverlay.choose(cams[1].first, hosts[1]) { cams })
        CameraOverlay.reset()
        hosts[0].setLayerType(View.LAYER_TYPE_NONE, null)
        hosts[0].alpha = 0.5f
        assertEquals(true, CameraOverlay.choose(cams[1].first, hosts[1]) { cams })
    }

    @Test
    fun `the chosen camera going lets the view choose again, and off switches it all off`() {
        val (_, hosts) = view(600 to 300, 300 to 100)
        val cams = owners(hosts)
        assertEquals(true, CameraOverlay.choose(cams[0].first, hosts[0]) { cams })
        CameraOverlay.forget(cams[0].first)
        val left = cams.drop(1)
        assertEquals(true, CameraOverlay.choose(cams[1].first, hosts[1]) { left })
        CameraOverlay.enabled = false
        assertFalse(CameraOverlay.choose(cams[1].first, hosts[1]) { left }!!)
    }

    @Test
    fun `with no camera on screen nothing is kept, so a later one can be chosen`() {
        val (scroll, hosts) = view(600 to 300)
        hosts[0].visibility = View.INVISIBLE
        val cams = owners(hosts)
        assertEquals(false, CameraOverlay.choose(cams[0].first, hosts[0]) { cams })
        hosts[0].visibility = View.VISIBLE
        layout(scroll)
        assertTrue(CameraOverlay.choose(cams[0].first, hosts[0]) { cams }!!)
    }
}
