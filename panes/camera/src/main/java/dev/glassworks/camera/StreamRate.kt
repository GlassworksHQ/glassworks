/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

/**
 * The target-rate range to ask any camera for, for presence and the stream alike: the steadiest one ([ranges]: the
 * camera's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, highest floor) at the camera's top rate. Null when the camera
 * lists none: it then runs at its own rate.
 *
 * Not a lower rate to save work, even one the camera lists: on the Mirror's stock Android 6 camera 15–15 broke every
 * frame, a third of streaky picture over two thirds of the green a zero frame is drawn as, in the reader and the stream
 * alike, while 7–30, 24–24 and 30–30 gave clean pictures (2026-11-25, the camera spike, fresh sessions). The top rate is
 * also what the Mirror's own request templates ask for (30–30). It costs little: the reader takes what it can copy
 * (about 9 fps on the Mirror whatever was asked for) and a stream gives it only some frames ([readerEvery]).
 */
fun cameraRangeFor(ranges: List<IntRange>?): IntRange? {
    val top = ranges?.maxOfOrNull { it.last } ?: return null
    return ranges.filter { it.last == top }.maxByOrNull { it.first }
}

/** The rate the stream's SDP announces (`a=framerate`): the top of the range asked for, or 0 for none. */
fun announcedFps(range: IntRange?): Int = range?.last ?: 0

/**
 * The size the hub reads frames at: [want] when the camera lists it for YUV ([sizes], width to height), else the
 * listed size nearest it in area among those up to [maxPixels] (the frame's cap, [CameraHub.MAX_PIXELS] by default).
 * A camera that lists nothing that small gets the smallest size it lists, which can be larger. With no list, [want].
 *
 * A LEGACY camera (Android 6's Camera1 shim) refuses a session at a size it doesn't list, and its [sensor] aspect
 * ([legacyAspect]) decides what the camera really runs at: the shim's preview is the smallest preview size with the
 * sensor's aspect that covers the largest output, else its largest preview size (AOSP 6.0.1,
 * `RequestThreadManager.configureOutputs`). The Mirror's 5 MP 4:3 camera ran its preview at 1920x1080 for a 1280x720
 * reader (#679). So on one, a size with the sensor's aspect up to [maxPixels] that the stream can use ([streams]) comes
 * first: the preview is then that size. With none, the rule above, so a camera that streamed before still does.
 */
fun yuvSizeFor(
    sizes: List<Pair<Int, Int>>?,
    want: Pair<Int, Int>,
    maxPixels: Int = CameraHub.MAX_PIXELS,
    sensor: Pair<Int, Int>? = null,
    streams: (Pair<Int, Int>) -> Boolean = { true },
): Pair<Int, Int> {
    if (sizes.isNullOrEmpty()) return want
    val area = want.first.toLong() * want.second
    fun nearest(of: List<Pair<Int, Int>>) =
        of.filter { it.first.toLong() * it.second <= maxPixels }.minByOrNull { Math.abs(it.first.toLong() * it.second - area) }
    sensor?.let { s -> nearest(sizes.filter { legacyAspect(it, s) && streams(it) })?.let { return it } }
    if (want in sizes && area <= maxPixels) return want
    return nearest(sizes) ?: sizes.minBy { it.first.toLong() * it.second }
}

/** The same shape as [sensor] within the LEGACY shim's tolerance (0.01, AOSP 6.0.1's `ASPECT_RATIO_TOLERANCE`). */
fun legacyAspect(size: Pair<Int, Int>, sensor: Pair<Int, Int>): Boolean =
    Math.abs(size.first.toFloat() / size.second - sensor.first.toFloat() / sensor.second) < 0.01f

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
