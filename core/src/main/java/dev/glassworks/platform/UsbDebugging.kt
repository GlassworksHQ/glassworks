/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
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

    /** Whether this app can see who asks and answer: Android 6 to 9, holding `MANAGE_USB` and `READ_LOGS` (a privileged app). */
    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT <= 28 && listOf(MANAGE_USB, READ_LOGS).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

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

    /** What the system log says about a computer asking. */
    sealed interface Heard {
        /** A computer asked with this key line (`<base64> user@host`). */
        data class Asked(val key: String) : Heard

        /** The Glass Panes image let its owner's key in by itself (its services.jar, image-patch.md). */
        data object LetInByImage : Heard

        /** Android refused an approval of [got]: [waiting] is the computer it waits on, null when none (`:241`). */
        data class Refused(val got: String, val waiting: String?) : Heard
    }

    /** One system log line as [Heard], or null when it says neither. */
    fun heard(line: String): Heard? {
        if (ASKED in line) return Heard.Asked(line.substringAfter(ASKED).trim())
        if (OWNER in line) return Heard.LetInByImage
        val r = REFUSED.find(line) ?: return null
        return Heard.Refused(r.groupValues[1], r.groupValues[2].takeIf { it != "null" })
    }

    private const val ASKED = "Received public key: "
    private const val OWNER = "Authorizing the Mirror owner's ADB key"
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
        private val logcat: () -> Process = { ProcessBuilder("logcat", "-b", "system", "-T", "1", "-s", "UsbDebuggingManager:D").redirectErrorStream(true).start() },
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
            Thread({
                try {
                    while (synchronized(this) { running && generation == mine }) {
                        try {
                            val p = logcat()
                            // Set in the same lock as the generation check: set after it, a stop() in between would leave it true.
                            val keep = synchronized(this) { if (running && generation == mine) { process = p; following = true; true } else false }
                            if (!keep) { p.destroy(); return@Thread }
                            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                                for (l in lines) {
                                    val h = heard(l) ?: continue
                                    // Under the lock, so a line an old reader had buffered never reaches the app after stop().
                                    val current = synchronized(this) {
                                        if (running && generation == mine) { runCatching { onHeard(h) }.onFailure { onError("adb request: ${it.message}") }; true } else false
                                    }
                                    if (!current) return@Thread
                                }
                            }
                        } catch (e: Exception) {
                            onError("system log: ${e.message}")
                        } finally {
                            synchronized(this) { if (generation == mine) following = false }
                        }
                        Thread.sleep(retryMs)
                    }
                } catch (_: InterruptedException) {}
            }, "glassworks-adb-requests").apply { isDaemon = true; start() }
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
