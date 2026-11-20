package dev.glassworks.assist.glowbench

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): the window's drawn frames for GlowBench, from FrameMetrics, on a
 * thread of its own. Android 8 and up only (frame timestamps arrived in API 26): GlowBench makes one only there, so
 * the legacy build (Android 6-7) never loads this class.
 */
internal class FrameTap(
    private val activity: Activity,
    private val phase: () -> String?,
    private val report: (phase: String, intervalMs: Float?, renderMs: Float, dropped: Int) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var lastVsync = 0L
    private var lastPhase: String? = null

    private val listener = Window.OnFrameMetricsAvailableListener { _, fm, dropped ->
        val p = phase()
        val vs = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        if (p == null) { lastVsync = 0L; lastPhase = null; return@OnFrameMetricsAvailableListener }
        // Only between two frames of the same phase: across a change the gap is the screen being still, not a late frame.
        val interval = if (lastVsync != 0L && vs > lastVsync && lastPhase == p) (vs - lastVsync) / 1e6f else null
        report(p, interval, fm.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6f, dropped)
        lastVsync = vs; lastPhase = p
    }

    fun start() {
        val t = HandlerThread("GlowBenchFrames").also { it.start(); thread = it }
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
    }

    fun stop() {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        thread?.quitSafely(); thread = null
        lastVsync = 0L; lastPhase = null
    }
}
