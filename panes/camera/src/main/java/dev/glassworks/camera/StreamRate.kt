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
 * listed size nearest it in area among those up to 1920x1080. A camera that lists nothing that small gets the
 * smallest size it lists, which can be larger. A LEGACY camera (Android 6's, on the
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

/** What the stream can be on this camera: H.264 at [Size], or no stream, for [Refused.why]. */
sealed interface StreamFit {
    data class Size(val width: Int, val height: Int) : StreamFit {
        override fun toString() = "${width}x$height"
    }

    /** [why] is shown on the camera's page as is. */
    data class Refused(val why: String) : StreamFit
}

/**
 * The stream at the size the hub reads frames at ([reader], from [yuvSizeFor]): the camera draws both at one size,
 * which a LEGACY camera's shim takes. Refused when the camera doesn't list that size for an encoder's surface
 * ([surfaces], null when it doesn't say) or the H.264 encoder can't take it ([encodes]).
 */
fun streamFitFor(reader: Pair<Int, Int>, surfaces: List<Pair<Int, Int>>?, encodes: (Int, Int) -> Boolean): StreamFit {
    val (w, h) = reader
    return when {
        surfaces != null && reader !in surfaces -> StreamFit.Refused("The camera can't send its $w × $h picture to a video encoder")
        !encodes(w, h) -> StreamFit.Refused("The video encoder can't take the camera's $w × $h picture")
        else -> StreamFit.Size(w, h)
    }
}
