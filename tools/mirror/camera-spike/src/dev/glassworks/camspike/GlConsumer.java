// SPDX-License-Identifier: MIT
// Part of the Mirror camera spike (tools/mirror/camera-spike): see its README for what it touches.
package dev.glassworks.camspike;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.CountDownLatch;

/** Consumes a SurfaceTexture on its own EGL pbuffer thread: updateTexImage on every frame, nothing drawn. */
class GlConsumer {
    static SurfaceTexture start(final Spike s) throws Exception {
        HandlerThread t = new HandlerThread("gl"); t.start();
        final Handler h = new Handler(t.getLooper());
        final SurfaceTexture[] out = new SurfaceTexture[1];
        final CountDownLatch l = new CountDownLatch(1);
        h.post(new Runnable() { public void run() {
            try {
                EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] v = new int[2]; EGL14.eglInitialize(d, v, 0, v, 1);
                EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
                EGL14.eglChooseConfig(d, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE}, 0, cfg, 0, 1, n, 0);
                EGLContext ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                EGLSurface pb = EGL14.eglCreatePbufferSurface(d, cfg[0], new int[]{EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE}, 0);
                EGL14.eglMakeCurrent(d, pb, pb, ctx);
                int[] tex = new int[1]; GLES20.glGenTextures(1, tex, 0);
                final SurfaceTexture st = new SurfaceTexture(tex[0]);
                final int[] k = {0};
                st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() { public void onFrameAvailable(SurfaceTexture x) {
                    try { x.updateTexImage(); if (++k[0] % 60 == 1) s.p("gl consumed " + k[0]); } catch (Throwable e) { s.p("gl " + e); }
                }}, h);
                out[0] = st;
                s.p("gl consumer ready, renderer=" + GLES20.glGetString(GLES20.GL_RENDERER));
            } catch (Throwable e) { s.p("gl setup " + e); }
            l.countDown();
        }});
        l.await();
        return out[0];
    }
}
