package dev.glassworks.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.ui.Mockup
import dev.glassworks.ui.TileView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pane page's tiles: as many to a row as the pane asks where they fit (the S1, a tablet either way), fewer where
 * they would clip. In portrait at 1080x1920, the lululemon Mirror's, three made "654 MiB free" read "654".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TileColumnsTest {

    /** A three-to-a-row page of five tiles, open. */
    private fun page(): KioskSettingsOverlay {
        val tiles = SettingsItem.Tiles(3) { (1..5).map { SettingsItem.Tile("RAM", "654", "MiB free", "App 214 MiB", history = listOf(1f, 2f)) } }
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(KioskSettingsOverlay.Model(listOf(SettingsSection("diagnostics", "Diagnostics", listOf(tiles))), "1.0.0", setOf("diagnostics")))
        o.previewSection("diagnostics")
        Mockup.idle()
        return o
    }

    /** How many tiles share the first row of [o] laid out at [w] by [h] px; every tile is at least [TILE_MIN_DP] wide. */
    private fun firstRow(o: KioskSettingsOverlay, w: Int, h: Int): Int {
        Mockup.layout(o, w, h)
        val tiles = views(o).filterIsInstance<TileView>().toList()
        assertEquals(5, tiles.size)
        val min = KioskSettingsOverlay.TILE_MIN_DP * o.resources.displayMetrics.density
        tiles.forEach { assertTrue("a tile ${it.width} px wide, under $min", it.width >= min) }
        val top = tiles.map { at(it) }
        return top.count { it == top.first() }
    }

    private fun firstRow(w: Int, h: Int): Int = firstRow(page(), w, h)

    private fun at(v: View): Int = IntArray(2).also(v::getLocationInWindow)[1]

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    @Test @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun theS1KeepsThree() = assertEquals(3, firstRow(1440, 720))

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun aTabletOnItsSideKeepsThree() = assertEquals(3, firstRow(1280, 800))

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun aTabletStoodUpKeepsThree() = assertEquals(3, firstRow(800, 1280))

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun theMirrorInPortraitHasOne() = assertEquals(1, firstRow(1080, 1920))

    /** A 450 dp page: three tiles of 150 dp fit, the two 12 dp gaps between them don't. */
    @Test @Config(qualifiers = "w722dp-h1000dp-port-mdpi")
    fun theGapsCountToo() = assertEquals(2, firstRow(722, 1000))

    /** A turn of the screen that keeps the activity (the generic app handles orientation itself) re-flows the tiles. */
    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun aTurnReflowsThem() {
        val o = page()
        assertEquals(1, firstRow(o, 1080, 1920))
        assertEquals(3, firstRow(o, 1920, 1080))
    }
}
