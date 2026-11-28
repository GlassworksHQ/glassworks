package dev.glassworks.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pages get the microphone, the camera and their permissions with no prompt (#847). */
class TrustedPagesTest {
    private val ha = "http://192.0.2.10:8123"
    private val hosts = listOf("192.0.2.10", "panel.example")

    @Test fun theSignedInHomeAssistantIsTrusted() {
        assertTrue(TrustedPages.trusts("$ha/lovelace/0?external_auth=1", ha, emptyList()))
        // Another origin of the same Home Assistant is not the sign-in's, unless it is a secure host.
        assertFalse(TrustedPages.trusts("https://192.0.2.10:8123/", ha, emptyList()))
    }

    @Test fun secureHostsAreTrustedOnAnyPortOverHttpOrHttps() {
        assertTrue(TrustedPages.trusts("http://192.0.2.10:8123/lovelace/0", null, hosts))
        assertTrue(TrustedPages.trusts("https://PANEL.example/cam", null, hosts))
        assertTrue(TrustedPages.trusts("http://panel.example:1984/webrtc.html", null, hosts))
    }

    /** Clarity's fake while a test holds the screen: its test dashboard's exact origin, not its host's other ports. */
    @Test fun theTestDashboardsOriginOnlyIsTrusted() {
        val test = "http://10.0.0.41:8089/clarity-ref/start"
        assertTrue(TrustedPages.trusts("http://10.0.0.41:8089/clarity-ref/heavy", null, emptyList(), test))
        assertFalse(TrustedPages.trusts("http://10.0.0.41:9000/", null, emptyList(), test))
        assertFalse(TrustedPages.trusts("https://10.0.0.41:8089/", null, emptyList(), test))
        assertFalse(TrustedPages.trusts("http://10.0.0.42:8089/", null, emptyList(), test))
        assertFalse(TrustedPages.trusts("http://10.0.0.41:8089/", null, emptyList(), null))
    }

    @Test fun anyOtherPageIsNot() {
        assertFalse(TrustedPages.trusts("http://192.0.2.66/", ha, hosts))
        assertFalse(TrustedPages.trusts("https://evil.example/", ha, hosts))
        // A host that only starts or ends like a trusted one.
        assertFalse(TrustedPages.trusts("http://192.0.2.100:8123/", ha, hosts))
        assertFalse(TrustedPages.trusts("http://panel.example.evil.example/", ha, hosts))
        // The trusted host as the user part of another host's URL.
        assertFalse(TrustedPages.trusts("http://192.0.2.10@192.0.2.66/", ha, hosts))
    }

    @Test fun pagesWithNoHostOrAnotherSchemeAreNot() {
        listOf(null, "", "about:blank", "data:text/html,hi", "blob:$ha/1234", "file:///sdcard/x.html", "moz-extension://abc/page.html")
            .forEach { assertFalse("$it", TrustedPages.trusts(it, ha, hosts)) }
    }

    @Test fun nothingIsTrustedBeforeHomeAssistantIsKnown() {
        assertFalse(TrustedPages.trusts("$ha/", null, emptyList()))
        assertFalse(TrustedPages.trusts("$ha/", null, listOf("", " ")))
    }
}
