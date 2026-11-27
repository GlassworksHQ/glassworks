package dev.glassworks.stockprobe;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tiny read-only HTTP probe server for stock Mirrors where 1.2x+ adbd rejects /data/misc/adb/adb_keys.
 * Listens on TCP port 18765 and serves only read-only system/proc/ramdisk files, properties,
 * device-tree panel nodes (skipping /chosen), optional FS_IOC_FIEMAP physical extent offsets,
 * and the single-file GWSB stock bundle (/stock-bundle.bin, page at / and /bundle; see README.md).
 * Also starts {@link BleProbeServer}, which serves the same bundle over BLE GATT.
 */
public final class ProbeServerService extends Service {
    private static final String TAG = "GlassworksStockProbe";
    public static final int PORT = 18765;

    private static final List<String> RAMDISK_EXACT = Arrays.asList(
            "/sepolicy", "/file_contexts", "/seapp_contexts",
            "/property_contexts", "/service_contexts", "/default.prop"
    );

    private static final String[] SAFE_PROPS = new String[] {
            "ro.build.fingerprint", "ro.bootimage.build.fingerprint",
            "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.type", "ro.build.tags", "ro.board.platform",
            "ro.product.model", "ro.product.device", "ro.product.manufacturer",
            "ro.hardware", "ro.crypto.state", "ro.adb.secure",
            "ro.secure", "ro.debuggable", "ro.sf.hwrotation",
            "persist.sys.usb.config", "persist.adb.tcp.port",
            "sys.boot_completed"
    };

    private static final Set<String> SAFE_PROPS_SET = new HashSet<>(Arrays.asList(SAFE_PROPS));

    private static final int MAX_WORKERS = 8;
    private static final int MAX_QUEUE = 16;
    private static final int MAX_REQUEST_LINE_BYTES = 4096;
    private static final int MAX_HEADER_LINES = 64;
    private static final long REQUEST_DEADLINE_MS = 15_000L;
    private static final long RESPONSE_DEADLINE_MS = 60_000L;
    private static final long BUNDLE_DEADLINE_MS = 600_000L;

    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile ExecutorService requestPool;
    private volatile ScheduledExecutorService deadlineTimer;
    private volatile BleProbeServer ble;
    private final Set<Socket> activeSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final class RequestTask implements Runnable {
        private final Socket sock;

        RequestTask(Socket sock) {
            this.sock = sock;
        }

        void closeSocket() {
            try { sock.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            handle(sock);
        }
    }

    private void shutdownWorkers(ExecutorService pool, ScheduledExecutorService timer) {
        if (pool != null) {
            for (Runnable queued : pool.shutdownNow()) {
                if (queued instanceof RequestTask) ((RequestTask) queued).closeSocket();
            }
        }
        // Close active sockets before stopping the deadline timer so workers blocked in
        // socket reads or response writes unblock immediately on shutdown.
        for (Socket active : activeSockets) {
            try { active.close(); } catch (IOException ignored) {}
        }
        if (timer != null) timer.shutdownNow();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Point the optional per-run HMAC secret at files/stage.secret before the first bundle read.
        StockBundle.init(this);
        dataDirs = ownDataDirs();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            maybeEnableWifiClient();
            Thread t = new Thread(this::serveLoop, "stock-probe-http");
            t.setDaemon(true);
            t.start();
            Thread warm = new Thread(() -> {
                try { StockBundle.current(); } catch (Throwable e) { ProbeLog.w(TAG, "stock bundle unavailable", e); }
            }, "stock-probe-bundle");
            warm.setDaemon(true);
            warm.start();
            try {
                ble = new BleProbeServer(this);
                ble.start();
            } catch (Throwable e) {
                ProbeLog.w(TAG, "BLE probe unavailable", e);
                ble = null;
            }
        }
        return START_STICKY;
    }

    /**
     * On a fresh userdata with pre-saved Wi-Fi credentials (--wifi-ssid), stock Android 6 often
     * boots with Wi-Fi disabled while stuck on onboarding Step 1. Turn Wi-Fi client on ONLY when
     * stock-probe planted the `enable_wifi` marker next to base.apk AND SoftAP is not active, so
     * runs without --wifi-ssid never race or interfere with stock's mirror-<serial> setup SoftAP.
     */
    private void maybeEnableWifiClient() {
        try {
            File apkDir = new File(getApplicationInfo().sourceDir).getParentFile();
            if (apkDir == null || !new File(apkDir, "enable_wifi").exists()) {
                ProbeLog.i(TAG, "no enable_wifi marker; leaving Wi-Fi/SoftAP untouched");
                return;
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.isWifiEnabled()) return;
            try {
                Method isAp = wm.getClass().getMethod("isWifiApEnabled");
                Object apOn = isAp.invoke(wm);
                if (Boolean.TRUE.equals(apOn)) {
                    ProbeLog.i(TAG, "SoftAP is active; leaving Wi-Fi mode untouched");
                    return;
                }
            } catch (Throwable ignored) {}
            boolean ok = wm.setWifiEnabled(true);
            ProbeLog.i(TAG, "requested Wi-Fi client enable: " + ok);
        } catch (Throwable t) {
            ProbeLog.w(TAG, "maybeEnableWifiClient failed", t);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (ble != null) {
            try { ble.stop(); } catch (Throwable ignored) {}
            ble = null;
        }
        ServerSocket listener = server;
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        shutdownWorkers(requestPool, deadlineTimer);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void serveLoop() {
        ServerSocket listener = null;
        ExecutorService pool = new ThreadPoolExecutor(
                2, MAX_WORKERS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE),
                r -> {
                    Thread t = new Thread(r, "stock-probe-req");
                    t.setDaemon(true);
                    return t;
                }
        );
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stock-probe-deadline");
            t.setDaemon(true);
            return t;
        });
        requestPool = pool;
        deadlineTimer = timer;
        try {
            listener = new ServerSocket(PORT);
            server = listener;
            if (!running) return;
            ProbeLog.i(TAG, "HTTP probe listening on port " + PORT + " uid=" + Process.myUid() + " fiemap=" + Fiemap.isAvailable());
            while (running) {
                Socket sock = listener.accept();
                RequestTask task = new RequestTask(sock);
                try {
                    pool.execute(task);
                } catch (RejectedExecutionException rejected) {
                    task.closeSocket();
                }
            }
        } catch (IOException e) {
            if (running) ProbeLog.e(TAG, "server loop failed", e);
        } finally {
            server = null;
            requestPool = null;
            deadlineTimer = null;
            shutdownWorkers(pool, timer);
            try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isExcludedChosen(String path) {
        return path.equals("/proc/device-tree/chosen")
                || path.startsWith("/proc/device-tree/chosen/")
                || path.equals("/sys/firmware/devicetree/base/chosen")
                || path.startsWith("/sys/firmware/devicetree/base/chosen/");
    }

    private static String normalizeLexical(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) return null;
            sb.append('/').append(seg);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * Whether a request path lands inside this app's own private data directory, so that the stage
     * secret (or anything else there) can never be read over HTTP. Canonicalizes first so a symlink
     * resolving into the data dir is caught too.
     */
    private boolean isInOwnDataDir(String p) {
        if (p == null) return false;
        String canonical;
        try {
            canonical = new File(p).getCanonicalPath();
        } catch (IOException e) {
            return true; // cannot resolve: refuse rather than risk a leak
        }
        for (String dir : dataDirs) {
            if (dir == null || dir.isEmpty()) continue;
            if (canonical.equals(dir) || canonical.startsWith(dir + "/")) return true;
        }
        return false;
    }

    /**
     * This app's private data directories, by every path they are reached through. Populated in
     * {@link #onCreate()} (a Context is not attached yet when fields initialize).
     */
    private volatile String[] dataDirs = new String[0];

    private String[] ownDataDirs() {
        String appDir = null;
        try {
            appDir = getApplicationInfo().dataDir;
        } catch (Throwable ignored) {}
        return new String[] {
                appDir,
                "/data/data/" + getPackageName(),
                "/data/user/0/" + getPackageName(),
        };
    }

    private static boolean isAllowedPath(String p) {
        if (p == null || !p.startsWith("/") || p.contains("..") || p.contains("\0")) return false;
        String normalized = normalizeLexical(p);
        if (normalized == null || isExcludedChosen(normalized)) return false;
        String canonical;
        try {
            canonical = new File(normalized).getCanonicalPath();
        } catch (IOException e) {
            return false;
        }
        if (isExcludedChosen(canonical)) return false;
        if (RAMDISK_EXACT.contains(normalized) && RAMDISK_EXACT.contains(canonical)) return true;
        boolean prefixAllowed = normalized.startsWith("/system/")
                || normalized.startsWith("/proc/")
                || normalized.equals("/sys/class/backlight")
                || normalized.startsWith("/sys/class/backlight/")
                || normalized.equals("/sys/class/leds")
                || normalized.startsWith("/sys/class/leds/");
        if (!prefixAllowed) return false;
        return canonical.startsWith("/system/")
                || canonical.startsWith("/proc/")
                || canonical.equals("/sys/firmware/devicetree/base")
                || canonical.startsWith("/sys/firmware/devicetree/base/")
                || canonical.equals("/sys/class/backlight")
                || canonical.startsWith("/sys/class/backlight/")
                || canonical.equals("/sys/class/leds")
                || canonical.startsWith("/sys/class/leds/")
                || canonical.startsWith("/sys/devices/");
    }

    private static String readBoundedRequestLine(Socket s, long deadlineMs) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        while (true) {
            long rem = deadlineMs - System.currentTimeMillis();
            if (rem <= 0) throw new SocketTimeoutException("request deadline exceeded");
            s.setSoTimeout((int) Math.min(rem, Integer.MAX_VALUE));
            int b = in.read();
            if (b < 0) return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.US_ASCII);
            if (b == '\n') break;
            if (b == '\r') continue;
            if (buf.size() >= MAX_REQUEST_LINE_BYTES) {
                throw new IllegalArgumentException("request line too long");
            }
            buf.write(b);
        }
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    private void handle(Socket sock) {
        ScheduledFuture<?> killer = null;
        activeSockets.add(sock);
        try (Socket s = sock) {
            if (!running) return;
            ScheduledExecutorService timer = deadlineTimer;
            if (timer != null && !timer.isShutdown()) {
                try {
                    killer = timer.schedule(() -> {
                        try { s.close(); } catch (IOException ignored) {}
                    }, RESPONSE_DEADLINE_MS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {}
            }
            long deadlineMs = System.currentTimeMillis() + REQUEST_DEADLINE_MS;
            s.setSoTimeout((int) REQUEST_DEADLINE_MS);
            String reqLine;
            try {
                reqLine = readBoundedRequestLine(s, deadlineMs);
            } catch (IllegalArgumentException tooLong) {
                sendText(s.getOutputStream(), 414, "text/plain", "request URI too long\n");
                return;
            }
            boolean head = reqLine != null && reqLine.startsWith("HEAD ");
            boolean options = reqLine != null && reqLine.startsWith("OPTIONS ");
            if (reqLine == null || !(reqLine.startsWith("GET ") || head || options)) {
                sendText(s.getOutputStream(), 405, "text/plain", "GET only\n");
                return;
            }
            // Drain request headers until the blank line so closing the socket never sends a TCP
            // RST due to unread request bytes (which breaks Windows Chrome and Node undici/fetch).
            for (int h = 0; h < MAX_HEADER_LINES; h++) {
                String hdrLine;
                try {
                    hdrLine = readBoundedRequestLine(s, deadlineMs);
                } catch (IllegalArgumentException tooLong) {
                    sendText(s.getOutputStream(), 431, "text/plain", "header too large\n");
                    return;
                }
                if (hdrLine == null || hdrLine.isEmpty()) break;
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) {
                sendText(s.getOutputStream(), 400, "text/plain", "bad request\n");
                return;
            }
            String target = parts[1];
            String route = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                route = target.substring(0, q);
                query = target.substring(q + 1);
            }

            OutputStream out = s.getOutputStream();
            if (options) {
                // CORS / Private Network Access preflight for Kiln, on the bundle routes only.
                if (isCorsRoute(route)) sendPreflight(out);
                else sendText(out, 405, "text/plain", "GET only\n");
            } else if (head && !"/stock-bundle.bin".equals(route)) {
                sendText(out, 405, "text/plain", "HEAD is only for /stock-bundle.bin\n");
            } else if ("/stock-bundle.bin".equals(route)) {
                // A multi-MB bundle over a slow SoftAP can outlast the 60 s response deadline.
                if (killer != null && killer.cancel(false)) {
                    ScheduledExecutorService t2 = deadlineTimer;
                    killer = null;
                    if (t2 != null && !t2.isShutdown()) {
                        try {
                            killer = t2.schedule(() -> {
                                try { s.close(); } catch (IOException ignored) {}
                            }, BUNDLE_DEADLINE_MS, TimeUnit.MILLISECONDS);
                        } catch (RejectedExecutionException ignored) {}
                    }
                }
                StockBundle b;
                try {
                    b = StockBundle.current();
                } catch (IOException e) {
                    sendText(out, 503, "text/plain", "services.jar unreadable: " + e.getMessage() + "\n");
                    return;
                }
                String hdr = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Disposition: attachment; filename=\"mirror-stock-bundle.bin\"\r\n"
                        + "Content-Length: " + b.size() + "\r\n"
                        + CORS_HEADERS
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                if (!head) b.writeTo(out);
                out.flush();
            } else if ("/".equals(route) || "/bundle".equals(route)) {
                sendText(out, 200, "text/html", bundlePage());
            } else if ("/log".equals(route)) {
                // the probe's own log and its Bluetooth state, for a stock Mirror that lets no adb in yet
                BleProbeServer b = ble;
                String text = "uptimeMs=" + android.os.SystemClock.elapsedRealtime() + "\n"
                        + (b != null ? b.describe() : "ble: not started") + "\n\n" + ProbeLog.dump();
                sendText(out, 200, "text/plain", text);
            } else if ("/status".equals(route)) {
                String json = "{\"ok\":true,\"uid\":" + Process.myUid()
                        + ",\"pid\":" + Process.myPid()
                        + ",\"sdk\":" + Build.VERSION.SDK_INT
                        + ",\"fiemap\":" + Fiemap.isAvailable()
                        + ",\"bootCompleted\":\"" + escapeJson(getSysProp("sys.boot_completed")) + "\"}\n";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                String hdr = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + CORS_HEADERS
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } else if ("/props".equals(route)) {
                sendText(out, 200, "text/plain", readAllProps());
            } else if ("/dt-panel".equals(route)) {
                StringBuilder sb = new StringBuilder();
                walkDt(new File("/proc/device-tree"), sb);
                sendText(out, 200, "text/plain", sb.toString());
            } else if ("/list".equals(route)) {
                String path = queryParam(query, "path");
                if (!isAllowedPath(path)) {
                    sendText(out, 400, "text/plain", "disallowed path\n");
                    return;
                }
                File dir = new File(path);
                if (!dir.exists()) {
                    sendText(out, 404, "text/plain", "not found\n");
                    return;
                }
                File[] kids = dir.listFiles();
                if (kids == null) {
                    sendText(out, 403, "text/plain", "unreadable\n");
                    return;
                }
                Arrays.sort(kids, (a, b) -> a.getName().compareTo(b.getName()));
                StringBuilder sb = new StringBuilder();
                for (File f : kids) {
                    sb.append(f.isDirectory() ? "d" : "f").append('\t')
                      .append(f.canRead() ? "r" : "-").append('\t')
                      .append(f.length()).append('\t')
                      .append(f.getAbsolutePath()).append('\n');
                }
                sendText(out, 200, "text/plain", sb.toString());
            } else if ("/file".equals(route)) {
                String path = queryParam(query, "path");
                if (!isAllowedPath(path)) {
                    sendText(out, 400, "text/plain", "disallowed path\n");
                    return;
                }
                if (isInOwnDataDir(path)) {
                    // Belt and suspenders: the stage secret and anything else in our private data
                    // dir must never leave over HTTP, even via a symlink that canonicalizes into it.
                    sendText(out, 403, "text/plain", "forbidden\n");
                    return;
                }
                File f = new File(path);
                if (!f.exists()) {
                    sendText(out, 404, "text/plain", "not found\n");
                    return;
                }
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] data = readAll(in);
                    sendBytes(out, 200, "application/octet-stream", data);
                } catch (IOException e) {
                    sendText(out, 403, "text/plain", "denied: " + e.getMessage() + "\n");
                }
            } else if ("/fiemap".equals(route)) {
                String path = queryParam(query, "path");
                if (!isAllowedPath(path)) {
                    sendText(out, 400, "application/json", "{\"error\":\"disallowed path\"}\n");
                    return;
                }
                if (!Fiemap.isAvailable()) {
                    sendText(out, 501, "application/json", "{\"error\":\"libfiemap.so not bundled\"}\n");
                    return;
                }
                sendText(out, 200, "application/json", Fiemap.queryJson(path) + "\n");
            } else {
                sendText(out, 404, "text/plain", "unknown route\n");
            }
            try { s.shutdownOutput(); } catch (IOException ignored) {}
        } catch (Throwable t) {
            ProbeLog.w(TAG, "request failed", t);
        } finally {
            activeSockets.remove(sock);
            if (killer != null) killer.cancel(false);
        }
    }

    private static final String CORS_HEADERS = "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Private-Network: true\r\n"
            + "Access-Control-Expose-Headers: Content-Length, Content-Disposition\r\n";

    private static boolean isCorsRoute(String route) {
        // /status too: Kiln checks it, across origins, to see that the computer reached the Mirror's own Wi-Fi
        return "/stock-bundle.bin".equals(route) || "/status".equals(route);
    }

    private static void sendPreflight(OutputStream out) throws IOException {
        String hdr = "HTTP/1.1 204 No Content\r\n"
                + CORS_HEADERS
                + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Access-Control-Max-Age: 600\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** The / and /bundle page: what the bundle holds and a one-click download link. */
    private static String bundlePage() {
        String sha;
        String odex;
        String size;
        String err = null;
        try {
            StockBundle b = StockBundle.current();
            sha = b.sha256();
            odex = b.odexPath() != null ? "found at " + b.odexPath() : "not found (services.jar "
                    + (b.hasClassesDex() ? "has" : "has no") + " classes.dex)";
            size = String.format(Locale.US, "%.1f MB", b.size() / 1048576.0);
        } catch (Throwable t) {
            sha = odex = size = "-";
            err = String.valueOf(t.getMessage());
        }
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Mirror stock bundle</title><style>"
                + "body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem;"
                + "color:#1b1b1f;background:#fafafa}"
                + "@media(prefers-color-scheme:dark){body{color:#e8e8ec;background:#141417}a.dl{color:#141417}}"
                + "code{word-break:break-all;font-size:.9em}"
                + "a.dl{display:block;text-align:center;padding:1rem;margin:1.5rem 0;border-radius:.75rem;"
                + "background:#4c8dff;color:#fff;font-weight:600;text-decoration:none;font-size:1.15rem}"
                + "dt{font-weight:600;margin-top:.75rem}dd{margin:0}small{opacity:.7}"
                + "</style></head><body><h1>Mirror stock bundle</h1>"
                + (err != null ? "<p><strong>services.jar could not be read:</strong> " + htmlEscape(err) + "</p>" : "")
                + "<a class=\"dl\" href=\"/stock-bundle.bin\" download=\"mirror-stock-bundle.bin\">"
                + "Download mirror-stock-bundle.bin</a>"
                + "<dl><dt>services.jar SHA-256</dt><dd><code>" + htmlEscape(sha) + "</code></dd>"
                + "<dt>services.odex</dt><dd>" + htmlEscape(odex) + "</dd>"
                + "<dt>Bundle size</dt><dd>" + htmlEscape(size) + "</dd></dl>"
                + "<p><small>Save the file, then give it to the Glassworks installer. "
                + "This page only reads the Mirror's system files.</small></p></body></html>\n";
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String filterGetpropOutput(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("[")) continue;
            int sep = trimmed.indexOf("]: [");
            if (sep <= 1 || !trimmed.endsWith("]")) continue;
            String key = trimmed.substring(1, sep);
            if (SAFE_PROPS_SET.contains(key) || key.startsWith("init.svc.")) {
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString();
    }

    private static String readAllProps() {
        // Try /system/bin/getprop first so we also capture init.svc.* entries,
        // filtering to SAFE_PROPS and init.svc.* before leaving the device.
        try {
            java.lang.Process p = new ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start();
            byte[] bytes = readAll(p.getInputStream());
            p.waitFor();
            if (bytes.length > 0) {
                String filtered = filterGetpropOutput(new String(bytes, StandardCharsets.UTF_8));
                if (!filtered.isEmpty()) return filtered;
            }
        } catch (Throwable ignored) {}
        // Fallback: in-process SystemProperties reflection for the allowlisted keys.
        StringBuilder sb = new StringBuilder();
        for (String k : SAFE_PROPS) {
            String v = getSysProp(k);
            if (!v.isEmpty()) sb.append('[').append(k).append("]: [").append(v).append("]\n");
        }
        return sb.toString();
    }

    static String getSysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v != null ? v.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void walkDt(File dir, StringBuilder out) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            if (isExcludedChosen(dir.getAbsolutePath()) || isExcludedChosen(dir.getCanonicalPath())) return;
        } catch (IOException e) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : kids) {
            if (f.isDirectory()) {
                walkDt(f, out);
            } else {
                String name = f.getName();
                if ("compatible".equals(name) || "qcom,mdss-dsi-panel-name".equals(name)) {
                    try (FileInputStream in = new FileInputStream(f)) {
                        String val = new String(readAll(in), StandardCharsets.US_ASCII).replace('\0', ' ').trim();
                        String combined = (f.getAbsolutePath() + val).toLowerCase(Locale.US);
                        if (combined.contains("panel-name") || combined.contains("lvds")
                                || combined.contains("panel") || combined.contains("bridge")
                                || combined.contains("dsi") || combined.contains("sn65")
                                || combined.contains("tc358") || combined.contains("lt89")
                                || combined.contains("it6")) {
                            out.append(f.getParent()).append(": ").append(name).append('=').append(val).append('\n');
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if (key.equals(k)) {
                try { return URLDecoder.decode(pair.substring(eq + 1), "UTF-8"); }
                catch (Exception e) { return null; }
            }
        }
        return null;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sendText(OutputStream out, int status, String contentType, String body) throws IOException {
        sendBytes(out, status, contentType + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String hdr = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
