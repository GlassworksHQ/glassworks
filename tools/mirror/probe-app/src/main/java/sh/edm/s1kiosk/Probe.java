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
