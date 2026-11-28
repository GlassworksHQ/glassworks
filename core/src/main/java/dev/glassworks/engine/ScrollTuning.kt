/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import android.util.Log

/**
 * The scroll path's optimizations, each behind a system property so one install can A/B them on a panel:
 * `adb shell setprop debug.glassworks.scroll.<knob> 1` turns one on, `0` keeps the behavior from before it. A knob
 * that isn't set follows `debug.glassworks.scroll.all` when that is set, else its default. The values are read
 * once, when the app starts ([init], or the first read), so a change takes a restart of the app.
 *
 * `debug.glassworks.scroll.trace 1` adds `Trace` sections where the knobs change something (for systrace/Perfetto);
 * `all` doesn't turn it on.
 */
object ScrollTuning {
    const val TAG = "GlassworksVitroScroll"
    private const val PREFIX = "debug.glassworks.scroll."

    enum class Knob(val key: String, val defaultOn: Boolean, val what: String) {
        /** A card clips its content to its rounded outline only when some of it reaches the corners. */
        CLIP("clip", true, "card outline clip only when content reaches the corners"),

        /** The view's backdrop is gone (no layer) unless the theme's backdrop is a picture. */
        BACKDROP("backdrop", true, "backdrop view gone unless it shows a picture"),

        /** The page's container and the window draw no full-screen fill under an opaque Vitro view. */
        OVERDRAW("overdraw", true, "no window or container fill under an opaque Vitro view"),

        /** A live update to an entity card lays out only that card, and unchanged text isn't set again. */
        RELAYOUT("relayout", true, "entity/glance live updates: unchanged text skipped, card-local relayout"),

        /** The window's surface is opaque (RGBX_8888) while Vitro shows a dashboard with no SurfaceView in it. */
        RGBX("rgbx", false, "opaque RGBX_8888 window while no SurfaceView is attached"),

        /** A state animation (tile badge, switch, slider) jumps to its end while the view scrolls or is off screen. */
        ANIM_SNAP("animsnap", true, "state animations snap to the end while scrolling or off screen"),

        /** A WebRTC camera posts its per-frame report only while someone wants it, as HLS does. */
        CAM_REPORT("camreport", true, "WebRTC frame reports only while wanted"),

        /** The clock sets a line again only when its text changed. */
        CLOCK_SPAN("clockspan", true, "clock lines skip unchanged text"),

        /**
         * A masonry column draws only the cards in a band around the viewport ([cullMarginPercent] of its height
         * above and below): the others aren't in its display list, so the render thread doesn't walk them.
         */
        CULL("cull", false, "masonry columns draw only cards near the viewport"),
    }

    /**
     * `debug.glassworks.scroll.cullmargin`: the band [Knob.CULL] keeps drawn above and below the viewport, in percent
     * of the viewport's height (default 50, 0 to 400).
     */
    @JvmStatic
    var cullMarginPercent: Int = DEFAULT_CULL_MARGIN
        get() {
            load()
            return field
        }
        private set

    private val values = BooleanArray(Knob.values().size)
    private val sources = arrayOfNulls<String>(Knob.values().size)

    @Volatile private var loaded = false

    /** `debug.glassworks.scroll.trace`: Trace sections where the knobs act. */
    @JvmStatic
    var trace: Boolean = false
        get() {
            load()
            return field
        }
        private set

    @JvmStatic
    fun on(knob: Knob): Boolean {
        load()
        return values[knob.ordinal]
    }

    val clip: Boolean get() = on(Knob.CLIP)
    val backdrop: Boolean get() = on(Knob.BACKDROP)
    val overdraw: Boolean get() = on(Knob.OVERDRAW)
    val relayout: Boolean get() = on(Knob.RELAYOUT)
    val rgbx: Boolean get() = on(Knob.RGBX)
    val animSnap: Boolean get() = on(Knob.ANIM_SNAP)
    val camReport: Boolean get() = on(Knob.CAM_REPORT)
    val clockSpan: Boolean get() = on(Knob.CLOCK_SPAN)
    val cull: Boolean get() = on(Knob.CULL)

    /** Reads the properties now and logs the values in force, once. MainActivity calls it as the app starts. */
    @JvmStatic
    fun init() = load()

    /** Tests: [knob] as given, whatever the properties say. */
    @JvmStatic
    fun setForTest(knob: Knob, on: Boolean) {
        load()
        values[knob.ordinal] = on
        sources[knob.ordinal] = "test"
    }

    /** Tests: every knob back to the properties' values. */
    @JvmStatic
    fun resetForTest() {
        synchronized(this) { loaded = false }
        load()
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val all = flag(property("all"))
            for (k in Knob.values()) {
                val own = flag(property(k.key))
                values[k.ordinal] = own ?: all ?: k.defaultOn
                sources[k.ordinal] = when {
                    own != null -> "set"
                    all != null -> "all"
                    else -> "default"
                }
            }
            trace = flag(property("trace")) == true
            cullMarginPercent = property("cullmargin").trim().toIntOrNull()?.coerceIn(0, 400) ?: DEFAULT_CULL_MARGIN
            loaded = true
            runCatching {
                Log.i(TAG, Knob.values().joinToString(" ", postfix = " cullmargin=$cullMarginPercent trace=${if (trace) 1 else 0}") { k ->
                    "${k.key}=${if (values[k.ordinal]) 1 else 0}(${sources[k.ordinal]})"
                })
            }
        }
    }

    private fun flag(v: String): Boolean? = when (v.trim()) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    private fun property(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, PREFIX + name) as? String
    }.getOrNull().orEmpty()

    /** A zero-length section named [name], when [trace] is on: marks an event on the calling thread's timeline. */
    @JvmStatic
    fun mark(name: String) {
        if (!trace) return
        android.os.Trace.beginSection(name.take(MAX_SECTION))
        android.os.Trace.endSection()
    }

    /** Runs [block] inside a section named by [name], when [trace] is on. */
    inline fun <T> section(name: () -> String, block: () -> T): T {
        if (!trace) return block()
        android.os.Trace.beginSection(name().take(MAX_SECTION))
        try {
            return block()
        } finally {
            android.os.Trace.endSection()
        }
    }

    /** Trace's limit on a section's name. */
    const val MAX_SECTION = 127

    private const val DEFAULT_CULL_MARGIN = 50

    /** Tests: [ScrollTuning.cullMarginPercent] as given. */
    @JvmStatic
    fun setCullMarginForTest(percent: Int) {
        load()
        cullMarginPercent = percent
    }
}
