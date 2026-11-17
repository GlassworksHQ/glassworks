package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.testing.FakeAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.auth.FollowedSignIn
import sh.edm.s1kiosk.auth.HaSeen
import sh.edm.s1kiosk.auth.SignInMove
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.discovery.HaDiscovery
import java.io.File

/**
 * #467 over the real files: Home Assistant's uuid is kept with the address discovery found and with the sign-in, a
 * sign-in stored before #467 (no `ha_uuid` in `auth.xml`) learns it at its own address, and an https move keeps the sealed
 * refresh token. The sign-in follows HaAuth's wiring ([KioskPrefs.haBase], [KioskPrefs.haUrlUuid]). RFC 5737 hosts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignInMovePrefsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val old = "https://192.0.2.20:8123"
    private val new = "https://192.0.2.21:8123"
    private val ours = "0123456789abcdef0123456789abcdef"
    private val token = "made-up-refresh-token-0f1e2d3c"

    @Before fun keyStore() = FakeAndroidKeyStore.install()
    @After fun noKeyStore() = FakeAndroidKeyStore.uninstall()

    private fun followed(prefs: KioskPrefs) = FollowedSignIn(
        load = { prefs.auth }, save = { prefs.auth = it }, base = { prefs.haBase },
        seen = { prefs.haUrlUuid.takeIf { it.isNotEmpty() }?.let { HaSeen(prefs.haUrl, it) } }, log = {},
    )

    @Test fun theUuidStaysWithTheAddressItCameWith() {
        val prefs = KioskPrefs(app)
        prefs.setHaUrl(old, HaDiscovery.SOURCE_MDNS, ours)
        assertEquals(ours, KioskPrefs(app).haUrlUuid)
        prefs.setHaUrl(old, HaDiscovery.SOURCE_ESPHOME, null)
        assertEquals("", KioskPrefs(app).haUrlUuid)
    }

    @Test fun aSignInFromBeforeLearnsItsUuidThenFollowsHomeAssistant() {
        val prefs = KioskPrefs(app)
        prefs.auth = StoredAuth(old, "http://192.0.2.10:8099/", token)
        assertFalse("written as before #467", File(app.dataDir, "shared_prefs/auth.xml").readText().contains("ha_uuid"))
        prefs.setHaUrl(new, HaDiscovery.SOURCE_MDNS, ours)
        assertEquals(SignInMove.Unconfirmed, followed(prefs).now()?.second)

        prefs.setHaUrl(old, HaDiscovery.SOURCE_MDNS, ours)
        assertEquals(SignInMove.Stay, followed(prefs).now()?.second)
        assertEquals(ours, KioskPrefs(app).auth?.haUuid)

        prefs.setHaUrl(new, HaDiscovery.SOURCE_MDNS, ours)
        assertEquals(SignInMove.Stay, followed(prefs).now()?.second)
        val moved = KioskPrefs(app).auth
        assertEquals(StoredAuth(new, "http://192.0.2.10:8099/", token, ours), moved)
        assertTrue(KioskPrefs(app).hasAuth)
    }

    @Test fun signingOutDropsTheUuid() {
        val prefs = KioskPrefs(app)
        prefs.auth = StoredAuth(old, "http://192.0.2.10:8099/", token, ours)
        prefs.auth = null
        assertFalse(File(app.dataDir, "shared_prefs/auth.xml").readText().contains("ha_uuid"))
        prefs.auth = StoredAuth(old, "http://192.0.2.10:8099/", token)
        assertNull(KioskPrefs(app).auth?.haUuid)
    }
}
