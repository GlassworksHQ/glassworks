// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import android.content.pm.PackageInstaller;
import android.content.pm.PermissionInfo;

/** The probe's log lines, apart from Android so the JVM tests can check them. */
final class Probe {
    static final String TAG = "GwProbe";

    private Probe() {}

    static String fact(String key, String value) {
        return "FACT " + key + " " + value;
    }

    static String result(String action, boolean ok, String detail) {
        return "RESULT " + action + " " + (ok ? "OK" : "FAIL") + " " + detail;
    }

    /** The final line for a PackageInstaller commit, from its EXTRA_STATUS and EXTRA_STATUS_MESSAGE. */
    static String installResult(int status, String message) {
        String msg = message == null || message.isEmpty() ? "" : ": " + message;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                return result("update", true, "silent update, no tap");
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                return result("update", false, "needs a tap (STATUS_PENDING_USER_ACTION: no INSTALL_PACKAGES)");
            default:
                return result("update", false, statusName(status) + msg);
        }
    }

    static String statusName(int status) {
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: return "STATUS_PENDING_USER_ACTION";
            case PackageInstaller.STATUS_SUCCESS: return "STATUS_SUCCESS";
            case PackageInstaller.STATUS_FAILURE: return "STATUS_FAILURE";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "STATUS_FAILURE_BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "STATUS_FAILURE_ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "STATUS_FAILURE_INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "STATUS_FAILURE_CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "STATUS_FAILURE_STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "STATUS_FAILURE_INCOMPATIBLE";
            default: return "status " + status;
        }
    }

    /**
     * ApplicationInfo.privateFlags and PRIVATE_FLAG_PRIVILEGED are hidden API, read by reflection:
     * null when that failed.
     */
    static String privileged(Integer privateFlags, Integer privilegedBit) {
        if (privateFlags == null || privilegedBit == null) return "unknown";
        return (privateFlags & privilegedBit) != 0 ? "yes" : "no";
    }

    /** adb's public key: RSAPublicKey from adb's adb_auth (2048-bit): len, n0inv, n[64], rr[64], exponent. */
    static final int ADB_KEY_BYTES = 4 + 4 + 256 + 256 + 4;

    /**
     * Why `line` isn't an adb public key line as ~/.android/adbkey.pub holds one ("<base64> user@host"),
     * or null when it is. The key itself never goes into a log line: only keyFingerprint does.
     */
    static String adbKeyProblem(String line) {
        if (line == null || line.trim().isEmpty()) return "no --es key <adb public key line>";
        // Android appends the string it's given to adb_keys as one line: a line break would add another key
        if (line.trim().indexOf('\n') >= 0 || line.trim().indexOf('\r') >= 0) return "the key must be one line";
        byte[] key = decodeBase64(adbKeyToken(line));
        if (key == null) return "the key isn't base64";
        if (key.length != ADB_KEY_BYTES) return "the key is " + key.length + " bytes, not adb's " + ADB_KEY_BYTES;
        if (le32(key, 0) != 64) return "the key isn't a 2048-bit adb key (len " + le32(key, 0) + ", not 64)";
        return null;
    }

    /** The base64 key alone, without user@host: what adbKeyProblem checks, and all the probe passes to Android. */
    static String adbKeyToken(String line) {
        return line.trim().split("\\s+")[0];
    }

    /** "sha256:<first 16 hex digits>" of the decoded key: enough to match it, never the key itself. */
    static String keyFingerprint(String line) {
        byte[] key = decodeBase64(adbKeyToken(line));
        if (key == null) return "sha256:?";
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder s = new StringBuilder("sha256:");
            for (int i = 0; i < 8; i++) s.append(String.format("%02x", d[i] & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "sha256:?";
        }
    }

    /** allow-adb-key's line once Android took the call: what it means, and the fingerprint only. */
    static String adbKeySent(String fingerprint) {
        return result("allow-adb-key", true, "sent " + fingerprint + " (alwaysAllow=true): Android writes it only if it matches"
            + " the last computer adb asked about; check /data/misc/adb/adb_keys");
    }

    /** allow-adb-key's line when the call threw: the exception's class and message, and the fingerprint only. */
    static String adbKeyFailed(String fingerprint, Throwable t) {
        return result("allow-adb-key", false, fingerprint + " " + t.getClass().getName() + ": " + t.getMessage());
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    /** Plain base64 with padding, as adbkey.pub writes it. Android 6 has no java.util.Base64, and the JVM tests no android.util.Base64. */
    static byte[] decodeBase64(String s) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        if (s.isEmpty() || s.length() % 4 != 0) return null;
        int pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
        byte[] out = new byte[s.length() / 4 * 3 - pad];
        int o = 0, acc = 0, bits = 0;
        for (int i = 0; i < s.length() - pad; i++) {
            int v = abc.indexOf(s.charAt(i));
            if (v < 0) return null;
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; out[o++] = (byte) (acc >> bits); }
        }
        return o == out.length ? out : null;
    }

    /** A permission's protection level as the manifest writes it, e.g. signature|privileged. */
    static String protection(int level) {
        StringBuilder s = new StringBuilder();
        switch (level & PermissionInfo.PROTECTION_MASK_BASE) {
            case PermissionInfo.PROTECTION_NORMAL: s.append("normal"); break;
            case PermissionInfo.PROTECTION_DANGEROUS: s.append("dangerous"); break;
            case PermissionInfo.PROTECTION_SIGNATURE: s.append("signature"); break;
            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM: s.append("signatureOrSystem"); break;
            default: s.append("base").append(level & PermissionInfo.PROTECTION_MASK_BASE);
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) s.append("|privileged");
        if ((level & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) s.append("|development");
        return s.toString();
    }
}
