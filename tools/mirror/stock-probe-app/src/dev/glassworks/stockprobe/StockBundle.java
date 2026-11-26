// SPDX-License-Identifier: MIT
package dev.glassworks.stockprobe;

import android.content.Context;
import android.util.Base64;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 *
 * <p>When a 32-byte per-run secret is planted at {@code files/stage.secret} (see {@link #init}),
 * the metadata carries {@code "hmacSha256"} as its last field. It is
 * {@code HMAC-SHA256(secret, metaBaseBytes || jarBytes)}, where {@code metaBaseBytes} is the exact
 * UTF-8 JSON of the metadata up to but not including the trailing
 * {@code ,"hmacSha256":"<64 hex>"} and closing {@code }}. A client strips that trailing field,
 * feeds {@code metaBaseBytes || jarBytes} to the same HMAC, and so authenticates every metadata
 * field (including {@code serialno}, {@code fiemap}, {@code servicesSha256}) and every byte of
 * {@code services.jar} with no JSON-canonicalization ambiguity. Without the secret file the field
 * is omitted.
 */
public final class StockBundle {
    public static final String SERVICES_PATH = "/system/framework/services.jar";
    /**
     * Stock's boot animation, which Kiln overwrites in place with its own at the same size and puts back from the
     * restore point: the bundle carries its bytes (base64), SHA-256 and FIEMAP in {@code "bootAnimation"}. Stock
     * 1.24.0's is 22,901 bytes; anything past {@link #BOOT_MAX_BYTES} is left out.
     */
    public static final String BOOT_ANIMATION_PATH = "/system/media/bootanimation.zip";
    static final int BOOT_MAX_BYTES = 256 * 1024;
    public static final int VERSION = 1;
    private static final String[] ODEX_PATHS = {
            "/system/framework/oat/arm64/services.odex",
            "/system/framework/oat/arm/services.odex",
            "/system/framework/arm64/services.odex",
            "/system/framework/arm/services.odex",
    };

    /** Name of the optional per-run secret file under the app's private files dir. */
    public static final String SECRET_FILE = "stage.secret";

    private static final Object LOCK = new Object();
    private static Static cachedStatic;
    private static StockBundle cached;
    private static volatile File secretFile;

    /** Things that never change while the app runs. */
    private static final class Static {
        byte[] jar;
        String sha256;
        String odexPath;
        boolean hasClassesDex;
        String fiemapJson;
        String mirrorVersion;
        String fingerprint;
        String serialno;
        /** The {@code "bootAnimation"} object, or {@code "null"} when stock has none Kiln can use. */
        String bootJson = "null";
        /** Its size when it's there, for descriptions that leave the object out (Bluetooth's META): Kiln then takes a fresh bundle over a kept one without it. */
        int bootSize;
    }

    public final byte[] header;
    public final byte[] jar;
    public final String metaJson;
    public final String lanIp;
    /** Lowercase hex HMAC-SHA256 over {@code metaBaseBytes || jar}, or null when no secret is planted. */
    public final String hmacHex;
    private final Static st;

    private StockBundle(Static st, String lanIp) {
        this.st = st;
        this.jar = st.jar;
        this.lanIp = lanIp;
        this.hmacHex = computeHmacHex(st, lanIp);
        this.metaJson = closeMeta(buildMetaBase(st, lanIp, st.fiemapJson, null, true), hmacHex);
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        byte[] h = new byte[12 + meta.length];
        h[0] = 'G'; h[1] = 'W'; h[2] = 'S'; h[3] = 'B';
        putLe32(h, 4, meta.length);
        putLe32(h, 8, jar.length);
        System.arraycopy(meta, 0, h, 12, meta.length);
        this.header = h;
    }

    /**
     * Points the optional per-run HMAC secret at {@code ctx.getFilesDir()/stage.secret}. Call once
     * from {@code Service.onCreate()} before the first {@link #current()}. When the file is absent
     * the metadata simply omits {@code hmacSha256}.
     */
    public static void init(Context ctx) {
        secretFile = new File(ctx.getFilesDir(), SECRET_FILE);
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

    /** Convenience overload: wires the secret file (see {@link #init}) and returns {@link #current()}. */
    public static StockBundle current(Context ctx) throws IOException {
        init(ctx);
        return current();
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

    /**
     * META characteristic JSON: the GWSB meta plus bundleSize and chunkSize, kept within the
     * 512-byte ATT attribute cap when possible. Tiers down until it fits: full FIEMAP, then a
     * truncated FIEMAP (the full one is always in the streamed GWSB header), then — if the 64-hex
     * {@code hmacSha256} is what pushes it over — a short {@code "hasHmac":true} flag in its place.
     * When present, the {@code hmacSha256} is identical to the GWSB header's, which authenticates
     * the full, untruncated metadata and jar; a central reading only this characteristic verifies
     * against the streamed header, not this (possibly truncated) readout.
     */
    public String bleMetaJson(int chunkSize, boolean chunkLocked) {
        String extra = ",\"bundleSize\":" + size() + ",\"chunkSize\":" + chunkSize + ",\"chunkLocked\":" + chunkLocked;
        String full = closeMeta(buildMetaBase(st, lanIp, st.fiemapJson, extra, false), hmacHex);
        if (fits512(full)) return full;
        String fiemap = "{\"truncated\":true,\"extentCount\":" + countExtents(st.fiemapJson) + "}";
        String trunc = closeMeta(buildMetaBase(st, lanIp, fiemap, extra, false), hmacHex);
        if (fits512(trunc)) return trunc;
        if (hmacHex != null) {
            String flagged = buildMetaBase(st, lanIp, fiemap, extra, false) + ",\"hasHmac\":true}";
            if (fits512(flagged)) return flagged;
        }
        // Final compact fallback guaranteed <= 512 bytes (the complete metadata and HMAC always ride
        // the streamed GWSB header; never slice JSON mid-byte).
        String compact = buildCompactMeta(st, lanIp, extra, hmacHex != null, true);
        if (fits512(compact)) return compact;
        return buildCompactMeta(st, lanIp, extra, hmacHex != null, false);
    }

    private static String buildCompactMeta(Static st, String lanIp, String extra, boolean hasHmac, boolean includeSerial) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("{\"version\":").append(VERSION)
          .append(",\"compact\":true");
        if (includeSerial) sb.append(",\"serialno\":").append(jsonStr(st.serialno));
        sb.append(",\"servicesSize\":").append(st.jar.length)
          .append(",\"servicesSha256\":").append(jsonStr(st.sha256))
          .append(",\"servicesOdexFound\":").append(st.odexPath != null)
          .append(",\"servicesHasClassesDex\":").append(st.hasClassesDex)
          .append(",\"lanIp\":").append(jsonStr(lanIp))
          .append(",\"httpPort\":").append(ProbeServerService.PORT);
        if (extra != null) sb.append(extra);
        if (hasHmac) sb.append(",\"hasHmac\":true");
        return sb.append('}').toString();
    }

    private static boolean fits512(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length <= 512;
    }

    /** Appends the trailing {@code hmacSha256} field (when present) and the closing brace to a meta base. */
    private static String closeMeta(String base, String hmacHex) {
        if (hmacHex == null) return base + "}";
        return base + ",\"hmacSha256\":\"" + hmacHex + "\"}";
    }

    /**
     * The metadata JSON up to but not including the trailing hmac field and the closing brace. {@code withBoot}: with
     * the boot animation (about 30 KB of base64), as the GWSB header and its HMAC carry it; the Bluetooth META
     * characteristic leaves it out to stay within 512 bytes, as it trims the FIEMAP.
     */
    private static String buildMetaBase(Static st, String lanIp, String fiemap, String extra, boolean withBoot) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"version\":").append(VERSION)
          .append(",\"mirrorVersion\":").append(jsonStr(st.mirrorVersion))
          .append(",\"fingerprint\":").append(jsonStr(st.fingerprint))
          .append(",\"serialno\":").append(jsonStr(st.serialno))
          .append(",\"servicesPath\":").append(jsonStr(SERVICES_PATH))
          .append(",\"servicesSize\":").append(st.jar.length)
          .append(",\"servicesSha256\":").append(jsonStr(st.sha256))
          .append(",\"servicesOdexFound\":").append(st.odexPath != null)
          .append(",\"servicesOdexPath\":").append(jsonStr(st.odexPath))
          .append(",\"servicesHasClassesDex\":").append(st.hasClassesDex)
          .append(",\"fiemap\":").append(fiemap == null ? "null" : fiemap)
          .append(",\"lanIp\":").append(jsonStr(lanIp))
          .append(",\"httpPort\":").append(ProbeServerService.PORT);
        if (withBoot) sb.append(",\"bootAnimation\":").append(st.bootJson);
        else if (st.bootSize > 0) sb.append(",\"bootAnimationSize\":").append(st.bootSize);
        if (extra != null) sb.append(extra);
        return sb.toString();
    }

    /**
     * {@code HMAC-SHA256(secret, metaBaseBytes || jar)} as lowercase hex, or null when no 32-byte
     * secret is planted. The base uses the full FIEMAP and no BLE extras, matching the GWSB header.
     */
    private static String computeHmacHex(Static st, String lanIp) {
        byte[] secret = loadSecret();
        if (secret == null) return null;
        try {
            byte[] metaBaseBytes = buildMetaBase(st, lanIp, st.fiemapJson, null, true).getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(metaBaseBytes);   // streaming: no 2.4 MB concat buffer
            mac.update(st.jar);
            return hex(mac.doFinal());
        } catch (Throwable t) {
            return null;
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Reads the optional per-run secret: 32 raw bytes, or 64 trimmed ASCII hex chars. Returns null
     * when the file is absent, unreadable or malformed.
     */
    private static byte[] loadSecret() {
        File f = secretFile;
        if (f == null || !f.isFile()) return null;
        try {
            byte[] raw;
            try (InputStream in = new FileInputStream(f)) {
                raw = ProbeServerService.readAll(in);
            }
            if (raw.length == 32) return raw;
            String s = new String(raw, StandardCharsets.US_ASCII).trim();
            if (s.length() == 64) {
                byte[] out = new byte[32];
                for (int i = 0; i < 32; i++) {
                    int hi = hexVal(s.charAt(2 * i));
                    int lo = hexVal(s.charAt(2 * i + 1));
                    if (hi < 0 || lo < 0) return null;
                    out[i] = (byte) ((hi << 4) | lo);
                }
                return out;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
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
        st.bootJson = bootJson();
        if (!"null".equals(st.bootJson)) st.bootSize = (int) new File(BOOT_ANIMATION_PATH).length();
        st.mirrorVersion = ProbeServerService.getSysProp("ro.build.version.mirror");
        st.fingerprint = ProbeServerService.getSysProp("ro.build.fingerprint");
        String serial = ProbeServerService.getSysProp("ro.serialno");
        st.serialno = (serial == null || serial.isEmpty()) ? null : serial;
        return st;
    }

    /**
     * Stock's boot animation as the {@code "bootAnimation"} object: path, size, SHA-256, FIEMAP (or null without
     * libfiemap) and the bytes as base64. {@code "null"} when it's missing, unreadable or bigger than
     * {@link #BOOT_MAX_BYTES}: Kiln then leaves the boot animation as it is.
     */
    private static String bootJson() {
        File f = new File(BOOT_ANIMATION_PATH);
        if (!f.isFile() || f.length() <= 0 || f.length() > BOOT_MAX_BYTES) return "null";
        try {
            byte[] b;
            try (InputStream in = new FileInputStream(f)) {
                b = ProbeServerService.readAll(in);
            }
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(b));
            String fiemap = null;
            if (Fiemap.isAvailable()) {
                String j = Fiemap.queryJson(BOOT_ANIMATION_PATH);
                if (j != null && j.trim().startsWith("{") && j.trim().endsWith("}")) fiemap = j.trim();
            }
            return "{\"path\":" + jsonStr(BOOT_ANIMATION_PATH)
                    + ",\"size\":" + b.length
                    + ",\"sha256\":" + jsonStr(sha)
                    + ",\"fiemap\":" + (fiemap == null ? "null" : fiemap)
                    + ",\"base64\":" + jsonStr(Base64.encodeToString(b, Base64.NO_WRAP)) + "}";
        } catch (Throwable t) {
            return "null";
        }
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
