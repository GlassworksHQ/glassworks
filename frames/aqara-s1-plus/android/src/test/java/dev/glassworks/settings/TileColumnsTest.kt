package dev.glassworks.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.ui.Mockup
import dev.glassworks.ui.TileView
import org.junit.Assert.assertEquals
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

    /** How many tiles share the first row of a three-to-a-row page on a [w] by [h] px screen. */
    private fun firstRow(w: Int, h: Int): Int {
        val tiles = SettingsItem.Tiles(3) { (1..5).map { SettingsItem.Tile("RAM", "654", "MiB free", "App 214 MiB", history = listOf(1f, 2f)) } }
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(KioskSettingsOverlay.Model(listOf(SettingsSection("diagnostics", "Diagnostics", listOf(tiles))), "1.0.0", setOf("diagnostics")))
        o.previewSection("diagnostics")
        Mockup.idle()
        Mockup.layout(o, w, h)
        val top = views(o).filterIsInstance<TileView>().map { at(it) }.toList()
        assertEquals(5, top.size)
        return top.count { it == top.first() }
    }

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
}
