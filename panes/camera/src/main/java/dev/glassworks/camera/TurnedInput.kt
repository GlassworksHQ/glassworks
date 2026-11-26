/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The stream's picture turned upright on the GPU: the camera draws [width] x [height] into [surface], and each frame
 * is drawn into [output] (the encoder's input) turned [turn] degrees clockwise, as [stillRotation] turns a still.
 * Every stream goes through it, a turn of 0 included, so the encoder has one input path on every camera.
 *
 * Runs on its own thread. Throws from the constructor when EGL or the shader can't be set up; [release] stops drawing
 * and frees everything but [output], which belongs to the encoder.
 */
class TurnedInput(private val output: Surface, width: Int, height: Int, private val turn: Int) {
    private val thread = HandlerThread("glassworks-turn").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var texture: SurfaceTexture
    private var cameraSurface: Surface? = null
    private var program = 0
    private var textureId = 0
    private val st = FloatArray(16)
    private val tex = FloatArray(16)
    private val turnMatrix = textureTurn(turn)
    private val out = turnedSize(width, height, turn)
    @Volatile private var released = false

    /** Where the camera draws. */
    val surface: Surface

    init {
        var failure: Throwable? = null
        val ready = CountDownLatch(1)
        handler.post {
            try {
                setUp(width, height)
                cameraSurface = Surface(texture)
            } catch (e: Throwable) {
                failure = e
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(SETUP_WAIT_MS, TimeUnit.MILLISECONDS)) failure = IllegalStateException("GL setup timed out")
        failure?.let { release(); throw it }
        surface = cameraSurface!!
    }

    private fun setUp(width: Int, height: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = program(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ draw() }, handler)
        }
    }

    /** One camera frame into the encoder, turned, stamped with the camera's time. */
    private fun draw() {
        if (released) return
        texture.updateTexImage()
        texture.getTransformMatrix(st)
        Matrix.multiplyMM(tex, 0, st, 0, turnMatrix, 0)
        GLES20.glViewport(0, 0, out.first, out.second)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, tex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.timestamp)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    /** Stops drawing and frees the GL side, waiting for its thread. Take [surface] out of the camera session first. */
    fun release() {
        released = true
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                cameraSurface?.release()
                if (::texture.isInitialized) texture.release()
                if (::display.isInitialized) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (::eglSurface.isInitialized) EGL14.eglDestroySurface(display, eglSurface)
                    if (::context.isInitialized) EGL14.eglDestroyContext(display, context)
                    // No eglTerminate: the display is the process's, and other GL in it may still use it.
                    EGL14.eglReleaseThread()
                }
            } finally {
                done.countDown()
            }
        }
        var interrupted = false
        if (posted) try { done.await(SETUP_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted = true }
        thread.quitSafely()
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val SETUP_WAIT_MS = 2_000L

        /** A quad over the whole output; texture coordinates come from the position. */
        private val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        private const val VERTEX = """
            attribute vec2 aPosition;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTex = (uTex * vec4((aPosition + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}

/** The picture's size once turned [turn] degrees: a quarter turn swaps [width] and [height]. */
fun turnedSize(width: Int, height: Int, turn: Int): Pair<Int, Int> =
    if (turn % 180 == 0) width to height else height to width

/**
 * The texture matrix that turns a picture [turn] degrees clockwise: an output point (x, y), both 0 to 1 from the bottom
 * left, reads the source at this matrix times it. Clockwise on screen is counter-clockwise in texture space, about the
 * middle.
 */
fun textureTurn(turn: Int): FloatArray {
    val m = FloatArray(16)
    Matrix.setIdentityM(m, 0)
    Matrix.translateM(m, 0, 0.5f, 0.5f, 0f)
    Matrix.rotateM(m, 0, turn.toFloat(), 0f, 0f, 1f)
    Matrix.translateM(m, 0, -0.5f, -0.5f, 0f)
    return m
}
