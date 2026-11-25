/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.glassworks.engine.vitro.cards.MdSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pictures decoded or drawn to real pixels: Markdown SVGs (group opacity, dashes, arc flags) and camera frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownSvgDrawTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun draw(svg: String): Bitmap {
        val parsed = MdSvg.parse(svg)
        assertNotNull(parsed)
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        parsed!!.draw(canvas, 100f, 100f, activity)
        return bmp
    }

    @Test
    fun aGroupsOpacityAppliesToTheGroupAsAWhole() {
        // Two overlapping black squares in a half-opaque group: one 50% grey where they overlap, not a darker one.
        val bmp = draw(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><g opacity='0.5'>" +
                "<rect x='0' y='0' width='60' height='60' fill='#000'/><rect x='40' y='40' width='60' height='60' fill='#000'/></g></svg>",
        )
        val overlap = Color.red(bmp.getPixel(50, 50))
        val single = Color.red(bmp.getPixel(10, 10))
        assertTrue("overlap $overlap", overlap in 120..135)
        assertEquals(overlap.toFloat(), single.toFloat(), 3f)
    }

    @Test
    fun aShapesOpacityCoversItsFillAndStrokeTogether() {
        // A black square with a black stroke at 50%: one even grey, no darker band where the stroke meets the fill.
        val bmp = draw("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>" +
            "<rect x='20' y='20' width='60' height='60' fill='#000' stroke='#000' stroke-width='10' opacity='0.5'/></svg>")
        val inside = Color.red(bmp.getPixel(50, 50))
        val onTheEdge = Color.red(bmp.getPixel(20, 50))
        assertTrue("inside $inside", inside in 120..135)
        assertEquals(inside.toFloat(), onTheEdge.toFloat(), 3f)
    }

    @Test
    fun aTranslatedHalfOpaqueShapeDoesNotMoveItsSibling() {
        // The first rect (fill, stroke, opacity, translated) mustn't leave its translation on the second.
        val bmp = draw("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>" +
            "<rect width='10' height='10' fill='#000' stroke='#000' opacity='0.5' transform='translate(50 0)'/>" +
            "<rect x='0' y='60' width='20' height='20' fill='#000'/></svg>")
        assertTrue("the sibling sits where it says", Color.red(bmp.getPixel(10, 70)) < 10)
        assertTrue("not moved", Color.red(bmp.getPixel(60, 70)) > 250)
    }

    @Test
    fun definitionsAreNotDrawnAndTheRootsOpacityCoversTheImage() {
        val defs = draw("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><defs><rect width='100' height='100' fill='#000'/></defs>" +
            "<rect x='60' width='40' height='100' fill='#000'/></svg>")
        assertTrue("an unused <defs> rect isn't painted", Color.red(defs.getPixel(20, 50)) > 250)
        assertTrue("the rect outside <defs> is", Color.red(defs.getPixel(80, 50)) < 10)
        val faded = draw("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100' opacity='0.5'><rect width='100' height='100' fill='#000'/></svg>")
        assertTrue(Color.red(faded.getPixel(50, 50)) in 120..135)
    }

    @Test
    fun aZeroFirstDashKeepsTheLaterDashes() {
        // "0 40 40 20" with a butt cap: the 40-unit dash from 40 to 80 paints.
        val bmp = draw(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>" +
                "<path d='M0 50 H100' stroke='#000' stroke-width='10' stroke-dasharray='0 40 40 20'/></svg>",
        )
        assertTrue(Color.red(bmp.getPixel(60, 50)) < 50)
        assertTrue(Color.red(bmp.getPixel(20, 50)) > 200)
    }

    @Test
    fun aNonFiniteOpacityIsIgnoredNotThrown() {
        // `opacity="NaN"` parses as a float; drawn, it would throw. It takes the default (opaque) instead.
        val bmp = draw("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><rect width='100' height='100' fill='#000' opacity='NaN'/>" +
            "<rect x='50' width='50' height='100' fill='#000' fill-opacity='Infinity'/></svg>")
        assertTrue(Color.red(bmp.getPixel(20, 50)) < 10)
        assertNull(dev.glassworks.engine.vitro.cards.MarkdownInline.parseOpacity("NaN"))
    }

    @Test
    fun compactArcFlagsLeaveTheCoordinate() {
        // `A5 5 0 011.5 1`: flags 0 and 1, then the end point (1.5, 1); the image is drawn, not dropped.
        assertNotNull(MdSvg.parse("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><path d='M0 0A5 5 0 011.5 1Z' fill='#000'/></svg>"))
        val compact = MdSvg.pathData("M0 0A5 5 0 011.5 1")
        val spaced = MdSvg.pathData("M0 0A5 5 0 0 1 1.5 1")
        val a = android.graphics.RectF().also { compact.computeBounds(it, true) }
        val b = android.graphics.RectF().also { spaced.computeBounds(it, true) }
        assertEquals(b, a)
        // On its own too: the arc is drawn (it has a length) and ends at (1.5, 1).
        val measure = android.graphics.PathMeasure(compact, false)
        assertTrue(measure.length > 0f)
        val end = FloatArray(2)
        measure.getPosTan(measure.length, end, null)
        assertEquals(1.5f, end[0], 0.01f)
        assertEquals(1f, end[1], 0.01f)
    }

    @Test
    fun aTallCameraFrameIsSampledDownToThePixelBudget() {
        // Narrow enough for the width limit, yet 2 M pixels: the height counts too.
        val tall = Bitmap.createBitmap(100, 20_000, Bitmap.Config.RGB_565)
        val jpeg = java.io.ByteArrayOutputStream().also { tall.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray()
        val bmp = dev.glassworks.engine.vitro.net.MjpegStreamPlayer.decode(jpeg, 1280)!!
        assertTrue("${bmp.width} x ${bmp.height}", bmp.width.toLong() * bmp.height <= dev.glassworks.engine.vitro.net.MjpegStreamPlayer.MAX_DECODED_PIXELS)
        // A normal frame keeps its size.
        val normal = Bitmap.createBitmap(640, 360, Bitmap.Config.RGB_565)
        val small = java.io.ByteArrayOutputStream().also { normal.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray()
        assertEquals(640, dev.glassworks.engine.vitro.net.MjpegStreamPlayer.decode(small, 1280)!!.width)
    }
}
