/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Hard cap on one JPEG part, so a corrupt or hostile stream can never run the panel out of memory. */
internal const val MAX_FRAME_BYTES = 2 * 1024 * 1024

/**
 * Plays HA's live camera feed (`/api/camera_proxy_stream/<entity>`, `multipart/x-mixed-replace` JPEG parts) on one
 * daemon thread, `vitro-mjpeg-<entityId>`. Frames are decoded off the UI thread as RGB_565, downsampled to at most
 * [maxWidthPx] wide, and handed to [onFrame] on the main thread. A frame that arrives while the previous one is
 * still waiting for the main thread is dropped, so a slow panel shows the newest picture instead of building lag.
 *
 * [onError] runs once on the main thread when the stream can't connect, answers non-200, isn't multipart, drops,
 * or goes [NO_FRAME_MS] without a frame that decodes (a proxy that keeps sending broken parts keeps the socket busy,
 * so no read times out); callers fall back to snapshot polling then. It never runs after [stop].
 */
class MjpegStreamPlayer(
    private val url: String,
    private val accessToken: String?,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: () -> Unit,
    private val maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
) {
    @Volatile private var running = false

    /** Set by [stop]: an error the worker already posted is dropped, as [onError] never runs after [stop]. */
    @Volatile private var cancelled = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private val frameInFlight = AtomicBoolean(false)
    private val errorSent = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = running

    /** When the last frame decoded (or the stream started): the [watchdog] ends a stream that stays without one. */
    @Volatile private var lastGoodFrame = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (android.os.SystemClock.elapsedRealtime() - lastGoodFrame > NO_FRAME_MS) {
                // Ends the worker's read; it reports the drop through the usual error path.
                runCatching { conn?.disconnect() }
                thread?.interrupt()
            } else {
                main.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        cancelled = false
        lastGoodFrame = android.os.SystemClock.elapsedRealtime()
        main.postDelayed(watchdog, WATCHDOG_MS)
        thread = Thread({ runLoop() }, "vitro-mjpeg-${entityLabel(url)}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        main.removeCallbacks(watchdog)
        cancelled = true
        running = false
        runCatching { conn?.disconnect() }
        conn = null
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                // HA's stream never redirects: one that does (a proxy sending it elsewhere) ends in the fallback,
                // and the camera's request never leaves Home Assistant's origin.
                instanceFollowRedirects = false
                setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg")
                accessToken?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            conn = c
            if (!running) return
            if (c.responseCode != HttpURLConnection.HTTP_OK) return
            val type = c.contentType.orEmpty().lowercase(Locale.ROOT)
            if (!type.startsWith("multipart/")) return
            c.inputStream.use { input ->
                readMjpegFrames(input) { bytes ->
                    if (!running) return@readMjpegFrames false
                    if (frameInFlight.compareAndSet(false, true)) {
                        val bmp = decode(bytes, maxWidthPx)
                        if (bmp == null) {
                            frameInFlight.set(false)
                        } else {
                            lastGoodFrame = android.os.SystemClock.elapsedRealtime()
                            main.post {
                                frameInFlight.set(false)
                                // Only a stop() drops it: a frame queued just before the stream ended normally still
                                // shows (before the fallback's error, which is posted after it).
                                if (!cancelled) onFrame(bmp)
                            }
                        }
                    }
                    running
                }
            }
        } catch (_: Exception) {
            // Falls through to the error report below; stop() disconnecting also lands here.
        } catch (_: OutOfMemoryError) {
            // A frame that still didn't fit: report it like any other drop.
        } finally {
            runCatching { conn?.disconnect() }
            conn = null
            if (running && errorSent.compareAndSet(false, true)) {
                running = false
                main.post { if (!cancelled) onError() }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_WIDTH_PX = 720
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000

        /** How long a stream may go without a frame that decodes, first one included, before it counts as dropped. */
        const val NO_FRAME_MS = 15_000L
        private const val WATCHDOG_MS = 2_500L

        /** Decoded pixels a frame may take (1280 x 720), whatever its width: a tall JPEG is sampled down too. */
        const val MAX_DECODED_PIXELS = 1280L * 720L

        /**
         * HA's stream address for [entityId] on [haBase]. A `token` on the entity's `entity_picture` (the signed
         * camera access token) is carried over, so the stream also works without a bearer header. Null without a base.
         */
        fun streamUrl(haBase: String, entityId: String, entityPicture: String?): String? {
            val base = haBase.trimEnd('/')
            if (base.isEmpty() || entityId.isBlank()) return null
            val token = entityPicture?.let(::tokenOf)
            val path = "$base/api/camera_proxy_stream/${Uri.encode(entityId)}"
            return if (token != null) "$path?token=${Uri.encode(token)}" else path
        }

        internal fun tokenOf(entityPicture: String): String? {
            val query = entityPicture.substringAfter('?', "")
            if (query.isEmpty()) return null
            return query.split('&').firstNotNullOfOrNull { part ->
                if (part.startsWith("token=")) Uri.decode(part.removePrefix("token=")).ifEmpty { null } else null
            }
        }

        private fun entityLabel(url: String): String =
            url.substringAfter("/api/camera_proxy_stream/", "camera").substringBefore('?')

        /**
         * Decodes [bytes] as RGB_565, halving until it's at most [maxWidthPx] wide and [MAX_DECODED_PIXELS] in all
         * (the 2 MiB part cap bounds the bytes, not the pixels: a 65535 px tall JPEG fits in it).
         */
        fun decode(bytes: ByteArray, maxWidthPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / sample > maxWidthPx.coerceAtLeast(1) || (w / sample).toLong() * (h / sample) > MAX_DECODED_PIXELS) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}

/**
 * Reads JPEG parts from a `multipart/x-mixed-replace` body and hands each one to [onFrame], until the stream ends or
 * [onFrame] returns false. A part with a `Content-Length` header is read by length; a part without one (or a bare
 * run of JPEGs) is cut from its SOI (`FF D8`) to its EOI (`FF D9`). Parts bigger than [maxBytes] are skipped
 * without being buffered. Returns the number of frames delivered.
 */
internal fun readMjpegFrames(input: InputStream, maxBytes: Int = MAX_FRAME_BYTES, onFrame: (ByteArray) -> Boolean): Int {
    val inp = if (input is BufferedInputStream) input else BufferedInputStream(input, 16 * 1024)
    var delivered = 0
    var contentLength = -1
    var inHeaders = false
    while (true) {
        inp.mark(2)
        val b0 = inp.read()
        val b1 = if (b0 == -1) -1 else inp.read()
        inp.reset()
        if (b0 == -1) return delivered
        if (b0 == 0xFF && b1 == 0xD8) {
            val frame = readSoiToEoi(inp, maxBytes) ?: return delivered
            contentLength = -1
            inHeaders = false
            if (frame.isNotEmpty()) {
                delivered++
                if (!onFrame(frame)) return delivered
            }
            continue
        }
        val line = readLine(inp) ?: return delivered
        when {
            line.isEmpty() && inHeaders -> {
                inHeaders = false
                if (contentLength >= 0) {
                    val len = contentLength
                    contentLength = -1
                    if (len > maxBytes) {
                        if (!skipFully(inp, len.toLong())) return delivered
                        continue
                    }
                    val frame = ByteArray(len)
                    if (!readFully(inp, frame)) return delivered
                    delivered++
                    if (!onFrame(frame)) return delivered
                } else if (!skipToSoi(inp)) {
                    return delivered
                }
            }
            line.isEmpty() -> Unit
            line.startsWith("--") -> { inHeaders = true; contentLength = -1 }
            ':' in line -> {
                inHeaders = true
                val name = line.substringBefore(':').trim().lowercase(Locale.ROOT)
                if (name == "content-length") contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
    }
}

private const val MAX_HEADER_LINE = 1024

/** One header line without its CR/LF, or null at end of stream. Overlong lines are cut at [MAX_HEADER_LINE]. */
private fun readLine(inp: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val b = inp.read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        if (sb.length < MAX_HEADER_LINE) sb.append(b.toChar())
    }
}

/** Moves [inp] to the next SOI marker (`FF D8`) without consuming it. False at end of stream. */
private fun skipToSoi(inp: InputStream): Boolean {
    while (true) {
        inp.mark(2)
        val b0 = inp.read()
        if (b0 == -1) return false
        if (b0 != 0xFF) continue
        val b1 = inp.read()
        inp.reset()
        if (b1 == 0xD8) return true
        if (b1 == -1) return false
        inp.read() // step past this FF only; the next byte may start the marker
    }
}

/**
 * Reads one JPEG starting at SOI through EOI. Empty array if it was larger than [maxBytes]; null at end of stream.
 * It walks the JPEG's segments by their lengths, so an EOI inside one (an Exif thumbnail in APP1) doesn't end the
 * frame; only entropy-coded scan data is scanned for markers. Bytes that aren't segments fall back to a plain scan.
 */
private fun readSoiToEoi(inp: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream(64 * 1024)
    var tooBig = false
    fun read(): Int {
        val b = inp.read()
        if (b != -1 && !tooBig) {
            if (out.size() >= maxBytes) {
                tooBig = true
                out.reset()
            } else {
                out.write(b)
            }
        }
        return b
    }
    fun done() = if (tooBig) ByteArray(0) else out.toByteArray()
    if (read() == -1 || read() == -1) return null // SOI
    var code = -1 // a marker code already read, else -1
    while (true) {
        if (code == -1) {
            val ff = read()
            if (ff == -1) return null
            if (ff != 0xFF) {
                // Not a segment where one belongs: find the EOI the plain way.
                var prev = ff
                while (true) {
                    val x = read()
                    if (x == -1) return null
                    if (prev == 0xFF && x == 0xD9) return done()
                    prev = x
                }
            }
            code = read()
            while (code == 0xFF) code = read() // fill bytes
            if (code == -1) return null
        }
        val c = code
        code = -1
        if (c == 0xD9) return done()
        if (c in 0xD0..0xD7 || c == 0x01) continue // no length
        val hi = read()
        val lo = read()
        if (hi == -1 || lo == -1) return null
        for (i in 0 until ((hi shl 8) or lo) - 2) if (read() == -1) return null
        if (c == 0xDA) {
            // Scan data: FF 00 is a stuffed byte and FF D0..D7 a restart; any other FF xx is the next marker.
            var prev = -1
            while (true) {
                val x = read()
                if (x == -1) return null
                if (prev == 0xFF && x != 0x00 && x != 0xFF && x !in 0xD0..0xD7) {
                    code = x
                    break
                }
                prev = x
            }
        }
    }
}

private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
        val n = inp.read(buf, off, buf.size - off)
        if (n < 0) return false
        off += n
    }
    return true
}

private fun skipFully(inp: InputStream, count: Long): Boolean {
    var left = count
    val scratch = ByteArray(8 * 1024)
    while (left > 0) {
        val n = inp.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
        if (n < 0) return false
        left -= n
    }
    return true
}
