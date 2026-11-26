// SPDX-License-Identifier: MIT
// Part of the Mirror camera spike (tools/mirror/camera-spike): see its README for what it touches.
package dev.glassworks.camspike;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway camera spike. am start -n dev.glassworks.camspike/.Spike --es test c1|c1enc|c2 [--es c2out both|reader|enc]
 * [--es run <id>]: with a run id, the run's done file holds that id, and a launch it ignores writes ignored-<id> in the
 * files folder itself, outside every run's folder, so no run's cleanup deletes it.
 */
public class Spike extends Activity {
    static final String TAG = "CAMSPIKE";
    static final int W = 1280, H = 720;
    static final String CODEC = "OMX.qcom.video.encoder.avc";
    File dir;
    PrintWriter log;
    volatile boolean busy;
    volatile String sets, previewTarget;

    @Override protected void onCreate(Bundle b) { super.onCreate(b); handle(getIntent()); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    void handle(final Intent i) {
        final String test = i.getStringExtra("test");
        final String run = i.getStringExtra("run");
        final String name = i.getStringExtra("name") != null ? i.getStringExtra("name")
            : test == null ? null : test + (i.getStringExtra("c2out") != null ? "-" + i.getStringExtra("c2out") : "");
        if (test == null || busy) {
            Log.i(TAG, "ignored test=" + test + " busy=" + busy);
            // Tell run.sh at once, so it fails instead of waiting for, or taking, another run's result.
            if (run != null) try { new FileOutputStream(new File(getExternalFilesDir(null), "ignored-" + run)).close(); } catch (Exception e) {}
            return;
        }
        busy = true;
        new Thread(new Runnable() { public void run() {
            PrintWriter w = null;
            try {
                // Setup fails like the test does: the run still says DONE, and the next launch isn't blocked.
                dir = new File(getExternalFilesDir(null), name);
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old == null) throw new java.io.IOException("can't use " + dir + " for the results");
                for (File f : old) f.delete();
                w = new PrintWriter(new FileOutputStream(new File(dir, "log.txt")), true);
                log = w;
                Thread.sleep(1500);
                sets = i.getStringExtra("set");
                previewTarget = i.getStringExtra("disp");
                if (test.equals("c1")) testC1(false, i.getStringExtra("fmt"));
                else if (test.equals("c1enc")) testC1(true, i.getStringExtra("fmt"));
                else if (test.equals("c2")) testC2(i.getStringExtra("c2out") == null ? "both" : i.getStringExtra("c2out"), i.getStringExtra("fps"));
                else if (test.equals("info")) info();
            } catch (Throwable t) {
                p("FAILED: " + Log.getStackTraceString(t));
            } finally {
                try {
                    p("DONE");
                    log = null;
                    if (w != null) w.close();
                    try (FileOutputStream done = new FileOutputStream(new File(dir, "done"))) {
                        if (run != null) done.write(run.getBytes());
                    } catch (Exception e) {}
                } finally {
                    busy = false;
                }
            }
        }}, "spike").start();
    }

    void p(String s) { Log.i(TAG, s); PrintWriter l = log; if (l != null) l.println(s); }

    // ---------- stats ----------
    String stats(byte[] y, int w, int h, int stride) {
        long sum = 0; long zeros = 0; int zeroRows = 0;
        for (int r = 0; r < h; r++) {
            boolean allZero = true;
            int off = r * stride;
            for (int c = 0; c < w; c++) {
                int v = y[off + c] & 0xff;
                sum += v;
                if (v == 0) zeros++; else allZero = false;
            }
            if (allZero) zeroRows++;
        }
        double n = (double) w * h;
        return String.format("Ymean=%.1f zeroFrac=%.3f zeroRowFrac=%.3f", sum / n, zeros / n, zeroRows / (double) h);
    }

    void saveJpeg(byte[] nv21, String name) throws Exception {
        YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, W, H, null);
        FileOutputStream o = new FileOutputStream(new File(dir, name));
        yi.compressToJpeg(new Rect(0, 0, W, H), 85, o);
        o.close();
    }

    void info() throws Exception {
        MediaCodecInfo ci = find(CODEC);
        if (ci == null) { p("codec not found"); return; }
        MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType("video/avc");
        p("colorFormats=" + fmtList(caps.colorFormats));
        Camera cam = Camera.open(0);
        p("params=" + cam.getParameters().flatten());
        cam.release();
    }

    static MediaCodecInfo find(String name) {
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
            if (ci.getName().equals(name)) return ci;
        return null;
    }

    static String fmtList(int[] f) {
        StringBuilder sb = new StringBuilder();
        for (int x : f) sb.append(String.format("0x%x(%d) ", x, x));
        return sb.toString();
    }

    // ---------- encoder (shared) ----------
    class Enc {
        MediaCodec codec; FileOutputStream out; Thread drain; volatile boolean running = true;
        int outBufs, keys; long bytes; int stride = W, slice = H; boolean surfaceMode, writeFailed;
        Surface surface;

        Enc(boolean surfaceMode, int colorFormat) throws Exception {
            this.surfaceMode = surfaceMode;
            codec = MediaCodec.createByCodecName(CODEC);
            try {
                setUp(colorFormat);
            } catch (Exception e) {
                // A format the codec rejects: nobody gets this Enc to stop, so free what it made here.
                if (surface != null) surface.release();
                try { codec.stop(); } catch (Exception x) {}
                codec.release();
                if (out != null) try { out.close(); } catch (Exception x) {}
                throw e;
            }
        }

        private void setUp(int colorFormat) throws Exception {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            p("encoder configure " + f);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            if (surfaceMode) surface = codec.createInputSurface();
            p("encoder inputFormat after configure: " + codec.getInputFormat());
            p("encoder outputFormat after configure: " + codec.getOutputFormat());
            MediaFormat in = codec.getInputFormat();
            if (in.containsKey("stride")) stride = in.getInteger("stride");
            if (in.containsKey("slice-height")) slice = in.getInteger("slice-height");
            if (slice < H) slice = H;
            if (stride < W) stride = W;
            codec.start();
            p("encoder inputFormat after start: " + codec.getInputFormat());
            out = new FileOutputStream(new File(dir, "out.h264"));
            drain = new Thread(new Runnable() { public void run() { drainLoop(); }}, "drain");
            drain.start();
        }

        void drainLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] tmp = new byte[1 << 20];
            while (running) {
                int i;
                try { i = codec.dequeueOutputBuffer(info, 100_000); } catch (Exception e) { p("drain err " + e); break; }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { p("encoder output format " + codec.getOutputFormat()); continue; }
                if (i < 0) continue;
                ByteBuffer b = codec.getOutputBuffer(i);
                b.position(info.offset); b.limit(info.offset + info.size);
                if (tmp.length < info.size) tmp = new byte[info.size];
                b.get(tmp, 0, info.size);
                // Only what reached out.h264 counts; after a failed write the capture is incomplete, and says so.
                if (!writeFailed) {
                    try {
                        out.write(tmp, 0, info.size);
                        outBufs++; bytes += info.size;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keys++;
                    } catch (Exception e) {
                        writeFailed = true;
                        p("H.264 write failed after " + outBufs + " buffers: " + e + "; the capture is incomplete");
                    }
                }
                if (outBufs <= 3) p("out buf " + outBufs + " size=" + info.size + " flags=" + info.flags + " firstBytes=" + hex(tmp, Math.min(info.size, 24)));
                codec.releaseOutputBuffer(i, false);
            }
        }

        /** NV21 → (semi-planar, NV12 order) into codec input; returns false if no buffer free. */
        boolean feedNv21(byte[] nv21, long ptsUs, boolean swapToNv12) {
            int i = codec.dequeueInputBuffer(0);
            if (i < 0) return false;
            ByteBuffer b = codec.getInputBuffer(i);
            b.clear();
            int need = stride * slice + stride * slice / 2;
            if (b.capacity() < need) { p("input buffer too small cap=" + b.capacity() + " need=" + need); }
            // Y
            for (int r = 0; r < H; r++) { b.position(r * stride); b.put(nv21, r * W, W); }
            // UV
            int uvOff = stride * slice;
            byte[] row = new byte[W];
            for (int r = 0; r < H / 2; r++) {
                System.arraycopy(nv21, W * H + r * W, row, 0, W);
                if (swapToNv12) for (int c = 0; c < W; c += 2) { byte t = row[c]; row[c] = row[c + 1]; row[c + 1] = t; }
                b.position(uvOff + r * stride); b.put(row, 0, W);
            }
            int size = uvOff + stride * (H / 2);
            codec.queueInputBuffer(i, 0, size, ptsUs, 0);
            return true;
        }

        void stop() {
            if (!surfaceMode) { /* nothing */ } else { try { codec.signalEndOfInputStream(); } catch (Exception e) {} }
            try { Thread.sleep(300); } catch (Exception e) {}
            running = false;
            try { drain.join(2000); } catch (Exception e) {}
            try {
                try { codec.stop(); } catch (Exception e) {}
                codec.release();
            } finally {
                if (surface != null) surface.release();
                try { out.close(); } catch (Exception e) {}
            }
            p(String.format("encoder: outBufs=%d keyframes=%d bytes=%d%s", outBufs, keys, bytes, writeFailed ? " (INCOMPLETE: a write failed)" : ""));
        }
    }

    static String hex(byte[] b, int n) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i])); return sb.toString(); }

    // ---------- test 1 / 2: Camera1 ----------
    void testC1(final boolean encode, String fmtPref) throws Exception {
        HandlerThread ht = new HandlerThread("c1"); ht.start();
        final Handler h = new Handler(ht.getLooper());
        final Camera[] cam = new Camera[1];
        final CountDownLatch opened = new CountDownLatch(1);
        // open on the handler thread so callbacks arrive there
        h.post(new Runnable() { public void run() {
            try { cam[0] = Camera.open(0); } catch (Exception e) { p("open failed " + e); }
            opened.countDown();
        }});
        opened.await();
        if (cam[0] == null) { ht.quitSafely(); return; }
        final Camera c = cam[0];
        final Enc[] encOut = {null};
        final SurfaceTexture[] stOut = {null};
        final GlConsumer[] glOut = {null};
        final ImageReader[] irOut = {null};
        final HandlerThread[] ihtOut = {null};
        try {
            c.setErrorCallback(new Camera.ErrorCallback() { public void onError(int e, Camera cc) { p("camera error " + e); }});
            Camera.Parameters prm = c.getParameters();
            prm.setPreviewSize(W, H);
            prm.setPreviewFormat(ImageFormat.NV21);
            if (sets != null) for (String kv : sets.split(";")) { String[] a = kv.split("=", 2); prm.set(a[0], a[1]); p("set " + a[0] + "=" + a[1]); }
            c.setParameters(prm);
            prm = c.getParameters();
            p("params now: picture-size=" + prm.get("picture-size") + " video-size=" + prm.get("video-size") + " recording-hint=" + prm.get("recording-hint") + " zsl=" + prm.get("zsl"));
            p("preview " + prm.getPreviewSize().width + "x" + prm.getPreviewSize().height + " fmt=" + prm.getPreviewFormat() + " fps=" + prm.get("preview-fps-range"));
            // The probe reads 1280x720 NV21 only (its buffers, statistics and JPEGs): a `set` that changes the
            // preview's size or format fails the run here rather than give numbers read at the wrong layout.
            if (prm.getPreviewSize().width != W || prm.getPreviewSize().height != H || prm.getPreviewFormat() != ImageFormat.NV21)
                throw new IllegalStateException("the camera took a preview the probe can't read; it needs " + W + "x" + H + " NV21");
            SurfaceTexture st0 = null;
            String disp = previewTarget == null ? "st" : previewTarget;
            p("preview target: " + disp);
            if (disp.equals("gl")) {
                glOut[0] = GlConsumer.start(this);
                st0 = glOut[0].texture;
                c.setPreviewTexture(st0);
            } else if (disp.equals("ir")) {
                final ImageReader ir = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 4);
                irOut[0] = ir;
                final int[] irn = {0};
                HandlerThread iht = new HandlerThread("ir"); iht.start();
                ihtOut[0] = iht;
                ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { public void onImageAvailable(ImageReader r) {
                    Image im = null;
                    try { im = r.acquireLatestImage(); if (im == null) return;
                        int k = ++irn[0];
                        if (k == 1 || k % 30 == 0) { byte[] nv = toNv21(im); p("ir frame " + k + " fmt=" + im.getFormat() + " " + stats(nv, W, H, W));
                            if (k == 60) saveJpeg(nv, "c1-ir-frame60.jpg"); }
                    } catch (Throwable t) { p("ir " + t); }
                    finally { if (im != null) im.close(); }
                }}, new Handler(iht.getLooper()));
                c.setPreviewDisplay(new Holder(ir.getSurface()));
            } else {
                st0 = new SurfaceTexture(10);
                c.setPreviewTexture(st0);
            }
            final SurfaceTexture st = st0;
            if (glOut[0] == null) stOut[0] = st; // the GL consumer frees its own

            Enc enc0 = null;
            if (encode) {
                MediaCodecInfo ci = find(CODEC);
                int[] fmts = ci.getCapabilitiesForType("video/avc").colorFormats;
                p("encoder colorFormats=" + fmtList(fmts));
                // feedNv21 writes semi-planar NV12 (Y, then interleaved UV rows): only formats with that layout.
                int pick = fmtPref != null ? Integer.decode(fmtPref) : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
                if (pick != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                        && pick != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar)
                    throw new IllegalArgumentException(String.format("color format 0x%x: the probe writes only semi-planar NV12 (0x15 or 0x27)", pick));
                boolean listed = false;
                for (int f : fmts) if (f == pick) listed = true;
                if (!listed) p(String.format("the encoder doesn't list color format 0x%x; trying it (configure refuses it if it can't)", pick));
                p(String.format("picked color format 0x%x", pick));
                enc0 = new Enc(false, pick);
                p("using stride=" + enc0.stride + " slice=" + enc0.slice);
            }
            final Enc enc = enc0;
            encOut[0] = enc;
            final int frameBytes = W * H * 3 / 2;
            for (int i = 0; i < 4; i++) c.addCallbackBuffer(new byte[frameBytes]);
            final int[] n = {0}; final int[] fed = {0}; final int[] dropped = {0};
            final long[] t0 = {0};
            final int total = encode ? 160 : 80;
            final CountDownLatch done = new CountDownLatch(1);
            c.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() { public void onPreviewFrame(byte[] data, Camera cc) {
                // Android hands null when a frame doesn't fit the buffers given: the probe can't go on.
                if (data == null) { p("FAILED: a preview frame didn't fit the probe's buffers"); done.countDown(); return; }
                int k = ++n[0];
                long now = System.nanoTime();
                if (k == 1) t0[0] = now;
                if (data.length != frameBytes) p("frame len " + data.length);
                if (k <= 5 || k % 15 == 0) p("frame " + k + " " + stats(data, W, H, W));
                if (k == 30 || k == 45 || k == 60) {
                    try { saveJpeg(data, "c1-frame" + k + ".jpg"); } catch (Exception e) { p("jpeg " + e); }
                }
                if (enc != null) {
                    if (enc.feedNv21(data, (now - t0[0]) / 1000, true)) fed[0]++; else dropped[0]++;
                }
                if (k >= total) {
                    p(String.format("frames=%d in %.2fs (%.1f fps) fed=%d dropped=%d", k, (now - t0[0]) / 1e9, (k - 1) / ((now - t0[0]) / 1e9), fed[0], dropped[0]));
                    done.countDown();
                } else cc.addCallbackBuffer(data);
            }});
            c.startPreview();
            p("preview started");
            if (!done.await(20, TimeUnit.SECONDS)) p("TIMEOUT frames=" + n[0]);
        } finally {
            // Whatever failed (a parameter the camera rejects, say), the camera goes back before the run says DONE.
            final CountDownLatch released = new CountDownLatch(1);
            h.post(new Runnable() { public void run() {
                try { c.setPreviewCallbackWithBuffer(null); c.stopPreview(); } catch (Exception e) { p("stop " + e); }
                c.release();
                released.countDown();
            }});
            released.await(5, TimeUnit.SECONDS);
            if (encOut[0] != null) encOut[0].stop();
            if (stOut[0] != null) stOut[0].release();
            if (glOut[0] != null) glOut[0].close();
            if (irOut[0] != null) {
                // On the reader's own thread, after any frame it is still handling; then the thread goes.
                final ImageReader ir = irOut[0];
                final CountDownLatch closed = new CountDownLatch(1);
                new Handler(ihtOut[0].getLooper()).post(new Runnable() { public void run() { ir.close(); closed.countDown(); }});
                closed.await(2, TimeUnit.SECONDS);
            }
            if (ihtOut[0] != null) ihtOut[0].quitSafely();
            ht.quitSafely();
        }
    }

    // ---------- test 3: Camera2, like the pane ----------
    void testC2(String outs, String want) throws Exception {
        final boolean useReader = !outs.equals("enc"), useEnc = !outs.equals("reader");
        HandlerThread ht = new HandlerThread("c2"); ht.start();
        final Handler h = new Handler(ht.getLooper());
        Enc enc0 = null;
        ImageReader reader0 = null;
        CameraDevice got = null;
        final CameraDevice[] dev = new CameraDevice[1];
        final boolean[] gaveUp = {false};
        final CameraCaptureSession[] sess = new CameraCaptureSession[1];
        try {
            CameraManager m = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics ch = m.getCameraCharacteristics("0");
            p("hw level=" + ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            p("fps ranges=" + Arrays.toString(ranges));
            Range<Integer> fps = null;
            for (Range<Integer> r : ranges) if (r.getUpper() == 30 && (fps == null || r.getLower() > fps.getLower())) fps = r;
            // want, the run's own fps extra: e.g. "15,15", the AE target range to ask for instead
            if ("template".equals(want)) fps = null; // keep the template's own range
            else if (want != null) { String[] a = want.split(","); fps = new Range<>(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim())); }
            enc0 = useEnc ? new Enc(true, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) : null;
            reader0 = useReader ? ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3) : null;
            final Enc enc = enc0;
            final ImageReader reader = reader0;
            final int[] n = {0};
            final long[] t0 = {0};
            if (reader != null) reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { public void onImageAvailable(ImageReader r) {
                Image im = r.acquireLatestImage();
                if (im == null) return;
                try {
                    int k = ++n[0];
                    if (k == 1) { t0[0] = System.nanoTime();
                        Image.Plane[] pl = im.getPlanes();
                        p("planes: Y rs=" + pl[0].getRowStride() + " ps=" + pl[0].getPixelStride() + " U rs=" + pl[1].getRowStride() + " ps=" + pl[1].getPixelStride() + " V rs=" + pl[2].getRowStride() + " ps=" + pl[2].getPixelStride() + " ts=" + im.getTimestamp());
                    }
                    if (k <= 5 || k % 15 == 0 || k == 30 || k == 45 || k == 60) {
                        byte[] nv21 = toNv21(im);
                        if (k <= 5 || k % 15 == 0) p("frame " + k + " " + stats(nv21, W, H, W));
                        if (k == 30 || k == 45 || k == 60) saveJpeg(nv21, "c2-frame" + k + ".jpg");
                    }
                } catch (Exception e) { p("image " + e); }
                finally { im.close(); }
            }}, h);

            final CountDownLatch opened = new CountDownLatch(1);
            m.openCamera("0", new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) {
                    // Opened after the wait gave up: nobody will close it, so close it here.
                    synchronized (dev) { if (gaveUp[0]) { d.close(); return; } dev[0] = d; }
                    opened.countDown();
                }
                public void onDisconnected(CameraDevice d) { p("disconnected"); d.close(); opened.countDown(); }
                public void onError(CameraDevice d, int e) { p("camera2 error " + e); d.close(); opened.countDown(); }
            }, h);
            opened.await(10, TimeUnit.SECONDS);
            synchronized (dev) { got = dev[0]; if (got == null) gaveUp[0] = true; }
            if (got == null) { p("open failed"); return; }
            final CameraDevice d = got;
            final List<Surface> targets = new ArrayList<>();
            if (reader != null) targets.add(reader.getSurface());
            if (enc != null) targets.add(enc.surface);
            final Range<Integer> fpsR = fps;
            final CountDownLatch configured = new CountDownLatch(1);
            d.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    try {
                        CaptureRequest.Builder rb = d.createCaptureRequest(enc != null ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                        for (Surface t : targets) rb.addTarget(t);
                        p("template fps " + rb.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE));
                        if (fpsR != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsR);
                        s.setRepeatingRequest(rb.build(), null, h);
                        p("session configured, fps " + fpsR);
                    } catch (Exception e) { p("repeat " + e); }
                    synchronized (sess) { sess[0] = s; }
                    configured.countDown();
                }
                public void onConfigureFailed(CameraCaptureSession s) { p("configure failed"); configured.countDown(); }
            }, h);
            configured.await(10, TimeUnit.SECONDS);
            Thread.sleep(5500);
            p("reader frames=" + n[0] + (n[0] > 1 ? String.format(" (%.1f fps)", (n[0] - 1) / ((System.nanoTime() - t0[0]) / 1e9)) : ""));
        } finally {
            // A failed probe (openCamera or createCaptureSession throwing, say) frees everything too, before the run
            // says DONE: a later probe in this process must not fail on, or measure, what this one left.
            synchronized (dev) { if (got == null) gaveUp[0] = true; }
            CameraCaptureSession s;
            synchronized (sess) { s = sess[0]; }
            if (s != null) { try { s.stopRepeating(); } catch (Exception e) {} s.close(); }
            if (got != null) { got.close(); Thread.sleep(500); }
            if (enc0 != null) enc0.stop();
            if (reader0 != null) {
                // On its callback thread, after any frame it is still copying, before the thread goes and the run says DONE.
                final ImageReader r = reader0;
                final CountDownLatch closed = new CountDownLatch(1);
                h.post(new Runnable() { public void run() { r.close(); closed.countDown(); }});
                closed.await(2, TimeUnit.SECONDS);
            }
            ht.quitSafely();
        }
    }

    static byte[] toNv21(Image im) {
        Image.Plane[] pl = im.getPlanes();
        byte[] out = new byte[W * H * 3 / 2];
        ByteBuffer y = pl[0].getBuffer(); int yrs = pl[0].getRowStride();
        for (int r = 0; r < H; r++) { y.position(r * yrs); y.get(out, r * W, W); }
        ByteBuffer u = pl[1].getBuffer(), v = pl[2].getBuffer();
        int urs = pl[1].getRowStride(), ups = pl[1].getPixelStride(), vrs = pl[2].getRowStride(), vps = pl[2].getPixelStride();
        int o = W * H;
        for (int r = 0; r < H / 2; r++)
            for (int c = 0; c < W / 2; c++) {
                out[o++] = v.get(r * vrs + c * vps);
                out[o++] = u.get(r * urs + c * ups);
            }
        return out;
    }
}
