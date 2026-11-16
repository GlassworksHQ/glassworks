// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;

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

    /** An adb public key line as adbkey.pub holds one: RSAPublicKey (len 64, …) as base64, then user@host. */
    private static String keyLine(int len, int bytes) {
        byte[] k = new byte[bytes];
        new Random(7).nextBytes(k);
        k[0] = (byte) len; k[1] = 0; k[2] = 0; k[3] = 0;
        return Base64.getEncoder().encodeToString(k) + " probe-test@glassworks";
    }

    @Test
    public void anAdbKeyLineIsCheckedBeforeAnythingIsCalled() {
        assertNull(Probe.adbKeyProblem(keyLine(64, 524)));
        assertNull(Probe.adbKeyProblem("  " + keyLine(64, 524) + "\n")); // as $(cat adbkey.pub) passes it
        assertEquals("no --es key <adb public key line>", Probe.adbKeyProblem(null));
        assertEquals("no --es key <adb public key line>", Probe.adbKeyProblem("   "));
        assertEquals("the key isn't base64", Probe.adbKeyProblem("not*base64 x@y"));
        assertEquals("the key is 300 bytes, not adb's 524", Probe.adbKeyProblem(keyLine(64, 300)));
        assertEquals("the key isn't a 2048-bit adb key (len 32, not 64)", Probe.adbKeyProblem(keyLine(32, 524)));
    }

    @Test
    public void theFingerprintIsASha256PrefixOfTheKeyAndNeverTheKey() throws Exception {
        String line = keyLine(64, 524);
        byte[] key = Base64.getDecoder().decode(line.split(" ")[0]);
        byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
        StringBuilder want = new StringBuilder("sha256:");
        for (int i = 0; i < 8; i++) want.append(String.format("%02x", d[i] & 0xff));
        assertEquals(want.toString(), Probe.keyFingerprint(line));
        String sent = Probe.adbKeySent(Probe.keyFingerprint(line));
        assertTrue(sent.startsWith("RESULT allow-adb-key OK sent sha256:"));
        assertFalse(sent.contains(line.split(" ")[0].substring(0, 24)));
        String failed = Probe.adbKeyFailed(Probe.keyFingerprint(line), new SecurityException("Neither user 10042 nor current process has android.permission.MANAGE_USB."));
        assertEquals("RESULT allow-adb-key FAIL " + want + " java.lang.SecurityException: Neither user 10042 nor current process has android.permission.MANAGE_USB.", failed);
    }

    @Test
    public void base64DecodesAsTheJdkDoes() {
        Random r = new Random(1);
        for (int n = 1; n < 40; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            assertArrayEquals(b, Probe.decodeBase64(Base64.getEncoder().encodeToString(b)));
        }
        assertNull(Probe.decodeBase64("abc"));
        assertNull(Probe.decodeBase64(""));
    }
}
