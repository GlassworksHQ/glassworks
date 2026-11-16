package sh.edm.s1kiosk.setup

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dev.glassworks.improv.AdbSocketServer
import sh.edm.s1kiosk.Glassworks
import sh.edm.s1kiosk.InstallerGlue
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.improv.ImprovController
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
 * and requests.
 */
object SetupChannelServer {
    private const val TAG = "S1Kiosk.SetupChannel"

    /** Improv's scan waits up to 15 s (it may turn Wi-Fi on first); a second more for the answer. */
    private const val SCAN_WAIT_MS = 16_000L

    private var server: AdbSocketServer? = null

    fun start(context: Context, runtime: Glassworks) {
        if (server != null) return
        val channel = channels(context, runtime, AndroidBackend(context.applicationContext, runtime))
        server = AdbSocketServer(SetupChannel.SOCKET, TAG) { input, output -> channel { true }.serve(input, output) }.also { it.start() }
    }

    /**
     * Makes a channel per connection, over the panes' steps as setup takes them (Glassworks.setupSteps). [mayChange]
     * is the LAN's pairing gate; over USB every request may run. Public for tests.
     */
    fun channels(context: Context, runtime: Glassworks, backend: SetupChannel.Backend): (mayChange: () -> Boolean) -> SetupChannel {
        val steps = runtime.setupSteps(context)
        return { mayChange -> SetupChannel(backend, steps, { Log.i(TAG, it) }, mayChange) }
    }

    /**
     * The setup channel's loop for the LAN's WebSocket (dev.glassworks.setup.LanSetup): the same channel as USB's,
     * gated by the browser's pairing.
     */
    fun lan(context: Context, runtime: Glassworks): (InputStream, OutputStream, () -> Boolean) -> Unit {
        val channel = channels(context, runtime, AndroidBackend(context.applicationContext, runtime))
        return { input, output, mayChange -> channel(mayChange).serve(input, output) }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private class AndroidBackend(private val ctx: Context, private val runtime: Glassworks) : SetupChannel.Backend {
        private val prefs = KioskPrefs(ctx)

        init { HaAuth.init(ctx) }

        override fun state(): ChannelState {
            val ip = InstallerGlue.wlanIpv4()
            val haUrl = prefs.haBase.orEmpty()
            return ChannelState(
                ip = ip,
                online = online(),
                ha = ChannelState.Ha(
                    discovered = ChannelState.discovered(prefs.haUrl, prefs.haUrlSource),
                    url = haUrl,
                    signedIn = HaAuth.signedIn,
                    signinUrl = ChannelState.signinUrl(ip, haUrl),
                ),
            )
        }

        /** A Wi-Fi or Ethernet network is up. The panel's own setup hotspot isn't one: Android lists it as no network. */
        private fun online(): Boolean = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            @Suppress("DEPRECATION") // allNetworks: the active network can be another one while Wi-Fi is up too
            (listOfNotNull(cm.activeNetwork) + cm.allNetworks).any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
        }.getOrDefault(false)

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
            Log.i(TAG, "Wi-Fi details from the setup page: joining (${password.length} character password)")
            return SetupChannel.Change(afterReply = {
                InstallerGlue.post {
                    if (!ImprovController.connectFromInstaller(ssid, password)) Log.w(TAG, "Wi-Fi: the panel's Wi-Fi isn't ready yet")
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
    }
}
