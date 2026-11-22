package dev.glassworks.stockprobe;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tiny read-only HTTP probe server for stock Mirrors where 1.2x+ adbd rejects /data/misc/adb/adb_keys.
 * Listens on TCP port 18765 and serves only read-only system/proc/ramdisk files, properties,
 * device-tree panel nodes (skipping /chosen), and optional FS_IOC_FIEMAP physical extent offsets.
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

    private volatile boolean running;
    private volatile ServerSocket server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            maybeEnableWifiClient();
            Thread t = new Thread(this::serveLoop, "stock-probe-http");
            t.setDaemon(true);
            t.start();
        }
        return START_STICKY;
    }

    /**
     * On a fresh userdata, stock Android 6 often boots with Wi-Fi disabled while stuck on onboarding
     * Step 1. Turn Wi-Fi on only if SoftAP is NOT active so a pre-saved wpa_supplicant.conf can join
     * without interfering with stock's setup AP if it happens to be broadcasting.
     */
    private void maybeEnableWifiClient() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.isWifiEnabled()) return;
            try {
                Method isAp = wm.getClass().getMethod("isWifiApEnabled");
                Object apOn = isAp.invoke(wm);
                if (Boolean.TRUE.equals(apOn)) {
                    Log.i(TAG, "SoftAP is active; leaving Wi-Fi mode untouched");
                    return;
                }
            } catch (Throwable ignored) {}
            boolean ok = wm.setWifiEnabled(true);
            Log.i(TAG, "requested Wi-Fi client enable: " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "maybeEnableWifiClient failed", t);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void serveLoop() {
        ServerSocket listener = null;
        try {
            listener = new ServerSocket(PORT);
            server = listener;
            if (!running) return;
            Log.i(TAG, "HTTP probe listening on port " + PORT + " uid=" + Process.myUid() + " fiemap=" + Fiemap.isAvailable());
            while (running) {
                Socket sock = listener.accept();
                Thread worker = new Thread(() -> handle(sock), "stock-probe-req");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "server loop failed", e);
        } finally {
            server = null;
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

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(15000);
            BufferedReader rd = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String reqLine = rd.readLine();
            if (reqLine == null || !reqLine.startsWith("GET ")) {
                sendText(s.getOutputStream(), 405, "text/plain", "GET only\n");
                return;
            }
            String target = reqLine.split(" ")[1];
            String route = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                route = target.substring(0, q);
                query = target.substring(q + 1);
            }

            OutputStream out = s.getOutputStream();
            if ("/status".equals(route)) {
                String json = "{\"ok\":true,\"uid\":" + Process.myUid()
                        + ",\"pid\":" + Process.myPid()
                        + ",\"sdk\":" + Build.VERSION.SDK_INT
                        + ",\"fiemap\":" + Fiemap.isAvailable()
                        + ",\"bootCompleted\":\"" + escapeJson(getSysProp("sys.boot_completed")) + "\"}\n";
                sendText(out, 200, "application/json", json);
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
        } catch (Throwable t) {
            Log.w(TAG, "request failed", t);
        }
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

    private static String getSysProp(String key) {
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

    private static byte[] readAll(InputStream in) throws IOException {
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
        String hdr = "HTTP/1.0 " + status + " " + (status == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
