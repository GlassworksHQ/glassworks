/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import java.nio.ByteBuffer

/**
 * A YUV_420_888 image's three planes as NV21 (the Y plane, then V and U interleaved), which
 * `YuvImage` compresses to JPEG. The planes keep their own strides: rows padded past [width], and
 * chroma either planar (pixel stride 1) or already interleaved (pixel stride 2).
 */
fun yuvToNv21(
    width: Int, height: Int,
    y: ByteBuffer, yRowStride: Int,
    u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
): ByteArray {
    val out = ByteArray(width * height * 3 / 2)
    var o = 0
    for (row in 0 until height) {
        val at = row * yRowStride
        for (x in 0 until width) out[o++] = y.get(at + x)
    }
    for (row in 0 until height / 2) {
        val at = row * uvRowStride
        for (x in 0 until width / 2) {
            val i = at + x * uvPixelStride
            out[o++] = v.get(i)
            out[o++] = u.get(i)
        }
    }
    return out
}

/**
 * How far to turn a still so it stands upright: the sensor's mounting [sensorDegrees], the screen's
 * [displayDegrees] (`Display.getRotation` as degrees), and a front camera turning the other way.
 */
fun stillRotation(sensorDegrees: Int, displayDegrees: Int, front: Boolean): Int =
    if (front) (sensorDegrees + displayDegrees) % 360 else (sensorDegrees - displayDegrees + 360) % 360

/**
 * Whether a YUV_420_888 image is blank: a camera session that went wrong, not a dark room. On the Mirror's stock
 * Android 6 camera about one session in three starts with frames whose three planes are all zero, which Home Assistant
 * shows as solid green with a dark noisy strip. A dark room still has sensor noise in Y (the Mirror's dark scene had a
 * Y mean of 4 to 5 and no row all zero) and chroma near 128, so it is never blank.
 *
 * Samples [BLANK_ROWS] rows of Y and half as many of each chroma plane, [BLANK_COLUMNS] points a row: blank when at
 * least [BLANK_SHARE] of the sampled rows are dead in Y and in both chroma planes. A dead row reads zero at every
 * point, or the green that zero becomes once a LEGACY camera has drawn it ([GREEN_Y], [GREEN_U], [GREEN_V]). No
 * picture has such a row (black still has chroma near 128), so a quarter of them is already a session gone wrong,
 * however much streaky picture the rest holds: on the Mirror (2026-11-25) a bad session's frames were two thirds dead
 * under a streaky third, in the stream and in stills alike. A few thousand reads a frame. Absolute reads: the
 * buffers' positions don't move.
 */
fun blankYuv(
    width: Int, height: Int,
    y: ByteBuffer, yRowStride: Int,
    u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
): Boolean {
    if (width <= 0 || height <= 0) return false
    if (!mostRowsDead(y, width, height, yRowStride, 1, BLANK_ROWS, GREEN_Y)) return false
    val rows = maxOf(1, BLANK_ROWS / 2)
    return mostRowsDead(u, width / 2, height / 2, uvRowStride, uvPixelStride, rows, GREEN_U) &&
        mostRowsDead(v, width / 2, height / 2, uvRowStride, uvPixelStride, rows, GREEN_V)
}

/**
 * Whether at least [BLANK_SHARE] of [rows] evenly spread rows of a plane are dead at [BLANK_COLUMNS] evenly spread
 * points: every point zero, or within [GREEN_SLACK] of [green], this plane's value of a drawn zero.
 */
private fun mostRowsDead(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rows: Int, green: Int): Boolean {
    if (width <= 0 || height <= 0) return true
    val sampled = minOf(rows, height)
    val columns = minOf(BLANK_COLUMNS, width)
    var dead = 0
    for (r in 0 until sampled) {
        val row = ((2L * r + 1) * height / (2L * sampled)).toInt()
        var allDead = true
        for (c in 0 until columns) {
            val x = ((2L * c + 1) * width / (2L * columns)).toInt()
            val i = row * rowStride + x * pixelStride
            if (i >= plane.limit()) { allDead = false; break }
            val b = plane.get(i).toInt() and 0xFF
            if (b != 0 && Math.abs(b - green) > GREEN_SLACK) { allDead = false; break }
        }
        if (allDead) dead++
    }
    return dead >= Math.ceil(sampled * BLANK_SHARE).toInt()
}

/** Rows of Y [blankYuv] samples; each chroma plane gets half. */
internal const val BLANK_ROWS = 24

/** Points [blankYuv] samples in each row. */
internal const val BLANK_COLUMNS = 64

/**
 * The share of sampled rows that must be dead for a plane to count as blank. A quarter: the Mirror's bad sessions
 * had two thirds of their rows dead, which the first threshold (90%) let through.
 */
internal const val BLANK_SHARE = 0.25

/**
 * A zero frame as a LEGACY camera hands it to an `ImageReader`. Android 6's camera shim draws each preview frame
 * through the GPU (as RGB) and converts it back to YUV for the reader and the encoder, so zero YUV comes out as RGB
 * (0, 135, 0) and then, in full range, Y 79, U 83, V 71: what the Mirror's reader frames read on 2026-11-25, while
 * an exact zero test let them through. Real pictures don't hold that saturated green flat across a whole row.
 */
internal const val GREEN_Y = 79
internal const val GREEN_U = 83
internal const val GREEN_V = 71

/** How far a point may be from [GREEN_Y], [GREEN_U] or [GREEN_V] and still read as a drawn zero. */
internal const val GREEN_SLACK = 4
