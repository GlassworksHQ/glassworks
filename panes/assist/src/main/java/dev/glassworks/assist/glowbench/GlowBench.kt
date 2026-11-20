package dev.glassworks.assist.glowbench

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
import android.view.Choreographer
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
import kotlin.math.pow
import kotlin.math.sin

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): the #691 edge glow benchmark inside the kiosk (the S1 app and the generic app, standard and legacy), round 4.
 *
 * Looks: `glow` is Spark built for RenderThread ([LitGlow]); `orb` is today's voice overlay, the baseline. Ways in:
 * - **Real Assist runs.** With look `glow` (the default) the glow replaces the voice overlay during a voice run,
 *   driven by the real voice state and mic level; with look `orb` the voice overlay draws as usual. Either way each
 *   run is measured per phase.
 * - **Scripted loops.** `am broadcast -a dev.glassworks.debug.GLOW --es look glow|orb --ei loops N --ei fps 60|30`
 *   (senders need android.permission.DUMP, which adb's shell has). `--ei loops 0` only sets the look and the cap for
 *   the real runs that follow.
 *
 * Measured per phase: UI-thread frame intervals (Choreographer), and the window's drawn frames (FrameMetrics:
 * the interval between frames, which includes RenderThread-only animation frames, and the time to render each).
 * Logged to logcat (GlowBench) and to JSON in the files dir and the external files dir.
 */
object GlowBench {
    const val ACTION = "dev.glassworks.debug.GLOW"
    const val TAG = "GlowBench"

    interface Handle {
        /** The voice overlay's update, in this test build: true when the glow took it. */
        fun voice(ui: VoiceUi, enabled: Boolean): Boolean
        fun detach()
    }

    /**
     * [orb] updates the voice overlay (the baseline look); [onShowing] is the voice overlay's onShowingChanged (a timer
     * set by voice flies out as it goes); [onCancel] stops the run, as the voice overlay's tap does.
     */
    fun attach(activity: Activity, voice: View, orb: (VoiceUi) -> Unit, onShowing: (Boolean) -> Unit, onCancel: () -> Unit): Handle =
        Bench(activity, voice, orb, onShowing, onCancel)

    private class Bench(
        private val activity: Activity,
        private val voice: View,
        private val orb: (VoiceUi) -> Unit,
        private val onShowing: (Boolean) -> Unit,
        private val onCancel: () -> Unit,
    ) : Handle {
        private val dp = activity.resources.displayMetrics.density
        private val glow = LitGlow(activity)
        private val caption = Caption(activity)
        private val layer = FrameLayout(activity).apply {
            clipChildren = false
            addView(glow, FrameLayout.LayoutParams(-1, -1))
            // The caption sits low on a landscape screen and higher on a portrait one (the Mirror), as in the mockups.
            val portrait = activity.resources.displayMetrics.let { it.heightPixels > it.widthPixels }
            addView(caption.view, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ((if (portrait) 120 else 34) * dp).toInt() })
            caption.view.alpha = 0f
            isClickable = false; isFocusable = false
        }
        private var look = "glow"
        private var fps = 60
        private var busy: String? = null // "loops" or "live"
        private var meter: Meter? = null
        private var live: Live? = null
        private var liveMeter: Meter? = null
        private var liveRuns = 0
        private var lastSeq = -1
        /** The broadcast's tag, echoed in the start and done lines so run.sh finds its own run in logcat. */
        private var tag = "-"
        /** The idle time between scripted runs, ms (a broadcast extra; a short gap tests the clocks' ramp-down). */
        private var gapMs = 700

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("look")?.let { if (it == "glow" || it == "orb") look = it }
                intent.getStringExtra("tag")?.let { tag = it }
                gapMs = intent.getIntExtra("gap", gapMs).coerceIn(0, 10_000)
                fps = if (intent.getIntExtra("fps", fps) == 30) 30 else 60
                val loops = intent.getIntExtra("loops", 3).coerceIn(0, 50)
                if (loops == 0) { liveMeter = null; Log.i(TAG, "config tag=$tag look=$look fps=$fps"); return }
                if (busy != null) { Log.w(TAG, "busy: a $busy run is on screen"); return }
                if (!show()) return
                busy = "loops"
                val m = Meter(activity, "loops", look, fps).also { meter = it }
                Log.i(TAG, "start tag=$tag mode=loops look=$look fps=$fps loops=$loops gap=${gapMs}ms refresh=${m.refreshHz}Hz size=${layer.width}x${layer.height}")
                Script(m, loops).start()
            }
        }

        init {
            val filter = IntentFilter(ACTION)
            // The API level comes from Platform (PlatformConfinementTest).
            if (dev.glassworks.platform.Platform.apiLevel >= 33) activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
            else activity.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }

        private fun show(): Boolean {
            if (layer.parent != null) return true
            val parent = voice.parent as? ViewGroup ?: run { Log.w(TAG, "no voice overlay layer yet"); return false }
            layer.elevation = voice.elevation; layer.translationZ = voice.translationZ
            parent.addView(layer, parent.indexOfChild(voice) + 1, FrameLayout.LayoutParams(-1, -1))
            return true
        }

        // ------------------------------------------------------------------ scripted loops
        /** One run: enter 650, listening 3600, thinking 2600, speaking 3800, exit (400 glow, 1600 orb), gap 700. */
        private inner class Script(private val m: Meter, private val loops: Int) : Choreographer.FrameCallback {
            private val exitMs = if (look == "glow") 400f else 1600f
            private val bounds = floatArrayOf(650f, 4250f, 6850f, 10650f, 10650f + exitMs, 10650f + exitMs + gapMs)
            private val names = arrayOf("enter", "listening", "thinking", "speaking", "exit", "gap")
            private var loop = 1
            private var t0 = 0L
            private var phase = ""
            private var frameNo = 0
            private var lastRipple = -1e9f
            private var lap = -1
            private var seq = 0

            fun start() { m.start(); Choreographer.getInstance().postFrameCallback(this) }

            override fun doFrame(now: Long) {
                if (t0 == 0L) t0 = now
                val t = (now - t0) / 1e6f
                val i = bounds.indexOfFirst { t < it }.let { if (it < 0) bounds.size else it }
                if (i >= bounds.size) { // the run is over
                    if (loop >= loops) { finish(); return }
                    loop++; t0 = now; phase = ""; lap = -1
                    Choreographer.getInstance().postFrameCallback(this); return
                }
                val p = names[i]
                val u = t - (if (i == 0) 0f else bounds[i - 1])
                if (p != phase) { phase = p; m.phase = if (p == "gap") null else p; enterPhase(p) }
                frameNo++
                val capped = fps == 60 || frameNo % 2 == 0
                when (p) {
                    "listening" -> {
                        val l = fakeMic(u)
                        if (look == "glow") {
                            if (capped) glow.level(l)
                            if (t - lastRipple >= 140f) { if (l > .3f) glow.ripple(l); lastRipple = t }
                        } else VoiceLevels.mic = l
                    }
                    "thinking" -> if (look == "glow") { val k = (u / 1300f).toInt(); if (k != lap) { lap = k; glow.thinking(k) } }
                    "speaking" -> {
                        val l = fakeTts(u)
                        if (look == "glow") { if (capped) glow.speaking(l) } else { VoiceLevels.ttsPlaying = l > 0f }
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }

            private fun enterPhase(p: String) {
                if (look == "glow") when (p) {
                    "enter" -> { glow.enter(); caption.show("LISTENING", VoiceTexts.PROMPT, 0xff38bdf8.toInt(), delay = 360) }
                    "listening" -> glow.listening()
                    "thinking" -> { caption.show("THINKING", "“Turn off the kitchen lights”", 0xff6b6b74.toInt()) }
                    "speaking" -> { glow.speakingStart(); caption.show("“Turn off the kitchen lights”", "Turned off 3 lights in the kitchen.", 0xffa1a1aa.toInt()) }
                    "exit" -> { glow.exit(); caption.hide() }
                    "gap" -> glow.reset()
                } else when (p) {
                    "enter" -> { seq++; orb(VoiceUi("listening", seq = seq)) }
                    "thinking" -> { seq++; orb(VoiceUi("thinking", heard = "Turn off the kitchen lights", seq = seq)) }
                    "speaking" -> { seq++; orb(VoiceUi("speaking", heard = "Turn off the kitchen lights", reply = "Turned off 3 lights in the kitchen.", seq = seq)) }
                    "exit" -> { VoiceLevels.ttsPlaying = false; VoiceLevels.mic = 0f; seq++; orb(VoiceUi("idle", heard = "Turn off the kitchen lights", reply = "Turned off 3 lights in the kitchen.", seq = seq)) }
                }
            }

            private fun finish() {
                m.phase = null
                val file = m.finish(loops)
                Log.i(TAG, "done tag=$tag json=${file.absolutePath}")
                busy = null; meter = null
            }
        }

        // ------------------------------------------------------------------ real Assist runs
        override fun voice(ui: VoiceUi, enabled: Boolean): Boolean {
            if (busy == "loops") return false // the loops own the screen; the voice overlay takes this run
            val fresh = ui.seq != lastSeq
            lastSeq = ui.seq
            val st = ui.state
            val target = when {
                st == "wake" || st == "listening" -> "listening"
                st == "thinking" || st == "speaking" -> st
                st.startsWith("error") -> if (fresh && !(ui.errorLocal && live == null)) "error" else null
                else -> if (fresh) "end" else null
            }
            if (target != null && target != "end" && live == null && enabled) startLive()
            live?.on(target, ui)
            return look == "glow" && enabled && live != null
        }

        private fun startLive() {
            if (look == "glow" && !show()) return
            busy = "live"; liveRuns++
            val m = liveMeter ?: Meter(activity, "live", look, fps).also { liveMeter = it }
            Log.i(TAG, "start tag=$tag mode=live look=$look fps=$fps run=$liveRuns refresh=${m.refreshHz}Hz")
            live = Live(m).also { it.start() }
        }

        private inner class Live(private val m: Meter) : Choreographer.FrameCallback {
            private var phase = "enter"
            private var phaseAt = 0L
            private var pending = "listening"
            private var t0 = 0L
            private var now = 0L
            private var lastFrame = 0L
            private var frameNo = 0
            private var ending = -1L
            private var lastRipple = 0L
            private var lap = -1
            private val mic = LevelSmoother()
            private val tts = LevelSmoother(attackMs = 350f, releaseMs = 700f)
            private var ui = VoiceUi()

            fun start() {
                m.start(); m.phase = "enter"
                if (look == "glow") {
                    glow.enter(); caption.show("LISTENING", VoiceTexts.PROMPT, 0xff38bdf8.toInt(), delay = 360)
                    onShowing(true)
                    // Tap anywhere to cancel, as with the orb: the layer takes touches only while a run is on screen.
                    layer.setOnClickListener { onCancel(); end() }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }

            fun on(target: String?, next: VoiceUi) {
                ui = next
                when (target) {
                    null -> Unit
                    "end" -> if (phase != "error") end()
                    "error" -> { set("error"); layer.postDelayed({ if (phase == "error") end() }, 1600) }
                    else -> if (phase == "enter") pending = target else set(target)
                }
            }

            private fun set(p: String) {
                if (ending >= 0 || p == phase) return
                phase = p; phaseAt = now; m.phase = p; lap = -1
                if (look != "glow") return
                when (p) {
                    "listening" -> { glow.listening(); caption.show("LISTENING", VoiceTexts.PROMPT, 0xff38bdf8.toInt()) }
                    "thinking" -> caption.show("THINKING", ui.heard.takeIf { it.isNotBlank() }?.let(VoiceTexts::quote) ?: "", 0xff6b6b74.toInt())
                    "speaking" -> { glow.speakingStart(); caption.show(ui.heard.takeIf { it.isNotBlank() }?.let(VoiceTexts::quote) ?: "", ui.reply, 0xffa1a1aa.toInt()) }
                    "error" -> { glow.error(); caption.show("SOMETHING WENT WRONG", VoiceTexts.friendlyError(ui.state, ui.errorLocal), 0xfffb7185.toInt()) }
                }
            }

            private fun end() {
                if (ending >= 0) return
                ending = now; m.phase = "exit"
                if (look == "glow") {
                    glow.exit(); caption.hide()
                    layer.setOnClickListener(null); layer.isClickable = false // touches go to the dashboard while it leaves
                    onShowing(false) // a timer set by voice flies out now
                }
            }

            override fun doFrame(frameTimeNanos: Long) {
                if (t0 == 0L) { t0 = frameTimeNanos; phaseAt = 0L }
                now = (frameTimeNanos - t0) / 1_000_000L
                val dt = if (lastFrame == 0L) 16f else ((frameTimeNanos - lastFrame) / 1e6f).coerceIn(0f, 100f); lastFrame = frameTimeNanos
                if (ending >= 0 && now - ending >= (if (look == "glow") 420 else 1600)) { finish(); return }
                if (phase == "enter" && now - phaseAt >= 650) set(pending)
                frameNo++
                val capped = fps == 60 || frameNo % 2 == 0
                if (look == "glow" && ending < 0) when (phase) {
                    "listening" -> {
                        val l = max(mic.step(VoiceLevels.mic, dt), AudioLevel.LISTENING_FLOOR)
                        if (capped) glow.level(l)
                        if (now - lastRipple >= 140) { if (l > .3f) glow.ripple(l); lastRipple = now }
                    }
                    "thinking" -> { val k = ((now - phaseAt) / 1300).toInt(); if (k != lap) { lap = k; glow.thinking(k) } }
                    "speaking" -> { val l = tts.step(if (VoiceLevels.ttsPlaying) AudioLevel.ttsPulse((now - phaseAt).toDouble()) else 0f, dt); if (capped) glow.speaking(l) }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }

            private fun finish() {
                m.phase = null; m.stop()
                if (look == "glow") glow.reset()
                val file = m.finish(liveRuns)
                Log.i(TAG, "live run $liveRuns done tag=$tag json=${file.absolutePath}")
                live = null; busy = null
            }
        }

        override fun detach() {
            glow.reset()
            (layer.parent as? ViewGroup)?.removeView(layer)
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }

    // ---------------------------------------------------------------------- fake levels (scripted loops)
    /** "Turn off the kitchen lights" from 0.3 s to 2.5 s of listening, then quiet. */
    fun fakeMic(u: Float): Float {
        val env = ((u - 300) / 120).coerceIn(0f, 1f) * ((2500 - u) / 160).coerceIn(0f, 1f)
        val syl = (0.5f + 0.5f * sin(u * 0.021f + 1.9f * sin(u * 0.0053f))).pow(1.6f)
        return 0.06f + 0.94f * env * syl
    }

    fun fakeTts(u: Float): Float {
        val env = (u / 150).coerceIn(0f, 1f) * ((3500 - u) / 250).coerceIn(0f, 1f)
        return env * (0.35f + 0.25f * sin(u * 0.027f + 2 * sin(u * 0.0041f)))
    }

    /** The glass caption at the bottom: a small label and the main line. Redrawn only when its text changes. */
    private class Caption(context: Context) {
        private val dp = context.resources.displayMetrics.density
        private val label = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); letterSpacing = .14f; gravity = Gravity.CENTER }
        private val main = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(0xfff4f4f5.toInt()); gravity = Gravity.CENTER; maxLines = 3
            maxWidth = minOf((640 * dp).toInt(), context.resources.displayMetrics.widthPixels - (124 * dp).toInt())
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((30 * dp).toInt(), (16 * dp).toInt(), (30 * dp).toInt(), (18 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(Color.argb(220, 10, 11, 15)); setStroke(dp.toInt(), Color.argb(20, 255, 255, 255)) }
            addView(label); addView(main)
            // 640 dp, or the screen less a 32 dp gutter each side on a narrow (portrait) one
            minimumWidth = minOf((640 * dp).toInt(), context.resources.displayMetrics.widthPixels - (64 * dp).toInt())
        }

        fun show(l: String, text: String, colour: Int, delay: Long = 0) {
            if (label.text != l) label.text = l
            label.setTextColor(colour)
            if (main.text != text) main.text = text
            if (view.alpha < 1f) view.animate().alpha(1f).setStartDelay(delay).setDuration(200).start()
        }

        fun hide() { view.animate().alpha(0f).setStartDelay(0).setDuration(200).start() }
    }

    /**
     * Frame times per phase. [phase] null pauses it. UI thread: Choreographer intervals. The window's drawn frames:
     * FrameMetrics on Android 8 and up ([FrameTap]: every frame the window draws, RenderThread-only frames included).
     * Below Android 8 FrameMetrics has no frame timestamps (and before Android 7, no FrameMetrics), so the drawn
     * frames fall back to the Choreographer intervals; the JSON's frameSource says which.
     */
    private class Meter(private val activity: Activity, private val mode: String, private val look: String, private val fps: Int) {
        @Suppress("DEPRECATION")
        val refreshHz: Float = activity.windowManager.defaultDisplay.refreshRate
        @Volatile var phase: String? = null
        private val startedAt = System.currentTimeMillis()
        private val lock = Any()
        class B { val ui = ArrayList<Float>(); val frame = ArrayList<Float>(); val render = ArrayList<Float>(); var dropped = 0 }
        private val buckets = LinkedHashMap<String, B>()
        private fun b(p: String) = buckets.getOrPut(p) { B() }
        // The API level comes from Platform (PlatformConfinementTest).
        private val tapped = dev.glassworks.platform.Platform.apiLevel >= 26
        private var tap: Any? = null // a FrameTap, only ever made on Android 8 and up
        private var lastUi = 0L
        private var lastUiPhase: String? = null
        private var running = false

        /** One drawn frame (from the FrameTap's thread): its interval since the last frame of the same phase, if any. */
        fun frame(p: String, intervalMs: Float?, renderMs: Float, dropped: Int) = synchronized(lock) {
            val bk = b(p)
            intervalMs?.let { bk.frame += it }
            bk.render += renderMs; bk.dropped += dropped
        }

        private val uiTick = object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (!running) return
                val p = phase
                // Only between two frames of the same phase: across a change the gap is the screen being still.
                if (p != null && lastUi != 0L && lastUiPhase == p) {
                    val iv = (now - lastUi) / 1e6f
                    synchronized(lock) { val bk = b(p); bk.ui += iv; if (!tapped) bk.frame += iv }
                }
                lastUi = if (p == null) 0L else now; lastUiPhase = p
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun start() {
            if (running) return
            running = true
            if (tapped) tap = FrameTap(activity, { phase }, ::frame).also { it.start() }
            Choreographer.getInstance().postFrameCallback(uiTick)
        }

        fun stop() {
            if (!running) return
            running = false
            (tap as? FrameTap)?.stop(); tap = null
            lastUi = 0L; lastUiPhase = null
        }

        /** Stops (loops) or keeps going (a live session), and writes everything so far; [runs] is loops or runs. */
        fun finish(runs: Int): File {
            if (mode == "loops") stop()
            val vsync = 1000f / refreshHz
            val rows = JSONArray()
            synchronized(lock) {
                for ((p, bk) in buckets) {
                    val fr = bk.frame.sorted(); val ui = bk.ui.sorted(); val rn = bk.render.sorted()
                    val over16 = fr.count { it > vsync + 1f }; val over33 = fr.count { it > 2 * vsync + 1f }
                    rows.put(JSONObject().put("variant", "spark").put("phase", p).put("frames", fr.size)
                        .put("p50", pct(fr, .5)).put("p90", pct(fr, .9)).put("p99", pct(fr, .99)).put("max", fr.lastOrNull() ?: 0f)
                        .put("over16_7", over16).put("over33", over33).put("dropped", bk.dropped)
                        .put("renderP50", pct(rn, .5)).put("renderP90", pct(rn, .9))
                        .put("uiP50", pct(ui, .5)).put("uiP90", pct(ui, .9)).put("uiP99", pct(ui, .99)))
                    Log.i(TAG, "%-5s %-4s %-9s frames=%4d p50=%5.1f p90=%5.1f p99=%5.1f over16.7=%3d over33=%3d render_p90=%5.1f ui_p90=%5.1f".format(
                        mode, look, p, fr.size, pct(fr, .5), pct(fr, .9), pct(fr, .99), over16, over33, pct(rn, .9), pct(ui, .9)))
                }
            }
            val metrics = activity.resources.displayMetrics
            val json = JSONObject().put("bench", "glowbench").put("round", 4).put("mode", mode).put("look", look).put("fps", fps)
                .put("package", activity.packageName).put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("api", dev.glassworks.platform.Platform.apiLevel).put("refreshHz", refreshHz.toDouble())
                .put("screen", "${metrics.widthPixels}x${metrics.heightPixels}").put("frameSource", if (tapped) "framemetrics" else "choreographer")
                .put("loops", runs).put("startedAt", startedAt).put("phases", rows)
            val name = "glowbench-$mode-$look-$fps-$startedAt.json"
            File(File(activity.filesDir, "glowbench").apply { mkdirs() }, name).writeText(json.toString(2))
            val ext = activity.getExternalFilesDir("glowbench")
            return if (ext != null) File(ext, name).apply { writeText(json.toString(2)) } else File(File(activity.filesDir, "glowbench"), name)
        }

        private fun pct(xs: List<Float>, q: Double): Float = if (xs.isEmpty()) 0f else xs[Math.round((xs.size - 1) * q).toInt()]
    }
}
