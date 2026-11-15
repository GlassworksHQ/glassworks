package sh.edm.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.bluetoothproxy.BluetoothProxyPrefs
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.diagnostics.DiagnosticsPrefs
import dev.glassworks.notifications.NotificationsPrefs
import sh.edm.s1kiosk.base.WallBasePrefs
import sh.edm.s1kiosk.frame.TimeZonePrefs
import sh.edm.s1kiosk.radio.RadioPrefs
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.voice.VoicePrefs

/**
 * Where this app keeps each pane's settings ([PANE_FILES]), and each pane's typed settings, for the code
 * that builds the panes and their definitions (GlassworksRuntime.paneDefinitions). The core's own
 * settings are in [KioskPrefs].
 */
class PanePrefs(private val context: Context) {

    /** The file that holds the settings of the pane with id [namespace] ([dev.glassworks.pane.PaneContext.prefs]). */
    fun paneFile(namespace: String): SharedPreferences = file(context, namespace)

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
        /** The file that holds the settings of the pane with id [namespace]. */
        fun file(context: Context, namespace: String): SharedPreferences = context.getSharedPreferences(
            PANE_FILES[namespace] ?: throw IllegalArgumentException("No settings file for pane '$namespace'"),
            Context.MODE_PRIVATE,
        )

        /**
         * The settings file of each pane, by pane id. Every pane's settings stay in `kiosk.xml`, where
         * they were before panes: panels update in place, so no stored value may move.
         */
        val PANE_FILES = mapOf(
            "sendspin" to "kiosk", "assist" to "kiosk", "wall-base" to "kiosk", "radio" to "kiosk",
            "time-zone" to "kiosk", "diagnostics" to "kiosk", "bluetooth-proxy" to "kiosk", "notifications" to "kiosk", "camera" to "kiosk",
        )
    }
}
