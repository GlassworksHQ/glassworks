package dev.glassworks.improv

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * No network name in the provisioner's log (#460, AGENTS.md rule 2): a join that runs to its outcome logs neither the
 * chosen network's name nor its password, and an outcome prints neither the network nor the one it went back to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WifiProvisionerLogTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aJoinToItsOutcomeLogsNeitherTheNameNorThePassword() {
        @Suppress("DEPRECATION") ctx.getSystemService(WifiManager::class.java).setWifiEnabled(true)
        ShadowLog.clear()
        var outcome: WifiProvisioner.Outcome? = null
        WifiProvisioner(ctx, Handler(Looper.getMainLooper())).connect("Example-Home-Net", "correct horse") { outcome = it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertNotNull("the join ended", outcome)
        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }
        assertTrue("the provisioner logged the join", lines.any { "chosen network" in it })
        assertTrue(lines.joinToString("\n"), lines.none { "Example-Home-Net" in it || "correct horse" in it })
    }

    @Test
    fun anOutcomePrintsNoNetworkName() {
        val shown = listOf(
            WifiProvisioner.Outcome.Connected("Example-Home-Net", "192.0.2.10", "addNetwork"),
            WifiProvisioner.Outcome.Failed("timed out", restored = "Example-Old-Net"),
        ).map { it.toString() }
        assertTrue(shown.toString(), shown.none { "Example-Home-Net" in it || "Example-Old-Net" in it })
    }
}
