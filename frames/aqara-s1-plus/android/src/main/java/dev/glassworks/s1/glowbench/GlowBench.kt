package dev.glassworks.s1.glowbench

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.assist.AudioLevel
import dev.glassworks.assist.LevelSmoother
import dev.glassworks.assist.VoiceLevels
import dev.glassworks.assist.VoiceTexts
import dev.glassworks.assist.VoiceUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): the #691 edge glow, Spark, inside the kiosk, in the voice
 * overlay's layer (same parent, same elevation), over the live dashboard. Two ways in:
 *
 * - **Real Assist runs.** In this test build the glow replaces the voice overlay during a voice run: [Handle.voice]
 *   takes the app's voice state (listening with the real mic level, thinking, speaking with the real playback,
 *   errors) and plays Spark's entrance, the states and Spark's exit, with a small caption.
 * - **Repeatable loops.** `am broadcast -a dev.glassworks.debug.GLOW --ei loops N` (senders need
 *   android.permission.DUMP, which adb's shell has) plays the scripted run N times: entrance, listening with a fake
 *   voice, thinking, speaking, exit.
 *
 * Both log each phase's frame intervals (Choreographer) and draw times to logcat (tag GlowBench) and to a JSON file
 * in the files dir and, so adb can read it from a release-signed test build, in the external files dir.
 */
object GlowBench {
    const val ACTION = "dev.glassworks.debug.GLOW"
    const val TAG = "GlowBench"
    private const val ENTER_MS = 650f

    interface Handle {
        /** The voice overlay's update, in this test build: true when the glow took it. */
        fun voice(ui: VoiceUi, enabled: Boolean): Boolean
        fun detach()
    }

    fun attach(activity: Activity, voice: View): Handle = Bench(activity, voice)

    private class Bench(private val activity: Activity, private val voice: View) : Handle {
        private val dp = activity.resources.displayMetrics.density
        private val glow = GlowBenchView(activity)
        private val caption = Caption(activity)
        private val layer = FrameLayout(activity).apply {
            addView(glow, FrameLayout.LayoutParams(-1, -1))
            addView(caption.view, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (34 * dp).toInt() })
            caption.view.visibility = View.INVISIBLE
            isClickable = false; isFocusable = false
        }
        private var busy: String? = null // "bench" or "live"
        private var live: Live? = null
        private var lastSeq = -1
        private val liveStats = Stats(activity, "live")
        private var liveRuns = 0

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (busy != null) { Log.w(TAG, "busy: a $busy run is on screen"); return }
                val v = intent.getStringExtra("variant") ?: "spark"
                if (!v.equals("spark", true) && !v.equals("all", true)) { Log.w(TAG, "only spark is in this build (decided 2026-11-20)"); return }
                val loops = intent.getIntExtra("loops", 3).coerceIn(1, 50)
                if (!show()) return
                busy = "bench"
                val stats = Stats(activity, "bench")
                Log.i(TAG, "start mode=bench variant=spark loops=$loops refresh=${stats.refreshHz}Hz size=${layer.width}x${layer.height}")
                glow.onFrame = { phase, iv, dr -> stats.add(phase, iv, dr); captionFor(phase, null) }
                runLoop(1, loops, stats)
            }
        }

        private fun runLoop(i: Int, loops: Int, stats: Stats) {
            if (i > loops) {
                val file = stats.finish(loops)
                Log.i(TAG, "done json=${file.absolutePath}")
                caption.view.visibility = View.INVISIBLE
                busy = null
                return
            }
            glow.post {
                val m = glow.modelFor(GlowModel.Variant.SPARK)
                if (m == null) { runLoop(i, loops, stats); return@post } // not laid out yet
                glow.play({ ms -> if (ms >= m.totalMs - 700f) null else m.at(ms).phase.name.lowercase() to m.frame(ms)!! }) {
                    caption.view.visibility = View.INVISIBLE
                    glow.postDelayed({ runLoop(i + 1, loops, stats) }, 700) // the gap: nothing on screen
                }
            }
        }

        init {
            val filter = IntentFilter(ACTION)
            // The API level comes from Platform (PlatformConfinementTest).
            if (dev.glassworks.platform.Platform.apiLevel >= 33) activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
            else activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }

        /** Puts the glow's layer next to the voice overlay, at its elevation; false if the overlay isn't in a window yet. */
        private fun show(): Boolean {
            if (layer.parent != null) return true
            val parent = voice.parent as? ViewGroup ?: run { Log.w(TAG, "no voice overlay layer yet"); return false }
            layer.elevation = voice.elevation; layer.translationZ = voice.translationZ
            parent.addView(layer, parent.indexOfChild(voice) + 1, FrameLayout.LayoutParams(-1, -1))
            return true
        }

        // ------------------------------------------------------------------ real Assist runs
        override fun voice(ui: VoiceUi, enabled: Boolean): Boolean {
            if (busy == "bench") return false // the loops own the screen; the old overlay takes this run
            val fresh = ui.seq != lastSeq
            lastSeq = ui.seq
            if (!enabled) { live?.leave(false); return true }
            val st = ui.state
            when {
                st == "wake" || st == "listening" -> start()?.request("listening")
                st == "thinking" -> start()?.request("thinking")
                st == "speaking" -> start()?.request("speaking")
                st.startsWith("error") -> if (fresh) {
                    // A panel-side problem (mic, models) outside a run doesn't bring the glow up, as with the orb.
                    if (ui.errorLocal && live == null) return true
                    start()?.request("error")
                    layer.postDelayed({ if (live?.phase == "error") live?.leave(true) }, 1600)
                }
                else -> if (fresh) live?.let { if (it.phase != "error") it.leave(false) } // idle or off: the run is over
            }
            live?.let { captionFor(it.phase, ui) }
            return true
        }

        private fun start(): Live? {
            live?.let { return it }
            if (!show()) return null
            busy = "live"
            val l = Live()
            live = l
            liveRuns++
            Log.i(TAG, "start mode=live run=$liveRuns refresh=${liveStats.refreshHz}Hz size=${layer.width}x${layer.height}")
            glow.onFrame = { phase, iv, dr -> liveStats.add(phase, iv, dr) }
            glow.play(l) {
                caption.view.visibility = View.INVISIBLE
                val file = liveStats.finish(liveRuns)
                Log.i(TAG, "live run $liveRuns done json=${file.absolutePath}")
                live = null; busy = null
            }
            return l
        }

        /** One voice run as Spark: entrance, then whatever the voice state asks for, then Spark's exit. */
        private inner class Live : GlowBenchView.Source {
            var phase = "enter"; private set
            private var phaseAt = 0f
            private var prev: String? = null
            private var prevAt = 0f
            private var pending = "listening"
            private var now = 0f
            private var lastT = 0f
            private var exitAt = -1f
            private var exitRose = false
            private var exitBase: GlowModel.Frame? = null
            private var last: GlowModel.Frame? = null
            private val mic = LevelSmoother()
            private val tts = LevelSmoother(attackMs = 350f, releaseMs = 700f) // speaking breathes slowly (calm)
            private var micL = 0f
            private var ttsL = 0f
            private val ripples = ArrayList<FloatArray>() // [sent at, level]
            private var lastRipple = -1e9f

            fun request(p: String) {
                if (exitAt >= 0f) { exitAt = -1f; set(p); return } // a new run while leaving: stay
                if (phase == "enter") pending = p else set(p)
            }

            private fun set(p: String) {
                if (p == phase) return
                prev = phase; prevAt = phaseAt; phase = p; phaseAt = now
                if (p == "listening") { ripples.clear(); lastRipple = -1e9f }
            }

            fun leave(rose: Boolean) {
                if (exitAt >= 0f) return
                exitAt = now; exitRose = rose; exitBase = last
            }

            override fun at(ms: Float): Pair<String, GlowModel.Frame>? {
                now = ms
                val dt = (ms - lastT).coerceIn(0f, 100f); lastT = ms
                val m = glow.model?.takeIf { it.w * dp == glow.width.toFloat() } ?: glow.modelFor(GlowModel.Variant.SPARK)
                    ?: return "enter" to GlowModel(1f, 1f, GlowModel.Variant.SPARK).sparkIn(0f) // not laid out yet
                if (phase == "enter" && ms - phaseAt >= ENTER_MS) set(pending)
                // the real levels, once per frame
                micL = max(mic.step(VoiceLevels.mic, dt), if (phase == "listening") AudioLevel.LISTENING_FLOOR else 0f)
                ttsL = tts.step(if (VoiceLevels.ttsPlaying) AudioLevel.ttsPulse((ms - phaseAt).toDouble()) else 0f, dt)
                if (phase == "listening" && ms - lastRipple >= 140f) { if (micL > 0.3f) ripples += floatArrayOf(ms, micL); lastRipple = ms }
                ripples.removeAll { ms - it[0] >= 1600f }
                if (exitAt >= 0f) {
                    val u = ms - exitAt
                    if (u >= GlowModel.Variant.SPARK.exitMs) return null
                    return "exit" to m.sparkExit(exitBase ?: m.speakingLive(0f), u, exitRose)
                }
                var f = frameFor(m, phase, ms - phaseAt)
                val p0 = prev
                if (p0 != null && p0 != "enter" && phase != "enter" && ms - phaseAt < 300f) {
                    val g = frameFor(m, p0, ms - prevAt); val k = GlowModel.ease((ms - phaseAt) / 300f); val tmp = FloatArray(5)
                    val a = f
                    f = m.Frame({ s, out -> a.eval(s, out); g.eval(s, tmp); for (i in 0 until 5) out[i] = out[i] * k + tmp[i] * (1 - k) },
                        a.flares, 1f, a.thick * k + g.thick * (1 - k))
                }
                last = f
                return phase to f
            }

            private fun frameFor(m: GlowModel, p: String, u: Float): GlowModel.Frame = when (p) {
                "enter" -> m.sparkIn(u)
                "listening" -> m.listeningLive(u, micL, ripples.mapNotNull { m.ripple(it[1], now - it[0]) })
                "thinking" -> m.thinking(u)
                "speaking" -> m.speakingLive(ttsL)
                else -> m.error(u)
            }
        }

        // ------------------------------------------------------------------ caption
        private var captionKey = ""
        private fun captionFor(phase: String, ui: VoiceUi?) {
            val (label, text, colour) = when (phase) {
                "enter", "listening" -> Triple("LISTENING", VoiceTexts.PROMPT, 0xff38bdf8.toInt())
                "thinking" -> Triple("THINKING", ui?.heard?.takeIf { it.isNotBlank() }?.let(VoiceTexts::quote) ?: "“Turn off the kitchen lights”", 0xff6b6b74.toInt())
                "speaking" -> Triple(ui?.heard?.takeIf { it.isNotBlank() }?.let(VoiceTexts::quote) ?: "", ui?.reply?.ifBlank { null } ?: if (ui == null) "Turned off 3 lights in the kitchen." else "", 0xffa1a1aa.toInt())
                "error" -> Triple("SOMETHING WENT WRONG", ui?.let { VoiceTexts.friendlyError(it.state, it.errorLocal) } ?: "", 0xfffb7185.toInt())
                else -> return
            }
            val key = "$label|$text"
            if (key == captionKey && caption.view.visibility == View.VISIBLE) return
            captionKey = key
            caption.set(label, text, colour)
        }

        override fun detach() {
            glow.stop()
            (layer.parent as? ViewGroup)?.removeView(layer)
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }

    /** The glass caption at the bottom: a small label and the main line. Redrawn only when its text changes. */
    private class Caption(context: Context) {
        private val dp = context.resources.displayMetrics.density
        private val label = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); letterSpacing = .14f; gravity = Gravity.CENTER }
        private val main = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(0xfff4f4f5.toInt()); gravity = Gravity.CENTER; maxLines = 2 }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((30 * dp).toInt(), (16 * dp).toInt(), (30 * dp).toInt(), (18 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(Color.argb(220, 10, 11, 15)); setStroke(dp.toInt(), Color.argb(20, 255, 255, 255)) }
            addView(label); addView(main)
            minimumWidth = (640 * dp).toInt()
        }

        fun set(l: String, text: String, colour: Int) {
            label.text = l; label.setTextColor(colour); main.text = text
            view.visibility = View.VISIBLE
        }
    }

    /** Frame intervals and draw times per phase. */
    private class Stats(private val activity: Activity, private val mode: String) {
        @Suppress("DEPRECATION")
        val refreshHz: Float = activity.windowManager.defaultDisplay.refreshRate
        private val startedAt = System.currentTimeMillis()
        private val buckets = LinkedHashMap<String, Pair<ArrayList<Float>, ArrayList<Float>>>()

        fun add(phase: String, intervalMs: Float, drawMs: Float) {
            val b = buckets.getOrPut(phase) { ArrayList<Float>() to ArrayList() }
            b.first += intervalMs; b.second += drawMs
        }

        /** Writes everything so far (a live session's file is rewritten after each run); [runs] is loops or runs so far. */
        fun finish(runs: Int): File {
            val vsync = 1000f / refreshHz
            val rows = JSONArray()
            for ((phase, b) in buckets) {
                val iv = b.first.sorted(); val dr = b.second.sorted()
                // A 60 Hz frame is 16.7 ms give or take a little jitter: one vsync plus 1 ms counts as "over 16.7 ms",
                // two vsyncs plus 1 ms as "over 33 ms".
                val over16 = iv.count { it > vsync + 1f }
                val over33 = iv.count { it > 2 * vsync + 1f }
                val missed = iv.sumOf { (Math.round(it / vsync) - 1).coerceAtLeast(0) }
                rows.put(JSONObject()
                    .put("variant", "spark").put("phase", phase).put("frames", iv.size)
                    .put("p50", pct(iv, .5)).put("p90", pct(iv, .9)).put("p99", pct(iv, .99)).put("max", iv.lastOrNull() ?: 0f)
                    .put("over16_7", over16).put("over33", over33).put("missedVsyncs", missed)
                    .put("drawP50", pct(dr, .5)).put("drawP90", pct(dr, .9)).put("drawP99", pct(dr, .99)))
                Log.i(TAG, "%-5s %-9s frames=%4d p50=%5.1f p90=%5.1f p99=%5.1f max=%5.1f over16.7=%3d over33=%3d draw_p90=%4.1f".format(
                    mode, phase, iv.size, pct(iv, .5), pct(iv, .9), pct(iv, .99), iv.lastOrNull() ?: 0f, over16, over33, pct(dr, .9)))
            }
            val json = JSONObject()
                .put("bench", "glowbench-s1").put("mode", mode).put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("refreshHz", refreshHz.toDouble()).put("loops", runs).put("variants", JSONArray(listOf("spark")))
                .put("startedAt", startedAt).put("phases", rows)
            val name = "glowbench-$mode-$startedAt.json"
            File(File(activity.filesDir, "glowbench").apply { mkdirs() }, name).writeText(json.toString(2))
            val ext = activity.getExternalFilesDir("glowbench")
            return if (ext != null) File(ext, name).apply { writeText(json.toString(2)) } else File(File(activity.filesDir, "glowbench"), name)
        }

        private fun pct(xs: List<Float>, q: Double): Float = if (xs.isEmpty()) 0f else xs[Math.round((xs.size - 1) * q).toInt()]
    }
}
