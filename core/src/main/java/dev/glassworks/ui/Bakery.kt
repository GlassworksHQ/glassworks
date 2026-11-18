package dev.glassworks.ui

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/** One background thread for baking bitmaps (backdrop, voice orb layers); results come back on the main thread. */
object Bakery {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glassworks-bake").apply { isDaemon = true }
    }

    fun <T : Any> bake(what: String, work: () -> T, done: (T) -> Unit) {
        exec.execute {
            val t0 = System.nanoTime()
            val result = try { work() } catch (t: Throwable) { Log.w("Glassworks", "bake $what failed", t); return@execute }
            Log.i("Glassworks", "baked $what in ${(System.nanoTime() - t0) / 1_000_000} ms")
            main.post { done(result) }
        }
    }
}
