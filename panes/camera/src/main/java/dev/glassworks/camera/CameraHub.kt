/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The device's camera, shared by everything the camera pane runs: presence frames ([open], a
 * [FrameSource]), stills for Home Assistant ([jpeg], a [StillCamera]) and the stream's encoder
 * ([setEncoder]). One Camera2 device, opened while anyone needs it and closed when nobody does. The
 * front camera when there is one: a panel faces the room.
 *
 * Everything happens on the hub's own thread, so the camera's state has one writer. Frames reach
 * presence at about the rate it asked for; the rest are dropped without a copy.
 *
 * A session that sends no frame for [SILENT_MS] is closed and the camera opened again ([ReopenBackoff]): a
 * LEGACY camera's first session can start and never deliver (the Mirror, 2026-11-19, #599). The first frame of a
 * session gets [FIRST_FRAME_MS]: the same camera can also take more than 5 s to start (#674). A blank frame
 * ([blankYuv]: rows that are zero in every plane, or the green zero turns into, Home Assistant's solid green) counts as no frame: the Mirror's stock camera starts
 * about one session in three that way (2026-11-25), and that session is reopened like a silent one.
 *
 * A camera that won't open (another app has it, an error) is tried again after [ConnectBackoff]'s wait, or sooner
 * once Android says another app freed it, never in bursts: on Android 6 a burst of connects to a camera another app
 * held crashed the media server (the Mirror, 2026-11-25).
 *
 * While streaming, the encoder gets every frame and the reader only one in [readerEvery]: on a LEGACY camera Android's
 * shim copies each frame it hands the reader on the CPU, which slowed the Mirror's stream to about 7 fps.
 */
class CameraHub(
    context: Context,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    /** The frame's cap on the camera's and the stream's size, in pixels ([dev.glassworks.frame.Frame.cameraMaxPixels]). */
    private val maxPixels: Int = MAX_PIXELS,
    /** The frame's word on how the sensor sits ([dev.glassworks.frame.Frame.cameraSensorDegrees]), over Android's. */
    private val frameSensorDegrees: Int? = null,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : FrameSource, StillCamera, EncoderInput, CameraFacts {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("glassworks-camera").apply { start() }
    private val handler = Handler(thread.looper)
    /** The hub's thread, for tests that run its work on Robolectric's clock. */
    internal val looper get() = thread.looper

    private class Sink(val fps: Float, val sink: FrameSink) { var lastMs = 0L }
    private class Waiter(val latch: CountDownLatch) { @Volatile var jpeg: ByteArray? = null }

    // Owned by the hub's thread.
    private val sinks = ArrayList<Sink>()
    private val waiters = ArrayList<Waiter>()
    private var encoder: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var opening = false
    /** Set by [release]: a camera that opens after it is closed at once. */
    private var released = false
    /** Bumped whenever a session is dropped, so a late callback from an older one is ignored. */
    private var generation = 0
    private var configuring = false
    private var lingerUntil = 0L
    /** Bumped by [idle]: a still asked for before it doesn't keep the camera open after it. */
    private var stillEpoch = 0
    private var luma = ByteArray(0)
    private var facingFront = true
    private var sensorDegrees = 0
    /** The range every request asks for ([cameraRange]). */
    private var fpsRange: android.util.Range<Int>? = null
    /** The reader's size: [width] x [height] when the camera lists it ([yuvSizeFor]). */
    private var size = width to height
    /** The stream on the open camera, read with [size]: the session never looks the camera up again. */
    private var openFit: StreamFit = StreamFit.Refused(SIZES_UNREADABLE)
    /** The camera the hub opens or holds open, for [availability]. */
    private var openId: String? = null
    /** When the session's next image is late: the silence watchdog's clock, started with its requests. */
    private val deadline = FrameDeadline()
    private val reopen = ReopenBackoff()
    /** A silent camera isn't opened again before this ([ReopenBackoff]). */
    private var reopenAt = 0L
    /** Blank frames ([blankYuv]) since the session started or its last good frame. */
    private var blanks = 0
    /**
     * The slowest the camera may run: the floor of the range asked for ([fpsRange]), or with none the
     * lowest floor the camera lists, since it then picks its own. 0: unknown. Sets [readerEvery].
     */
    private var streamFloor = 0
    private val connect = ConnectBackoff()
    /** A camera that failed to open or failed while open isn't tried again before this ([ConnectBackoff]). */
    private var connectAt = 0L
    /** Waiting for another app to free this camera (any camera, for [ANY_CAMERA]), else null: its freeing ends the wait. */
    private var freeFor: String? = null

    private val wanted get() = sinks.isNotEmpty() || waiters.isNotEmpty() || encoder != null ||
        SystemClock.elapsedRealtime() < lingerUntil

    override fun open(fps: Float, sink: FrameSink): AutoCloseable {
        val s = Sink(fps, sink)
        handler.post { sinks += s; settle() }
        return AutoCloseable { handler.post { sinks -= s; settle() } }
    }

    /**
     * The next frame as a JPEG, upright, or null after [STILL_WAIT_MS]. Blocks the caller (a camera
     * entity's thread), never the hub's. The camera stays open [LINGER_MS] after, so a card that asks
     * every few seconds doesn't reopen it each time.
     */
    override fun jpeg(): ByteArray? {
        val w = Waiter(CountDownLatch(1))
        var epoch = 0
        handler.post { epoch = stillEpoch; waiters += w; settle() }
        w.latch.await(STILL_WAIT_MS, TimeUnit.MILLISECONDS)
        handler.post {
            waiters -= w
            if (epoch == stillEpoch) lingerUntil = SystemClock.elapsedRealtime() + LINGER_MS
            handler.postDelayed(::settle, LINGER_MS + 50)
        }
        return w.jpeg
    }

    /**
     * The stream encoder whose input the camera draws into, or null to take it out. The capture session
     * is rebuilt either way. Taking it out waits for the hub's thread to stop the session's requests and
     * close it, and returns after at most [DETACH_WAIT_MS]: that bound, not the session's onClosed (API
     * 28), is what the caller relies on before it releases the encoder's surface. Never call from the
     * hub's thread.
     */
    override fun setEncoder(encoder: StreamEncoder?) {
        val surface = encoder?.surface
        val done = CountDownLatch(1)
        handler.post {
            if (this.encoder !== surface) {
                this.encoder = surface
                closeSession()
                settle()
            }
            done.countDown()
        }
        if (surface == null) done.await(DETACH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Nothing may use the camera now (privacy, the pane stopping): drops the still linger and any waiting
     * still, so the camera closes at once instead of [LINGER_MS] later. Presence closes its own frames.
     */
    fun idle() {
        handler.post {
            stillEpoch++
            lingerUntil = 0L
            waiters.forEach { it.latch.countDown() }; waiters.clear()
            settle()
        }
    }

    /** Closes the camera and ends the hub's thread. Nothing may use the hub after. */
    fun release() {
        handler.post {
            released = true
            manager.unregisterAvailabilityCallback(availability)
            sinks.clear(); waiters.forEach { it.latch.countDown() }; waiters.clear(); encoder = null
            closeCamera()
            // An open in flight still calls back: quit only once it has, so it can close that camera.
            if (!opening) thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ the hub's thread

    private fun settle() {
        noteRate()
        if (released) return
        // Nobody wants the camera (privacy, the pane stopping): the next use starts without a backoff.
        // A camera that failed to open keeps its wait: another app holding it doesn't free it because nobody here wants it.
        if (!wanted) { closeCamera(); reopen.reset(); reopenAt = 0L; return }
        if (device == null) {
            val now = SystemClock.elapsedRealtime()
            if (!opening && now >= reopenAt && now >= connectAt) openCamera()
            return
        }
        if (session == null && !configuring) startSession()
    }

    @SuppressLint("MissingPermission") // the pane opens the hub only with the permission; a refusal is caught
    private fun openCamera() {
        val id = try { pickCamera() } catch (e: Exception) { fail("no camera: ${e.javaClass.simpleName}"); return }
            ?: run { fail("this device has no camera", waitFor = ANY_CAMERA); return }
        opening = true
        openId = id
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (released) { camera.close(); thread.quitSafely(); return }
                    device = camera
                    log("camera $id open (${if (facingFront) "front" else "back"} at $sensorDegrees°, ${size.first}x${size.second}${fpsRange?.let { ", $it fps" } ?: ""})")
                    settle()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close(); forget(camera)
                    if (released) { thread.quitSafely(); return }
                    fail("another app took the camera", waitFor = id)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close(); forget(camera)
                    if (released) { thread.quitSafely(); return }
                    val inUse = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    fail(if (inUse) "another app has the camera (error $error)" else "camera error $error", waitFor = id.takeIf { inUse })
                }
            }, handler)
        } catch (e: SecurityException) {
            opening = false
            fail("Android hasn't allowed the camera")
        } catch (e: CameraAccessException) {
            opening = false
            val inUse = e.reason == CameraAccessException.CAMERA_IN_USE || e.reason == CameraAccessException.MAX_CAMERAS_IN_USE ||
                e.reason == CameraAccessException.CAMERA_DISCONNECTED
            fail("camera unavailable (${e.reason})", waitFor = id.takeIf { inUse })
        }
    }

    /** The front camera, else the first. */
    private fun cameraId(): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: ids.firstOrNull()
    }

    /**
     * The range the camera is asked for ([cameraRangeFor]). Read once and kept: the first
     * reader may be the stream's start ([streamFps], off the hub's thread, before the camera opens) or
     * [pickCamera] on the hub's thread; `lazy` makes that safe, and the camera's ranges don't change.
     */
    private val cameraRange: IntRange? by lazy {
        try {
            cameraId()?.let { id ->
                val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                cameraRangeFor(ranges?.map { it.lower..it.upper })
            }
        } catch (e: Exception) {
            null
        }
    }

    override val streamFps: Int get() = announcedFps(cameraRange)

    /** The still's rule ([stillRotation]) on the camera the stream would use, with the screen as it stands now. */
    override val streamTurn: Int get() = try {
        val s = sizes.kept() ?: read(cameraId())
        stillRotation(s.sensorDegrees, displayDegrees(), s.front)
    } catch (e: Exception) {
        0
    }

    /** What [read] finds on a camera: its sizes, and how its sensor sits ([sensorOf]) and faces. */
    private class Sizes(val reader: Pair<Int, Int>, val fit: StreamFit, val sensorDegrees: Int = 0, val front: Boolean = false)

    /** How [c]'s sensor sits: the frame's word ([frameSensorDegrees]) over Android's. */
    private fun sensorOf(c: CameraCharacteristics): Int = frameSensorDegrees ?: c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /**
     * The last sizes [read], with the camera they were read from. Dropped when opening a camera fails ([fail]) and
     * whenever Android says a camera the hub doesn't hold came, went, or was taken or freed ([availability]), so a
     * camera that was unplugged or replaced under the same id is read again.
     */
    private val sizes = KeptRead<Sizes>()

    /** Runs on the hub's thread, as [openId] changes. */
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) { changed(cameraId); freed(cameraId) }
        override fun onCameraUnavailable(cameraId: String) = changed(cameraId)

        private fun changed(cameraId: String) { if (cameraId != openId) sizes.drop() }

        /** The camera another app held is free: try it now, or once [ConnectBackoff.MIN_GAP_MS] after the last failure. */
        private fun freed(cameraId: String) {
            val waiting = freeFor ?: return
            if (waiting != ANY_CAMERA && waiting != cameraId) return
            freeFor = null
            val now = SystemClock.elapsedRealtime()
            val at = connect.freed(now)
            if (at >= connectAt) return
            connectAt = at
            log("camera: $cameraId is free again; opening ${if (at <= now) "now" else "in ${(at - now + 999) / 1000} s"}")
            handler.postDelayed(::settle, at - now)
        }
    }

    // On the hub's thread, not the caller's: it is the process's first call to the camera service, and the panes are
    // built on the main thread at start. release() unregisters on the same thread, after it.
    init {
        handler.post { manager.registerAvailabilityCallback(availability, handler) }
    }

    /**
     * The reader size ([yuvSizeFor]) and stream ([streamFitFor]) of camera [id] (null: the panel has none now): the
     * encoder runs at the reader's size, or not at all. The stream asks before it opens a port, the hub when it opens
     * the camera. Neither no camera nor a read that throws is kept, so the next ask reads again.
     */
    private fun read(id: String?): Sizes {
        if (id == null) return Sizes(width to height, StreamFit.Refused(NO_CAMERA))
        return sizes.read(id) {
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // On a LEGACY camera the largest JPEG size has the sensor's shape, as the shim assumes.
            val sensor = if (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) null
                else map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }?.let { it.width to it.height }
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }
            // The stream's camera output is the GPU step's texture ([TurnedInput]); the encoder takes the picture turned.
            val surfaces = map?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }
            val degrees = sensorOf(c)
            val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val turn = stillRotation(degrees, displayDegrees(), front)
            val encodes = { w: Int, h: Int -> turnedSize(w, h, turn).let { (tw, th) -> H264Encoder.encodes(tw, th) } }
            val reader = yuvSizeFor(yuv, width to height, maxPixels, sensor) { streamFitFor(it, surfaces, encodes) is StreamFit.Size }
            if (sensor != null) {
                log("camera: LEGACY, sensor ${sensor.first}x${sensor.second}, YUV ${yuv?.joinToString { "${it.first}x${it.second}" }}")
                // No reader size bounds the shim's preview then: it takes the smallest sensor-shaped size that covers
                // the reader, which is already the smallest there is, or with none its largest preview size.
                if (!legacyAspect(reader, sensor)) log("camera: no size of the sensor's shape up to $maxPixels px; the preview may run larger than ${reader.first}x${reader.second}")
            }
            Sizes(reader, streamFitFor(reader, surfaces, encodes), degrees, front)
        }
    }

    /**
     * No stream while the camera's sizes can't be read: the pane asks again on its next check. Kept sizes answer
     * without asking the camera service, so the page's refresh costs no binder call.
     */
    override val streamFit: StreamFit get() = try {
        (sizes.kept() ?: read(cameraId())).fit
    } catch (e: Exception) {
        log("camera: sizes unreadable (${e.javaClass.simpleName})")
        StreamFit.Refused(SIZES_UNREADABLE)
    }

    /** Which camera the hub opens, for the page (#117's Camera tile): read once, like [streamRange]. */
    override val lens: CameraFacts.Lens? by lazy {
        try {
            cameraId()?.let { id ->
                when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraFacts.Lens.FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraFacts.Lens.BACK
                    else -> CameraFacts.Lens.EXTERNAL
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The rate asked of the camera now, set by the hub's thread on every change ([noteRate]). */
    @Volatile private var rate: Int? = 0

    override fun frameRate(): Int? = rate

    /** While streaming, the encoder's rate when the camera is asked for one, else its own (null); else presence's. 0: nothing asks. */
    private fun noteRate() {
        val presence = sinks.maxOfOrNull { it.fps }?.let { Math.round(it) } ?: 0
        rate = if (encoder != null) streamFps.takeIf { it > 0 } else presence
    }

    private fun pickCamera(): String? {
        val id = cameraId() ?: return null
        val c = manager.getCameraCharacteristics(id)
        facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorDegrees = sensorOf(c)
        fpsRange = cameraRange?.let { android.util.Range(it.first, it.last) }
        streamFloor = cameraRange?.first
            ?: c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.minOfOrNull { it.lower } ?: 0
        val read = sizes.kept(id) ?: read(id) // throws to openCamera, which retries
        size = read.reader
        openFit = read.fit
        return id
    }

    @Suppress("DEPRECATION") // createCaptureSession(List): SessionConfiguration needs API 28, the S1 app's floor is 26
    private fun startSession() {
        val camera = device ?: return
        val r = reader ?: ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = r
        }
        // The stream never asks on a camera that can't stream ([CameraStream] opens no port); if it did, presence goes on alone.
        val encoder = encoder?.takeIf { openFit is StreamFit.Size }
        if (this.encoder != null && encoder == null) log("camera: no stream ($openFit); presence goes on")
        val targets = listOfNotNull(r.surface, encoder)
        val gen = ++generation
        configuring = true
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || device !== camera) { s.close(); return }
                    configuring = false
                    session = s
                    try {
                        val template = if (encoder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        fun request(to: List<Surface>) = camera.createCaptureRequest(template).apply {
                            to.forEach(::addTarget)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        val every = readerEvery(streaming = encoder != null, streamFloor)
                        if (every <= 1 || encoder == null) {
                            s.setRepeatingRequest(request(targets), null, handler)
                        } else {
                            // Android's shim reads back only the outputs a request names (AOSP 6.0.1
                            // SurfaceTextureRenderer.drawIntoSurfaces), so the encoder alone runs at full rate.
                            val plain = request(listOf(encoder))
                            s.setRepeatingBurst(List(every - 1) { plain } + request(targets), null, handler)
                            log("camera: streaming; the reader gets 1 frame in $every")
                        }
                        blanks = 0
                        deadline.started(SystemClock.elapsedRealtime())
                        sinks.forEach { it.sink.started() }
                        watchSilence(gen, deadline.nextCheck(SystemClock.elapsedRealtime()))
                    } catch (e: Exception) {
                        fail("camera session failed: ${e.javaClass.simpleName}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    configuring = false
                    fail("the camera refused the session (${targets.size} outputs)")
                }
            }, handler)
        } catch (e: Exception) {
            configuring = false
            fail("camera session failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onImage(r: ImageReader) {
        // The reader can be closed between the callback being posted and now: then there is no image.
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val blank = try { blankOf(image) } catch (e: IllegalStateException) { return }
            onFrame(SystemClock.elapsedRealtime(), blank, luma = { lumaOf(image) }, jpeg = { jpegOf(image) })
        } finally {
            image.close()
        }
    }

    /**
     * One image from the reader, on the hub's thread. A [blank] one is no frame: it doesn't hold off the silence
     * watchdog, reach presence or answer a still, so a still waits for a good frame or fails after [STILL_WAIT_MS].
     */
    internal fun onFrame(now: Long, blank: Boolean, luma: () -> LumaFrame, jpeg: () -> ByteArray) {
        if (blank) {
            if (++blanks == 1) log("camera: blank frame (zero or drawn-green rows); not used")
            return
        }
        blanks = 0
        deadline.frame(now)
        reopen.reset()
        connect.reset()
        val due = sinks.filter { now - it.lastMs >= (1000f / it.fps).toLong() }
        if (due.isNotEmpty()) {
            val frame = try { luma() } catch (e: IllegalStateException) { null }
            if (frame != null) for (s in due) { s.lastMs = now; s.sink.frame(frame) }
        }
        if (waiters.isNotEmpty()) {
            val still = try { jpeg() } catch (e: Exception) { log("still failed: ${e.javaClass.simpleName}"); null }
            waiters.forEach { it.jpeg = still; it.latch.countDown() }
            waiters.clear()
        }
    }

    private fun blankOf(image: Image): Boolean {
        val (y, u, v) = image.planes
        return blankYuv(image.width, image.height, y.buffer, y.rowStride, u.buffer, v.buffer, u.rowStride, u.pixelStride)
    }

    /** The Y plane, copied once into a buffer the hub reuses: presence reads it before [FrameSink.frame] returns. */
    private fun lumaOf(image: Image): LumaFrame {
        val plane = image.planes[0]
        val buf = plane.buffer
        val size = plane.rowStride * (image.height - 1) + image.width
        if (luma.size < size) luma = ByteArray(size)
        buf.position(0)
        buf.get(luma, 0, minOf(size, buf.remaining()))
        return LumaFrame(image.width, image.height, plane.rowStride, luma)
    }

    private fun jpegOf(image: Image): ByteArray {
        val (y, u, v) = image.planes
        val nv21 = yuvToNv21(image.width, image.height, y.buffer, y.rowStride, u.buffer, v.buffer, u.rowStride, u.pixelStride)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null).compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        val turn = stillRotation(sensorDegrees, displayDegrees(), facingFront)
        if (turn == 0) return out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it); bmp.recycle(); rotated.recycle() }.toByteArray()
    }

    private fun displayDegrees(): Int {
        val d = (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        return when (d?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    /**
     * Checks session [gen] for silence in [delayMs]: no first image for [FIRST_FRAME_MS], or no image for [SILENT_MS]
     * after one, closes the camera and opens it again, at once the first time and later after each silence that
     * follows ([ReopenBackoff]). The sinks hear of it ([FrameSink.lost]) first; this is what ends it.
     */
    private fun watchSilence(gen: Int, delayMs: Long) {
        handler.postDelayed({
            if (gen != generation || session == null || released) return@postDelayed
            val now = SystemClock.elapsedRealtime()
            val left = deadline.left(now)
            if (left > 0) { watchSilence(gen, deadline.nextCheck(now)); return@postDelayed }
            val why = deadline.why(now) + if (blanks > 0) ", $blanks blank" else ""
            val wait = reopen.silence()
            log("camera: reopen ${reopen.attempt} ${if (wait == 0L) "now" else "in ${wait / 1000} s"} ($why)")
            // Before the reopen's started() gives presence a new grace, so the silence is reported, not skipped.
            sinks.forEach { it.sink.lost(why) }
            closeCamera()
            reopenAt = SystemClock.elapsedRealtime() + wait
            handler.postDelayed(::settle, wait)
        }, delayMs)
    }

    /**
     * Tells presence the camera is gone, answers waiting stills, and tries again after [ConnectBackoff]'s wait while it
     * is still wanted. [waitFor]: the camera another app holds ([ANY_CAMERA]: none was there), whose freeing ends the wait.
     */
    private fun fail(why: String, waitFor: String? = null) {
        log("camera: $why")
        sizes.drop()
        closeCamera()
        sinks.forEach { it.sink.lost(why) }
        waiters.forEach { it.latch.countDown() }
        waiters.clear()
        val wait = connect.failed(SystemClock.elapsedRealtime())
        connectAt = SystemClock.elapsedRealtime() + wait
        freeFor = waitFor
        log("camera: try ${connect.attempt} failed; next in ${wait / 1000} s${if (waitFor != null) " or once the camera is free" else ""}")
        handler.postDelayed(::settle, wait)
    }

    private fun forget(camera: CameraDevice) {
        if (device === camera) { device = null; session = null }
    }

    private fun closeSession() {
        generation++
        configuring = false
        session?.let { s ->
            // Stop drawing now, not when close() finishes in the background: the encoder's surface may go next.
            try { s.stopRepeating(); s.abortCaptures() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
        session = null
    }

    private fun closeCamera() {
        closeSession()
        try { device?.close() } catch (_: Exception) {}
        if (device != null) log("camera closed")
        device = null
        openId = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    companion object {
        private const val TAG = "Glassworks.Camera"

        /** Enough for a still people recognise and an H.264 stream; presence reads a sparse 48x27 grid of it. */
        const val WIDTH = 1280
        const val HEIGHT = 720
        /** The most a camera runs at on a frame that sets no cap of its own: 1080p. */
        const val MAX_PIXELS = 1920 * 1080
        const val JPEG_QUALITY = 85
        const val STILL_WAIT_MS = 2_000L
        const val LINGER_MS = 10_000L
        /** A session that sends no frame this long is reopened: [CameraPresence.STALE_MS], so both see one silence. */
        const val SILENT_MS = CameraPresence.STALE_MS
        /** A session's first frame may take this long: [CameraPresence.FIRST_FRAME_MS], for the same reason. */
        const val FIRST_FRAME_MS = CameraPresence.FIRST_FRAME_MS
        const val DETACH_WAIT_MS = 1_000L
        const val SIZES_UNREADABLE = "The panel couldn't read the camera's sizes"
        const val NO_CAMERA = "The panel has no camera now"

        /** [fail]'s waitFor when there was no camera: any camera that appears ends the wait. */
        private const val ANY_CAMERA = "*"

        // CameraDevice.StateCallback's errors for a camera someone else holds.
        private const val ERROR_CAMERA_IN_USE = CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
        private const val ERROR_MAX_CAMERAS_IN_USE = CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE

        /** The reader's rate while the camera streams: presence still sees motion, the encoder keeps the rest. */
        const val STREAM_READER_FPS = 3
    }
}

/**
 * Which of a session's repeating requests carry the frame reader: 1 is every one, else 1 in that many, the rest only the
 * stream's encoder. Every one unless the camera is [streaming]: a LEGACY camera's shim copies each frame it gives the
 * reader on the CPU (the Mirror's stream fell to about 7 fps with the reader in every request, 26 fps without it,
 * 2026-11-25), and presence needs no more on any camera. The reader gets at least [CameraHub.STREAM_READER_FPS] even at the camera's
 * slowest, [minFps] (the floor of its rate), so presence and the silence watchdog always have frames. A floor too low
 * for that, or unknown (0), keeps the reader in every request, as before. At most 1 in 30.
 */
internal fun readerEvery(streaming: Boolean, minFps: Int): Int {
    if (!streaming || minFps <= 0) return 1
    return (minFps / CameraHub.STREAM_READER_FPS).coerceIn(1, 30)
}

/**
 * How long a camera that went silent waits before it is opened again: not at all the first time, then [firstMs],
 * doubling up to [capMs], so a camera that is truly broken isn't hammered. A frame [reset]s it. One thread.
 */
internal class ReopenBackoff(private val firstMs: Long = FIRST_MS, private val capMs: Long = CAP_MS) {
    /** Silences since the last frame; the reopen's number in the log. */
    var attempt = 0
        private set

    /** One more silence: the wait before the camera opens again. */
    fun silence(): Long {
        attempt++
        if (attempt == 1) return 0L
        return (firstMs shl minOf(attempt - 2, 20)).coerceAtMost(capMs)
    }

    fun reset() { attempt = 0 }

    companion object {
        const val FIRST_MS = 10_000L
        const val CAP_MS = 300_000L
    }
}

/**
 * How long a camera that failed to open (another app holds it, an error) or failed while open waits before it is tried
 * again: [firstMs], doubling up to [capMs]. A good frame [reset]s it. When Android says another app freed the camera,
 * it is tried at once, but never sooner than [minGapMs] after the last failure ([freed]), so connects never come in a
 * burst. One thread.
 */
internal class ConnectBackoff(
    private val firstMs: Long = FIRST_MS,
    private val capMs: Long = CAP_MS,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    /** Failures since the last good frame; the try's number in the log. */
    var attempt = 0
        private set
    private var failedAt: Long? = null

    /** A failure at [now]: the wait before the next try. */
    fun failed(now: Long): Long {
        attempt++
        failedAt = now
        return (firstMs shl minOf(attempt - 1, 20)).coerceAtMost(capMs)
    }

    /** When to try a camera another app freed at [now]. */
    fun freed(now: Long): Long = failedAt?.let { maxOf(now, it + minGapMs) } ?: now

    fun reset() { attempt = 0; failedAt = null }

    companion object {
        const val FIRST_MS = 5_000L
        const val CAP_MS = 60_000L
        const val MIN_GAP_MS = 2_000L
    }
}

/**
 * A value read for one camera id, kept until [drop]. A read that a [drop] overtook is returned but not kept, so a
 * camera that changed while it was being read is read again. Any thread.
 */
internal class KeptRead<V : Any> {
    private val lock = Any()
    private var kept: Pair<String, V>? = null
    private var generation = 0

    fun kept(): V? = synchronized(lock) { kept?.second }

    fun kept(id: String): V? = synchronized(lock) { kept?.takeIf { it.first == id }?.second }

    fun read(id: String, read: () -> V): V {
        val gen = synchronized(lock) { generation }
        val v = read()
        synchronized(lock) { if (gen == generation) kept = id to v }
        return v
    }

    fun drop() = synchronized(lock) { generation++; kept = null }
}
