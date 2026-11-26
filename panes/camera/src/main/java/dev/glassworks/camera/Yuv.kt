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
 * least [BLANK_SHARE] of the sampled rows are all zero in Y and in both chroma planes, so a noisy strip of a few rows
 * doesn't save a bad frame. A few thousand reads a frame. Absolute reads: the buffers' positions don't move.
 */
fun blankYuv(
    width: Int, height: Int,
    y: ByteBuffer, yRowStride: Int,
    u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
): Boolean {
    if (width <= 0 || height <= 0) return false
    if (!mostRowsZero(y, width, height, yRowStride, 1, BLANK_ROWS)) return false
    val rows = maxOf(1, BLANK_ROWS / 2)
    return mostRowsZero(u, width / 2, height / 2, uvRowStride, uvPixelStride, rows) &&
        mostRowsZero(v, width / 2, height / 2, uvRowStride, uvPixelStride, rows)
}

/** Whether at least [BLANK_SHARE] of [rows] evenly spread rows of a plane are zero at [BLANK_COLUMNS] evenly spread points. */
private fun mostRowsZero(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rows: Int): Boolean {
    if (width <= 0 || height <= 0) return true
    val sampled = minOf(rows, height)
    val columns = minOf(BLANK_COLUMNS, width)
    var zero = 0
    for (r in 0 until sampled) {
        val row = ((2L * r + 1) * height / (2L * sampled)).toInt()
        var allZero = true
        for (c in 0 until columns) {
            val x = ((2L * c + 1) * width / (2L * columns)).toInt()
            val i = row * rowStride + x * pixelStride
            if (i >= plane.limit() || plane.get(i).toInt() != 0) { allZero = false; break }
        }
        if (allZero) zero++
    }
    return zero >= Math.ceil(sampled * BLANK_SHARE).toInt()
}

/** Rows of Y [blankYuv] samples; each chroma plane gets half. */
internal const val BLANK_ROWS = 24

/** Points [blankYuv] samples in each row. */
internal const val BLANK_COLUMNS = 64

/** The share of sampled rows that must be all zero for a plane to count as blank. */
internal const val BLANK_SHARE = 0.9
