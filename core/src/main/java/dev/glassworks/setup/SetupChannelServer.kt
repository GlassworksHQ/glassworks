package dev.glassworks.setup

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.improv.AdbSocketServer
import dev.glassworks.Glassworks
import dev.glassworks.InstallerGlue
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.dashboard.Dashboard
import dev.glassworks.dashboard.DashboardOptions
import dev.glassworks.dashboard.Dashboards
import dev.glassworks.provisioning.ImprovController
import dev.glassworks.provisioning.ImprovDone
import dev.glassworks.improv.ScannedNetwork
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The setup channel on `@s1kiosk_setup` (adb `localabstract:`), started by KioskService. Thin glue:
 * the protocol is [SetupChannel]; this reads the app's state and applies changes the same way the
 * panel itself does. The panes' setup steps (Glassworks.paneDefinitions) bring their own state
 * and requests; the Panes step is the panel screen's own (Glassworks.panesStep).
 */
object SetupChannelServer {
    private const val TAG = "Glassworks.SetupChannel"

    /** Improv's scan waits up to 15 s (it may turn Wi-Fi on first); a second more for the answer. */
    private const val SCAN_WAIT_MS = 16_000L

    private var server: AdbSocketServer? = null

    fun start(context: Context, runtime: Glassworks) {
        if (server != null) return
        val channel = channels(context, runtime, AndroidBackend(context.applicationContext, runtime, ImprovDone.Via.COMPUTER))
        server = AdbSocketServer(SetupChannel.SOCKET, TAG) { input, output -> channel { true }.serve(input, output) }.also { it.start() }
    }

    /**
     * Makes a channel per connection, over the panes' steps as setup takes them (Glassworks.setupSteps). [mayChange]
     * is the LAN's pairing gate; over USB every request may run. Public for tests.
     */
    fun channels(context: Context, runtime: Glassworks, backend: SetupChannel.Backend): (mayChange: () -> Boolean) -> SetupChannel {
        val steps = runtime.setupSteps(context)
        val panes = runtime.panesStep(context)
        return { mayChange -> SetupChannel(backend, steps, { Log.i(TAG, it) }, mayChange, panes) }
    }

    /**
     * The setup channel's loop for the LAN's WebSocket (dev.glassworks.setup.LanSetup): the same channel as USB's,
     * gated by the browser's pairing.
     */
    fun lan(context: Context, runtime: Glassworks): (InputStream, OutputStream, () -> Boolean) -> Unit {
        // Wi-Fi on this page is the hotspot's case: a panel on a network skips the step.
        val channel = channels(context, runtime, AndroidBackend(context.applicationContext, runtime, ImprovDone.Via.SETUP_PAGE))
        return { input, output, mayChange -> channel(mayChange).serve(input, output) }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** [via]: where this channel's Wi-Fi comes from, for the panel's done card. */
    private class AndroidBackend(private val ctx: Context, private val runtime: Glassworks, private val via: ImprovDone.Via) : SetupChannel.Backend {
        private val prefs = KioskPrefs(ctx)

        init { HaAuth.init(ctx) }

        private fun knownDashboards(): List<Dashboard> =
            Dashboards.latest ?: DashboardOptions.parse(prefs.dashboardsJson).orEmpty()

        override fun state(): ChannelState {
            val ip = dev.glassworks.platform.PanelAddress.now(ctx)
            val haUrl = prefs.haBase.orEmpty()
            val signedIn = HaAuth.signedIn
            if (signedIn && Dashboards.latest == null) HaAuth.ensureDashboards()
            val list = if (signedIn) knownDashboards() else emptyList()
            val base = prefs.settingBase
            val current = if (signedIn && base != null) DashboardOptions.currentOption(prefs.urlSetting, base, list) else null
            val options = if (signedIn && base != null && (list.isNotEmpty() || current == DashboardOptions.CUSTOM)) {
                DashboardOptions.pickerOptions(list, current!!)
            } else null
            return ChannelState(
                ip = ip,
                online = dev.glassworks.platform.NetworkUp.now(ctx),
                touch = runtime.frame.input != dev.glassworks.frame.Input.NONE,
                ha = ChannelState.Ha(
                    discovered = ChannelState.discovered(prefs.haUrl, prefs.haUrlSource),
                    url = haUrl,
                    signedIn = signedIn,
                    signinUrl = ChannelState.signinUrl(ip, haUrl),
                    dashboards = options,
                    dashboard = if (options != null) current else null,
                ),
            )
        }

        override fun hotspotNetworks(): List<WifiNetwork>? = runtime.hotspotScan.takeIf { runtime.setupHotspot?.isOn == true }

        override fun wifiRadioLocked(): Boolean = dev.glassworks.platform.WifiRadio.lockedOff(ctx)

        /** A fresh scan through Improv's provisioner, as Kiln's USB scan (ImprovSerialServer). */
        override fun scanWifi(): List<WifiNetwork> {
            val got = AtomicReference<List<ScannedNetwork>>(emptyList())
            val done = CountDownLatch(1)
            val started = InstallerGlue.onMain { ImprovController.scanFromInstaller { got.set(it); done.countDown() } } == true
            if (!started) return emptyList()
            done.await(SCAN_WAIT_MS, TimeUnit.MILLISECONDS)
            return got.get().map { WifiNetwork(it.ssid, it.rssi, secure = it.auth != "NO") }
        }

        /** Joins as an installer's join, so the panel's Wi-Fi step shows the progress and the outcome. */
        override fun joinWifi(ssid: String, password: String): SetupChannel.Change {
            Log.i(TAG, "Wi-Fi details from the setup page: joining")
            return SetupChannel.Change(afterReply = {
                InstallerGlue.post {
                    if (!ImprovController.connectFromInstaller(ssid, password, via)) Log.w(TAG, "Wi-Fi: the panel's Wi-Fi isn't ready yet")
                }
            })
        }

        override fun armPairing(): String? {
            prefs.armLanSetup()
            Log.i(TAG, "Pairing over the network re-armed over the setup channel")
            return null
        }

        /**
         * As the `url` intent extra (MainActivity.applyIntent): saves the dashboard URL setting, then
         * reloads the dashboard, or restarts the process when what the engine starts with changed: the
         * secure-context hosts, or the dashboard's origin (KioskPrefs.engineStart; the legacy engine holds
         * pages to it for the life of the process, #198). The setting is saved first so the reply already has
         * it; the reload/restart runs after the reply.
         */
        override fun setHa(url: String): SetupChannel.Change {
            val restart = InstallerGlue.onMain {
                val before = prefs.engineStart()
                prefs.urlSetting = url
                prefs.engineStart() != before
            } ?: return SetupChannel.Change(error = "The panel didn't save the address; try again.")
            Log.i(TAG, "Home Assistant address set from the installer: $url${if (restart) " (restarting: the engine starts with it)" else ""}")
            return SetupChannel.Change(afterReply = {
                InstallerGlue.post(if (restart) 300L else 0L) {
                    val sink = runtime.commandSink
                    if (sink == null) Log.i(TAG, "dashboard not running; it loads the new address when it starts")
                    else sink(if (restart) Intent().putExtra("restart", true) else Intent().putExtra("url", url))
                }
            })
        }

        override fun setDashboard(dashboard: String): SetupChannel.Change {
            val list = knownDashboards()
            val base = prefs.settingBase ?: return SetupChannel.Change(error = "Home Assistant isn't known yet.")
            val current = DashboardOptions.currentOption(prefs.urlSetting, base, list)
            if (dashboard == current || dashboard == DashboardOptions.CUSTOM) return SetupChannel.Change()
            val url = DashboardOptions.urlFor(dashboard, prefs.urlSetting, base, prefs.haUrl, list)
                ?: return SetupChannel.Change(error = "Unknown dashboard '$dashboard'.")
            val saved = InstallerGlue.onMain {
                prefs.urlSetting = url
                true
            } ?: return SetupChannel.Change(error = "The panel didn't save the dashboard; try again.")
            if (!saved) return SetupChannel.Change(error = "The panel didn't save the dashboard; try again.")
            Log.i(TAG, "Dashboard set from setup: $dashboard ($url)")
            return SetupChannel.Change(afterReply = {
                InstallerGlue.post {
                    val sink = runtime.commandSink
                    if (sink == null) Log.i(TAG, "dashboard not running; it loads the chosen dashboard when it starts")
                    else sink(Intent().putExtra("url", url))
                }
            })
        }
    }
}
