// SPDX-License-Identifier: MIT
package dev.glassworks.stockprobe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * The single-file GWSB stock bundle that Kiln reads over HTTP (/stock-bundle.bin) or BLE GATT.
 *
 * <pre>
 * offset        size     field
 * 0             4        ASCII "GWSB"
 * 4             4        uint32 LE metaLen
 * 8             4        uint32 LE jarLen
 * 12            metaLen  UTF-8 JSON metadata (see {@link #metaJson})
 * 12+metaLen    jarLen   raw /system/framework/services.jar bytes
 * </pre>
 *
 * The jar, its SHA-256, the odex probe and the FIEMAP are read once and cached (they live on the
 * read-only system partition). The header is rebuilt only when the LAN IP changes, and an
 * instance never changes once handed out, so a BLE session can keep chunk indexes stable.
 */
public final class StockBundle {
    public static final String SERVICES_PATH = "/system/framework/services.jar";
    public static final int VERSION = 1;
    private static final String[] ODEX_PATHS = {
            "/system/framework/oat/arm64/services.odex",
            "/system/framework/oat/arm/services.odex",
            "/system/framework/arm64/services.odex",
            "/system/framework/arm/services.odex",
    };

    private static final Object LOCK = new Object();
    private static Static cachedStatic;
    private static StockBundle cached;

    /** Things that never change while the app runs. */
    private static final class Static {
        byte[] jar;
        String sha256;
        String odexPath;
        boolean hasClassesDex;
        String fiemapJson;
        String mirrorVersion;
        String fingerprint;
    }

    public final byte[] header;
    public final byte[] jar;
    public final String metaJson;
    public final String lanIp;
    private final Static st;

    private StockBundle(Static st, String lanIp) {
        this.st = st;
        this.jar = st.jar;
        this.lanIp = lanIp;
        this.metaJson = buildMeta(st, lanIp, null);
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        byte[] h = new byte[12 + meta.length];
        h[0] = 'G'; h[1] = 'W'; h[2] = 'S'; h[3] = 'B';
        putLe32(h, 4, meta.length);
        putLe32(h, 8, jar.length);
        System.arraycopy(meta, 0, h, 12, meta.length);
        this.header = h;
    }

    /** Returns the current bundle, rebuilding the header if the LAN IP changed. Throws if services.jar is unreadable. */
    public static StockBundle current() throws IOException {
        synchronized (LOCK) {
            if (cachedStatic == null) cachedStatic = loadStatic();
            String ip = lanIp();
            if (cached == null || !eq(cached.lanIp, ip)) cached = new StockBundle(cachedStatic, ip);
            return cached;
        }
    }

    public long size() {
        return (long) header.length + jar.length;
    }

    public String sha256() {
        return st.sha256;
    }

    public String odexPath() {
        return st.odexPath;
    }

    public boolean hasClassesDex() {
        return st.hasClassesDex;
    }

    /** Copies up to len bundle bytes starting at off into dst; returns the count copied. */
    public int read(long off, byte[] dst, int dstOff, int len) {
        long total = size();
        if (off < 0 || off >= total || len <= 0) return 0;
        int n = (int) Math.min(len, total - off);
        int done = 0;
        if (off < header.length) {
            int h = (int) Math.min(n, header.length - off);
            System.arraycopy(header, (int) off, dst, dstOff, h);
            done = h;
        }
        if (done < n) {
            int jarOff = (int) (off + done - header.length);
            System.arraycopy(jar, jarOff, dst, dstOff + done, n - done);
        }
        return n;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(header);
        out.write(jar);
    }

    /** META characteristic JSON: the GWSB meta plus bundleSize and chunkSize, kept within 512 bytes. */
    public String bleMetaJson(int chunkSize, boolean chunkLocked) {
        String extra = ",\"bundleSize\":" + size() + ",\"chunkSize\":" + chunkSize + ",\"chunkLocked\":" + chunkLocked;
        String full = buildMeta(st, lanIp, extra);
        if (full.getBytes(StandardCharsets.UTF_8).length <= 512) return full;
        // The ATT spec caps an attribute at 512 bytes; the full FIEMAP is always in the GWSB header.
        String fiemap = "{\"truncated\":true,\"extentCount\":" + countExtents(st.fiemapJson) + "}";
        return buildMetaWithFiemap(st, lanIp, fiemap, extra);
    }

    private static String buildMeta(Static st, String lanIp, String extra) {
        return buildMetaWithFiemap(st, lanIp, st.fiemapJson, extra);
    }

    private static String buildMetaWithFiemap(Static st, String lanIp, String fiemap, String extra) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"version\":").append(VERSION)
          .append(",\"mirrorVersion\":").append(jsonStr(st.mirrorVersion))
          .append(",\"fingerprint\":").append(jsonStr(st.fingerprint))
          .append(",\"servicesPath\":").append(jsonStr(SERVICES_PATH))
          .append(",\"servicesSize\":").append(st.jar.length)
          .append(",\"servicesSha256\":").append(jsonStr(st.sha256))
          .append(",\"servicesOdexFound\":").append(st.odexPath != null)
          .append(",\"servicesOdexPath\":").append(jsonStr(st.odexPath))
          .append(",\"servicesHasClassesDex\":").append(st.hasClassesDex)
          .append(",\"fiemap\":").append(fiemap == null ? "null" : fiemap)
          .append(",\"lanIp\":").append(jsonStr(lanIp))
          .append(",\"httpPort\":").append(ProbeServerService.PORT);
        if (extra != null) sb.append(extra);
        return sb.append('}').toString();
    }

    private static Static loadStatic() throws IOException {
        Static st = new Static();
        File f = new File(SERVICES_PATH);
        try (InputStream in = new FileInputStream(f)) {
            st.jar = ProbeServerService.readAll(in);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            st.sha256 = hex(md.digest(st.jar));
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        for (String p : ODEX_PATHS) {
            if (new File(p).isFile()) { st.odexPath = p; break; }
        }
        try (ZipFile z = new ZipFile(f)) {
            st.hasClassesDex = z.getEntry("classes.dex") != null;
        } catch (Throwable ignored) {
            st.hasClassesDex = false;
        }
        st.fiemapJson = null;
        if (Fiemap.isAvailable()) {
            try {
                String j = Fiemap.queryJson(SERVICES_PATH);
                if (j != null && j.trim().startsWith("{") && j.trim().endsWith("}")) st.fiemapJson = j.trim();
            } catch (Throwable ignored) {}
        }
        st.mirrorVersion = ProbeServerService.getSysProp("ro.build.version.mirror");
        st.fingerprint = ProbeServerService.getSysProp("ro.build.fingerprint");
        return st;
    }

    /** First IPv4 address on a Wi-Fi interface (client or SoftAP), else any non-loopback IPv4, else null. */
    static String lanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.US);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("softap") || name.startsWith("ap")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static int countExtents(String fiemap) {
        if (fiemap == null) return 0;
        int n = 0;
        for (int i = fiemap.indexOf("\"logical\""); i >= 0; i = fiemap.indexOf("\"logical\"", i + 1)) n++;
        return n;
    }

    static void putLe32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
