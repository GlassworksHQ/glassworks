/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException

/**
 * Android's "Allow USB debugging?", answered by the app (#435), for a panel with no touchscreen to answer it: the
 * Mirror, whose app Kiln installs as a privileged system app. Read at `android-6.0.1_r81`
 * (frames/lululemon-mirror/research/privileged-app.md):
 * - adbd hands each computer's key to the framework, which logs `Received public key: <key>` on the system log
 *   (`UsbDebuggingManager.java:138`) and remembers that computer's fingerprint as the one asking.
 * - The hidden `IUsbManager.allowUsbDebugging` (`MANAGE_USB`) lets a key in only when its fingerprint is the one
 *   asking (`:240`). With `alwaysAllow` false it writes nothing to `adb_keys` (`:246`).
 *
 * Android 10 moved this to `IAdbManager` and `AdbDebuggingManager` (`IUsbManager.aidl:117` at `android-9.0.0_r1`
 * has it; 10.0.0_r1 has neither the method nor this log tag), so the app answers on Android 6 to 9 only.
 */
object UsbDebugging {
    private const val MANAGE_USB = "android.permission.MANAGE_USB"
    private const val READ_LOGS = "android.permission.READ_LOGS"

    /**
     * The global setting an installer writes when it opened `allowUsbDebugging` to another permission than
     * `MANAGE_USB`, naming that permission: Kiln's in-place install on a stock Android 6 framework changes the check
     * to `WRITE_SECURE_SETTINGS` and grants that over adb, as it does `READ_LOGS` (an app adb installs can't hold
     * `MANAGE_USB`). Gone with the rest of the device's data when the stock framework comes back.
     */
    const val ALLOW_PERMISSION_SETTING = "glassworks_usb_allow_permission"

    /**
     * Whether this app can see who asks and answer: Android 6 to 9, holding `READ_LOGS` and the permission
     * `allowUsbDebugging` checks, `MANAGE_USB` (a privileged app) or the one an installer named in
     * [ALLOW_PERMISSION_SETTING].
     */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > 28 || !holds(context, READ_LOGS)) return false
        if (holds(context, MANAGE_USB)) return true
        val named = try { Settings.Global.getString(context.contentResolver, ALLOW_PERMISSION_SETTING) } catch (_: Exception) { null }
        return !named.isNullOrBlank() && named.startsWith("android.permission.") && holds(context, named)
    }

    private fun holds(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Lets the computer with [publicKey] (its base64 key) in for this connection, if it is the one asking. Null when
     * Android took the call; Android itself drops a key that isn't the one asking. Else why not, in words.
     * Never `clearUsbDebuggingKeys`: it deletes every key, the owner's too, on a panel with no screen to tap.
     */
    fun allow(publicKey: String): String? = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "usb")
        val usb = Class.forName("android.hardware.usb.IUsbManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        // From the public interface: the proxy is a private class, and invoke checks access against the declaring class.
        Class.forName("android.hardware.usb.IUsbManager").getMethod("allowUsbDebugging", Boolean::class.javaPrimitiveType, String::class.java)
            .invoke(usb, false, publicKey)
        null
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: e
        "${cause.javaClass.simpleName}: ${cause.message}"
    } catch (e: ReflectiveOperationException) {
        "${e.javaClass.simpleName}: ${e.message}"
    } catch (e: RuntimeException) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * Brings the app's own screen back over Android's "Allow USB debugging?" on a screen nobody can tap: its running
     * task to the front ([android.app.ActivityManager.AppTask.moveToFront], no permission for the app's own task), or
     * its home screen when none runs. A HOME intent alone starts a second dashboard in a new task (API 23 emulator,
     * 2026-11-18). Android 6 doesn't close that dialog for `ACTION_CLOSE_SYSTEM_DIALOGS` (same emulator): it stays
     * behind and ends when the computer is unplugged. Null when done, else why not.
     */
    fun cover(context: Context): String? = try {
        val task = context.getSystemService(android.app.ActivityManager::class.java)?.appTasks?.firstOrNull()
        if (task != null) task.moveToFront()
        else context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                .setPackage(context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (e: RuntimeException) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * The request still waiting in [lines] (`logcat -v time`, oldest first): the last computer that asked, if the image
     * didn't let it in itself after, and if it asked within [withinMs] of [now]. Older ones aren't replayed: the computer
     * may be gone, and it asks again when it reconnects.
     */
    fun pendingRequest(lines: List<String>, now: Long, withinMs: Long): Heard.Asked? {
        var last: Pair<Heard.Asked, Long?>? = null
        for (line in lines) {
            when (val h = heard(line)) {
                is Heard.Asked -> last = h to timeOf(line, now)
                Heard.LetInByImage -> last = null
                else -> Unit
            }
        }
        val (asked, at) = last ?: return null
        return asked.takeIf { at != null && now - at in 0..withinMs }
    }

    private val TIME = Regex("^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})")

    /** A `-v time` line's time (`MM-DD hh:mm:ss.mmm`, local, no year) in ms, in the year that puts it at or before [now]. */
    internal fun timeOf(line: String, now: Long): Long? {
        val m = TIME.find(line) ?: return null
        val (mo, d, h, mi, s, ms) = m.destructured
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        fun at(year: Int) = java.util.Calendar.getInstance().apply {
            clear()
            set(year, mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            set(java.util.Calendar.MILLISECOND, ms.toInt())
        }.timeInMillis
        val year = cal.get(java.util.Calendar.YEAR)
        return at(year).let { if (it > now + 60_000) at(year - 1) else it }
    }

    /** What the system log says about a computer asking. */
    sealed interface Heard {
        /** A computer asked with this key line (`<base64> user@host`). */
        data class Asked(val key: String) : Heard

        /**
         * The Glass Panes image let a key in by itself: the owner's its services.jar names, or (image 3) one listed in
         * `adb_keys` (image-patch.md).
         */
        data object LetInByImage : Heard

        /** Android refused an approval of [got]: [waiting] is the computer it waits on, null when none (`:241`). */
        data class Refused(val got: String, val waiting: String?) : Heard

        /** Android put its own "Allow USB debugging?" on the screen (ActivityManager's `START` of SystemUI's activity). */
        data object QuestionShown : Heard
    }

    /** One system log line as [Heard], or null when it says neither. */
    fun heard(line: String): Heard? {
        if (ASKED in line) return Heard.Asked(line.substringAfter(ASKED).trim())
        if (OWNER.containsMatchIn(line)) return Heard.LetInByImage
        if (SHOWN.containsMatchIn(line)) return Heard.QuestionShown
        val r = REFUSED.find(line) ?: return null
        return Heard.Refused(r.groupValues[1], r.groupValues[2].takeIf { it != "null" })
    }

    private const val ASKED = "Received public key: "
    /** SystemUI's `UsbDebuggingActivity` (or its secondary-user twin) starting: `START u0 {… cmp=com.android.systemui/.usb.UsbDebuggingActivity …}`. */
    private val SHOWN = Regex("START u\\d+ \\{[^}]*cmp=\\S+/\\S*UsbDebugging\\w*Activity")
    /** The image's own line when it lets its owner in ("Authorizing the … owner's ADB key", image-patch.md), whatever device it names. */
    private val OWNER = Regex("Authorizing (the \\S+ owner's ADB key|an ADB key listed in adb_keys)")
    private val REFUSED = Regex("Fingerprints do not match\\. Got ([0-9A-F:]+), expected (\\S+)")

    /**
     * Follows the system log for computers asking ([heard]) while started, starting it again when it ends. `-T 1`:
     * from the newest line on; a computer asking at that moment is the one Android still waits on. Tests pass a fake
     * [logcat].
     */
    class Requests(
        private val onHeard: (Heard) -> Unit,
        private val onError: (String) -> Unit = {},
        private val retryMs: Long = 10_000,
        private val logcat: () -> Process = { ProcessBuilder("logcat", "-b", "system", "-T", "1", "-s", "UsbDebuggingManager:D", "ActivityManager:I").redirectErrorStream(true).start() },
        /** The system log so far, with times: a computer that asked just before the reader started ([pendingRequest]). */
        private val history: () -> Process = { ProcessBuilder("logcat", "-b", "system", "-d", "-v", "time", "-s", "UsbDebuggingManager:D").redirectErrorStream(true).start() },
        private val clock: () -> Long = System::currentTimeMillis,
        /** How recent a request in [history] must be to count as the one Android still waits on. */
        private val replayMs: Long = 60_000,
    ) {
        private var running = false
        private var generation = 0
        private var process: Process? = null

        /** True while a reader is following the log: without one, nobody sees Android's refusals. */
        @Volatile var following = false
            private set

        @Synchronized
        fun start() {
            if (running) return
            running = true
            val mine = ++generation
            Thread({ follow(mine) }, "glassworks-adb-requests").apply { isDaemon = true; start() }
        }

        /**
         * The reader thread: no synchronized block and no return out of an inline lambda in here. Android 6's verifier
         * rejected the whole class when this body held them ("expected to be within a catch-all for an instruction
         * where a monitor is held", the Mirror, 2026-11-18). Each locked step is a small synchronized method instead.
         */
        private fun follow(mine: Int) {
            while (current(mine)) {
                try {
                    val p = logcat()
                    if (!adopt(mine, p)) {
                        p.destroy()
                        return
                    }
                    // After the live reader is up, so a request between the two is in one or the other.
                    val missed = replay()
                    if (missed != null && !dispatch(mine, missed)) return
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    var line = reader.readLine()
                    while (line != null) {
                        val h = heard(line)
                        if (h != null && !dispatch(mine, h)) return
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                    onError("system log: ${e.message}")
                } finally {
                    ended(mine)
                }
                try {
                    Thread.sleep(retryMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /** A request Android logged in the last [replayMs] that the image didn't answer itself, or null. */
        private fun replay(): Heard.Asked? = try {
            val p = history()
            val lines = BufferedReader(InputStreamReader(p.inputStream)).readLines()
            p.destroy()
            pendingRequest(lines, clock(), replayMs)
        } catch (e: Exception) {
            onError("system log history: ${e.message}")
            null
        }

        @Synchronized
        private fun current(mine: Int) = running && generation == mine

        /** Takes [p] as the reader's process. Set in the same lock as the generation check: set after it, a stop() in between would leave [following] true. */
        @Synchronized
        private fun adopt(mine: Int, p: Process): Boolean {
            if (!current(mine)) return false
            process = p
            following = true
            return true
        }

        /** Hands [h] to the app, under the lock, so a line an old reader had buffered never reaches the app after stop(). */
        @Synchronized
        private fun dispatch(mine: Int, h: Heard): Boolean {
            if (!current(mine)) return false
            try {
                onHeard(h)
            } catch (e: Exception) {
                onError("adb request: ${e.message}")
            }
            return true
        }

        @Synchronized
        private fun ended(mine: Int) {
            if (generation == mine) following = false
        }

        @Synchronized
        fun stop() {
            running = false
            following = false
            generation++
            process?.destroy()
            process = null
        }
    }
}
