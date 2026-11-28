package dev.glassworks.s1.manage

import dev.glassworks.s1.frame.S1Names
import android.content.Context
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.auth.ThreadJoin
import dev.glassworks.manage.AppManage
import java.io.File

/**
 * The S1's Manage page (#33): the core's [AppManage], plus what only the S1 has. The radio's row links the
 * Thread network share, the QR pages serve the Thread join, the diagnostics end with the root side's
 * properties, the log names the radio, time zone and wall base, and the Security page lets computers in over USB
 * adb through the frame agent ([S1AdbComputers]).
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
            tag.startsWith("Glassworks.Radio") -> "Radio"
            tag.startsWith("Glassworks.TimeZone") -> "Time zone"
            tag == "Glassworks.Base" -> "Wall base"
            else -> null
        }


        internal val S1 = Own(
            paneLinks = { id -> if (id == "radio") listOf("Share Home Assistant's Thread network (start it on the panel first)" to ThreadJoin.PATH) else emptyList() },
            qrPaths = setOf(ThreadJoin.PATH, SignInServer.THREAD_CONTINUE, ThreadJoin.CALLBACK),
            diagnostics = {
                appendLine()
                appendLine("Root side:")
                // The boot image's names: the loader's on a frame loader image, else 0.4.3's (S1Names).
                for (k in S1Names.of(AppManage::prop).diagnostics) appendLine("  $k = ${AppManage.prop(k)}")
            },
            logSource = ::s1LogSource,
            licence = "MIT, with the Glassworks core under MPL-2.0",
            // The computers over USB adb, which the frame agent keeps in adb_keys: on a frame loader image only.
            usb = { context, dir, event ->
                if (S1Names.of(AppManage::prop) !== S1Names.LOADER) null
                else S1AdbComputers(File(S1AdbComputers.PUB), File(context.filesDir, S1AdbComputers.REQUEST), File(dir, "adb-notes.json"), event)
            },
        )
    }
}
