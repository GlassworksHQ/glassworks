package dev.glassworks.s1.glowbench

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): the #691 edge glow benchmark inside the kiosk. A debug or test
 * build listens for
 *
 *   am broadcast -a dev.glassworks.debug.GLOW --es variant spark|sheen|pour|all --ei loops N
 *
 * (senders need android.permission.DUMP, which adb's shell has). It adds [GlowBenchView] next to the voice overlay,
 * in the same parent at the same elevation, so it draws in the voice overlay's layer over the live dashboard; runs
 * entrance, listening (a fake voice), thinking, speaking and exit for each variant, N times; and logs each phase's
 * frame intervals (Choreographer) and draw times. Results: logcat tag GlowBench, and a JSON file in the app's files
 * dir and, so adb can pull it from a release-signed test build, its external files dir.
 */
object GlowBench {
    const val ACTION = "dev.glassworks.debug.GLOW"
    const val TAG = "GlowBench"

    /** Registers the receiver on [activity]; [voice] is the voice overlay, whose layer the glow joins. Returns the undo. */
    fun attach(activity: Activity, voice: View): () -> Unit {
        var view: GlowBenchView? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (view != null) { Log.w(TAG, "busy: a run is in progress"); return }
                val variants = when (val v = intent.getStringExtra("variant") ?: "all") {
                    "all" -> GlowModel.Variant.entries.toList()
                    else -> listOfNotNull(GlowModel.Variant.entries.firstOrNull { it.name.equals(v, ignoreCase = true) })
                }
                if (variants.isEmpty()) { Log.w(TAG, "unknown variant"); return }
                val loops = intent.getIntExtra("loops", 3).coerceIn(1, 50)
                val parent = voice.parent as? ViewGroup ?: run { Log.w(TAG, "no voice overlay layer"); return }
                val v = GlowBenchView(activity).apply { elevation = voice.elevation; translationZ = voice.translationZ }
                parent.addView(v, parent.indexOfChild(voice) + 1, FrameLayout.LayoutParams(-1, -1))
                view = v
                val stats = Stats(activity, variants, loops)
                v.onFrame = stats::add
                v.onDone = {
                    val file = stats.finish()
                    Log.i(TAG, "done json=${file.absolutePath}")
                    parent.removeView(v)
                    view = null
                }
                Log.i(TAG, "start variants=${variants.joinToString(",") { it.name.lowercase() }} loops=$loops refresh=${stats.refreshHz}Hz size=${parent.width}x${parent.height}")
                // Wait for layout before the first run, so the model knows the screen's size.
                v.post { v.run(variants, loops) }
            }
        }
        val filter = IntentFilter(ACTION)
        // The API level comes from Platform (PlatformConfinementTest).
        if (dev.glassworks.platform.Platform.apiLevel >= 33) activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        return {
            view?.let { it.stop(); (it.parent as? ViewGroup)?.removeView(it) }
            view = null
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }

    /** Frame intervals and draw times per variant and phase, over every loop. */
    private class Stats(private val activity: Activity, private val variants: List<GlowModel.Variant>, private val loops: Int) {
        @Suppress("DEPRECATION")
        val refreshHz: Float = activity.windowManager.defaultDisplay.refreshRate
        private val startedAt = System.currentTimeMillis()
        private val buckets = LinkedHashMap<String, Pair<ArrayList<Float>, ArrayList<Float>>>()

        fun add(variant: GlowModel.Variant, phase: GlowModel.Phase, loop: Int, intervalMs: Float, drawMs: Float) {
            val b = buckets.getOrPut("${variant.name.lowercase()}/${phase.name.lowercase()}") { ArrayList<Float>() to ArrayList() }
            b.first += intervalMs; b.second += drawMs
        }

        fun finish(): File {
            val vsync = 1000f / refreshHz
            val rows = JSONArray()
            for ((key, b) in buckets) {
                val (variant, phase) = key.split("/")
                val iv = b.first.sorted(); val dr = b.second.sorted()
                // A 60 Hz frame is 16.7 ms give or take a little jitter: count one vsync plus 1 ms as "over 16.7 ms",
                // and two vsyncs plus 1 ms as "over 33 ms".
                val over16 = iv.count { it > vsync + 1f }
                val over33 = iv.count { it > 2 * vsync + 1f }
                val jank = iv.sumOf { (Math.round(it / vsync) - 1).coerceAtLeast(0) }
                val row = JSONObject()
                    .put("variant", variant).put("phase", phase).put("frames", iv.size)
                    .put("p50", pct(iv, .5)).put("p90", pct(iv, .9)).put("p99", pct(iv, .99)).put("max", iv.lastOrNull() ?: 0f)
                    .put("over16_7", over16).put("over33", over33).put("missedVsyncs", jank)
                    .put("drawP50", pct(dr, .5)).put("drawP90", pct(dr, .9)).put("drawP99", pct(dr, .99))
                rows.put(row)
                Log.i(TAG, "%-6s %-9s frames=%4d p50=%5.1f p90=%5.1f p99=%5.1f max=%5.1f over16.7=%3d over33=%3d draw_p90=%4.1f".format(
                    variant, phase, iv.size, pct(iv, .5), pct(iv, .9), pct(iv, .99), iv.lastOrNull() ?: 0f, over16, over33, pct(dr, .9)))
            }
            val json = JSONObject()
                .put("bench", "glowbench-s1").put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("refreshHz", refreshHz.toDouble()).put("loops", loops).put("variants", JSONArray(variants.map { it.name.lowercase() }))
                .put("startedAt", startedAt).put("uptimeMs", SystemClock.uptimeMillis()).put("phases", rows)
            val name = "glowbench-$startedAt.json"
            val inFiles = File(File(activity.filesDir, "glowbench").apply { mkdirs() }, name).apply { writeText(json.toString(2)) }
            val ext = activity.getExternalFilesDir("glowbench")
            return if (ext != null) File(ext, name).apply { writeText(json.toString(2)) } else inFiles
        }

        private fun pct(xs: List<Float>, q: Double): Float =
            if (xs.isEmpty()) 0f else xs[((xs.size - 1) * q).let { Math.round(it).toInt() }]
    }
}
