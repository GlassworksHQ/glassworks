/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

/**
 * The target-rate range ([ranges]: the camera's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`) to ask for
 * while streaming: one whose top is the encoder's [want], with the highest floor, so the rate stays
 * steady. Null when the camera offers none: it then runs at its own rate.
 */
fun fpsRangeFor(ranges: List<IntRange>?, want: Int): IntRange? =
    ranges?.filter { it.last == want }?.maxByOrNull { it.first }

/** The rate the stream's SDP announces (`a=framerate`): the top of the range asked for, or 0 for none. */
fun announcedFps(range: IntRange?): Int = range?.last ?: 0

/**
 * The size the hub reads frames at: [want] when the camera lists it for YUV ([sizes], width to height), else the
 * listed size nearest it in area, up to 1920x1080, else the smallest it lists. A LEGACY camera (Android 6's, on the
 * Camera1 shim) refuses a session at a size it doesn't list. With no list, [want].
 */
fun yuvSizeFor(sizes: List<Pair<Int, Int>>?, want: Pair<Int, Int>): Pair<Int, Int> {
    if (sizes.isNullOrEmpty() || want in sizes) return want
    val area = want.first.toLong() * want.second
    return sizes.filter { it.first * it.second <= 1920 * 1080 }
        .minByOrNull { Math.abs(it.first.toLong() * it.second - area) } ?: sizes.minBy { it.first.toLong() * it.second }
}

/**
 * While only presence reads frames from a LEGACY camera: the range with the lowest top that still covers presence's
 * [want], with the lowest floor among those, so the camera may slow down in the dark. Android's Camera1 shim copies
 * every preview frame into the hub's reader on the CPU, so fewer frames cost less. Null: the camera's own rate.
 */
fun presenceRangeFor(ranges: List<IntRange>?, want: Int): IntRange? =
    ranges?.filter { it.last >= want }?.minWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })
