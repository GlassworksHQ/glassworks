/* SPDX-License-Identifier: MIT AND BSD-3-Clause
 *
 * TextureFrameDrawer.drawFrame and toGlMatrix reproduce the steps of libwebrtc M150's VideoFrameDrawer
 * (drawFrame, calculateTransformedRenderSize, drawTexture) and RendererCommon
 * (convertMatrixFromAndroidGraphicsMatrix), sdk/android/api/org/webrtc in https://webrtc.googlesource.com/src
 * (branch-heads/7871), Copyright (c) 2011, The WebRTC project authors, BSD-3-Clause, with WebRTC's additional IP
 * rights grant. Changes: rewritten in Kotlin to work into matrices and arrays kept across frames instead of new ones
 * per frame. The rest of the file is MIT. The texts are in libs/ha-webrtc/NOTICE, THIRD-PARTY-NOTICES.md and
 * LICENSES/. */

package dev.glassworks.hawebrtc

import android.graphics.Matrix
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoFrameDrawer
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * libwebrtc's [VideoFrameDrawer], drawing a texture frame (what the hardware decoder gives) with nothing allocated.
 *
 * libwebrtc's `drawTexture` makes a `Matrix` for every frame it draws (an object with a native peer of its own and a
 * cleaner, registered with the runtime as a native allocation), and two float arrays to hand the drawer its matrix.
 * Four cameras at 15 frames a second each is 60 of each a second, all garbage at once. Here the same matrices are
 * worked out in place, in the same steps (`VideoFrameDrawer.drawFrame` and `drawTexture` of libwebrtc M150, so the
 * picture is drawn exactly as before), into matrices and arrays this drawer keeps. A drawer copies the matrix it gets
 * as it draws (`glUniformMatrix4fv`), so the array can be the same every frame. Any other frame (an I420 one, from a
 * software decoder) goes to libwebrtc's own path.
 *
 * The render thread only, as libwebrtc's.
 */
internal class TextureFrameDrawer : VideoFrameDrawer() {
    private val renderMatrix = Matrix()
    private val finalMatrix = Matrix()
    private val values = FloatArray(9)
    private val glMatrix = FloatArray(16)
    private val dstPoints = FloatArray(6)

    override fun drawFrame(
        frame: VideoFrame,
        drawer: RendererCommon.GlDrawer,
        additionalRenderMatrix: Matrix?,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val buffer = frame.buffer as? VideoFrame.TextureBuffer
        if (buffer == null) {
            super.drawFrame(frame, drawer, additionalRenderMatrix, viewportX, viewportY, viewportWidth, viewportHeight)
            return
        }
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        var renderWidth = width
        var renderHeight = height
        if (additionalRenderMatrix != null) {
            // calculateTransformedRenderSize: the frame's size once the extra matrix has scaled it.
            additionalRenderMatrix.mapPoints(dstPoints, SRC_POINTS)
            for (i in 0 until 3) {
                dstPoints[i * 2] *= width
                dstPoints[i * 2 + 1] *= height
            }
            renderWidth = distance(dstPoints[0], dstPoints[1], dstPoints[2], dstPoints[3])
            renderHeight = distance(dstPoints[0], dstPoints[1], dstPoints[4], dstPoints[5])
        }
        // libwebrtc logs this and draws nothing; the log (a string built per frame) is left out.
        if (renderWidth <= 0 || renderHeight <= 0) return
        renderMatrix.reset()
        renderMatrix.preTranslate(0.5f, 0.5f)
        renderMatrix.preRotate(frame.rotation.toFloat())
        renderMatrix.preTranslate(-0.5f, -0.5f)
        if (additionalRenderMatrix != null) renderMatrix.preConcat(additionalRenderMatrix)
        finalMatrix.set(buffer.transformMatrix)
        finalMatrix.preConcat(renderMatrix)
        toGlMatrix(finalMatrix, values, glMatrix)
        when (buffer.type) {
            VideoFrame.TextureBuffer.Type.OES ->
                drawer.drawOes(buffer.textureId, glMatrix, renderWidth, renderHeight, viewportX, viewportY, viewportWidth, viewportHeight)
            VideoFrame.TextureBuffer.Type.RGB ->
                drawer.drawRgb(buffer.textureId, glMatrix, renderWidth, renderHeight, viewportX, viewportY, viewportWidth, viewportHeight)
            else -> throw RuntimeException("Unknown texture type.")
        }
    }

    internal companion object {
        /** `VideoFrameDrawer.srcPoints`: the corners the extra matrix maps to find the drawn size. */
        private val SRC_POINTS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

        private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Int =
            hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).roundToLong().toInt()

        /**
         * `RendererCommon.convertMatrixFromAndroidGraphicsMatrix`, into [out] (16) through [values] (9): the 3 × 3
         * matrix as OpenGL's 4 × 4, column-major.
         */
        fun toGlMatrix(matrix: Matrix, values: FloatArray, out: FloatArray) {
            matrix.getValues(values)
            out[0] = values[0]; out[1] = values[3]; out[2] = 0f; out[3] = values[6]
            out[4] = values[1]; out[5] = values[4]; out[6] = 0f; out[7] = values[7]
            out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
            out[12] = values[2]; out[13] = values[5]; out[14] = 0f; out[15] = values[8]
        }
    }
}
