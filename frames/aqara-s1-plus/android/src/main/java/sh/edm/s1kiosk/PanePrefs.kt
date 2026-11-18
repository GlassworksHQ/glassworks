package sh.edm.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.bluetoothproxy.BluetoothProxyPrefs
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.diagnostics.DiagnosticsPrefs
import dev.glassworks.notifications.NotificationsPrefs
import dev.glassworks.secrets.SecretStore
import sh.edm.s1kiosk.base.WallBasePrefs
import sh.edm.s1kiosk.frame.TimeZonePrefs
import dev.glassworks.radio.RadioPrefs
import dev.glassworks.speaker.SendspinPrefs
import dev.glassworks.assist.VoicePrefs
import dev.glassworks.PaneFiles

/**
 * Where this app keeps each pane's settings ([PANE_FILES]), and each pane's typed settings, for the code
 * that builds the panes and their definitions (S1Panes). The core's own settings are in [KioskPrefs].
 * A file that holds secrets (the camera's password in `kiosk.xml`) opens through SecretFiles ([PaneFiles]), so
 * they read back as they were set.
 */
class PanePrefs(private val context: Context, private val secrets: SecretStore = SecretStore.keystore()) {

    /** The file that holds the settings of the pane with id [namespace] ([dev.glassworks.pane.PaneContext.prefs]). */
    fun paneFile(namespace: String): SharedPreferences = file(context, namespace, secrets)

    val sendspin = SendspinPrefs(paneFile("sendspin"))
    val voice = VoicePrefs(paneFile("assist"))
    val wallBase = WallBasePrefs(paneFile("wall-base"))
    val radio = RadioPrefs(paneFile("radio"))
    val timeZone = TimeZonePrefs(paneFile("time-zone"))
    val diagnostics = DiagnosticsPrefs(paneFile("diagnostics"))
    val camera = CameraPrefs(paneFile("camera"))
    val bluetoothProxy = BluetoothProxyPrefs(paneFile("bluetooth-proxy"))
    val notifications = NotificationsPrefs(paneFile("notifications"))

    companion object {
        /** The file that holds the settings of the pane with id [namespace] ([PaneFiles.open]). */
        fun file(context: Context, namespace: String, secrets: SecretStore = SecretStore.keystore()): SharedPreferences =
            PaneFiles.open(context, namespace, secrets)

        /** The settings file of each pane, by pane id ([PaneFiles.FILES]). */
        val PANE_FILES get() = PaneFiles.FILES
    }
}
