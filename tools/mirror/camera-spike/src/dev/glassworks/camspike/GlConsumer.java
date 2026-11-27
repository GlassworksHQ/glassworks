// SPDX-License-Identifier: MIT
// Part of the Mirror camera spike (tools/mirror/camera-spike): see its README for what it touches.
package dev.glassworks.camspike;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Consumes a SurfaceTexture on its own EGL pbuffer thread: updateTexImage on every frame, nothing drawn. [close] frees
 * the texture, the EGL objects and the thread, so repeated runs in one process don't pile up.
 */
class GlConsumer implements AutoCloseable {
    private final HandlerThread thread = new HandlerThread("gl");
    private final Handler h;
    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface pbuffer;
    /** Null when setup failed (the log says why). */
    SurfaceTexture texture;

    private GlConsumer() { thread.start(); h = new Handler(thread.getLooper()); }

    static GlConsumer start(final Spike s) throws Exception {
        final GlConsumer g = new GlConsumer();
        final CountDownLatch l = new CountDownLatch(1);
        g.h.post(new Runnable() { public void run() {
            try {
                EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] v = new int[2]; EGL14.eglInitialize(d, v, 0, v, 1);
                g.display = d;
                EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
                EGL14.eglChooseConfig(d, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE}, 0, cfg, 0, 1, n, 0);
                g.context = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                g.pbuffer = EGL14.eglCreatePbufferSurface(d, cfg[0], new int[]{EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE}, 0);
                EGL14.eglMakeCurrent(d, g.pbuffer, g.pbuffer, g.context);
                int[] tex = new int[1]; GLES20.glGenTextures(1, tex, 0);
                final SurfaceTexture st = new SurfaceTexture(tex[0]);
                final int[] k = {0};
                st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() { public void onFrameAvailable(SurfaceTexture x) {
                    try { x.updateTexImage(); if (++k[0] % 60 == 1) s.p("gl consumed " + k[0]); } catch (Throwable e) { s.p("gl " + e); }
                }}, g.h);
                g.texture = st;
                s.p("gl consumer ready, renderer=" + GLES20.glGetString(GLES20.GL_RENDERER));
            } catch (Throwable e) { s.p("gl setup " + e); }
            l.countDown();
        }});
        l.await();
        return g;
    }

    /** Frees everything on the GL thread, then stops it. The camera must no longer draw into [texture]. */
    @Override public void close() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        h.post(new Runnable() { public void run() {
            try {
                if (texture != null) texture.release();
                if (display != null) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (pbuffer != null) EGL14.eglDestroySurface(display, pbuffer);
                    if (context != null) EGL14.eglDestroyContext(display, context);
                    EGL14.eglReleaseThread();
                }
            } finally {
                done.countDown();
            }
        }});
        done.await(5, TimeUnit.SECONDS);
        thread.quitSafely();
    }
}
