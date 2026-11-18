package sh.edm.s1kiosk.manage

import android.content.Context
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.auth.ThreadJoin
import dev.glassworks.manage.AppManage

/**
 * The S1's Manage page (#33): the core's [AppManage], plus what only the S1 has. The radio's row links the
 * Thread network share, the QR pages serve the Thread join, the diagnostics end with the root side's
 * properties, and the log names the radio, time zone and wall base.
 */
class S1Manage(
    context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs,
    /** The version the page shows ([dev.glassworks.platform.AppBuild.shown]); a test passes a test build's. */
    version: String = runtime.build.shown,
) : AppManage(context, runtime, signIn, tokens, prefs, version, S1) {

    companion object {
        const val TAG = AppManage.TAG

        /** How long a restart or reboot waits, so the page that started it reaches the browser first. */
        const val ACTION_DELAY_MS = AppManage.ACTION_DELAY_MS

        /** The S1's log tags, in words, for the Manage log: its own, then the core's. The rest (Gecko, Android) stays out. */
        fun logSource(tag: String): String? = s1LogSource(tag) ?: AppManage.logSource(tag)

        private fun s1LogSource(tag: String): String? = when {
            tag.startsWith("S1Kiosk.Radio") || tag.startsWith("S1Radio") -> "Radio"
            tag.startsWith("S1Kiosk.TimeZone") -> "Time zone"
            tag == "S1Base" -> "Wall base"
            else -> null
        }

        private val ROOT_PROPS = listOf("sys.s1kiosk.radio.status", "sys.s1kiosk.radio.version", "sys.s1kiosk.radio.fw", "sys.s1kiosk.net.status",
            "sys.s1kiosk.update.version", "sys.s1kiosk.timezone.status", "persist.s1kiosk.radio_mode", "persist.s1kiosk.radio_zigbee")

        internal val S1 = Own(
            paneLinks = { id -> if (id == "radio") listOf("Share Home Assistant's Thread network (start it on the panel first)" to ThreadJoin.PATH) else emptyList() },
            qrPaths = setOf(ThreadJoin.PATH, SignInServer.THREAD_CONTINUE, ThreadJoin.CALLBACK),
            diagnostics = {
                appendLine()
                appendLine("Root side:")
                for (k in ROOT_PROPS) appendLine("  $k = ${AppManage.prop(k)}")
            },
            logSource = ::s1LogSource,
            licence = "MIT, with the Glassworks core under MPL-2.0",
        )
    }
}
