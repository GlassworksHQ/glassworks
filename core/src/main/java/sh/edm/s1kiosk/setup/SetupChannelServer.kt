package sh.edm.s1kiosk.setup

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.improv.AdbSocketServer
import sh.edm.s1kiosk.Glassworks
import sh.edm.s1kiosk.InstallerGlue
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaAuth
import java.io.InputStream
import java.io.OutputStream

/**
 * The setup channel on `@s1kiosk_setup` (adb `localabstract:`), started by KioskService. Thin glue:
 * the protocol is [SetupChannel]; this reads the app's state and applies changes the same way the
 * panel itself does. The panes' setup steps (Glassworks.paneDefinitions) bring their own state
 * and requests.
 */
object SetupChannelServer {
    private const val TAG = "S1Kiosk.SetupChannel"

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
                ha = ChannelState.Ha(
                    discovered = ChannelState.discovered(prefs.haUrl, prefs.haUrlSource),
                    url = haUrl,
                    signedIn = HaAuth.signedIn,
                    signinUrl = ChannelState.signinUrl(ip, haUrl),
                ),
            )
        }

        override fun armPairing(): String? {
            prefs.armLanSetup()
            Log.i(TAG, "Pairing over the network re-armed over the setup channel")
            return null
        }

        /**
         * As the `url` intent extra (MainActivity.applyIntent): saves the dashboard URL setting, then
         * reloads the dashboard, or restarts the process when the secure-context hosts changed. The
         * setting is saved first so the reply already has it; the reload/restart runs after the reply.
         */
        override fun setHa(url: String): SetupChannel.HaChange {
            val restart = InstallerGlue.onMain {
                val before = prefs.secureHosts()
                prefs.urlSetting = url
                prefs.secureHosts() != before
            } ?: return SetupChannel.HaChange(error = "The panel didn't save the address; try again.")
            Log.i(TAG, "Home Assistant address set from the installer: $url${if (restart) " (restarting for the secure-context hosts)" else ""}")
            return SetupChannel.HaChange(afterReply = {
                InstallerGlue.post(if (restart) 300L else 0L) {
                    val sink = runtime.commandSink
                    if (sink == null) Log.i(TAG, "dashboard not running; it loads the new address when it starts")
                    else sink(if (restart) Intent().putExtra("restart", true) else Intent().putExtra("url", url))
                }
            })
        }
    }
}
