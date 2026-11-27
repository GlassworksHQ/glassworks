// SPDX-License-Identifier: MIT
// Part of the Mirror camera spike (tools/mirror/camera-spike): see its README for what it touches.
package dev.glassworks.camspike;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceHolder;

/** A SurfaceHolder around any Surface, so Camera.setPreviewDisplay can render into an ImageReader. */
class Holder implements SurfaceHolder {
    final Surface s; Holder(Surface s) { this.s = s; }
    public Surface getSurface() { return s; }
    public void addCallback(Callback c) {} public void removeCallback(Callback c) {}
    public boolean isCreating() { return false; } public void setType(int t) {}
    public void setFixedSize(int w, int h) {} public void setSizeFromLayout() {} public void setFormat(int f) {}
    public void setKeepScreenOn(boolean b) {} public Canvas lockCanvas() { return null; } public Canvas lockCanvas(Rect r) { return null; }
    public void unlockCanvasAndPost(Canvas c) {} public Rect getSurfaceFrame() { return new Rect(0, 0, 1280, 720); }
}
