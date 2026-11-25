package dev.glassworks.provisioning

import dev.glassworks.frame.Frame
import dev.glassworks.platform.AppBuild
import dev.glassworks.improv.*
import android.content.Context
import android.util.Log
import dev.glassworks.platform.PanelAddress
import dev.glassworks.InstallerGlue
import dev.glassworks.auth.OAuth
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Improv Serial for the web installer on `@s1kiosk_improv` (adb `localabstract:`), started by
 * KioskService. Thin glue around [ImprovSerialSession]: joins go through
 * [ImprovController.connectFromInstaller], so the setup screen's Wi-Fi step shows their progress
 * exactly like any installer join; scans come from WifiManager ([ImprovController.scanFromInstaller]).
 */
object ImprovSerialServer {
    /** Abstract socket name (adb: `localabstract:s1kiosk_improv`). */
    const val SOCKET = "s1kiosk_improv"
    private const val TAG = "Glassworks.ImprovSerial"
    /** The client waits 20 s for a scan; Wi-Fi may have to be turned on first. */
    private const val SCAN_WAIT_MS = 15_000L

    private var server: ImprovAdbServer? = null

    /** [frame] and [build]: the device and app it speaks for ([ImprovIdentity.serial]); [context]: where the panel's address is read. */
    fun start(context: Context, frame: Frame, build: AppBuild) {
        if (server != null) return
        val info = ImprovIdentity.serial(frame, build)
        val app = context.applicationContext
        server = ImprovAdbServer(SOCKET, TAG) { output ->
            Effects(output, info, panelIp = { PanelAddress.now(app) }, onWifi = { PanelAddress.now(app, wifiOnly = true) != null })
        }.also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /**
     * [panelIp]: the panel's address on the LAN ([PanelAddress]), null while it has none. [onWifi]: whether it has one
     * on Wi-Fi. A fresh session starts PROVISIONED only on Wi-Fi: Ethernet alone would tell an installer that
     * reconnects after an error that its Wi-Fi join went through.
     */
    private class Effects(
        private val out: OutputStream,
        private val info: List<String>,
        private val panelIp: () -> String?,
        private val onWifi: () -> Boolean,
    ) : ImprovSerialSession.Effects {
        override fun send(packet: ByteArray) = synchronized(out) { out.write(packet); out.flush() }

        override fun online(): Boolean = onWifi()

        override fun redirectUrl(): String = redirectFor(panelIp())

        override fun info(): ByteArray = info.let { (firmware, version, chip, name) -> ImprovSerial.info(firmware, version, chip, name) }

        override fun scan(): List<SerialNetwork> {
            val got = AtomicReference<List<ScannedNetwork>>(emptyList())
            val done = CountDownLatch(1)
            val started = InstallerGlue.onMain {
                ImprovController.scanFromInstaller { nets -> got.set(nets); done.countDown() }
            } == true
            if (!started) return emptyList()
            done.await(SCAN_WAIT_MS + 1_000L, TimeUnit.MILLISECONDS)
            return got.get().map { SerialNetwork(it.ssid, it.rssi, secure = it.auth != "NO") }
        }

        override fun connect(ssid: String, password: String, done: (redirect: String?) -> Unit) {
            InstallerGlue.post {
                val ok = ImprovController.connectFromInstaller(ssid, password, ImprovDone.Via.COMPUTER, hidden = null) { o ->
                    when (o) {
                        is WifiProvisioner.Outcome.Connected -> done(redirectFor(o.ip ?: panelIp()))
                        is WifiProvisioner.Outcome.Failed -> done(null)
                    }
                }
                if (!ok) {
                    Log.w(TAG, "Improv controller not ready; Wi-Fi not joined")
                    done(null)
                }
            }
        }
    }

    /** The panel's sign-in page, the same link as its QR code ("" without an address). */
    fun redirectFor(ip: String?): String = ip?.let { "http://$it:${OAuth.SIGNIN_PORT}/signin" } ?: ""

    internal const val SCAN_TIMEOUT_MS = SCAN_WAIT_MS
}
