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
import dev.glassworks.engine.vitro.camera.CameraPlayers
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.MjpegCameraStream
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
import org.junit.After
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
    /** The picture loads a test starts are all back before the next test: one in flight lands in its looper (D28). */
    private val pendingImagesBefore = dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get()

    @After fun imageLoadsSettle() = awaitImageLoads(pendingImagesBefore)


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
    fun aRedirectedStreamFallsBackWithoutLeavingHomeAssistant() {
        val elsewhere = java.util.concurrent.CopyOnWriteArrayList<String>()
        val other = dev.glassworks.web.HttpServer(0, { req -> elsewhere += req.target; dev.glassworks.web.HttpResponse.text(200, "x") },
            bindAddress = java.net.InetAddress.getByName("127.0.0.1"), workers = 1).apply { start() }
        val ha = dev.glassworks.web.HttpServer(0, { _ ->
            dev.glassworks.web.HttpResponse(302, mapOf("Location" to "http://127.0.0.1:${other.localPort}/stolen"), ByteArray(0))
        }, bindAddress = java.net.InetAddress.getByName("127.0.0.1"), workers = 1).apply { start() }
        try {
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val player = MjpegStreamPlayer("http://127.0.0.1:${ha.localPort}/api/camera_proxy_stream/camera.door", "t", onFrame = {}, onError = { failed.set(true) })
            player.start()
            awaitCondition("the fallback", tick = { org.robolectric.shadows.ShadowLooper.idleMainLooper() }) { failed.get() }
            player.stop()
            assertTrue("nothing reached the other host: $elsewhere", elsewhere.isEmpty())
        } finally {
            ha.stop()
            other.stop()
        }
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
        cameraPlayers: CameraPlayers? = null,
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
        cameraPlayers = cameraPlayers,
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
        showWindow(activity.window.decorView)
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

        // HA's two rows: the sensor's icon (opens More Info) first, then the light's (toggles).
        val (sensor, light) = card.find(MdiIconView::class.java).also { assertEquals(2, it.size) }
        assertEquals(0xFFA9A9A9.toInt(), light.tintColor) // off: HA's #a9a9a9
        (light.parent as View).performClick()
        assertEquals("light.toggle light.porch", calls.single())
        ShadowLooper.idleMainLooper()
        // The optimistic flip turns it white, HA's on color for a picture-glance icon.
        assertEquals(android.graphics.Color.WHITE, light.tintColor)

        (sensor.parent as View).performClick()
        assertEquals("more-info sensor.porch_temp", calls.last())
        val before = calls.size
        (sensor.parent as View).performLongClick()
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
        var started = 0
        var stopped = 0
        val mjpeg = object : CameraStreamFactory {
            override fun create(entityId: String, kind: CameraStreamKind): CameraStream {
                val url = MjpegStreamPlayer.streamUrl(base, entityId, store[entityId]?.entityPicture)!!
                return MjpegCameraStream(activity, url, { null }) { u, _, onFrame, _ ->
                    asked = u
                    pushFrame = onFrame
                    object : MjpegCameraStream.Player {
                        override fun start() { started++ }
                        override fun stop() { stopped++ }
                    }
                }
            }
        }
        val card = PictureCardView(
            context(store, cameraPlayers = CameraPlayers(mapOf(CameraStreamKind.MJPEG to mjpeg))),
            LovelaceParser.parseCard(JSONObject("""{"type": "picture-entity", "entity": "camera.porch", "camera_view": "live"}""")),
        )
        attach(card)
        // A camera without HA's STREAM feature plays MJPEG straight away, with the entity's signed token.
        assertEquals("$base/api/camera_proxy_stream/camera.porch?token=t1", asked)
        assertEquals(1, started)

        val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        pushFrame!!(frame)
        // The stream's own view, over the card's still.
        val image = card.find(ImageView::class.java).last()
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
        val counting = object : CameraStreamFactory {
            override fun create(entityId: String, kind: CameraStreamKind): CameraStream? {
                streams++
                return null
            }
        }
        val card = PictureCardView(
            context(store, cameraPlayers = CameraPlayers(CameraStreamKind.values().associateWith { counting })),
            LovelaceParser.parseCard(JSONObject("""{"type": "picture-entity", "entity": "camera.porch"}""")),
        )
        attach(card)
        ShadowLooper.idleMainLooper()
        assertEquals(0, streams)
    }

    @Test
    fun aResumedMjpegStreamShowsNothingUntilItsFirstNewFrame() {
        // The CameraStream contract: the view draws nothing until the first frame of each start or resume.
        val frames = ArrayList<(Bitmap) -> Unit>()
        val stream = MjpegCameraStream(activity, "$base/api/camera_proxy_stream/camera.porch", { null }) { _, _, onFrame, _ ->
            frames += onFrame
            object : MjpegCameraStream.Player {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        val image = stream.view as ImageView
        stream.start()
        frames.last()(Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565))
        assertTrue(image.drawable != null)
        stream.setActive(false)
        stream.setActive(true)
        assertNull(image.drawable)
        val fresh = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        frames.last()(fresh)
        assertSame(fresh, (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap)
        assertEquals(2, frames.size)
        stream.close()
    }
}
