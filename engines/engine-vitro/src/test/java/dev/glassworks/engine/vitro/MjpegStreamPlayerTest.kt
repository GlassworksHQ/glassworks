/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.net.MjpegStreamPlayer
import dev.glassworks.engine.vitro.net.readMjpegFrames
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** HA's MJPEG camera stream parser, and the picture cards that play it. Addresses are RFC 5737. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MjpegStreamPlayerTest {

    private val base = FakeHaServer.BASE

    /** A fake JPEG: SOI, [fill] bytes of payload (with an FF that isn't a marker end), EOI. */
    private fun jpeg(seed: Int, fill: Int = 32): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        repeat(fill) { i -> out.write(if (i == fill / 2) 0xFF else (seed + i) and 0x7F) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun multipart(frames: List<ByteArray>, withLength: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in frames) {
            out.write("--frame\r\nContent-Type: image/jpeg\r\n".toByteArray())
            if (withLength) out.write("Content-Length: ${f.size}\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(f)
            out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    private fun collect(bytes: ByteArray, maxBytes: Int = 2 * 1024 * 1024): List<ByteArray> {
        val got = ArrayList<ByteArray>()
        readMjpegFrames(ByteArrayInputStream(bytes), maxBytes) { got += it; true }
        return got
    }

    @Test
    fun readsContentLengthFramedParts() {
        val frames = listOf(jpeg(1), jpeg(2, 100), jpeg(3, 7))
        val got = collect(multipart(frames, withLength = true))
        assertEquals(3, got.size)
        frames.indices.forEach { assertArrayEquals(frames[it], got[it]) }
    }

    @Test
    fun readsSoiToEoiPartsWithoutContentLength() {
        val frames = listOf(jpeg(4), jpeg(5, 64))
        val got = collect(multipart(frames, withLength = false))
        assertEquals(2, got.size)
        frames.indices.forEach { assertArrayEquals(frames[it], got[it]) }
        // Bare back-to-back JPEGs, no multipart headers at all.
        val bare = collect(jpeg(6) + jpeg(7))
        assertEquals(2, bare.size)
        assertArrayEquals(jpeg(7), bare[1])
    }

    @Test
    fun anExifThumbnailsEoiDoesNotEndTheFrame() {
        // SOI, APP1 holding a whole thumbnail (its own SOI..EOI), SOS with scan data (a stuffed FF 00), EOI.
        val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (thumb.size + 2).toByte()))
        out.write(thumb)
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 9, 9))
        out.write(byteArrayOf(5, 0xFF.toByte(), 0, 6, 0xFF.toByte(), 0xD0.toByte(), 7))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val frame = out.toByteArray()
        val got = collect(multipart(listOf(frame, jpeg(10)), withLength = false))
        assertEquals(2, got.size)
        assertArrayEquals(frame, got[0])
        assertArrayEquals(jpeg(10), got[1])
    }

    @Test
    fun skipsPartsOverTheCapAndKeepsReading() {
        val small = jpeg(8, 16)
        val huge = jpeg(9, 4_096)
        // By length: the huge part is skipped unread, the next one still arrives.
        assertEquals(listOf(small.toList()), collect(multipart(listOf(huge, small), withLength = true), maxBytes = 1_024).map { it.toList() })
        // By markers: same, without ever buffering past the cap.
        assertEquals(listOf(small.toList()), collect(multipart(listOf(huge, small), withLength = false), maxBytes = 1_024).map { it.toList() })
        // A Content-Length far beyond the stream doesn't allocate it; the read just ends.
        val lying = "--frame\r\nContent-Length: 2000000000\r\n\r\n".toByteArray() + small
        assertTrue(collect(lying, maxBytes = 1_024).isEmpty())
    }

    @Test
    fun stopsWhenTheCallbackSaysSo() {
        var seen = 0
        val n = readMjpegFrames(ByteArrayInputStream(multipart(listOf(jpeg(1), jpeg(2), jpeg(3)), true))) { seen++; false }
        assertEquals(1, n)
        assertEquals(1, seen)
    }

    @Test
    fun streamUrlCarriesTheSignedToken() {
        assertEquals(
            "$base/api/camera_proxy_stream/camera.porch?token=abc123",
            MjpegStreamPlayer.streamUrl("$base/", "camera.porch", "/api/camera_proxy/camera.porch?token=abc123&t=1"),
        )
        assertEquals("$base/api/camera_proxy_stream/camera.porch", MjpegStreamPlayer.streamUrl(base, "camera.porch", null))
        assertNull(MjpegStreamPlayer.streamUrl("", "camera.porch", null))
    }

    // ---- PictureCardView ----

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun context(
        store: EntityStore,
        calls: MutableList<String> = ArrayList(),
        cameraStream: ((String, String?, (Bitmap) -> Unit, () -> Unit) -> () -> Unit)? = null,
    ) = VitroCardContext(
        context = activity,
        haBase = base,
        store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += "$domain.$service ${data?.optString("entity_id")}" },
        navigate = {},
        openMoreInfo = { calls += "more-info $it" },
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
        cameraStream = cameraStream,
    )

    private fun <T : View> ViewGroup.find(type: Class<T>): List<T> {
        val out = ArrayList<T>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (type.isInstance(c)) out += type.cast(c)!!
            if (c is ViewGroup) out += c.find(type)
        }
        return out
    }

    private fun attach(view: View) {
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(720, 405)) })
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun pictureGlanceTogglesItsEntityIcons() {
        val store = EntityStore()
        store.putLocal(EntityState("light.porch", "off", mapOf("friendly_name" to "Porch Light")))
        store.putLocal(EntityState("sensor.porch_temp", "12", mapOf("friendly_name" to "Porch Temp")))
        val calls = ArrayList<String>()
        val card = PictureCardView(
            context(store, calls),
            LovelaceParser.parseCard(
                JSONObject(
                    """{"type": "picture-glance", "title": "Porch", "image": "/local/porch.png",
                       "entities": ["light.porch", "sensor.porch_temp"]}""",
                ),
            ),
        )
        attach(card)

        val icons = card.find(MdiIconView::class.java)
        assertEquals(2, icons.size)
        assertEquals(0xCCFFFFFF.toInt(), icons[0].tintColor) // off: white
        (icons[0].parent as View).performClick()
        assertEquals("light.toggle light.porch", calls.single())
        ShadowLooper.idleMainLooper()
        // The optimistic flip tints it with the light's active color.
        assertTrue(icons[0].tintColor != 0xCCFFFFFF.toInt())

        (icons[1].parent as View).performClick()
        assertEquals("more-info sensor.porch_temp", calls.last())
        val before = calls.size
        (icons[1].parent as View).performLongClick()
        // The hold added its own call (the click's would still be last otherwise).
        assertEquals(before + 1, calls.size)
        assertEquals("more-info sensor.porch_temp", calls.last())
    }

    @Test
    fun liveCameraPlaysTheStreamAndStopsItOnDetach() {
        val store = EntityStore()
        store.putLocal(EntityState("camera.porch", "streaming", mapOf("entity_picture" to "/api/camera_proxy/camera.porch?token=t1")))
        var pushFrame: ((Bitmap) -> Unit)? = null
        var asked: String? = null
        var stopped = 0
        val card = PictureCardView(
            context(store, cameraStream = { id, picture, onFrame, _ ->
                asked = "$id $picture"
                pushFrame = onFrame
                ({ stopped++ })
            }),
            LovelaceParser.parseCard(JSONObject("""{"type": "picture-entity", "entity": "camera.porch", "camera_view": "live"}""")),
        )
        attach(card)
        assertEquals("camera.porch /api/camera_proxy/camera.porch?token=t1", asked)

        val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        pushFrame!!(frame)
        val image = card.find(ImageView::class.java).first()
        val drawn = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertSame(frame, drawn)

        activity.setContentView(View(activity))
        ShadowLooper.idleMainLooper()
        assertEquals(1, stopped)
    }

    @Test
    fun aCameraWithoutLiveViewShowsSnapshotsAndOpensNoStream() {
        // HA's `camera_view: auto` (the default): hui-image refreshes a still every 10 s, no MJPEG.
        val store = EntityStore()
        store.putLocal(EntityState("camera.porch", "idle", mapOf("entity_picture" to "/api/camera_proxy/camera.porch?token=t1")))
        var streams = 0
        val card = PictureCardView(
            context(store, cameraStream = { _, _, _, _ -> streams++; {} }),
            LovelaceParser.parseCard(JSONObject("""{"type": "picture-entity", "entity": "camera.porch"}""")),
        )
        attach(card)
        ShadowLooper.idleMainLooper()
        assertEquals(0, streams)
    }
}
