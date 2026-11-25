/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import android.content.Context
import dev.glassworks.KioskPrefs
import dev.glassworks.secrets.SecretStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The first sign-in after an install ran out ("This link has expired", naming every start): Kiln's Home Assistant
 * step saves the address (`set_ha`), which restarts the app 300 ms after its reply when the address is new to the
 * engine (KioskPrefs.engineStart), and opens the sign-in window on that reply. The old process handed out the state;
 * Home Assistant sent the person back to the new one, which had never heard of it. The second try found the address
 * already set, so nothing restarted, and it worked.
 *
 * A "process" here is what the app builds again after a restart: KioskPrefs over the same files and the sign-in
 * server's store ([HaAuth.signInStates]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInRestartTest {
    private val app = RuntimeEnvironment.getApplication()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = SecretStore { key }

    @Before fun clean() {
        app.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun process() = HaAuth.signInStates(KioskPrefs(app, secrets))

    @Test fun aSignInStartedBeforeTheAppRestartsIsKnownAfterIt() {
        val before = process()
        val sign = before.create("http://192.0.2.20:8099/", "http://192.0.2.20:8099/callback", "http://192.0.2.10:8123", then = Then.KILN)

        val after = process() // exitProcess(0); KioskService starts the server again
        val p = after.consume(sign.state)
        assertNotNull("the new process knows the sign-in Home Assistant sends back", p)
        assertEquals(Then.KILN, p!!.then)
        assertEquals("the verifier came across, so the code still trades", sign.codeChallenge, OAuth.codeChallenge(p.codeVerifier))
        assertNull("still single use", process().consume(sign.state))
    }

    @Test fun theVerifierIsSealedInTheSettingsFile() {
        process().create("http://192.0.2.20:8099/", "http://192.0.2.20:8099/callback", "http://192.0.2.10:8123")
        val raw = app.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("pending_signins", null)
        assertNotNull(raw)
        assertTrue(raw!!, SecretStore.isSealed(raw))
        assertFalse(raw, raw.contains("192.0.2.10"))
    }

    @Test fun nothingIsLeftOnceTheSignInIsUsed() {
        val s = process()
        val sign = s.create("http://192.0.2.20:8099/", "http://192.0.2.20:8099/callback", "http://192.0.2.10:8123")
        assertNotNull(s.consume(sign.state))
        assertFalse(app.getSharedPreferences("auth", Context.MODE_PRIVATE).contains("pending_signins"))
    }

    @Test fun aFactoryResetForgetsTheSignInsInProgress() {
        val s = process()
        val sign = s.create("http://192.0.2.20:8099/", "http://192.0.2.20:8099/callback", "http://192.0.2.10:8123")
        s.clear() // what HaAuth.forgetPendingSignIns does to the running server's store
        assertNull("none finishes before the restart", s.consume(sign.state))
        assertFalse(app.getSharedPreferences("auth", Context.MODE_PRIVATE).contains("pending_signins"))
        assertNull("nor after it", process().consume(sign.state))
    }
}
