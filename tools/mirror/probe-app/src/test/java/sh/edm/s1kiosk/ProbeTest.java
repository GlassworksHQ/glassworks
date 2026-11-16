// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProbeTest {
    @Test
    public void successIsASilentUpdate() {
        assertEquals("RESULT update OK silent update, no tap", Probe.installResult(0, null));
    }

    @Test
    public void pendingUserActionIsAFailThatNeedsATap() {
        assertEquals("RESULT update FAIL needs a tap (STATUS_PENDING_USER_ACTION: no INSTALL_PACKAGES)", Probe.installResult(-1, "ignored"));
    }

    @Test
    public void failuresNameTheStatusAndKeepAndroidsMessage() {
        // Android 6 reports a signature mismatch as STATUS_FAILURE_CONFLICT (PackageManager.installStatusToPublicStatus)
        assertEquals("RESULT update FAIL STATUS_FAILURE_CONFLICT: INSTALL_FAILED_UPDATE_INCOMPATIBLE: New package has a different signature: sh.edm.s1kiosk",
            Probe.installResult(5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: New package has a different signature: sh.edm.s1kiosk"));
        assertEquals("RESULT update FAIL STATUS_FAILURE_INCOMPATIBLE: INSTALL_FAILED_OLDER_SDK", Probe.installResult(7, "INSTALL_FAILED_OLDER_SDK"));
        assertEquals("RESULT update FAIL STATUS_FAILURE", Probe.installResult(1, ""));
        assertEquals("RESULT update FAIL STATUS_FAILURE_BLOCKED", Probe.installResult(2, null));
        assertEquals("RESULT update FAIL STATUS_FAILURE_ABORTED", Probe.installResult(3, null));
        assertEquals("RESULT update FAIL STATUS_FAILURE_INVALID", Probe.installResult(4, null));
        assertEquals("RESULT update FAIL STATUS_FAILURE_STORAGE", Probe.installResult(6, null));
        assertEquals("RESULT update FAIL status 42: what", Probe.installResult(42, "what"));
    }

    @Test
    public void resultAndFactLinesAreOneGreppableLine() {
        assertEquals("RESULT settings OK global auto_time=1 written back", Probe.result("settings", true, "global auto_time=1 written back"));
        assertEquals("RESULT reboot FAIL x", Probe.result("reboot", false, "x"));
        assertEquals("FACT code-path /system/priv-app/S1Kiosk/S1Kiosk.apk", Probe.fact("code-path", "/system/priv-app/S1Kiosk/S1Kiosk.apk"));
    }

    @Test
    public void privilegedReadsTheBitOrSaysUnknown() {
        // PRIVATE_FLAG_PRIVILEGED is 1 << 3 on Android 6
        assertEquals("yes", Probe.privileged(8 | 1, 8));
        assertEquals("no", Probe.privileged(1, 8));
        assertEquals("unknown", Probe.privileged(null, 8));
        assertEquals("unknown", Probe.privileged(8, null));
    }

    @Test
    public void protectionLevelsReadAsTheManifestWritesThem() {
        assertEquals("signature|privileged", Probe.protection(0x02 | 0x10));
        assertEquals("signature|privileged|development", Probe.protection(0x02 | 0x10 | 0x20));
        assertEquals("dangerous", Probe.protection(0x01));
        assertEquals("normal", Probe.protection(0));
        assertEquals("signatureOrSystem", Probe.protection(0x03));
    }
}
