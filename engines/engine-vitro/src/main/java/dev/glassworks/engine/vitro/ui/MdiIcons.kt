/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.glassworks.engine.vitro.data.EntityState
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Crisp 24x24 Material Design Icon vector renderer with zero icon-font overhead.
 * Works on Android 6+ (API 23) and scales cleanly from 720p wall switches to 40" 1080p mirrors.
 *
 * Once [init] has run, every name resolves through the official `@mdi/svg` catalog bundled as
 * [CATALOG_ASSET] (all 7,447 icons of 7.4.47, Apache-2.0), so glyphs match Home Assistant's own. A built APK
 * carries it un-gzipped as `vitro-mdi.tsv`; either form loads. The catalog is
 * read on first use and kept as one Latin-1 byte blob plus an offset index; a [Path] is parsed only when an icon is
 * actually drawn. Without a [Context] (pure JVM tests) the small built-in set below is the whole catalog.
 */
object MdiIcons {
    /** The gzipped `name\tpathData` catalog that `tools/vitro/update_mdi_icons.py` writes. */
    const val CATALOG_ASSET = "vitro-mdi.tsv.gz"

    /**
     * The same catalog as it lands in a built APK: the Android Gradle plugin's asset merge un-gzips `.gz` assets
     * (and the APK's own zip compresses it again), so the packaged name has no `.gz`.
     */
    private const val CATALOG_ASSET_PACKAGED = "vitro-mdi.tsv"

    private val cache = ConcurrentHashMap<String, Path>()
    private val registered = ConcurrentHashMap<String, String>()

    @Volatile private var assets: AssetManager? = null
    @Volatile private var catalog: Catalog? = null
    @Volatile private var catalogTried = false
    private val catalogLock = Any()

    /**
     * True while the warm-up thread parses the catalog. Icon lookups don't wait for it (a startup stall on a slow
     * panel's UI thread is worse than a moment of fallback glyphs): they draw the built-in glyph, and the views
     * that did are redrawn once the catalog is in ([redrawWhenReady]).
     */
    @Volatile private var loading = false
    private val waitingViews: MutableSet<IconHost> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** Path data packed in one byte array; [index] maps a name to `offset shl 32 or length`. */
    private class Catalog(val blob: ByteArray, val index: HashMap<String, Long>) {
        fun get(key: String): String? {
            val packed = index[key] ?: return null
            return String(blob, (packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt(), Charsets.ISO_8859_1)
        }
    }

    /**
     * Points the icon set at [context]'s bundled catalog. Call once per process (the engine does, on open); later
     * calls are no-ops. The catalog is warmed on a background thread so the first drawn icon doesn't wait for it.
     */
    fun init(context: Context) {
        if (assets != null) return
        val app = context.applicationContext ?: context
        synchronized(catalogLock) {
            if (assets != null) return
            assets = app.assets
            if (catalog == null) {
                catalogTried = false
                loading = true
            }
        }
        Thread({ catalog(warmUp = true) }, "vitro-mdi").apply { isDaemon = true }.start()
    }

    /** Loads a catalog from [input] (TSV, gzipped or not) directly, e.g. for off-device tools. Replaces any loaded one. */
    fun loadCatalog(input: InputStream) {
        val loaded = parseCatalog(input)
        synchronized(catalogLock) {
            catalog = loaded
            catalogTried = true
        }
        cache.clear()
    }

    /** How many icons the bundled catalog holds (0 until it is loaded or when there is none). */
    fun catalogSize(): Int = catalog(warmUp = true)?.index?.size ?: 0

    /**
     * The official catalog, loading it on first use; null when there's no [Context] or no asset. While the warm-up
     * thread is still loading it, an icon lookup ([warmUp] false) gets null at once instead of waiting.
     */
    private fun catalog(warmUp: Boolean = false): Catalog? {
        catalog?.let { return it }
        if (catalogTried) return null
        if (loading && !warmUp) return null
        val loaded: Catalog?
        synchronized(catalogLock) {
            catalog?.let { return it }
            if (catalogTried) return null
            val am = assets ?: return null
            loaded = sequenceOf(CATALOG_ASSET_PACKAGED, CATALOG_ASSET).firstNotNullOfOrNull { name ->
                runCatching { am.open(name).use(::parseCatalog) }.getOrNull()
            }
            catalog = loaded
            catalogTried = true
            if (loaded != null) cache.clear() // drop built-in glyphs parsed before the official ones arrived
            loading = false
        }
        redrawWaitingViews()
        return loaded
    }

    /** [view] drew a built-in glyph while the catalog was loading: it gets its icon again once the catalog is in. */
    internal fun redrawWhenReady(view: IconHost) {
        synchronized(waitingViews) { waitingViews += view }
        if (!loading) redrawWaitingViews() // finished in between
    }

    internal val isLoading: Boolean get() = loading

    private fun redrawWaitingViews() {
        val views = synchronized(waitingViews) { waitingViews.toList().also { waitingViews.clear() } }
        if (views.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post { views.forEach { it.reloadIcon() } }
    }

    private fun parseCatalog(input: InputStream): Catalog {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val source = if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered
        val bytes = ByteArrayOutputStream(3 shl 20).also { out -> source.use { it.copyTo(out, 64 * 1024) } }.toByteArray()
        val index = HashMap<String, Long>(10_000)
        var lineStart = 0
        val n = bytes.size
        while (lineStart < n) {
            var end = lineStart
            while (end < n && bytes[end] != '\n'.code.toByte()) end++
            var tab = lineStart
            while (tab < end && bytes[tab] != '\t'.code.toByte()) tab++
            var dataEnd = end
            if (dataEnd > tab && bytes[dataEnd - 1] == '\r'.code.toByte()) dataEnd--
            if (tab in (lineStart + 1) until dataEnd) {
                val name = String(bytes, lineStart, tab - lineStart, Charsets.ISO_8859_1)
                val off = tab + 1
                index[name] = (off.toLong() shl 32) or (dataEnd - off).toLong()
            }
            lineStart = end + 1
        }
        return Catalog(bytes, index)
    }

    /** Picks the best built-in MDI icon name for [explicitIcon] or [entity]. */
    fun resolveIconName(explicitIcon: String?, entity: EntityState?): String {
        if (!explicitIcon.isNullOrBlank()) return explicitIcon.lowercase()
        entity?.icon?.let { return it.lowercase() }
        // HA's own icon data (registry, integration and domain icons) once it has arrived.
        entity?.let { dev.glassworks.engine.vitro.data.HaDisplay.icon(it) }?.let { return it }
        val domain = entity?.domain.orEmpty()
        val dc = entity?.deviceClass.orEmpty()
        val st = entity?.state.orEmpty()
        // HA's own default icons (core's entity_component icons.json, as frontend/get_icons returns them), used until
        // the live data arrives: the domain's (or device class's) default, or its icon for the current state.
        return when (domain) {
            "light" -> if (st == "off") "mdi:lightbulb-off" else "mdi:lightbulb"
            "switch" -> if (dc == "outlet") { if (st == "off") "mdi:power-plug-off" else "mdi:power-plug" }
                else if (st == "off") "mdi:toggle-switch-variant-off" else "mdi:toggle-switch-variant"
            "input_boolean" -> if (st == "off") "mdi:close-circle-outline" else "mdi:check-circle-outline"
            "fan" -> if (st == "off") "mdi:fan-off" else "mdi:fan"
            "climate" -> "mdi:thermostat"
            "cover" -> {
                val (open, closed) = when (dc) {
                    "blind" -> "mdi:blinds-horizontal" to "mdi:blinds-horizontal-closed"
                    "curtain" -> "mdi:curtains" to "mdi:curtains-closed"
                    "shade" -> "mdi:roller-shade" to "mdi:roller-shade-closed"
                    "shutter" -> "mdi:window-shutter-open" to "mdi:window-shutter"
                    "garage" -> "mdi:garage-open" to "mdi:garage"
                    "door" -> "mdi:door-open" to "mdi:door-closed"
                    "gate" -> "mdi:gate-open" to "mdi:gate"
                    "awning" -> "mdi:awning-outline" to "mdi:awning-outline"
                    else -> "mdi:window-open" to "mdi:window-closed"
                }
                when (st) {
                    "closed" -> closed
                    "closing" -> "mdi:arrow-down-box"
                    "opening" -> "mdi:arrow-up-box"
                    else -> open
                }
            }
            "lock" -> when (st) {
                "jammed" -> "mdi:lock-alert"
                "locking", "unlocking", "opening" -> "mdi:lock-clock"
                "open", "unlocked" -> "mdi:lock-open-variant"
                else -> "mdi:lock"
            }
            "camera" -> if (st == "off") "mdi:video-off" else "mdi:video"
            "media_player" -> when (dc) {
                "speaker" -> when (st) { "off" -> "mdi:speaker-off"; "paused" -> "mdi:speaker-pause"; "playing" -> "mdi:speaker-play"; else -> "mdi:speaker" }
                "tv" -> when (st) { "off" -> "mdi:television-off"; "paused" -> "mdi:television-pause"; "playing" -> "mdi:television-play"; else -> "mdi:television" }
                "receiver" -> if (st == "off") "mdi:audio-video-off" else "mdi:audio-video"
                else -> when (st) { "off" -> "mdi:cast-off"; "paused", "playing" -> "mdi:cast-connected"; else -> "mdi:cast" }
            }
            "weather" -> weatherIcon(st)
            "person", "device_tracker" -> if (st == "not_home") "mdi:account-arrow-right" else "mdi:account"
            "scene" -> "mdi:palette"
            "script" -> if (st == "on") "mdi:script-text-play" else "mdi:script-text"
            "automation" -> if (st == "off") "mdi:robot-off" else "mdi:robot"
            "todo" -> "mdi:clipboard-list"
            "schedule" -> "mdi:calendar-clock"
            "timer" -> "mdi:timer-outline"
            "calendar" -> when (st) { "off" -> "mdi:calendar-blank"; "on" -> "mdi:calendar-check"; else -> "mdi:calendar" }
            "alarm_control_panel" -> when (st) {
                "armed_away" -> "mdi:shield-lock"
                "armed_custom_bypass" -> "mdi:security"
                "armed_home" -> "mdi:shield-home"
                "armed_night" -> "mdi:shield-moon"
                "armed_vacation" -> "mdi:shield-airplane"
                "disarmed" -> "mdi:shield-off"
                "pending" -> "mdi:shield-outline"
                "triggered" -> "mdi:bell-ring"
                else -> "mdi:shield"
            }
            "select", "input_select" -> "mdi:format-list-bulleted"
            "number", "input_number" -> "mdi:ray-vertex"
            "text", "input_text" -> "mdi:form-textbox"
            "button", "input_button" -> "mdi:button-pointer"
            "vacuum" -> "mdi:robot-vacuum"
            "image" -> "mdi:image"
            "assist_satellite" -> "mdi:account-voice"
            "humidifier" -> if (st == "off") "mdi:air-humidifier-off" else "mdi:air-humidifier"
            "siren" -> "mdi:bullhorn"
            // No icons.json in core: the frontend's FALLBACK_DOMAIN_ICONS.
            "alert" -> "mdi:alert"
            "update" -> if (st == "off") "mdi:package" else "mdi:package-up"
            "valve" -> when (st) { "closed" -> "mdi:valve-closed"; "closing", "opening" -> "mdi:valve"; else -> "mdi:valve-open" }
            "binary_sensor" -> {
                val on = st == "on"
                when (dc) {
                    "battery" -> if (on) "mdi:battery-outline" else "mdi:battery"
                    "connectivity" -> if (on) "mdi:check-network-outline" else "mdi:close-network-outline"
                    "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
                    "garage_door" -> if (on) "mdi:garage-open" else "mdi:garage"
                    "lock" -> if (on) "mdi:lock-open" else "mdi:lock"
                    "moisture" -> if (on) "mdi:water" else "mdi:water-off"
                    "motion" -> if (on) "mdi:motion-sensor" else "mdi:motion-sensor-off"
                    "occupancy", "presence" -> if (on) "mdi:home" else "mdi:home-outline"
                    "opening" -> if (on) "mdi:square-outline" else "mdi:square"
                    "plug", "power" -> if (on) "mdi:power-plug" else "mdi:power-plug-off"
                    "problem" -> if (on) "mdi:alert-circle" else "mdi:check-circle"
                    "smoke" -> if (on) "mdi:smoke-detector-variant-alert" else "mdi:smoke-detector-variant"
                    "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
                    else -> if (on) "mdi:checkbox-marked-circle" else "mdi:radiobox-blank"
                }
            }
            "sensor" -> when (dc) {
                "temperature" -> "mdi:thermometer"
                "humidity" -> "mdi:water-percent"
                "power" -> "mdi:flash"
                "energy" -> "mdi:lightning-bolt"
                "voltage" -> "mdi:sine-wave"
                "current" -> "mdi:current-ac"
                "battery" -> "mdi:battery-unknown"
                "illuminance" -> "mdi:brightness-5"
                "pressure" -> "mdi:gauge"
                "carbon_dioxide" -> "mdi:molecule-co2"
                "pm25", "pm10", "pm1" -> "mdi:molecule"
                "aqi" -> "mdi:air-filter"
                "duration" -> "mdi:progress-clock"
                "timestamp" -> "mdi:clock"
                "date" -> "mdi:calendar"
                "monetary" -> "mdi:cash"
                "signal_strength" -> "mdi:wifi"
                "gas" -> "mdi:meter-gas"
                "water", "moisture" -> "mdi:water"
                else -> "mdi:eye"
            }
            else -> "mdi:bookmark"
        }
    }

    fun weatherIcon(condition: String): String = when (condition.lowercase()) {
        "sunny" -> "mdi:white-balance-sunny"
        "clear-night" -> "mdi:weather-night"
        "partlycloudy" -> "mdi:weather-partly-cloudy"
        "cloudy" -> "mdi:weather-cloudy"
        "fog" -> "mdi:weather-fog"
        "windy", "windy-variant" -> "mdi:weather-windy"
        "rainy", "pouring" -> "mdi:weather-rainy"
        "snowy", "snowy-rainy", "hail" -> "mdi:snowflake"
        "lightning", "lightning-rainy" -> "mdi:flash"
        // HA's weatherIcons: no weather glyph for it, the alert one.
        "exceptional" -> "mdi:alert-circle-outline"
        else -> "mdi:weather-cloudy"
    }

    /** Returns the raw 24x24 SVG path data string for [name] (also served to Tier 3 `<ha-icon>` shims). */
    fun pathDataFor(name: String): String = pathDataOrNull(name) ?: pathDataOrNull("home") ?: PATHS.getValue("home")

    /**
     * The path data for [name], or null when no source knows it. [register]ed glyphs win, then the official
     * catalog, then the built-in set.
     */
    fun pathDataOrNull(name: String): String? {
        val key = keyOf(name)
        return registered[key] ?: catalog()?.get(key) ?: PATHS[key]
    }

    /** True when [name] (with or without `mdi:`/`hass:`) has a glyph, so callers can fall back before drawing `home`. */
    fun has(name: String): Boolean = pathDataOrNull(name) != null

    /** Every icon name Vitro can draw: the built-in set, the official catalog when loaded, and anything [register]ed. */
    fun names(): Set<String> {
        val out = LinkedHashSet<String>(PATHS.keys)
        catalog(warmUp = true)?.index?.keys?.let(out::addAll)
        out += registered.keys
        return out
    }

    /**
     * Adds or replaces the glyph for [name] with 24x24 SVG [pathData] (M/L/H/V/C/S/Q/T/A/Z, absolute or
     * relative). Registered glyphs win over built-in ones.
     */
    fun register(name: String, pathData: String) {
        val key = keyOf(name)
        require(key.isNotEmpty()) { "icon name is empty" }
        registered[key] = pathData
        cache.remove(key)
    }

    fun pathFor(name: String): Path {
        val key = keyOf(name)
        cache[key]?.let { return it }
        val reg = registered[key]
        val pendingCatalog = loading
        // The same order as the island's lookup: registered, the catalog's own glyph, the built-in one, and only then
        // the catalog's home (a catalog without `thermometer` must not turn a built-in thermometer into a house).
        val catalog = if (reg == null) catalog() else null
        val exact = catalog?.get(key)
        val builtIn = if (reg == null && exact == null) PATHS[key] else null
        val official = exact ?: if (reg == null && builtIn == null) catalog?.get("home") else null
        // Official MDI glyphs use SVG's default non-zero fill; the hand-drawn built-ins rely on even-odd holes.
        val path = when {
            reg != null -> parseSvgPath(reg)
            official != null -> parseSvgPath(official, Path.FillType.WINDING)
            else -> parseSvgPath(builtIn ?: PATHS.getValue("home"))
        }
        // A stand-in drawn while the catalog loads isn't cached: the official glyph replaces it.
        if (pendingCatalog && reg == null && official == null) return path
        return cache.putIfAbsent(key, path) ?: path
    }

    /**
     * [glyph] scaled by [scale] and moved by [dx], [dy], as an icon box draws it: one shared, never-changed path per
     * glyph, size and offset, so identical icons hand the renderer the same path (Skia caches a path's mask by its
     * generation ID, which a path rebuilt for every draw never repeats). Built with the same scale-then-translate
     * matrix each view used to apply to its own copy, so the points, and the pixels, are the same.
     */
    fun placed(glyph: Path, scale: Float, dx: Float, dy: Float): Path {
        val key = PlacedKey(glyph, scale, dx, dy)
        synchronized(placedCache) { placedCache[key]?.let { return it } }
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val out = Path().apply {
            set(glyph)
            transform(m)
        }
        synchronized(placedCache) { return placedCache.getOrPut(key) { out } }
    }

    /** A glyph (by identity: a reloaded catalog's glyph is a new path) at one size and offset. */
    private class PlacedKey(val glyph: Path, val scale: Float, val dx: Float, val dy: Float) {
        override fun equals(other: Any?): Boolean = other is PlacedKey && other.glyph === glyph &&
            other.scale.toRawBits() == scale.toRawBits() && other.dx.toRawBits() == dx.toRawBits() && other.dy.toRawBits() == dy.toRawBits()

        override fun hashCode(): Int =
            ((System.identityHashCode(glyph) * 31 + scale.toRawBits()) * 31 + dx.toRawBits()) * 31 + dy.toRawBits()
    }

    /** The placed glyphs, least recently drawn first; a dashboard draws far fewer distinct ones than this holds. */
    private val placedCache = object : LinkedHashMap<PlacedKey, Path>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlacedKey, Path>): Boolean = size > PLACED_MAX
    }
    private const val PLACED_MAX = 512

    private fun keyOf(name: String): String = name.trim().lowercase().removePrefix("mdi:").removePrefix("hass:")

    /** Receives the drawing commands [parseSvgPathInto] emits. Pure, so path data can be checked off-device. */
    interface PathSink {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
        fun close()
    }

    /** Parses SVG path data into an Android [Path] for 24x24 MDI glyphs ([fillType] defaults to even-odd). */
    fun parseSvgPath(d: String, fillType: Path.FillType = Path.FillType.EVEN_ODD): Path {
        // Even-odd by default so nested sub-paths (door panes, glyph cut-outs) render as holes regardless of winding.
        val path = Path().apply { this.fillType = fillType }
        parseSvgPathInto(
            d,
            object : PathSink {
                override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
                override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadTo(x1, y1, x, y)
                override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
                override fun close() = path.close()
            },
        )
        return path
    }

    /**
     * SVG path data parser (M/L/H/V/C/S/Q/T/A/Z, upper and lower case) feeding [sink]. Arcs become cubic
     * Béziers, so MDI paths copied from the web render with round curves rather than straight chords.
     */
    fun parseSvgPathInto(d: String, sink: PathSink) {
        val tokens = tokenize(d)
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        // Last control point, for the smooth S/T forms; reset by every other command.
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastWasCubic = false
        var lastWasQuad = false

        fun nextF(): Float = if (i < tokens.size) tokens[i++].toFloatOrNull() ?: 0f else 0f
        fun isNum(t: String): Boolean = t.firstOrNull()?.let { it.isDigit() || it == '-' || it == '.' || it == '+' } == true
        fun hasNum(): Boolean = i < tokens.size && isNum(tokens[i])
        // A whole parameter group is there: SVG stops at an incomplete one instead of filling in zeros.
        fun hasNums(n: Int): Boolean = (0 until n).all { k -> i + k < tokens.size && isNum(tokens[i + k]) }
        // Arc flags may be packed against the next number ("0 01 1" = 0, 0, 1, 1); take one digit.
        fun nextFlag(): Boolean {
            if (i >= tokens.size) return false
            val t = tokens[i]
            if (t.length > 1 && (t[0] == '0' || t[0] == '1')) {
                tokens[i] = t.substring(1)
                return t[0] == '1'
            }
            i++
            return (t.toFloatOrNull() ?: 0f) != 0f
        }

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    sink.close()
                    cx = sx; cy = sy
                    lastWasCubic = false; lastWasQuad = false
                    continue
                }
            } else if (!hasNum()) {
                i++
                continue
            }
            var cubic = false
            var quad = false
            val need = when (cmd) {
                'M', 'm', 'L', 'l', 'T', 't' -> 2
                'H', 'h', 'V', 'v' -> 1
                'C', 'c' -> 6
                'S', 's', 'Q', 'q' -> 4
                else -> 0
            }
            if (need > 0 && !hasNums(need)) break
            when (cmd) {
                'M', 'm' -> {
                    val x = nextF(); val y = nextF()
                    if (cmd == 'm') { cx += x; cy += y } else { cx = x; cy = y }
                    sx = cx; sy = cy
                    sink.moveTo(cx, cy)
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L' -> { cx = nextF(); cy = nextF(); sink.lineTo(cx, cy) }
                'l' -> { cx += nextF(); cy += nextF(); sink.lineTo(cx, cy) }
                'H' -> { cx = nextF(); sink.lineTo(cx, cy) }
                'h' -> { cx += nextF(); sink.lineTo(cx, cy) }
                'V' -> { cy = nextF(); sink.lineTo(cx, cy) }
                'v' -> { cy += nextF(); sink.lineTo(cx, cy) }
                'C', 'c', 'S', 's' -> {
                    val rel = cmd.isLowerCase()
                    val ox = if (rel) cx else 0f
                    val oy = if (rel) cy else 0f
                    val x1: Float
                    val y1: Float
                    if (cmd == 'S' || cmd == 's') {
                        x1 = if (lastWasCubic) 2 * cx - lastCtrlX else cx
                        y1 = if (lastWasCubic) 2 * cy - lastCtrlY else cy
                    } else {
                        x1 = ox + nextF(); y1 = oy + nextF()
                    }
                    val x2 = ox + nextF(); val y2 = oy + nextF()
                    cx = ox + nextF(); cy = oy + nextF()
                    sink.cubicTo(x1, y1, x2, y2, cx, cy)
                    lastCtrlX = x2; lastCtrlY = y2
                    cubic = true
                }
                'Q', 'q', 'T', 't' -> {
                    val rel = cmd.isLowerCase()
                    val ox = if (rel) cx else 0f
                    val oy = if (rel) cy else 0f
                    val x1: Float
                    val y1: Float
                    if (cmd == 'T' || cmd == 't') {
                        x1 = if (lastWasQuad) 2 * cx - lastCtrlX else cx
                        y1 = if (lastWasQuad) 2 * cy - lastCtrlY else cy
                    } else {
                        x1 = ox + nextF(); y1 = oy + nextF()
                    }
                    cx = ox + nextF(); cy = oy + nextF()
                    sink.quadTo(x1, y1, cx, cy)
                    lastCtrlX = x1; lastCtrlY = y1
                    quad = true
                }
                'A', 'a' -> {
                    val rx = nextF(); val ry = nextF(); val rot = nextF()
                    val large = nextFlag(); val sweep = nextFlag()
                    val x = nextF(); val y = nextF()
                    val ex = if (cmd == 'a') cx + x else x
                    val ey = if (cmd == 'a') cy + y else y
                    arcTo(sink, cx, cy, rx, ry, rot, large, sweep, ex, ey)
                    cx = ex; cy = ey
                }
                else -> nextF() // Unknown command: skip its numbers.
            }
            lastWasCubic = cubic
            lastWasQuad = quad
        }
    }

    /** SVG elliptical arc (endpoint form, SVG 1.1 F.6.5) as at most four cubic Béziers per full turn. */
    private fun arcTo(
        sink: PathSink, x0: Float, y0: Float, rxIn: Float, ryIn: Float, angleDeg: Float,
        largeArc: Boolean, sweep: Boolean, x: Float, y: Float,
    ) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { sink.lineTo(x, y); return }
        val phi = Math.toRadians(angleDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (largeArc == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val ccx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val ccy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0
        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dTheta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if (sweep && dTheta < 0) dTheta += 2 * PI else if (!sweep && dTheta > 0) dTheta -= 2 * PI
        val segments = ceil(abs(dTheta) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val delta = dTheta / segments
        val t = 4.0 / 3.0 * tan(delta / 4)
        var th = theta1
        repeat(segments) { n ->
            val th2 = th + delta
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th2); val s2 = sin(th2)
            val p1x = ccx + rx * cosPhi * c1 - ry * sinPhi * s1
            val p1y = ccy + rx * sinPhi * c1 + ry * cosPhi * s1
            val d1x = -rx * cosPhi * s1 - ry * sinPhi * c1
            val d1y = -rx * sinPhi * s1 + ry * cosPhi * c1
            val last = n == segments - 1
            val p2x = if (last) x.toDouble() else ccx + rx * cosPhi * c2 - ry * sinPhi * s2
            val p2y = if (last) y.toDouble() else ccy + rx * sinPhi * c2 + ry * cosPhi * s2
            val d2x = -rx * cosPhi * s2 - ry * sinPhi * c2
            val d2y = -rx * sinPhi * s2 + ry * cosPhi * c2
            sink.cubicTo(
                (p1x + t * d1x).toFloat(), (p1y + t * d1y).toFloat(),
                (p2x - t * d2x).toFloat(), (p2y - t * d2y).toFloat(),
                p2x.toFloat(), p2y.toFloat(),
            )
            th = th2
        }
    }

    private fun tokenize(d: String): MutableList<String> {
        val out = ArrayList<String>(64)
        var i = 0
        val n = d.length
        while (i < n) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> { out += c.toString(); i++ }
                else -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    var dot = false
                    while (i < n) {
                        val ch = d[i]
                        if (ch.isDigit()) i++
                        else if (ch == '.' && !dot) { dot = true; i++ }
                        // An exponent belongs to the number (`1e1`, `2.5E-3`); a sign without one starts the next.
                        else if ((ch == 'e' || ch == 'E') && i > start && i + 1 < n &&
                            (d[i + 1].isDigit() || ((d[i + 1] == '-' || d[i + 1] == '+') && i + 2 < n && d[i + 2].isDigit()))
                        ) {
                            i += if (d[i + 1].isDigit()) 1 else 2
                            while (i < n && d[i].isDigit()) i++
                            break
                        }
                        else break
                    }
                    if (i > start) out += d.substring(start, i) else i++
                }
            }
        }
        return out
    }

    // Standard 24x24 geometric icon paths (original clean vector paths for HA domains and dashboards).
    private val PATHS = mapOf(
        "home" to "M10 20V14H14V20H19V12H22L12 3L2 12H5V20H10Z",
        "lightbulb" to "M9 21H15V19H9V21ZM12 2C8.1 2 5 5.1 5 9C5 11.4 6.2 13.5 8 14.7V17H16V14.7C17.8 13.5 19 11.4 19 9C19 5.1 15.9 2 12 2Z",
        "lightbulb-on" to "M12 2C8.1 2 5 5.1 5 9C5 11.4 6.2 13.5 8 14.7V17H16V14.7C17.8 13.5 19 11.4 19 9C19 5.1 15.9 2 12 2ZM9 21H15V19H9V21ZM1 9H3V11H1V9ZM21 9H23V11H21V9Z",
        "power" to "M11 3H13V12H11V3ZM16.5 5.5L15.1 6.9C16.9 8.1 18 10.1 18 12.3C18 15.6 15.3 18.3 12 18.3C8.7 18.3 6 15.6 6 12.3C6 10.1 7.1 8.1 8.9 6.9L7.5 5.5C5.4 7.1 4 9.5 4 12.3C4 16.7 7.6 20.3 12 20.3C16.4 20.3 20 16.7 20 12.3C20 9.5 18.6 7.1 16.5 5.5Z",
        "thermometer" to "M15 13V5C15 3.3 13.7 2 12 2C10.3 2 9 3.3 9 5V13C7.8 14 7 15.4 7 17C7 19.8 9.2 22 12 22C14.8 22 17 19.8 17 17C17 15.4 16.2 14 15 13ZM11 5C11 4.4 11.4 4 12 4C12.6 4 13 4.4 13 5V11H11V5Z",
        "water-percent" to "M12 2L6.5 10.2C5.5 11.7 5 13.3 5 15C5 18.9 8.1 22 12 22C15.9 22 19 18.9 19 15C19 13.3 18.5 11.7 17.5 10.2L12 2Z",
        "flash" to "M7 2V13H10V22L17 10H13L17 2H7Z",
        "door-closed" to "M6 3H18V21H6V3ZM8 5V19H16V5H8ZM13 11H15V13H13V11Z",
        "door-open" to "M5 3H15V5H19V21H17V7H15V21H5V3ZM12 11H14V13H12V11Z",
        "window-closed" to "M4 3H20V21H4V3ZM6 5V11H11V5H6ZM13 5V11H18V5H13ZM6 13V19H11V13H6ZM13 13V19H18V13H13Z",
        "window-open" to "M4 3H20V21H4V3ZM6 5V9H18V5H6ZM6 11V19H18V11H6Z",
        "motion-sensor" to "M13.5 5.5C14.6 5.5 15.5 4.6 15.5 3.5C15.5 2.4 14.6 1.5 13.5 1.5C12.4 1.5 11.5 2.4 11.5 3.5C11.5 4.6 12.4 5.5 13.5 5.5ZM9.8 8.9L7 23H9.1L10.9 15L13 17V23H15V15.5L12.9 13.5L13.5 10.5C14.8 12 16.8 13 19 13V11C17.1 11 15.5 10 14.7 8.6L13.7 7C13.3 6.4 12.7 6 12 6C11.7 6 11.5 6.1 11.2 6.2L6 8.3V13H8V9.6L9.8 8.9Z",
        "fan" to "M12 11C11.4 11 11 11.4 11 12C11 12.6 11.4 13 12 13C12.6 13 13 12.6 13 12C13 11.4 12.6 11 12 11ZM12 3C9.5 3 8.5 5.2 9.5 7.5L10.8 10.2C11.2 10 11.6 9.9 12 9.9L14.5 6C15.5 4.3 14.2 3 12 3ZM21 12C21 9.5 18.8 8.5 16.5 9.5L13.8 10.8C14 11.2 14.1 11.6 14.1 12L18 14.5C19.7 15.5 21 14.2 21 12ZM12 21C14.5 21 15.5 18.8 14.5 16.5L13.2 13.8C12.8 14 12.4 14.1 12 14.1L9.5 18C8.5 19.7 9.8 21 12 21ZM3 12C3 14.5 5.2 15.5 7.5 14.5L10.2 13.2C10 12.8 9.9 12.4 9.9 12L6 9.5C4.3 8.5 3 9.8 3 12Z",
        "lock" to "M18 8H17V6C17 3.2 14.8 1 12 1C9.2 1 7 3.2 7 6V8H6C4.9 8 4 8.9 4 10V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V10C20 8.9 19.1 8 18 8ZM9 6C9 4.3 10.3 3 12 3C13.7 3 15 4.3 15 6V8H9V6Z",
        "lock-open" to "M18 8H17V6C17 3.2 14.8 1 12 1C9.8 1 8 2.4 7.3 4.3L9.2 5C9.6 3.8 10.7 3 12 3C13.7 3 15 4.3 15 6V8H6C4.9 8 4 8.9 4 10V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V10C20 8.9 19.1 8 18 8Z",
        "cctv" to "M3 6L17 10L15 16L1 12L3 6ZM17 13L21 14V18H19V16L16 15L17 13Z",
        "play" to "M8 5V19L19 12L8 5Z",
        "pause" to "M6 5H10V19H6V5ZM14 5H18V19H14V5Z",
        "speaker" to "M7 3H17C18.1 3 19 3.9 19 5V19C19 20.1 18.1 21 17 21H7C5.9 21 5 20.1 5 19V5C5 3.9 5.9 3 7 3ZM12 10C9.8 10 8 11.8 8 14C8 16.2 9.8 18 12 18C14.2 18 16 16.2 16 14C16 11.8 14.2 10 12 10Z",
        "white-balance-sunny" to "M12 7C9.2 7 7 9.2 7 12C7 14.8 9.2 17 12 17C14.8 17 17 14.8 17 12C17 9.2 14.8 7 12 7ZM11 1H13V4H11V1ZM11 20H13V23H11V20ZM1 11H4V13H1V11ZM20 11H23V13H20V11Z",
        "weather-cloudy" to "M6 19H19C21.2 19 23 17.2 23 15C23 12.9 21.4 11.2 19.3 11C18.6 7.6 15.6 5 12 5C9.1 5 6.6 6.6 5.4 9C2.9 9.3 1 11.4 1 14C1 16.8 3.2 19 6 19Z",
        "weather-rainy" to "M6 15H19C21.2 15 23 13.2 23 11C23 8.9 21.4 7.2 19.3 7C18.6 3.6 15.6 1 12 1C9.1 1 6.6 2.6 5.4 5C2.9 5.3 1 7.4 1 10C1 12.8 3.2 15 6 15ZM7 17H9V22H7V17ZM12 17H14V22H12V17ZM17 17H19V22H17V17Z",
        "snowflake" to "M20.79 13.95L18.46 14.57L16.46 13.44V10.56L18.46 9.43L20.79 10.05L21.31 8.12L19.54 7.65L20 5.88L18.07 5.36L17.45 7.69L15.45 8.82L13 7.38V5.12L14.71 3.41L13.29 2L12 3.29L10.71 2L9.29 3.41L11 5.12V7.38L8.5 8.82L6.5 7.69L5.92 5.36L4 5.88L4.47 7.65L2.7 8.12L3.22 10.05L5.55 9.43L7.55 10.56V13.45L5.55 14.58L3.22 13.96L2.7 15.89L4.47 16.36L4 18.12L5.93 18.64L6.55 16.31L8.55 15.18L11 16.62V18.88L9.29 20.59L10.71 22L12 20.71L13.29 22L14.7 20.59L13 18.88V16.62L15.5 15.17L17.5 16.3L18.12 18.63L20 18.12L19.53 16.35L21.3 15.88L20.79 13.95ZM9.5 10.56L12 9.11L14.5 10.56V13.44L12 14.89L9.5 13.44V10.56Z",
        "battery" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9Z",
        "gauge" to "M12 4C6.5 4 2 8.5 2 14C2 16.2 2.7 18.2 4 19.9L5.5 18.5C4.6 17.2 4 15.7 4 14C4 9.6 7.6 6 12 6C16.4 6 20 9.6 20 14C20 15.7 19.4 17.2 18.5 18.5L20 19.9C21.3 18.2 22 16.2 22 14C22 8.5 17.5 4 12 4ZM12 12L16 8L17.4 9.4L13.4 13.4C13.8 14.3 13.2 15.5 12 15.5C10.9 15.5 10.2 14.3 10.7 13.3L12 12Z",
        "account" to "M12 4C9.8 4 8 5.8 8 8C8 10.2 9.8 12 12 12C14.2 12 16 10.2 16 8C16 5.8 14.2 4 12 4ZM12 14C8.7 14 4 15.7 4 19V21H20V19C20 15.7 15.3 14 12 14Z",
        "checkbox-blank-circle" to "M12 4C7.6 4 4 7.6 4 12C4 16.4 7.6 20 12 20C16.4 20 20 16.4 20 12C20 7.6 16.4 4 12 4Z",
        // Extended set: rooms, scenes, media, appliances, status and navigation glyphs (even-odd fill: sub-paths cut holes).
        "sofa" to "M5 8C3.9 8 3 8.9 3 10V12.3C1.8 12.7 1 13.8 1 15V19H3V17H21V19H23V15C23 13.8 22.2 12.7 21 12.3V10C21 8.9 20.1 8 19 8H5ZM5 10H19V12H17.5C16.7 12 16 12.7 16 13.5V15H8V13.5C8 12.7 7.3 12 6.5 12H5V10Z",
        "bed" to "M2 5H4V13H22V19H20V16H4V19H2V5ZM7 8H11Q12 8 12 9V10.6Q12 11.6 11 11.6H7Q6 11.6 6 10.6V9Q6 8 7 8ZM14 9H19C20.7 9 22 10.3 22 12V12.6H14V9Z",
        "desk" to "M2 6H22V9H20V19H18V9H6V19H4V9H2V6ZM8 10H16V14H8V10Z",
        "stove" to "M5 2H19Q21 2 21 4V20Q21 22 19 22H5Q3 22 3 20V4Q3 2 5 2ZM5 8H19V20H5ZM9.1 5C9.1 5.61 8.61 6.1 8 6.1C7.39 6.1 6.9 5.61 6.9 5C6.9 4.39 7.39 3.9 8 3.9C8.61 3.9 9.1 4.39 9.1 5ZM13.1 5C13.1 5.61 12.61 6.1 12 6.1C11.39 6.1 10.9 5.61 10.9 5C10.9 4.39 11.39 3.9 12 3.9C12.61 3.9 13.1 4.39 13.1 5ZM17.1 5C17.1 5.61 16.61 6.1 16 6.1C15.39 6.1 14.9 5.61 14.9 5C14.9 4.39 15.39 3.9 16 3.9C16.61 3.9 17.1 4.39 17.1 5Z",
        "television" to "M3 5H21Q23 5 23 7V17Q23 19 21 19H3Q1 19 1 17V7Q1 5 3 5ZM3 7H21V17H3ZM7 20H17V21.8H7Z",
        "movie-open" to "M18 4L20 8H17L15 4H13L15 8H12L10 4H8L10 8H7L5 4H4C2.9 4 2 4.9 2 6V18C2 19.1 2.9 20 4 20H20C21.1 20 22 19.1 22 18V4H18Z",
        "heart" to "M12 21L10.5 19.7C5.4 15.1 2 12 2 8.5C2 5.4 4.4 3 7.5 3C9.2 3 10.9 3.8 12 5.1C13.1 3.8 14.8 3 16.5 3C19.6 3 22 5.4 22 8.5C22 12 18.6 15.1 13.5 19.7L12 21Z",
        "weather-night" to "M12 3C7 3 3 7 3 12C3 17 7 21 12 21C15.6 21 18.7 18.9 20.2 15.8C19.5 16 18.7 16.2 18 16.2C13.7 16.2 10.2 12.7 10.2 8.4C10.2 6.6 10.8 5 11.8 3.7C11.9 3.4 12 3.2 12 3Z",
        "chat-processing" to "M4 3H20C21.1 3 22 3.9 22 5V15C22 16.1 21.1 17 20 17H7L2 22V5C2 3.9 2.9 3 4 3ZM8.9 10C8.9 10.77 8.27 11.4 7.5 11.4C6.73 11.4 6.1 10.77 6.1 10C6.1 9.23 6.73 8.6 7.5 8.6C8.27 8.6 8.9 9.23 8.9 10ZM13.4 10C13.4 10.77 12.77 11.4 12 11.4C11.23 11.4 10.6 10.77 10.6 10C10.6 9.23 11.23 8.6 12 8.6C12.77 8.6 13.4 9.23 13.4 10ZM17.9 10C17.9 10.77 17.27 11.4 16.5 11.4C15.73 11.4 15.1 10.77 15.1 10C15.1 9.23 15.73 8.6 16.5 8.6C17.27 8.6 17.9 9.23 17.9 10Z",
        "lightbulb-group" to "M12 3C9 3 6.5 5.4 6.5 8.4C6.5 10.3 7.5 12 9 13V15H15V13C16.5 12 17.5 10.3 17.5 8.4C17.5 5.4 15 3 12 3ZM9 16.5H15V18H9ZM9.5 19.5H14.5V21H9.5ZM3.5 7C2.4 7 1.5 7.9 1.5 9C1.5 9.8 2 10.5 2.7 10.8V12H4.3V10.8C5 10.5 5.5 9.8 5.5 9C5.5 7.9 4.6 7 3.5 7ZM20.5 7C19.4 7 18.5 7.9 18.5 9C18.5 9.8 19 10.5 19.7 10.8V12H21.3V10.8C22 10.5 22.5 9.8 22.5 9C22.5 7.9 21.6 7 20.5 7Z",
        "tablet" to "M5 2H19Q21 2 21 4V20Q21 22 19 22H5Q3 22 3 20V4Q3 2 5 2ZM5 5H19V17.5H5ZM12.9 19.8C12.9 20.3 12.5 20.7 12 20.7C11.5 20.7 11.1 20.3 11.1 19.8C11.1 19.3 11.5 18.9 12 18.9C12.5 18.9 12.9 19.3 12.9 19.8Z",
        "tablet-dashboard" to "M5 2H19Q21 2 21 4V20Q21 22 19 22H5Q3 22 3 20V4Q3 2 5 2ZM5 5H19V17.5H5ZM6.8 6.8H11.2V11.2H6.8ZM12.8 6.8H17.2V9.8H12.8ZM6.8 12.4H11.2V15.7H6.8ZM12.8 11.4H17.2V15.7H12.8Z",
        "monitor" to "M3 3H21Q23 3 23 5V16Q23 18 21 18H3Q1 18 1 16V5Q1 3 3 3ZM3 5H21V16H3ZM10 18H14V20H17V22H7V20H10V18Z",
        "shield-home" to "M12 1L3 5V11C3 16.5 6.8 21.7 12 23C17.2 21.7 21 16.5 21 11V5L12 1ZM12 7L7 11.5H8.5V16H10.8V13H13.2V16H15.5V11.5H17L12 7Z",
        "shield-lock" to "M12 1L3 5V11C3 16.5 6.8 21.7 12 23C17.2 21.7 21 16.5 21 11V5L12 1ZM9 11.5H15V17H9ZM12 6.2C10.6 6.2 9.5 7.3 9.5 8.7V11.5H10.8V8.7C10.8 8 11.3 7.5 12 7.5C12.7 7.5 13.2 8 13.2 8.7V11.5H14.5V8.7C14.5 7.3 13.4 6.2 12 6.2Z",
        "robot-vacuum" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20 12C20 16.42 16.42 20 12 20C7.58 20 4 16.42 4 12C4 7.58 7.58 4 12 4C16.42 4 20 7.58 20 12ZM14.6 12C14.6 13.44 13.44 14.6 12 14.6C10.56 14.6 9.4 13.44 9.4 12C9.4 10.56 10.56 9.4 12 9.4C13.44 9.4 14.6 10.56 14.6 12ZM11.2 5L11.2 7.6L12.8 7.6L12.8 5Z",
        "blinds" to "M3 3H21V5H3ZM4 6.5H20V8.5H4ZM4 10H20V12H4ZM4 13.5H20V15.5H4ZM4 17H20V19H4ZM11 19H13V22H11Z",
        "curtains" to "M2 3H22V5H2ZM3 5H11C11 11 9.5 17 8 21H3V5ZM21 5H13C13 11 14.5 17 16 21H21V5Z",
        "air-purifier" to "M6 2H18Q20 2 20 4V20Q20 22 18 22H6Q4 22 4 20V4Q4 2 6 2ZM7 5H17V7H7ZM7 9H17V10.5H7ZM7 12H17V13.5H7ZM7 15H17V16.5H7Z",
        "speaker-multiple" to "M5 4H13Q15 4 15 6V20Q15 22 13 22H5Q3 22 3 20V6Q3 4 5 4ZM10.6 8.5C10.6 9.38 9.88 10.1 9 10.1C8.12 10.1 7.4 9.38 7.4 8.5C7.4 7.62 8.12 6.9 9 6.9C9.88 6.9 10.6 7.62 10.6 8.5ZM12.2 16C12.2 17.77 10.77 19.2 9 19.2C7.23 19.2 5.8 17.77 5.8 16C5.8 14.23 7.23 12.8 9 12.8C10.77 12.8 12.2 14.23 12.2 16ZM17 6H19C20.1 6 21 6.9 21 8V18C21 19.1 20.1 20 19 20H17V6ZM19.9 15.5C19.9 16 19.5 16.4 19 16.4C18.5 16.4 18.1 16 18.1 15.5C18.1 15 18.5 14.6 19 14.6C19.5 14.6 19.9 15 19.9 15.5Z",
        "wifi" to "M2 8.5Q12 -0.5 22 8.5L20.3 10.2Q12 3 3.7 10.2Z M5 12.2Q12 6.3 19 12.2L17.4 13.8Q12 9.3 6.6 13.8Z M8.4 16Q12 13.2 15.6 16L12 20Z",
        "flower" to "M14.8 6.8C14.8 8.35 13.55 9.6 12 9.6C10.45 9.6 9.2 8.35 9.2 6.8C9.2 5.25 10.45 4 12 4C13.55 4 14.8 5.25 14.8 6.8ZM19.75 10.39C19.75 11.94 18.49 13.19 16.95 13.19C15.4 13.19 14.15 11.94 14.15 10.39C14.15 8.85 15.4 7.59 16.95 7.59C18.49 7.59 19.75 8.85 19.75 10.39ZM17.86 16.21C17.86 17.75 16.6 19.01 15.06 19.01C13.51 19.01 12.26 17.75 12.26 16.21C12.26 14.66 13.51 13.41 15.06 13.41C16.6 13.41 17.86 14.66 17.86 16.21ZM11.74 16.21C11.74 17.75 10.49 19.01 8.94 19.01C7.4 19.01 6.14 17.75 6.14 16.21C6.14 14.66 7.4 13.41 8.94 13.41C10.49 13.41 11.74 14.66 11.74 16.21ZM9.85 10.39C9.85 11.94 8.6 13.19 7.05 13.19C5.51 13.19 4.25 11.94 4.25 10.39C4.25 8.85 5.51 7.59 7.05 7.59C8.6 7.59 9.85 8.85 9.85 10.39ZM13.9 12C13.9 13.05 13.05 13.9 12 13.9C10.95 13.9 10.1 13.05 10.1 12C10.1 10.95 10.95 10.1 12 10.1C13.05 10.1 13.9 10.95 13.9 12Z",
        "water" to "M12 2.5C9.5 5.6 5 10.2 5 15C5 18.9 8.1 22 12 22C15.9 22 19 18.9 19 15C19 10.2 14.5 5.6 12 2.5Z",
        "calendar" to "M5 3H7V1.5H9V3H15V1.5H17V3H19C20.1 3 21 3.9 21 5V20C21 21.1 20.1 22 19 22H5C3.9 22 3 21.1 3 20V5C3 3.9 3.9 3 5 3ZM5 9H19V20H5ZM7 11H10V14H7ZM10.5 11H13.5V14H10.5ZM14 11H17V14H14ZM7 15H10V18H7ZM10.5 15H13.5V18H10.5Z",
        "checkbox-marked" to "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM10.5 17L6.5 13L7.9 11.6L10.5 14.2L16.1 8.6L17.5 10L10.5 17Z",
        "circle" to "M12 2A10 10 0 0 0 2 12A10 10 0 0 0 12 22A10 10 0 0 0 22 12A10 10 0 0 0 12 2Z",
        "circle-small" to "M12 10A2 2 0 0 0 10 12C10 13.11 10.9 14 12 14C13.11 14 14 13.11 14 12A2 2 0 0 0 12 10Z",
        "flash-alert" to "M7 2V13H10V22L17 10H13L17 2H7ZM19 15H21V17H19V15ZM19 7H21V13H19V7Z",
        "wrench" to "M22.7 19L13.6 9.9C14.5 7.6 14 4.9 12.1 3C10.1 1 7.1 0.6 4.7 1.7L9 6L6 9L1.6 4.7C0.4 7.1 0.9 10.1 2.9 12.1C4.8 14 7.5 14.5 9.8 13.6L18.9 22.7C19.3 23.1 19.9 23.1 20.3 22.7L22.6 20.4C23.1 20 23.1 19.3 22.7 19Z",
        "table-furniture" to "M2 6H22V9H20V19H18V13H6V19H4V9H2V6ZM6 9V11H18V9H6Z",
        "hvac" to "M19 3H5C3.9 3 3 3.9 3 5V19C3 20.1 3.9 21 5 21H19C20.1 21 21 20.1 21 19V5C21 3.9 20.1 3 19 3ZM19 19H5V5H19V19ZM12 12C12 9 13 7 15 7C16.4 7 17 7.8 17 8.8C17 10.6 14.8 12 12 12ZM12 12C15 12 17 13 17 15C17 16.4 16.2 17 15.2 17C13.4 17 12 14.8 12 12ZM12 12C12 15 11 17 9 17C7.6 17 7 16.2 7 15.2C7 13.4 9.2 12 12 12ZM12 12C9 12 7 11 7 9C7 7.6 7.8 7 8.8 7C10.6 7 12 9.2 12 12Z",
        "weather-sunny" to "M12 7A5 5 0 0 1 17 12A5 5 0 0 1 12 17A5 5 0 0 1 7 12A5 5 0 0 1 12 7ZM12 9A3 3 0 0 0 9 12A3 3 0 0 0 12 15A3 3 0 0 0 15 12A3 3 0 0 0 12 9ZM12 2L14.39 5.42C13.65 5.15 12.84 5 12 5C11.16 5 10.35 5.15 9.61 5.42L12 2ZM3.34 7L7.5 6.65C6.9 7.16 6.36 7.78 5.94 8.5C5.5 9.24 5.25 10 5.11 10.79L3.34 7ZM3.36 17L5.12 13.23C5.26 14 5.53 14.78 5.95 15.5C6.37 16.24 6.91 16.86 7.5 17.37L3.36 17ZM20.65 7L18.88 10.79C18.74 10 18.47 9.23 18.05 8.5C17.63 7.78 17.1 7.15 16.5 6.64L20.65 7ZM20.64 17L16.5 17.36C17.09 16.85 17.62 16.22 18.04 15.5C18.46 14.77 18.73 14 18.87 13.21L20.64 17ZM12 22L9.59 18.56C10.33 18.83 11.14 19 12 19C12.82 19 13.63 18.83 14.37 18.56L12 22Z",
        "cart-outline" to "M17 18A2 2 0 0 1 19 20A2 2 0 0 1 17 22C15.89 22 15 21.1 15 20C15 18.89 15.89 18 17 18ZM1 2H4.27L5.21 4H20A1 1 0 0 1 21 5C21 5.17 20.95 5.34 20.88 5.5L17.3 11.97C16.96 12.58 16.3 13 15.55 13H8.1L7.2 14.63L7.17 14.75A0.25 0.25 0 0 0 7.42 15H19V17H7C5.89 17 5 16.1 5 15C5 14.65 5.09 14.32 5.24 14.04L6.6 11.59L3 4H1V2ZM7 18A2 2 0 0 1 9 20A2 2 0 0 1 7 22C5.89 22 5 21.1 5 20C5 18.89 5.89 18 7 18ZM16 11L18.78 6H6.14L8.5 11H16Z",
        "cart" to "M1 2H4.3L5.1 4H21L18.5 12.5C18.3 13.1 17.8 13.5 17.1 13.5H8.1L7.2 15H19V17H5.3C4.3 17 3.7 15.9 4.2 15.1L5.5 12.8L2.3 4H1V2ZM8.9 20C8.9 21.05 8.05 21.9 7 21.9C5.95 21.9 5.1 21.05 5.1 20C5.1 18.95 5.95 18.1 7 18.1C8.05 18.1 8.9 18.95 8.9 20ZM18.9 20C18.9 21.05 18.05 21.9 17 21.9C15.95 21.9 15.1 21.05 15.1 20C15.1 18.95 15.95 18.1 17 18.1C18.05 18.1 18.9 18.95 18.9 20Z",
        "bell" to "M12 2C10.9 2 10 2.9 10 4V4.3C7.7 5.1 6 7.3 6 10V16L4 18V19H20V18L18 16V10C18 7.3 16.3 5.1 14 4.3V4C14 2.9 13.1 2 12 2Z M10 20C10 21.1 10.9 22 12 22C13.1 22 14 21.1 14 20H10Z",
        "alert" to "M12 2L1 21H23L12 2ZM11 9H13V15H11ZM11 16.5H13V18.5H11Z",
        "check-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM10.6 16.6L6.6 12.6L8 11.2L10.6 13.8L16.4 8L17.8 9.4L10.6 16.6Z",
        "close" to "M19 6.4L17.6 5L12 10.6L6.4 5L5 6.4L10.6 12L5 17.6L6.4 19L12 13.4L17.6 19L19 17.6L13.4 12L19 6.4Z",
        "chevron-right" to "M8.6 16.6L13.2 12L8.6 7.4L10 6L16 12L10 18L8.6 16.6Z",
        "chevron-up" to "M7.4 15.4L12 10.8L16.6 15.4L18 14L12 8L6 14L7.4 15.4Z",
        "chevron-down" to "M7.4 8.6L12 13.2L16.6 8.6L18 10L12 16L6 10L7.4 8.6Z",
        "plus" to "M19 13H13V19H11V13H5V11H11V5H13V11H19V13Z",
        "shield-off" to "M2.8 2.1L1.4 3.5L4.3 6.4C4.1 6.9 4 7.4 4 8V11C4 16.5 7.4 21.4 12 23C13.9 22.3 15.6 21.1 16.9 19.5L20.5 23.1L21.9 21.7L2.8 2.1ZM12 1L5.6 3.8L19.5 17.7C19.8 16.2 20 14.6 20 13V5L12 1Z",
        "checkbox-marked-circle" to "M12 2C6.5 2 2 6.5 2 12C2 17.5 6.5 22 12 22C17.5 22 22 17.5 22 12C22 6.5 17.5 2 12 2ZM10 17L5 12L6.4 10.6L10 14.2L17.6 6.6L19 8L10 17Z",
        "checkbox-blank-circle-outline" to "M12 2C6.5 2 2 6.5 2 12C2 17.5 6.5 22 12 22C17.5 22 22 17.5 22 12C22 6.5 17.5 2 12 2ZM12 4C16.4 4 20 7.6 20 12C20 16.4 16.4 20 12 20C7.6 20 4 16.4 4 12C4 7.6 7.6 4 12 4Z",
        "backspace-outline" to "M22 3H7C6.3 3 5.8 3.4 5.4 3.9L0 12L5.4 20.1C5.8 20.6 6.3 21 7 21H22C23.1 21 24 20.1 24 19V5C24 3.9 23.1 3 22 3ZM22 19H7.1L2.4 12L7.1 5H22V19ZM10.4 17L14 13.4L17.6 17L19 15.6L15.4 12L19 8.4L17.6 7L14 10.6L10.4 7L9 8.4L12.6 12L9 15.6L10.4 17Z",
        "minus" to "M19 13H5V11H19V13Z",
        "arrow-left" to "M20 11V13H8L13.5 18.5L12.1 19.9L4.2 12L12.1 4.1L13.5 5.5L8 11H20Z",
        "refresh" to "M17.7 6.3C16.2 4.8 14.2 4 12 4C7.6 4 4 7.6 4 12C4 16.4 7.6 20 12 20C15.7 20 18.8 17.5 19.7 14H17.6C16.8 16.3 14.6 18 12 18C8.7 18 6 15.3 6 12C6 8.7 8.7 6 12 6C13.7 6 15.1 6.7 16.3 7.7L13 11H20V4L17.7 6.3Z",
        "cog" to "M10.16 4.63L10.44 2.12L13.56 2.12L13.84 4.63L15.91 5.49L17.88 3.91L20.09 6.12L18.51 8.09L19.37 10.16L21.88 10.44L21.88 13.56L19.37 13.84L18.51 15.91L20.09 17.88L17.88 20.09L15.91 18.51L13.84 19.37L13.56 21.88L10.44 21.88L10.16 19.37L8.09 18.51L6.12 20.09L3.91 17.88L5.49 15.91L4.63 13.84L2.12 13.56L2.12 10.44L4.63 10.16L5.49 8.09L3.91 6.12L6.12 3.91L8.09 5.49ZM15.4 12C15.4 13.88 13.88 15.4 12 15.4C10.12 15.4 8.6 13.88 8.6 12C8.6 10.12 10.12 8.6 12 8.6C13.88 8.6 15.4 10.12 15.4 12Z",
        "tune" to "M3 5.2H12.6V6.8H3ZM17.4 5.2H21V6.8H17.4ZM17.1 6C17.1 7.16 16.16 8.1 15 8.1C13.84 8.1 12.9 7.16 12.9 6C12.9 4.84 13.84 3.9 15 3.9C16.16 3.9 17.1 4.84 17.1 6ZM3 11.2H5.6V12.8H3ZM10.4 11.2H21V12.8H10.4ZM10.1 12C10.1 13.16 9.16 14.1 8 14.1C6.84 14.1 5.9 13.16 5.9 12C5.9 10.84 6.84 9.9 8 9.9C9.16 9.9 10.1 10.84 10.1 12ZM3 17.2H13.6V18.8H3ZM18.4 17.2H21V18.8H18.4ZM18.1 18C18.1 19.16 17.16 20.1 16 20.1C14.84 20.1 13.9 19.16 13.9 18C13.9 16.84 14.84 15.9 16 15.9C17.16 15.9 18.1 16.84 18.1 18Z",
        "tune-vertical" to "M5 3V12H3V14H5V21H7V14H9V12H7V3H5ZM11 3V8H9V10H11V21H13V10H15V8H13V3H11ZM17 3V14H15V16H17V21H19V16H21V14H19V3H17Z",
        "swap-vertical" to "M9 3L5 7H8V14H10V7H13L9 3ZM16 17V10H14V17H11L15 21L19 17H16Z",
        "brightness-6" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM12 5.8C15.4 5.8 18.2 8.6 18.2 12C18.2 15.4 15.4 18.2 12 18.2Z",
        "palette" to "M12 3C7 3 3 7 3 12C3 17 7 21 11 21C12.1 21 13 20.1 13 19C13 18.5 12.8 18 12.5 17.7C12.2 17.4 12 16.9 12 16.5C12 15.4 12.9 14.5 14 14.5H16.5C19 14.5 21 12.5 21 10C21 6 17 3 12 3ZM8.9 11.5C8.9 12.27 8.27 12.9 7.5 12.9C6.73 12.9 6.1 12.27 6.1 11.5C6.1 10.73 6.73 10.1 7.5 10.1C8.27 10.1 8.9 10.73 8.9 11.5ZM11.4 7.5C11.4 8.27 10.77 8.9 10 8.9C9.23 8.9 8.6 8.27 8.6 7.5C8.6 6.73 9.23 6.1 10 6.1C10.77 6.1 11.4 6.73 11.4 7.5ZM15.9 7.5C15.9 8.27 15.27 8.9 14.5 8.9C13.73 8.9 13.1 8.27 13.1 7.5C13.1 6.73 13.73 6.1 14.5 6.1C15.27 6.1 15.9 6.73 15.9 7.5ZM18.9 11C18.9 11.77 18.27 12.4 17.5 12.4C16.73 12.4 16.1 11.77 16.1 11C16.1 10.23 16.73 9.6 17.5 9.6C18.27 9.6 18.9 10.23 18.9 11Z",
        "music" to "M12 3V13.6C11.4 13.2 10.7 13 10 13C7.8 13 6 14.8 6 17C6 19.2 7.8 21 10 21C12.2 21 14 19.2 14 17V7H18V3H12Z",
        "skip-next" to "M6 18L14.5 12L6 6V18ZM16 6V18H18V6H16Z",
        "skip-previous" to "M18 18L9.5 12L18 6V18ZM8 6V18H6V6H8Z",
        "volume-high" to "M3 9V15H7L12 20V4L7 9H3Z M14.5 8.8C15.5 9.6 16 10.7 16 12C16 13.3 15.5 14.4 14.5 15.2L15.6 16.6C17 15.4 17.8 13.8 17.8 12C17.8 10.2 17 8.6 15.6 7.4L14.5 8.8Z M17.4 5.6C19.3 7.2 20.4 9.5 20.4 12C20.4 14.5 19.3 16.8 17.4 18.4L18.5 19.8C20.8 17.9 22.2 15.1 22.2 12C22.2 8.9 20.8 6.1 18.5 4.2L17.4 5.6Z",
        "volume-off" to "M3 9V15H7L12 20V4L7 9H3Z M15 9.2L16.2 8L18 9.8L19.8 8L21 9.2L19.2 11L21 12.8L19.8 14L18 12.2L16.2 14L15 12.8L16.8 11L15 9.2Z",
        "sleep" to "M4 5H12V7L7.3 13H12V15H4V13L8.7 7H4V5Z M14 12H20V13.6L16.6 18H20V19.6H14V18L17.4 13.6H14V12Z",
        "heart-pulse" to "M12 21L10.5 19.7C5.4 15.1 2 12 2 8.5C2 5.4 4.4 3 7.5 3C9.2 3 10.9 3.8 12 5.1C13.1 3.8 14.8 3 16.5 3C19.6 3 22 5.4 22 8.5C22 12 18.6 15.1 13.5 19.7L12 21ZM4 12.85L8.96 12.85L9.95 9.9L12.53 17.3L14.75 12.85L20 12.85L20 11.15L13.65 11.15L12.67 13.9L10.05 6.5L7.84 11.15L4 11.15Z",
        "fire" to "M12 2C12 2 14 5 14 7.5C15.3 6.8 16.4 5.6 17 4C19.4 6.5 20.5 9 20.5 12.5C20.5 17.2 16.7 21 12 21C7.3 21 3.5 17.2 3.5 12.5C3.5 9.5 5.2 6.8 7.4 5C7.4 6.7 8.2 8.2 9.5 9C9.6 6.4 10.5 4 12 2ZM12 19C13.7 19 15 17.7 15 16C15 14.4 13.8 13 12 11C10.2 13 9 14.4 9 16C9 17.7 10.3 19 12 19Z",
        "clock" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM11 6.5H12.5V11.6L16.6 14L15.9 15.3L11 12.4V6.5Z",
        "car" to "M5.5 4H18.5C19.2 4 19.8 4.4 20.1 5L22 10V19C22 19.6 21.6 20 21 20H20C19.4 20 19 19.6 19 19V18H5V19C5 19.6 4.6 20 4 20H3C2.4 20 2 19.6 2 19V10L3.9 5C4.2 4.4 4.8 4 5.5 4ZM5.7 6H18.3L19.7 9.5H4.3L5.7 6ZM8 14C8 14.83 7.33 15.5 6.5 15.5C5.67 15.5 5 14.83 5 14C5 13.17 5.67 12.5 6.5 12.5C7.33 12.5 8 13.17 8 14ZM19 14C19 14.83 18.33 15.5 17.5 15.5C16.67 15.5 16 14.83 16 14C16 13.17 16.67 12.5 17.5 12.5C18.33 12.5 19 13.17 19 14Z",
        "garage" to "M12 2L1 8.5V22H23V8.5L12 2ZM4 10H20V22H4ZM5.5 12H18.5V13.5H5.5ZM5.5 15H18.5V16.5H5.5ZM5.5 18H18.5V19.5H5.5Z",
        "ev-station" to "M3 3H13C14.1 3 15 3.9 15 5V21H3V3ZM5 5H13V9.6H5ZM8.8 11.5L6.4 15.6H8.2V19L10.8 14.6H9L10.4 11.5H8.8ZM2 21H16V23H2ZM15 9.8L18.2 9.8L18.2 16.5L19.8 16.5L19.8 8.2L15 8.2ZM17.6 16.5H20.4V19.5H17.6Z",
        "solar-power" to "M4.5 10L19.5 10L22 19L2 19ZM5.03 10.9L8.9 10.9L8.55 14.05L4.24 14.05ZM9.98 10.9L13.86 10.9L14.07 14.05L9.76 14.05ZM14.95 10.9L18.82 10.9L19.59 14.05L15.28 14.05ZM4.01 14.95L8.45 14.95L8.1 18.1L3.23 18.1ZM9.69 14.95L14.13 14.95L14.34 18.1L9.46 18.1ZM15.37 14.95L19.81 14.95L20.58 18.1L15.71 18.1ZM14.1 4.4C14.1 5.56 13.16 6.5 12 6.5C10.84 6.5 9.9 5.56 9.9 4.4C9.9 3.24 10.84 2.3 12 2.3C13.16 2.3 14.1 3.24 14.1 4.4ZM11.25 0.3H12.75V2H11.25ZM5.8 3.65H8.4V5.15H5.8ZM15.6 3.65H18.2V5.15H15.6ZM11 19H13V21.4H11ZM7 21.4H17V23H7Z",
        "battery-charging" to "M9 2H15V4H17C17.6 4 18 4.4 18 5V21C18 21.6 17.6 22 17 22H7C6.4 22 6 21.6 6 21V5C6 4.4 6.4 4 7 4H9V2ZM12.6 8L9.5 13.4H11.7V18L14.8 12.6H12.6V8Z",
        "stop" to "M7 5H17Q19 5 19 7V17Q19 19 17 19H7Q5 19 5 17V7Q5 5 7 5Z",
        "radiator" to "M3 4H21V6H3ZM3 17H21V19H3ZM4.4 6H6.6V17H4.4ZM8.3 6H10.5V17H8.3ZM12.2 6H14.4V17H12.2ZM16.1 6H18.3V17H16.1ZM4 19H6V21H4ZM18 19H20V21H18Z",
        "air-conditioner" to "M4 3H20Q22 3 22 5V10Q22 12 20 12H4Q2 12 2 10V5Q2 3 4 3ZM4 8.4H20V10H4ZM6.4 15.36L7.38 17L6.18 19L7.6 21.36L8.8 20.64L7.82 19L9.02 17L7.6 14.64ZM11.4 15.36L12.38 17L11.18 19L12.6 21.36L13.8 20.64L12.82 19L14.02 17L12.6 14.64ZM16.4 15.36L17.38 17L16.18 19L17.6 21.36L18.8 20.64L17.82 19L19.02 17L17.6 14.64Z",
        "weather-windy" to "M3 9.9L14.3 9.9L16.9 7.95L16.9 4.53L14.52 2.86L13.48 4.34L15.1 5.47L15.1 7.05L13.7 8.1L3 8.1ZM3 14.1L17.68 14.1L19.1 15.23L19.1 16.33L17.48 17.46L18.52 18.94L20.9 17.27L20.9 14.37L18.32 12.3L3 12.3ZM3 18.1L8.7 18.1L9.7 18.85L9.7 19.95L8.46 20.88L9.54 22.32L11.5 20.85L11.5 17.95L9.3 16.3L3 16.3Z",
        "weather-fog" to "M7 12H18C19.7 12 21 10.7 21 9C21 7.5 19.9 6.2 18.4 6C17.7 3.7 15.5 2 13 2C10.9 2 9 3.2 8.2 5.1C6 5.3 4.5 7.1 4.5 9C4.5 10.7 5.6 12 7 12ZM3 15H21V16.7H3ZM5 18.6H19V20.3H5Z",
        "weather-partly-cloudy" to "M9.8 7C9.8 8.55 8.55 9.8 7 9.8C5.45 9.8 4.2 8.55 4.2 7C4.2 5.45 5.45 4.2 7 4.2C8.55 4.2 9.8 5.45 9.8 7ZM6.3 1H7.7V3H6.3ZM1 6.3H3V7.7H1ZM11 6.3H13V7.7H11ZM6.3 11H7.7V13H6.3ZM11 20H19C20.7 20 22 18.7 22 17C22 15.5 20.9 14.2 19.4 14C18.9 12.2 17.3 11 15.4 11C13.8 11 12.4 11.9 11.8 13.2C10.2 13.4 9 14.7 9 16.3C9 18.3 9.9 20 11 20Z",
        "home-assistant" to "M12 2L1.5 11.5V22H22.5V11.5L12 2ZM12 5L4 12.2V20H20V12.2L12 5ZM13.9 15.4C13.9 16.45 13.05 17.3 12 17.3C10.95 17.3 10.1 16.45 10.1 15.4C10.1 14.35 10.95 13.5 12 13.5C13.05 13.5 13.9 14.35 13.9 15.4ZM12.8 13.5L12.8 10L11.2 10L11.2 13.5Z",
        "view-dashboard" to "M3 3H11V13H3ZM13 3H21V9H13ZM3 15H11V21H3ZM13 11H21V21H13Z",
        "mirror" to "M19.5 10.8C19.5 15.94 16.14 20.1 12 20.1C7.86 20.1 4.5 15.94 4.5 10.8C4.5 5.66 7.86 1.5 12 1.5C16.14 1.5 19.5 5.66 19.5 10.8ZM17.8 10.8C17.8 15 15.2 18.4 12 18.4C8.8 18.4 6.2 15 6.2 10.8C6.2 6.6 8.8 3.2 12 3.2C15.2 3.2 17.8 6.6 17.8 10.8ZM9.92 13.02L13.82 7.22L12.58 6.38L8.68 12.18ZM11 20.1H13V21.5H11ZM8 21.5H16V23H8Z",
        "lamp" to "M8 3H16L19 12H5L8 3ZM11 12H13V19H11ZM7 19H17V21H7Z",
        "floor-lamp" to "M8 2H16L18 9H6L8 2ZM11.2 9H12.8V20.5H11.2ZM7 20.5H17V22H7Z",
        "ceiling-light" to "M2 2H22V3.8H2ZM11 3.8H13V7H11ZM4 14C4 10.2 7.5 7 12 7C16.5 7 20 10.2 20 14H4ZM5.23 16.28L4.23 19.78L5.77 20.22L6.77 16.72ZM11.2 16.5L11.2 21L12.8 21L12.8 16.5ZM17.23 16.72L18.23 20.22L19.77 19.78L18.77 16.28Z",
        "led-strip" to "M3.5 8H20.5Q22 8 22 9.5V14.5Q22 16 20.5 16H3.5Q2 16 2 14.5V9.5Q2 8 3.5 8ZM6.6 12C6.6 12.88 5.88 13.6 5 13.6C4.12 13.6 3.4 12.88 3.4 12C3.4 11.12 4.12 10.4 5 10.4C5.88 10.4 6.6 11.12 6.6 12ZM10.3 12C10.3 12.88 9.58 13.6 8.7 13.6C7.82 13.6 7.1 12.88 7.1 12C7.1 11.12 7.82 10.4 8.7 10.4C9.58 10.4 10.3 11.12 10.3 12ZM14 12C14 12.88 13.28 13.6 12.4 13.6C11.52 13.6 10.8 12.88 10.8 12C10.8 11.12 11.52 10.4 12.4 10.4C13.28 10.4 14 11.12 14 12ZM17.7 12C17.7 12.88 16.98 13.6 16.1 13.6C15.22 13.6 14.5 12.88 14.5 12C14.5 11.12 15.22 10.4 16.1 10.4C16.98 10.4 17.7 11.12 17.7 12ZM21 12C21 12.88 20.28 13.6 19.4 13.6C18.52 13.6 17.8 12.88 17.8 12C17.8 11.12 18.52 10.4 19.4 10.4C20.28 10.4 21 11.12 21 12Z",
        "microphone" to "M12 2C10.3 2 9 3.3 9 5V11C9 12.7 10.3 14 12 14C13.7 14 15 12.7 15 11V5C15 3.3 13.7 2 12 2ZM5 10.5H6.8C6.8 13.6 9.1 15.8 12 15.8C14.9 15.8 17.2 13.6 17.2 10.5H19C19 14.2 16.2 17.3 12.9 17.8V21H11.1V17.8C7.8 17.3 5 14.2 5 10.5Z",
        "image" to "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM10.3 8.5C10.3 9.49 9.49 10.3 8.5 10.3C7.51 10.3 6.7 9.49 6.7 8.5C6.7 7.51 7.51 6.7 8.5 6.7C9.49 6.7 10.3 7.51 10.3 8.5ZM5 18L10 12L13 15.5L15.5 12.5L19 18H5Z",
        "script-text" to "M6 2H18Q20 2 20 4V20Q20 22 18 22H6Q4 22 4 20V4Q4 2 6 2ZM7 6H17V7.6H7ZM7 10H17V11.6H7ZM7 14H13V15.6H7Z",
        "format-list-bulleted" to "M6.1 6C6.1 6.88 5.38 7.6 4.5 7.6C3.62 7.6 2.9 6.88 2.9 6C2.9 5.12 3.62 4.4 4.5 4.4C5.38 4.4 6.1 5.12 6.1 6ZM6.1 12C6.1 12.88 5.38 13.6 4.5 13.6C3.62 13.6 2.9 12.88 2.9 12C2.9 11.12 3.62 10.4 4.5 10.4C5.38 10.4 6.1 11.12 6.1 12ZM6.1 18C6.1 18.88 5.38 19.6 4.5 19.6C3.62 19.6 2.9 18.88 2.9 18C2.9 17.12 3.62 16.4 4.5 16.4C5.38 16.4 6.1 17.12 6.1 18ZM8 5.2H21V6.8H8ZM8 11.2H21V12.8H8ZM8 17.2H21V18.8H8Z",
        "gesture-tap-button" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM15.4 12C15.4 13.88 13.88 15.4 12 15.4C10.12 15.4 8.6 13.88 8.6 12C8.6 10.12 10.12 8.6 12 8.6C13.88 8.6 15.4 10.12 15.4 12Z",
        "text-box" to "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM5 5H19V19H5ZM7 8H17V9.6H7ZM7 11.3H17V12.9H7ZM7 14.6H13V16.2H7Z",
        "check" to "M9 16.2L4.8 12L3.4 13.4L9 19L21 7L19.6 5.6L9 16.2Z",
        "dots-vertical" to "M12 4C13.1 4 14 4.9 14 6C14 7.1 13.1 8 12 8C10.9 8 10 7.1 10 6C10 4.9 10.9 4 12 4ZM12 10C13.1 10 14 10.9 14 12C14 13.1 13.1 14 12 14C10.9 14 10 13.1 10 12C10 10.9 10.9 10 12 10ZM12 16C13.1 16 14 16.9 14 18C14 19.1 13.1 20 12 20C10.9 20 10 19.1 10 18C10 16.9 10.9 16 12 16Z",
        "menu-down" to "M7 10L12 15L17 10H7Z",
        "thermostat" to "M12 2C17.52 2 22 6.48 22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2ZM12 3.8C16.53 3.8 20.2 7.47 20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8ZM13 13.3V7.5C13 6.9 12.6 6.5 12 6.5C11.4 6.5 11 6.9 11 7.5V13.3C10.4 13.6 10 14.3 10 15C10 16.1 10.9 17 12 17C13.1 17 14 16.1 14 15C14 14.3 13.6 13.6 13 13.3Z",
        "arrow-up" to "M13 20H11V8L5.5 13.5L4.1 12.1L12 4.2L19.9 12.1L18.5 13.5L13 8V20Z",
        "arrow-down" to "M11 4H13V16L18.5 10.5L19.9 11.9L12 19.8L4.1 11.9L5.5 10.5L11 16V4Z",
        "chart-box-outline" to "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM5 5H19V19H5ZM7 12H9V17H7ZM11 7H13V17H11ZM15 10H17V17H15Z",
        // Status, climate, appliances and navigation glyphs drawn on the same 24x24 grid (original geometry).
        "information" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM11 10.5L13 10.5L13 17L11 17ZM13.2 7.8C13.2 8.46 12.66 9 12 9C11.34 9 10.8 8.46 10.8 7.8C10.8 7.14 11.34 6.6 12 6.6C12.66 6.6 13.2 7.14 13.2 7.8Z",
        "information-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM11 10.5L13 10.5L13 17L11 17ZM13.2 7.8C13.2 8.46 12.66 9 12 9C11.34 9 10.8 8.46 10.8 7.8C10.8 7.14 11.34 6.6 12 6.6C12.66 6.6 13.2 7.14 13.2 7.8Z",
        "alert-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM11 6.5L13 6.5L13 13.5L11 13.5ZM13.2 16.6C13.2 17.26 12.66 17.8 12 17.8C11.34 17.8 10.8 17.26 10.8 16.6C10.8 15.94 11.34 15.4 12 15.4C12.66 15.4 13.2 15.94 13.2 16.6Z",
        "alert-circle-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM11 6.5L13 6.5L13 13.5L11 13.5ZM13.2 16.6C13.2 17.26 12.66 17.8 12 17.8C11.34 17.8 10.8 17.26 10.8 16.6C10.8 15.94 11.34 15.4 12 15.4C12.66 15.4 13.2 15.94 13.2 16.6Z",
        "alert-outline" to "M12 2L23 21L1 21ZM12 6L19.5 19L4.5 19ZM11 9.5L13 9.5L13 14.5L11 14.5ZM11 15.8L13 15.8L13 17.8L11 17.8Z",
        "check-circle-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM10.6 16.6L6.6 12.6L8 11.2L10.6 13.8L16.4 8L17.8 9.4L10.6 16.6Z",
        "close-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM16.34 8.53L15.47 7.66L12 11.13L8.53 7.66L7.66 8.53L11.13 12L7.66 15.47L8.53 16.34L12 12.87L15.47 16.34L16.34 15.47L12.87 12Z",
        "close-circle-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM15.85 8.92L15.08 8.15L12 11.23L8.92 8.15L8.15 8.92L11.23 12L8.15 15.08L8.92 15.85L12 12.77L15.08 15.85L15.85 15.08L12.77 12Z",
        "help-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM9.6 8.6C9.6 6.83 11.03 5.4 12.8 5.4C14.57 5.4 16 6.83 16 8.6C16 10.37 14.57 11.8 12.8 11.8L12.8 13.9L11.2 13.9L11.2 10.2L12.8 10.2C13.68 10.2 14.4 9.48 14.4 8.6C14.4 7.72 13.68 7 12.8 7C11.92 7 11.2 7.72 11.2 8.6ZM13.15 15.9C13.15 16.54 12.64 17.05 12 17.05C11.36 17.05 10.85 16.54 10.85 15.9C10.85 15.26 11.36 14.75 12 14.75C12.64 14.75 13.15 15.26 13.15 15.9Z",
        "help-circle-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM9.6 8.6C9.6 6.83 11.03 5.4 12.8 5.4C14.57 5.4 16 6.83 16 8.6C16 10.37 14.57 11.8 12.8 11.8L12.8 13.9L11.2 13.9L11.2 10.2L12.8 10.2C13.68 10.2 14.4 9.48 14.4 8.6C14.4 7.72 13.68 7 12.8 7C11.92 7 11.2 7.72 11.2 8.6ZM13.15 15.9C13.15 16.54 12.64 17.05 12 17.05C11.36 17.05 10.85 16.54 10.85 15.9C10.85 15.26 11.36 14.75 12 14.75C12.64 14.75 13.15 15.26 13.15 15.9Z",
        "eye" to "M2 12C4.5 7 8 5 12 5C16 5 19.5 7 22 12C19.5 17 16 19 12 19C8 19 4.5 17 2 12ZM16.2 12C16.2 14.32 14.32 16.2 12 16.2C9.68 16.2 7.8 14.32 7.8 12C7.8 9.68 9.68 7.8 12 7.8C14.32 7.8 16.2 9.68 16.2 12ZM14.3 12C14.3 13.27 13.27 14.3 12 14.3C10.73 14.3 9.7 13.27 9.7 12C9.7 10.73 10.73 9.7 12 9.7C13.27 9.7 14.3 10.73 14.3 12Z",
        "eye-off" to "M2 12C4.5 7 8 5 12 5C16 5 19.5 7 22 12C19.5 17 16 19 12 19C8 19 4.5 17 2 12ZM16.2 12C16.2 14.32 14.32 16.2 12 16.2C9.68 16.2 7.8 14.32 7.8 12C7.8 9.68 9.68 7.8 12 7.8C14.32 7.8 16.2 9.68 16.2 12ZM14.3 12C14.3 13.27 13.27 14.3 12 14.3C10.73 14.3 9.7 13.27 9.7 12C9.7 10.73 10.73 9.7 12 9.7C13.27 9.7 14.3 10.73 14.3 12ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "bell-outline" to "M12 2C10.9 2 10 2.9 10 4V4.3C7.7 5.1 6 7.3 6 10V16L4 18V19H20V18L18 16V10C18 7.3 16.3 5.1 14 4.3V4C14 2.9 13.1 2 12 2ZM10 20C10 21.1 10.9 22 12 22C13.1 22 14 21.1 14 20H10ZM12 5.8C9.8 5.8 8 7.6 8 9.8V16.9H16V9.8C16 7.6 14.2 5.8 12 5.8Z",
        "bell-off" to "M12 2C10.9 2 10 2.9 10 4V4.3C7.7 5.1 6 7.3 6 10V16L4 18V19H20V18L18 16V10C18 7.3 16.3 5.1 14 4.3V4C14 2.9 13.1 2 12 2ZM10 20C10 21.1 10.9 22 12 22C13.1 22 14 21.1 14 20H10ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "bell-ring" to "M12 2C10.9 2 10 2.9 10 4V4.3C7.7 5.1 6 7.3 6 10V16L4 18V19H20V18L18 16V10C18 7.3 16.3 5.1 14 4.3V4C14 2.9 13.1 2 12 2ZM10 20C10 21.1 10.9 22 12 22C13.1 22 14 21.1 14 20H10ZM1.76 7.26C2.41 4.85 3.88 2.75 5.92 1.32L6.84 2.63C5.1 3.84 3.85 5.63 3.31 7.67ZM18.08 1.32C20.12 2.75 21.59 4.85 22.24 7.26L20.69 7.67C20.15 5.63 18.9 3.84 17.16 2.63Z",
        "alarm" to "M20.5 13C20.5 17.69 16.69 21.5 12 21.5C7.31 21.5 3.5 17.69 3.5 13C3.5 8.31 7.31 4.5 12 4.5C16.69 4.5 20.5 8.31 20.5 13ZM18.8 13C18.8 16.76 15.76 19.8 12 19.8C8.24 19.8 5.2 16.76 5.2 13C5.2 9.24 8.24 6.2 12 6.2C15.76 6.2 18.8 9.24 18.8 13ZM11.2 8.5L12.8 8.5L12.8 13.8L11.2 13.8ZM11.56 14.17L14.56 16.17L15.44 14.83L12.44 12.83ZM3.22 6.65L6.82 3.25L5.58 1.95L1.98 5.35ZM22.02 5.35L18.42 1.95L17.18 3.25L20.78 6.65Z",
        "timer-outline" to "M20.5 13.5C20.5 18.19 16.69 22 12 22C7.31 22 3.5 18.19 3.5 13.5C3.5 8.81 7.31 5 12 5C16.69 5 20.5 8.81 20.5 13.5ZM18.8 13.5C18.8 17.26 15.76 20.3 12 20.3C8.24 20.3 5.2 17.26 5.2 13.5C5.2 9.74 8.24 6.7 12 6.7C15.76 6.7 18.8 9.74 18.8 13.5ZM9.5 1.5L14.5 1.5L14.5 3.3L9.5 3.3ZM11.2 3.3L12.8 3.3L12.8 5L11.2 5ZM11.2 9L12.8 9L12.8 14.3L11.2 14.3ZM18.77 6.77L20.37 5.17L19.23 4.03L17.63 5.63Z",
        "air-humidifier" to "M8 10H16Q18 10 18 12V20Q18 22 16 22H8Q6 22 6 20V12Q6 10 8 10ZM14.4 16C14.4 17.33 13.33 18.4 12 18.4C10.67 18.4 9.6 17.33 9.6 16C9.6 14.67 10.67 13.6 12 13.6C13.33 13.6 14.4 14.67 14.4 16ZM12 1.5C11 3.25 9.5 4.65 9.5 6.26C9.5 7.8 10.6 8.5 12 8.5C13.4 8.5 14.5 7.8 14.5 6.26C14.5 4.65 13 3.25 12 1.5Z",
        "fan-off" to "M12 11C11.4 11 11 11.4 11 12C11 12.6 11.4 13 12 13C12.6 13 13 12.6 13 12C13 11.4 12.6 11 12 11ZM12 3C9.5 3 8.5 5.2 9.5 7.5L10.8 10.2C11.2 10 11.6 9.9 12 9.9L14.5 6C15.5 4.3 14.2 3 12 3ZM21 12C21 9.5 18.8 8.5 16.5 9.5L13.8 10.8C14 11.2 14.1 11.6 14.1 12L18 14.5C19.7 15.5 21 14.2 21 12ZM12 21C14.5 21 15.5 18.8 14.5 16.5L13.2 13.8C12.8 14 12.4 14.1 12 14.1L9.5 18C8.5 19.7 9.8 21 12 21ZM3 12C3 14.5 5.2 15.5 7.5 14.5L10.2 13.2C10 12.8 9.9 12.4 9.9 12L6 9.5C4.3 8.5 3 9.8 3 12ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "thermometer-lines" to "M11.5 13V5C11.5 3.3 10.2 2 8.5 2C6.8 2 5.5 3.3 5.5 5V13C4.3 14 3.5 15.4 3.5 17C3.5 19.8 5.7 22 8.5 22C11.3 22 13.5 19.8 13.5 17C13.5 15.4 12.7 14 11.5 13ZM7.5 5C7.5 4.4 7.9 4 8.5 4C9.1 4 9.5 4.4 9.5 5V11H7.5V5ZM15 5L21 5L21 6.6L15 6.6ZM15 9L19 9L19 10.6L15 10.6ZM15 13L21 13L21 14.6L15 14.6Z",
        "water-alert" to "M8.5 2.5C6 5.6 1.5 10.2 1.5 15C1.5 18.9 4.6 22 8.5 22C12.4 22 15.5 18.9 15.5 15C15.5 10.2 11 5.6 8.5 2.5ZM19 7L21 7L21 15L19 15ZM19 16L21 16L21 18L19 18Z",
        "water-off" to "M12 2.5C9.5 5.6 5 10.2 5 15C5 18.9 8.1 22 12 22C15.9 22 19 18.9 19 15C19 10.2 14.5 5.6 12 2.5ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "smoke-detector" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.3 12C20.3 16.58 16.58 20.3 12 20.3C7.42 20.3 3.7 16.58 3.7 12C3.7 7.42 7.42 3.7 12 3.7C16.58 3.7 20.3 7.42 20.3 12ZM16.5 12C16.5 14.49 14.49 16.5 12 16.5C9.51 16.5 7.5 14.49 7.5 12C7.5 9.51 9.51 7.5 12 7.5C14.49 7.5 16.5 9.51 16.5 12ZM13.6 12C13.6 12.88 12.88 13.6 12 13.6C11.12 13.6 10.4 12.88 10.4 12C10.4 11.12 11.12 10.4 12 10.4C12.88 10.4 13.6 11.12 13.6 12ZM17.8 12.6L19.2 12.6L19.2 11.4L17.8 11.4ZM14.38 17.32L15.08 18.54L16.12 17.94L15.42 16.72ZM8.58 16.72L7.88 17.94L8.92 18.54L9.62 17.32ZM6.2 11.4L4.8 11.4L4.8 12.6L6.2 12.6ZM9.62 6.68L8.92 5.46L7.88 6.06L8.58 7.28ZM15.42 7.28L16.12 6.06L15.08 5.46L14.38 6.68Z",
        "fire-alert" to "M9.5 3C9.5 3 11.3 5.7 11.3 7.95C12.47 7.32 13.46 6.24 14 4.8C16.16 7.05 17.15 9.3 17.15 12.45C17.15 16.68 13.73 20.1 9.5 20.1C5.27 20.1 1.85 16.68 1.85 12.45C1.85 9.75 3.38 7.32 5.36 5.7C5.36 7.23 6.08 8.58 7.25 9.3C7.34 6.96 8.15 4.8 9.5 3ZM9.5 18.3C11.03 18.3 12.2 17.13 12.2 15.6C12.2 14.16 11.12 12.9 9.5 11.1C7.88 12.9 6.8 14.16 6.8 15.6C6.8 17.13 7.97 18.3 9.5 18.3ZM19.5 7L21.5 7L21.5 15L19.5 15ZM19.5 16L21.5 16L21.5 18L19.5 18Z",
        "weather-sunrise" to "M7.2 16.5C7.2 13.85 9.35 11.7 12 11.7C14.65 11.7 16.8 13.85 16.8 16.5ZM6.03 14.07L3.51 13.4L3.1 14.94L5.61 15.62ZM8.5 11.08L6.83 9.09L5.6 10.12L7.27 12.11ZM12.8 10.1L12.8 7.5L11.2 7.5L11.2 10.1ZM16.73 12.11L18.4 10.12L17.17 9.09L15.5 11.08ZM18.39 15.62L20.9 14.94L20.49 13.4L17.97 14.07ZM2 17.5L22 17.5L22 19.3L2 19.3ZM8.5 23L12 19.8L15.5 23Z",
        "weather-sunset" to "M7.2 16.5C7.2 13.85 9.35 11.7 12 11.7C14.65 11.7 16.8 13.85 16.8 16.5ZM6.03 14.07L3.51 13.4L3.1 14.94L5.61 15.62ZM8.5 11.08L6.83 9.09L5.6 10.12L7.27 12.11ZM12.8 10.1L12.8 7.5L11.2 7.5L11.2 10.1ZM16.73 12.11L18.4 10.12L17.17 9.09L15.5 11.08ZM18.39 15.62L20.9 14.94L20.49 13.4L17.97 14.07ZM2 17.5L22 17.5L22 19.3L2 19.3ZM8.5 20L15.5 20L12 23.2Z",
        "weather-lightning" to "M6 15.5H19C21.2 15.5 23 13.7 23 11.5C23 9.4 21.4 7.7 19.3 7.5C18.6 4.1 15.6 1.5 12 1.5C9.1 1.5 6.6 3.1 5.4 5.5C2.9 5.8 1 7.9 1 10.5C1 13.3 3.2 15.5 6 15.5ZM11.5 12L8.5 18L11.2 18L10.2 23L15.5 16L12.7 16L14 12Z",
        "weather-snowy" to "M6 15H19C21.2 15 23 13.2 23 11C23 8.9 21.4 7.2 19.3 7C18.6 3.6 15.6 1 12 1C9.1 1 6.6 2.6 5.4 5C2.9 5.3 1 7.4 1 10C1 12.8 3.2 15 6 15ZM9.2 18C9.2 18.66 8.66 19.2 8 19.2C7.34 19.2 6.8 18.66 6.8 18C6.8 17.34 7.34 16.8 8 16.8C8.66 16.8 9.2 17.34 9.2 18ZM17.2 18C17.2 18.66 16.66 19.2 16 19.2C15.34 19.2 14.8 18.66 14.8 18C14.8 17.34 15.34 16.8 16 16.8C16.66 16.8 17.2 17.34 17.2 18ZM13.2 20.3C13.2 20.96 12.66 21.5 12 21.5C11.34 21.5 10.8 20.96 10.8 20.3C10.8 19.64 11.34 19.1 12 19.1C12.66 19.1 13.2 19.64 13.2 20.3ZM9.1 22.4C9.1 23.01 8.61 23.5 8 23.5C7.39 23.5 6.9 23.01 6.9 22.4C6.9 21.79 7.39 21.3 8 21.3C8.61 21.3 9.1 21.79 9.1 22.4ZM17.1 22.4C17.1 23.01 16.61 23.5 16 23.5C15.39 23.5 14.9 23.01 14.9 22.4C14.9 21.79 15.39 21.3 16 21.3C16.61 21.3 17.1 21.79 17.1 22.4Z",
        "weather-pouring" to "M6 15H19C21.2 15 23 13.2 23 11C23 8.9 21.4 7.2 19.3 7C18.6 3.6 15.6 1 12 1C9.1 1 6.6 2.6 5.4 5C2.9 5.3 1 7.4 1 10C1 12.8 3.2 15 6 15ZM6.73 16.77L5.23 21.77L6.77 22.23L8.27 17.23ZM10.73 16.77L9.23 21.77L10.77 22.23L12.27 17.23ZM14.73 16.77L13.23 21.77L14.77 22.23L16.27 17.23ZM18.73 16.77L17.23 21.77L18.77 22.23L20.27 17.23Z",
        "battery-alert" to "M6 2H12V4H14V22H4V4H6V2ZM6 6V20H12V6H6ZM18.5 8L20.5 8L20.5 15L18.5 15ZM18.5 16L20.5 16L20.5 18L18.5 18Z",
        "battery-outline" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9Z",
        "battery-20" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9ZM10 16.6L14 16.6L14 19L10 19Z",
        "battery-50" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9ZM10 13L14 13L14 19L10 19Z",
        "battery-80" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9ZM10 9.4L14 9.4L14 19L10 19Z",
        "wifi-off" to "M2 8.5Q12 -0.5 22 8.5L20.3 10.2Q12 3 3.7 10.2ZM5 12.2Q12 6.3 19 12.2L17.4 13.8Q12 9.3 6.6 13.8ZM8.4 16Q12 13.2 15.6 16L12 20ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "bluetooth" to "M11 2L12 2L17.7 7.7L13.4 12L17.7 16.3L12 22L11 22L11 14.4L6.4 19L5 17.6L10.6 12L5 6.4L6.4 5L11 9.6ZM13 5.8L14.9 7.7L13 9.6ZM13 14.4L14.9 16.3L13 18.2Z",
        "bluetooth-off" to "M11 2L12 2L17.7 7.7L13.4 12L17.7 16.3L12 22L11 22L11 14.4L6.4 19L5 17.6L10.6 12L5 6.4L6.4 5L11 9.6ZM13 5.8L14.9 7.7L13 9.6ZM13 14.4L14.9 16.3L13 18.2ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "motion-sensor-off" to "M13.5 5.5C14.6 5.5 15.5 4.6 15.5 3.5C15.5 2.4 14.6 1.5 13.5 1.5C12.4 1.5 11.5 2.4 11.5 3.5C11.5 4.6 12.4 5.5 13.5 5.5ZM9.8 8.9L7 23H9.1L10.9 15L13 17V23H15V15.5L12.9 13.5L13.5 10.5C14.8 12 16.8 13 19 13V11C17.1 11 15.5 10 14.7 8.6L13.7 7C13.3 6.4 12.7 6 12 6C11.7 6 11.5 6.1 11.2 6.2L6 8.3V13H8V9.6L9.8 8.9ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "home-battery" to "M12 3L2 12L5 12L5 21L19 21L19 12L22 12ZM10 11.5L14 11.5L14 18.5L10 18.5ZM11 10.5L13 10.5L13 11.5L11 11.5Z",
        "lightning-bolt" to "M11 15L6 15L13 1L13 9L18 9L11 23Z",
        "leaf" to "M4.5 19.5C4.5 10 10.5 4 21 3C20 13.5 14 19.5 4.5 19.5ZM6.96 17.96L15.96 8.96L15.04 8.04L6.04 17.04ZM2.87 22.27L4.97 20.17L3.83 19.03L1.73 21.13Z",
        "led-strip-variant" to "M3.5 8.5H20.5Q22 8.5 22 10V14Q22 15.5 20.5 15.5H3.5Q2 15.5 2 14V10Q2 8.5 3.5 8.5ZM4 10.75L6.5 10.75L6.5 13.25L4 13.25ZM8.5 10.75L11 10.75L11 13.25L8.5 13.25ZM13 10.75L15.5 10.75L15.5 13.25L13 13.25ZM17.5 10.75L20 10.75L20 13.25L17.5 13.25Z",
        "lightbulb-outline" to "M9 21H15V19H9V21ZM12 2C8.1 2 5 5.1 5 9C5 11.4 6.2 13.5 8 14.7V17H16V14.7C17.8 13.5 19 11.4 19 9C19 5.1 15.9 2 12 2ZM12 4C9.2 4 7 6.2 7 9C7 10.7 7.9 12.3 9.3 13.2L10 13.7V15H14V13.7L14.7 13.2C16.1 12.3 17 10.7 17 9C17 6.2 14.8 4 12 4Z",
        "lightbulb-off" to "M9 21H15V19H9V21ZM12 2C8.1 2 5 5.1 5 9C5 11.4 6.2 13.5 8 14.7V17H16V14.7C17.8 13.5 19 11.4 19 9C19 5.1 15.9 2 12 2ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "power-plug" to "M8 2L10 2L10 7L8 7ZM14 2L16 2L16 7L14 7ZM6 7H18V12C18 14.8 16 17 13 17.4V22H11V17.4C8 17 6 14.8 6 12Z",
        "power-plug-off" to "M8 2L10 2L10 7L8 7ZM14 2L16 2L16 7L14 7ZM6 7H18V12C18 14.8 16 17 13 17.4V22H11V17.4C8 17 6 14.8 6 12ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "outlet" to "M6.5 3H17.5Q21 3 21 6.5V17.5Q21 21 17.5 21H6.5Q3 21 3 17.5V6.5Q3 3 6.5 3ZM8 7L9.8 7L9.8 12L8 12ZM14.2 7L16 7L16 12L14.2 12ZM13.6 16C13.6 16.88 12.88 17.6 12 17.6C11.12 17.6 10.4 16.88 10.4 16C10.4 15.12 11.12 14.4 12 14.4C12.88 14.4 13.6 15.12 13.6 16Z",
        "speaker-wireless" to "M5.6 5.4H13.6C14.48 5.4 15.2 6.12 15.2 7V18.2C15.2 19.08 14.48 19.8 13.6 19.8H5.6C4.72 19.8 4 19.08 4 18.2V7C4 6.12 4.72 5.4 5.6 5.4ZM9.6 11C7.84 11 6.4 12.44 6.4 14.2C6.4 15.96 7.84 17.4 9.6 17.4C11.36 17.4 12.8 15.96 12.8 14.2C12.8 12.44 11.36 11 9.6 11ZM14.59 6.47C17.27 9.67 17.27 14.33 14.59 17.53L13.44 16.56C15.65 13.92 15.65 10.08 13.44 7.44ZM16.2 3.8C20.73 8.33 20.73 15.67 16.2 20.2L15.14 19.14C19.09 15.2 19.09 8.8 15.14 4.86Z",
        "cast" to "M1 9.5V5Q1 3 3 3H21Q23 3 23 5V19Q23 21 21 21H14V19H21V5H3V9.5ZM1 21V17.5C2.93 17.5 4.5 19.07 4.5 21ZM1 13.8C4.98 13.8 8.2 17.02 8.2 21L6.5 21C6.5 17.96 4.04 15.5 1 15.5ZM1 10C7.08 10 12 14.92 12 21L10.3 21C10.3 15.86 6.14 11.7 1 11.7Z",
        "music-note" to "M12 3H14C14 5.5 16.5 6.5 18.5 7.5L17.6 9.3C16.2 8.6 15 8 14 7.2V17C14 19.2 12.2 21 10 21C7.8 21 6 19.2 6 17C6 14.8 7.8 13 10 13C10.7 13 11.4 13.2 12 13.6Z",
        "volume-mute" to "M3 9V15H7L12 20V4L7 9H3ZM21 9.2L20.3 8.5L17.5 11.3L14.7 8.5L14 9.2L16.8 12L14 14.8L14.7 15.5L17.5 12.7L20.3 15.5L21 14.8L18.2 12Z",
        "volume-low" to "M3 9V15H7L12 20V4L7 9H3ZM14.97 9.03C16.61 10.67 16.61 13.33 14.97 14.97L13.84 13.84C14.85 12.82 14.85 11.18 13.84 10.16Z",
        "volume-medium" to "M3 9V15H7L12 20V4L7 9H3ZM14.97 9.03C16.61 10.67 16.61 13.33 14.97 14.97L13.84 13.84C14.85 12.82 14.85 11.18 13.84 10.16ZM16.76 6.33C18.43 7.74 19.4 9.81 19.4 12C19.4 14.19 18.43 16.26 16.76 17.67L15.73 16.44C17.04 15.34 17.8 13.71 17.8 12C17.8 10.29 17.04 8.66 15.73 7.56Z",
        "play-pause" to "M3 5L11 12L3 19ZM13 5L16 5L16 19L13 19ZM18 5L21 5L21 19L18 19Z",
        "play-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM10 7.5L16.5 12L10 16.5Z",
        "pause-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM8.8 7.5L11 7.5L11 16.5L8.8 16.5ZM13 7.5L15.2 7.5L15.2 16.5L13 16.5Z",
        "stop-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM8.5 8.5L15.5 8.5L15.5 15.5L8.5 15.5Z",
        "washing-machine" to "M6 2H18Q20 2 20 4V20Q20 22 18 22H6Q4 22 4 20V4Q4 2 6 2ZM17 13.5C17 16.26 14.76 18.5 12 18.5C9.24 18.5 7 16.26 7 13.5C7 10.74 9.24 8.5 12 8.5C14.76 8.5 17 10.74 17 13.5ZM15.4 13.5C15.4 15.38 13.88 16.9 12 16.9C10.12 16.9 8.6 15.38 8.6 13.5C8.6 11.62 10.12 10.1 12 10.1C13.88 10.1 15.4 11.62 15.4 13.5ZM8.5 5C8.5 5.55 8.05 6 7.5 6C6.95 6 6.5 5.55 6.5 5C6.5 4.45 6.95 4 7.5 4C8.05 4 8.5 4.45 8.5 5ZM11.5 5C11.5 5.55 11.05 6 10.5 6C9.95 6 9.5 5.55 9.5 5C9.5 4.45 9.95 4 10.5 4C11.05 4 11.5 4.45 11.5 5ZM14 4.2L18 4.2L18 5.8L14 5.8Z",
        "dishwasher" to "M6 2H18Q20 2 20 4V20Q20 22 18 22H6Q4 22 4 20V4Q4 2 6 2ZM6 6.5L18 6.5L18 7.7L6 7.7ZM8.9 4.3C8.9 4.8 8.5 5.2 8 5.2C7.5 5.2 7.1 4.8 7.1 4.3C7.1 3.8 7.5 3.4 8 3.4C8.5 3.4 8.9 3.8 8.9 4.3ZM11.9 4.3C11.9 4.8 11.5 5.2 11 5.2C10.5 5.2 10.1 4.8 10.1 4.3C10.1 3.8 10.5 3.4 11 3.4C11.5 3.4 11.9 3.8 11.9 4.3ZM9 9.2L15 9.2L15 10.6L9 10.6ZM6 12L18 12L18 20L6 20Z",
        "fridge" to "M7 2H17Q19 2 19 4V20Q19 22 17 22H7Q5 22 5 20V4Q5 2 7 2ZM5 9L19 9L19 10.4L5 10.4ZM7 4.5L8.5 4.5L8.5 7.5L7 7.5ZM7 12L8.5 12L8.5 16.5L7 16.5Z",
        "coffee-maker" to "M5 2L19 2L19 6L5 6ZM15 6L19 6L19 20L15 20ZM5 20L19 20L19 22L5 22ZM9 6L11 6L11 8.5L9 8.5ZM6.5 12H13.5V15.5C13.5 17.4 12 19 10 19C8 19 6.5 17.4 6.5 15.5Z",
        "garage-open" to "M12 2L1 8.5V22H23V8.5L12 2ZM4 10H20V22H4ZM5.5 12H18.5V13.5H5.5Z",
        "gate" to "M2 4L4.5 4L4.5 21L2 21ZM19.5 4L22 4L22 21L19.5 21ZM4.5 7L19.5 7L19.5 8.6L4.5 8.6ZM4.5 15L19.5 15L19.5 16.6L4.5 16.6ZM8 8.6L9.6 8.6L9.6 15L8 15ZM11.2 8.6L12.8 8.6L12.8 15L11.2 15ZM14.4 8.6L16 8.6L16 15L14.4 15Z",
        "gate-open" to "M2 4L4.5 4L4.5 21L2 21ZM19.5 4L22 4L22 21L19.5 21ZM4.5 6.5L9 9L9 18L4.5 16.5ZM6 9L7.5 9.8L7.5 15.8L6 15.2ZM19.5 6.5L15 9L15 18L19.5 16.5ZM18 9L16.5 9.8L16.5 15.8L18 15.2Z",
        "door" to "M6 3L18 3L18 21L6 21ZM16.2 12C16.2 12.55 15.75 13 15.2 13C14.65 13 14.2 12.55 14.2 12C14.2 11.45 14.65 11 15.2 11C15.75 11 16.2 11.45 16.2 12ZM4 21L20 21L20 22.5L4 22.5Z",
        "window-shutter" to "M3 3L21 3L21 6L3 6ZM4 7.5L20 7.5L20 9.7L4 9.7ZM4 11L20 11L20 13.2L4 13.2ZM4 14.5L20 14.5L20 16.7L4 16.7ZM4 18L20 18L20 20.2L4 20.2Z",
        "window-shutter-open" to "M3 3L21 3L21 6L3 6ZM4 7L20 7L20 9.2L4 9.2ZM4 10.5L20 10.5L20 21L4 21ZM6 12.5L18 12.5L18 19L6 19Z",
        "blinds-open" to "M3 3L21 3L21 5L3 5ZM4 6.5L20 6.5L20 8.5L4 8.5ZM11.2 8.5L12.8 8.5L12.8 19.5L11.2 19.5ZM13.3 20.8C13.3 21.52 12.72 22.1 12 22.1C11.28 22.1 10.7 21.52 10.7 20.8C10.7 20.08 11.28 19.5 12 19.5C12.72 19.5 13.3 20.08 13.3 20.8Z",
        "car-electric" to "M6.8 9.1H17.2C17.76 9.1 18.24 9.42 18.48 9.9L20 13.9V21.1C20 21.58 19.68 21.9 19.2 21.9H18.4C17.92 21.9 17.6 21.58 17.6 21.1V20.3H6.4V21.1C6.4 21.58 6.08 21.9 5.6 21.9H4.8C4.32 21.9 4 21.58 4 21.1V13.9L5.52 9.9C5.76 9.42 6.24 9.1 6.8 9.1ZM6.96 10.7H17.04L18.16 13.5H5.84L6.96 10.7ZM8.8 17.1C8.8 17.76 8.26 18.3 7.6 18.3C6.94 18.3 6.4 17.76 6.4 17.1C6.4 16.44 6.94 15.9 7.6 15.9C8.26 15.9 8.8 16.44 8.8 17.1ZM17.6 17.1C17.6 17.76 17.06 18.3 16.4 18.3C15.74 18.3 15.2 17.76 15.2 17.1C15.2 16.44 15.74 15.9 16.4 15.9C17.06 15.9 17.6 16.44 17.6 17.1ZM11.6 0.8L8.6 5.4L11 5.4L10 8.2L14.2 3.4L11.8 3.4L13 0.8Z",
        "doorbell-video" to "M8.5 2H15.5Q18 2 18 4.5V19.5Q18 22 15.5 22H8.5Q6 22 6 19.5V4.5Q6 2 8.5 2ZM14.2 7C14.2 8.22 13.22 9.2 12 9.2C10.78 9.2 9.8 8.22 9.8 7C9.8 5.78 10.78 4.8 12 4.8C13.22 4.8 14.2 5.78 14.2 7ZM15 15.5C15 17.16 13.66 18.5 12 18.5C10.34 18.5 9 17.16 9 15.5C9 13.84 10.34 12.5 12 12.5C13.66 12.5 15 13.84 15 15.5ZM13.6 15.5C13.6 16.38 12.88 17.1 12 17.1C11.12 17.1 10.4 16.38 10.4 15.5C10.4 14.62 11.12 13.9 12 13.9C12.88 13.9 13.6 14.62 13.6 15.5Z",
        "shield-check" to "M12 1L3 5V11C3 16.5 6.8 21.7 12 23C17.2 21.7 21 16.5 21 11V5L12 1ZM10.6 16.6L6.6 12.6L8 11.2L10.6 13.8L16.4 8L17.8 9.4L10.6 16.6Z",
        "shield-alert" to "M12 1L3 5V11C3 16.5 6.8 21.7 12 23C17.2 21.7 21 16.5 21 11V5L12 1ZM11 6.5L13 6.5L13 14.5L11 14.5ZM11 16L13 16L13 18L11 18Z",
        "arrow-right" to "M4 11V13H16L10.5 18.5L11.9 19.9L19.8 12L11.9 4.1L10.5 5.5L16 11H4Z",
        "chevron-left" to "M15.4 16.6L10.8 12L15.4 7.4L14 6L8 12L14 18L15.4 16.6Z",
        "dots-horizontal" to "M8 12C8 13.1 7.1 14 6 14C4.9 14 4 13.1 4 12C4 10.9 4.9 10 6 10C7.1 10 8 10.9 8 12ZM14 12C14 13.1 13.1 14 12 14C10.9 14 10 13.1 10 12C10 10.9 10.9 10 12 10C13.1 10 14 10.9 14 12ZM20 12C20 13.1 19.1 14 18 14C16.9 14 16 13.1 16 12C16 10.9 16.9 10 18 10C19.1 10 20 10.9 20 12Z",
        "cog-outline" to "M10.16 4.63L10.44 2.12L13.56 2.12L13.84 4.63L15.91 5.49L17.88 3.91L20.09 6.12L18.51 8.09L19.37 10.16L21.88 10.44L21.88 13.56L19.37 13.84L18.51 15.91L20.09 17.88L17.88 20.09L15.91 18.51L13.84 19.37L13.56 21.88L10.44 21.88L10.16 19.37L8.09 18.51L6.12 20.09L3.91 17.88L5.49 15.91L4.63 13.84L2.12 13.56L2.12 10.44L4.63 10.16L5.49 8.09L3.91 6.12L6.12 3.91L8.09 5.49ZM10.53 6.1L10.75 4.1L13.25 4.1L13.47 6.1L15.13 6.79L16.7 5.53L18.47 7.3L17.21 8.87L17.9 10.53L19.9 10.75L19.9 13.25L17.9 13.47L17.21 15.13L18.47 16.7L16.7 18.47L15.13 17.21L13.47 17.9L13.25 19.9L10.75 19.9L10.53 17.9L8.87 17.21L7.3 18.47L5.53 16.7L6.79 15.13L6.1 13.47L4.1 13.25L4.1 10.75L6.1 10.53L6.79 8.87L5.53 7.3L7.3 5.53L8.87 6.79ZM16 12C16 14.21 14.21 16 12 16C9.79 16 8 14.21 8 12C8 9.79 9.79 8 12 8C14.21 8 16 9.79 16 12ZM14.2 12C14.2 13.22 13.22 14.2 12 14.2C10.78 14.2 9.8 13.22 9.8 12C9.8 10.78 10.78 9.8 12 9.8C13.22 9.8 14.2 10.78 14.2 12Z",
        "sync" to "M4.48 9.26C5.46 6.58 7.8 4.62 10.61 4.12C13.43 3.63 16.29 4.67 18.13 6.86L16.83 7.95C15.38 6.23 13.12 5.4 10.91 5.8C8.69 6.19 6.85 7.73 6.08 9.85ZM19.47 5.73L19.65 9.99L15.49 9.08ZM19.52 14.74C18.54 17.42 16.2 19.38 13.39 19.88C10.57 20.37 7.71 19.33 5.87 17.14L7.17 16.05C8.62 17.77 10.88 18.6 13.09 18.2C15.31 17.81 17.15 16.27 17.92 14.15ZM4.53 18.27L4.35 14.01L8.51 14.92Z",
        "key" to "M11.88 13.1C11.32 15.58 8.98 17.25 6.45 16.97C3.92 16.69 2 14.55 2 12C2 9.45 3.92 7.31 6.45 7.03C8.98 6.75 11.32 8.42 11.88 10.9L22 10.9L22 13.1L20.5 13.1L20.5 16L18.5 16L18.5 13.1L16.5 13.1L16.5 15L14.5 15L14.5 13.1ZM9 12C9 13.1 8.1 14 7 14C5.9 14 5 13.1 5 12C5 10.9 5.9 10 7 10C8.1 10 9 10.9 9 12Z",
        "magnify" to "M16 9.5C16 13.09 13.09 16 9.5 16C5.91 16 3 13.09 3 9.5C3 5.91 5.91 3 9.5 3C13.09 3 16 5.91 16 9.5ZM14.3 9.5C14.3 12.15 12.15 14.3 9.5 14.3C6.85 14.3 4.7 12.15 4.7 9.5C4.7 6.85 6.85 4.7 9.5 4.7C12.15 4.7 14.3 6.85 14.3 9.5ZM13.52 15.08L20.02 21.58L21.58 20.02L15.08 13.52Z",
        "map-marker" to "M12 2C8.1 2 5 5.1 5 9C5 14.2 12 22 12 22C12 22 19 14.2 19 9C19 5.1 15.9 2 12 2ZM14.5 9C14.5 10.38 13.38 11.5 12 11.5C10.62 11.5 9.5 10.38 9.5 9C9.5 7.62 10.62 6.5 12 6.5C13.38 6.5 14.5 7.62 14.5 9Z",
        "star" to "M12 2.2L14.53 9.12L21.89 9.39L16.09 13.93L18.11 21.01L12 16.9L5.89 21.01L7.91 13.93L2.11 9.39L9.47 9.12Z",
        "star-outline" to "M12 2.2L14.53 9.12L21.89 9.39L16.09 13.93L18.11 21.01L12 16.9L5.89 21.01L7.91 13.93L2.11 9.39L9.47 9.12ZM12 6.3L13.62 10.68L18.28 10.86L14.62 13.75L15.88 18.24L12 15.65L8.12 18.24L9.38 13.75L5.72 10.86L10.38 10.68Z",
        "heart-outline" to "M12 21L10.5 19.7C5.4 15.1 2 12 2 8.5C2 5.4 4.4 3 7.5 3C9.2 3 10.9 3.8 12 5.1C13.1 3.8 14.8 3 16.5 3C19.6 3 22 5.4 22 8.5C22 12 18.6 15.1 13.5 19.7L12 21ZM12 18.74L10.92 17.8C7.25 14.49 4.8 12.26 4.8 9.74C4.8 7.51 6.53 5.78 8.76 5.78C9.98 5.78 11.21 6.36 12 7.29C12.79 6.36 14.02 5.78 15.24 5.78C17.47 5.78 19.2 7.51 19.2 9.74C19.2 12.26 16.75 14.49 13.08 17.8L12 18.74Z",
        "account-circle" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM15.2 9C15.2 10.77 13.77 12.2 12 12.2C10.23 12.2 8.8 10.77 8.8 9C8.8 7.23 10.23 5.8 12 5.8C13.77 5.8 15.2 7.23 15.2 9ZM6.2 17.6C7.5 15.6 9.6 14.5 12 14.5C14.4 14.5 16.5 15.6 17.8 17.6C16.3 19.1 14.3 20 12 20C9.7 20 7.7 19.1 6.2 17.6Z",
        "account-group" to "M15.2 7.5C15.2 9.27 13.77 10.7 12 10.7C10.23 10.7 8.8 9.27 8.8 7.5C8.8 5.73 10.23 4.3 12 4.3C13.77 4.3 15.2 5.73 15.2 7.5ZM6.2 19C6.2 15.8 9 14 12 14C15 14 17.8 15.8 17.8 19ZM7.3 9.5C7.3 10.77 6.27 11.8 5 11.8C3.73 11.8 2.7 10.77 2.7 9.5C2.7 8.23 3.73 7.2 5 7.2C6.27 7.2 7.3 8.23 7.3 9.5ZM0.8 18.5C0.8 16.2 2.6 14.9 5 14.9C5.4 14.9 5.7 14.9 6 15C5.2 16 4.8 17.2 4.8 18.5ZM21.3 9.5C21.3 10.77 20.27 11.8 19 11.8C17.73 11.8 16.7 10.77 16.7 9.5C16.7 8.23 17.73 7.2 19 7.2C20.27 7.2 21.3 8.23 21.3 9.5ZM23.2 18.5C23.2 16.2 21.4 14.9 19 14.9C18.6 14.9 18.3 14.9 18 15C18.8 16 19.2 17.2 19.2 18.5Z",
        "home-outline" to "M12 3L2 12L5 12L5 20L19 20L19 12L22 12ZM12 5.7L17 10.2L17 18L7 18L7 10.2ZM10 12.5L14 12.5L14 18L10 18Z",
        "robot-vacuum-variant" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM4.21 8.37C5.62 5.34 8.66 3.4 12 3.4C15.34 3.4 18.38 5.34 19.79 8.37L18.53 8.96C17.34 6.42 14.8 4.8 12 4.8C9.2 4.8 6.66 6.42 5.47 8.96ZM14.2 13C14.2 14.22 13.22 15.2 12 15.2C10.78 15.2 9.8 14.22 9.8 13C9.8 11.78 10.78 10.8 12 10.8C13.22 10.8 14.2 11.78 14.2 13Z",
        "brightness-7" to "M19.6 12.9L22.2 12.9L22.2 11.1L19.6 11.1ZM16.74 18.01L18.58 19.85L19.85 18.58L18.01 16.74ZM11.1 19.6L11.1 22.2L12.9 22.2L12.9 19.6ZM5.99 16.74L4.15 18.58L5.42 19.85L7.26 18.01ZM4.4 11.1L1.8 11.1L1.8 12.9L4.4 12.9ZM7.26 5.99L5.42 4.15L4.15 5.42L5.99 7.26ZM12.9 4.4L12.9 1.8L11.1 1.8L11.1 4.4ZM18.01 7.26L19.85 5.42L18.58 4.15L16.74 5.99ZM18 12C18 15.31 15.31 18 12 18C8.69 18 6 15.31 6 12C6 8.69 8.69 6 12 6C15.31 6 18 8.69 18 12ZM16.4 12C16.4 14.43 14.43 16.4 12 16.4C9.57 16.4 7.6 14.43 7.6 12C7.6 9.57 9.57 7.6 12 7.6C14.43 7.6 16.4 9.57 16.4 12ZM15 12C15 13.66 13.66 15 12 15C10.34 15 9 13.66 9 12C9 10.34 10.34 9 12 9C13.66 9 15 10.34 15 12Z",
        "theme-light-dark" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.3 12C20.3 16.58 16.58 20.3 12 20.3C7.42 20.3 3.7 16.58 3.7 12C3.7 7.42 7.42 3.7 12 3.7C16.58 3.7 20.3 7.42 20.3 12ZM12 3.7C16.58 3.7 20.3 7.42 20.3 12C20.3 16.58 16.58 20.3 12 20.3Z",
        "clock-outline" to "M22 12C22 17.52 17.52 22 12 22C6.48 22 2 17.52 2 12C2 6.48 6.48 2 12 2C17.52 2 22 6.48 22 12ZM20.2 12C20.2 16.53 16.53 20.2 12 20.2C7.47 20.2 3.8 16.53 3.8 12C3.8 7.47 7.47 3.8 12 3.8C16.53 3.8 20.2 7.47 20.2 12ZM11 6.5H12.5V11.6L16.6 14L15.9 15.3L11 12.4V6.5Z",
        "calendar-outline" to "M5 3H7V1.5H9V3H15V1.5H17V3H19C20.1 3 21 3.9 21 5V20C21 21.1 20.1 22 19 22H5C3.9 22 3 21.1 3 20V5C3 3.9 3.9 3 5 3ZM5 9L19 9L19 20L5 20ZM7 11L11 11L11 15L7 15Z",
        "chart-line" to "M3 3H5V19H21V21H3ZM7.24 16.52L10.17 12.33L13.59 15.27L20.68 7.09L19.32 5.91L13.41 12.73L9.83 9.67L5.76 15.48Z",
        "chart-bar" to "M3 13L7 13L7 21L3 21ZM10 8L14 8L14 21L10 21ZM17 3L21 3L21 21L17 21Z",
        "table" to "M4.5 3H19.5Q21 3 21 4.5V19.5Q21 21 19.5 21H4.5Q3 21 3 19.5V4.5Q3 3 4.5 3ZM5 9L11 9L11 13.5L5 13.5ZM13 9L19 9L19 13.5L13 13.5ZM5 15.5L11 15.5L11 19L5 19ZM13 15.5L19 15.5L19 19L13 19Z",
        "pencil" to "M3 17.25L3 21L6.75 21L17.4 10.35L13.65 6.6ZM15.06 5.19L18.81 8.94L20.6 7.15L16.85 3.4Z",
        "delete" to "M5 4L19 4L19 6L5 6ZM9 2.5L15 2.5L15 4L9 4ZM6 7L18 7L17 21L7 21Z",
        "trash-can" to "M5 4L19 4L19 6L5 6ZM9 2.5L15 2.5L15 4L9 4ZM6 7L18 7L17 21L7 21ZM9.2 9.5L10.8 9.5L10.8 18.5L9.2 18.5ZM13.2 9.5L14.8 9.5L14.8 18.5L13.2 18.5Z",
        "fullscreen" to "M3 3L10 3L10 5L5 5L5 10L3 10ZM3 21L10 21L10 19L5 19L5 14L3 14ZM21 3L14 3L14 5L19 5L19 10L21 10ZM21 21L14 21L14 19L19 19L19 14L21 14Z",
        "fullscreen-exit" to "M8 3L10 3L10 10L3 10L3 8L8 8ZM8 21L10 21L10 14L3 14L3 16L8 16ZM16 3L14 3L14 10L21 10L21 8L16 8ZM16 21L14 21L14 14L21 14L21 16L16 16Z",
        "microphone-off" to "M12 2C10.3 2 9 3.3 9 5V11C9 12.7 10.3 14 12 14C13.7 14 15 12.7 15 11V5C15 3.3 13.7 2 12 2ZM5 10.5H6.8C6.8 13.6 9.1 15.8 12 15.8C14.9 15.8 17.2 13.6 17.2 10.5H19C19 14.2 16.2 17.3 12.9 17.8V21H11.1V17.8C7.8 17.3 5 14.2 5 10.5ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "video" to "M4 6H13Q15 6 15 8V16Q15 18 13 18H4Q2 18 2 16V8Q2 6 4 6ZM16 10L22 6L22 18L16 14Z",
        "video-off" to "M4 6H13Q15 6 15 8V16Q15 18 13 18H4Q2 18 2 16V8Q2 6 4 6ZM16 10L22 6L22 18L16 14ZM2.33 3.87L20.33 21.87L21.67 20.53L3.67 2.53Z",
        "camera" to "M4 7H7.5L9 5H15L16.5 7H20Q22 7 22 9V19Q22 21 20 21H4Q2 21 2 19V9Q2 7 4 7ZM16.2 14C16.2 16.32 14.32 18.2 12 18.2C9.68 18.2 7.8 16.32 7.8 14C7.8 11.68 9.68 9.8 12 9.8C14.32 9.8 16.2 11.68 16.2 14ZM14.6 14C14.6 15.44 13.44 16.6 12 16.6C10.56 16.6 9.4 15.44 9.4 14C9.4 12.56 10.56 11.4 12 11.4C13.44 11.4 14.6 12.56 14.6 14Z",
        "laptop" to "M5.5 4H18.5Q20 4 20 5.5V13.5Q20 15 18.5 15H5.5Q4 15 4 13.5V5.5Q4 4 5.5 4ZM6 6L18 6L18 13L6 13ZM1 16.5H23V18Q23 19.5 21.5 19.5H2.5Q1 19.5 1 18Z",
        "cellphone" to "M8.5 1H15.5Q18 1 18 3.5V20.5Q18 23 15.5 23H8.5Q6 23 6 20.5V3.5Q6 1 8.5 1ZM7.8 4L16.2 4L16.2 18.5L7.8 18.5ZM12.9 20.6C12.9 21.1 12.5 21.5 12 21.5C11.5 21.5 11.1 21.1 11.1 20.6C11.1 20.1 11.5 19.7 12 19.7C12.5 19.7 12.9 20.1 12.9 20.6Z",
        "router-wireless" to "M4.5 14H19.5Q21 14 21 15.5V19.5Q21 21 19.5 21H4.5Q3 21 3 19.5V15.5Q3 14 4.5 14ZM8 17.5C8 18.05 7.55 18.5 7 18.5C6.45 18.5 6 18.05 6 17.5C6 16.95 6.45 16.5 7 16.5C7.55 16.5 8 16.95 8 17.5ZM11.5 17.5C11.5 18.05 11.05 18.5 10.5 18.5C9.95 18.5 9.5 18.05 9.5 17.5C9.5 16.95 9.95 16.5 10.5 16.5C11.05 16.5 11.5 16.95 11.5 17.5ZM16 9.5L17.6 9.5L17.6 14L16 14ZM14.25 6.45C15.66 5.05 17.94 5.05 19.35 6.45L18.36 7.44C17.5 6.59 16.1 6.59 15.24 7.44ZM12.13 4.33C14.71 1.76 18.89 1.76 21.47 4.33L20.48 5.32C18.45 3.29 15.15 3.29 13.12 5.32Z",
        "server" to "M4 2.5H20Q21 2.5 21 3.5V6.5Q21 7.5 20 7.5H4Q3 7.5 3 6.5V3.5Q3 2.5 4 2.5ZM7.5 5C7.5 5.55 7.05 6 6.5 6C5.95 6 5.5 5.55 5.5 5C5.5 4.45 5.95 4 6.5 4C7.05 4 7.5 4.45 7.5 5ZM10 4.5L18 4.5L18 5.5L10 5.5ZM4 9.5H20Q21 9.5 21 10.5V13.5Q21 14.5 20 14.5H4Q3 14.5 3 13.5V10.5Q3 9.5 4 9.5ZM7.5 12C7.5 12.55 7.05 13 6.5 13C5.95 13 5.5 12.55 5.5 12C5.5 11.45 5.95 11 6.5 11C7.05 11 7.5 11.45 7.5 12ZM10 11.5L18 11.5L18 12.5L10 12.5ZM4 16.5H20Q21 16.5 21 17.5V20.5Q21 21.5 20 21.5H4Q3 21.5 3 20.5V17.5Q3 16.5 4 16.5ZM7.5 19C7.5 19.55 7.05 20 6.5 20C5.95 20 5.5 19.55 5.5 19C5.5 18.45 5.95 18 6.5 18C7.05 18 7.5 18.45 7.5 19ZM10 18.5L18 18.5L18 19.5L10 19.5Z",
        "paw" to "M7.7 10.5C7.7 11.72 6.72 12.7 5.5 12.7C4.28 12.7 3.3 11.72 3.3 10.5C3.3 9.28 4.28 8.3 5.5 8.3C6.72 8.3 7.7 9.28 7.7 10.5ZM11.5 5.8C11.5 7.02 10.52 8 9.3 8C8.08 8 7.1 7.02 7.1 5.8C7.1 4.58 8.08 3.6 9.3 3.6C10.52 3.6 11.5 4.58 11.5 5.8ZM16.9 5.8C16.9 7.02 15.92 8 14.7 8C13.48 8 12.5 7.02 12.5 5.8C12.5 4.58 13.48 3.6 14.7 3.6C15.92 3.6 16.9 4.58 16.9 5.8ZM20.7 10.5C20.7 11.72 19.72 12.7 18.5 12.7C17.28 12.7 16.3 11.72 16.3 10.5C16.3 9.28 17.28 8.3 18.5 8.3C19.72 8.3 20.7 9.28 20.7 10.5ZM12 11.5C8.5 11.5 6.5 15.5 6.5 17.8C6.5 19.5 8 20.5 9.5 20.2C10.5 20 11.2 19.6 12 19.6C12.8 19.6 13.5 20 14.5 20.2C16 20.5 17.5 19.5 17.5 17.8C17.5 15.5 15.5 11.5 12 11.5Z",
        "pool" to "M7 3L8.8 3L8.8 16L7 16ZM15.2 3L17 3L17 16L15.2 16ZM8.8 6.5L15.2 6.5L15.2 8.1L8.8 8.1ZM8.8 10.5L15.2 10.5L15.2 12.1L8.8 12.1ZM2 17.4L2.5 17.87L3 18.16L3.5 18.16L4 17.87L4.5 17.4L5 16.93L5.5 16.64L6 16.64L6.5 16.93L7 17.4L7.5 17.87L8 18.16L8.5 18.16L9 17.87L9.5 17.4L10 16.93L10.5 16.64L11 16.64L11.5 16.93L12 17.4L12.5 17.87L13 18.16L13.5 18.16L14 17.87L14.5 17.4L15 16.93L15.5 16.64L16 16.64L16.5 16.93L17 17.4L17.5 17.87L18 18.16L18.5 18.16L19 17.87L19.5 17.4L20 16.93L20.5 16.64L21 16.64L21.5 16.93L22 17.4L22 19L21.5 18.53L21 18.24L20.5 18.24L20 18.53L19.5 19L19 19.47L18.5 19.76L18 19.76L17.5 19.47L17 19L16.5 18.53L16 18.24L15.5 18.24L15 18.53L14.5 19L14 19.47L13.5 19.76L13 19.76L12.5 19.47L12 19L11.5 18.53L11 18.24L10.5 18.24L10 18.53L9.5 19L9 19.47L8.5 19.76L8 19.76L7.5 19.47L7 19L6.5 18.53L6 18.24L5.5 18.24L5 18.53L4.5 19L4 19.47L3.5 19.76L3 19.76L2.5 19.47L2 19ZM2 20.8L2.5 21.27L3 21.56L3.5 21.56L4 21.27L4.5 20.8L5 20.33L5.5 20.04L6 20.04L6.5 20.33L7 20.8L7.5 21.27L8 21.56L8.5 21.56L9 21.27L9.5 20.8L10 20.33L10.5 20.04L11 20.04L11.5 20.33L12 20.8L12.5 21.27L13 21.56L13.5 21.56L14 21.27L14.5 20.8L15 20.33L15.5 20.04L16 20.04L16.5 20.33L17 20.8L17.5 21.27L18 21.56L18.5 21.56L19 21.27L19.5 20.8L20 20.33L20.5 20.04L21 20.04L21.5 20.33L22 20.8L22 22.4L21.5 21.93L21 21.64L20.5 21.64L20 21.93L19.5 22.4L19 22.87L18.5 23.16L18 23.16L17.5 22.87L17 22.4L16.5 21.93L16 21.64L15.5 21.64L15 21.93L14.5 22.4L14 22.87L13.5 23.16L13 23.16L12.5 22.87L12 22.4L11.5 21.93L11 21.64L10.5 21.64L10 21.93L9.5 22.4L9 22.87L8.5 23.16L8 23.16L7.5 22.87L7 22.4L6.5 21.93L6 21.64L5.5 21.64L5 21.93L4.5 22.4L4 22.87L3.5 23.16L3 23.16L2.5 22.87L2 22.4Z",
        "fireplace" to "M1 3L23 3L23 5.5L1 5.5ZM3 5.5L21 5.5L21 21L3 21ZM6 9L18 9L18 21L6 21ZM12 12.52C12 12.52 12.84 13.78 12.84 14.83C13.39 14.54 13.85 14.03 14.1 13.36C15.11 14.41 15.57 15.46 15.57 16.93C15.57 18.9 13.97 20.5 12 20.5C10.03 20.5 8.43 18.9 8.43 16.93C8.43 15.67 9.14 14.54 10.07 13.78C10.07 14.49 10.4 15.12 10.95 15.46C10.99 14.37 11.37 13.36 12 12.52ZM12 19.66C12.71 19.66 13.26 19.11 13.26 18.4C13.26 17.73 12.76 17.14 12 16.3C11.24 17.14 10.74 17.73 10.74 18.4C10.74 19.11 11.29 19.66 12 19.66Z",
        "broom" to "M18.79 1.64L12.59 9.54L14.01 10.66L20.21 2.76ZM10.5 9.5L14.5 13.5L10 20.5L3.5 20.5L3.5 16ZM5.85 18.85L11.85 12.85L11.15 12.15L5.15 18.15Z",
        "lock-outline" to "M6 8H18Q20 8 20 10V20Q20 22 18 22H6Q4 22 4 20V10Q4 8 6 8ZM6 10L18 10L18 20L6 20ZM13.7 15C13.7 15.94 12.94 16.7 12 16.7C11.06 16.7 10.3 15.94 10.3 15C10.3 14.06 11.06 13.3 12 13.3C12.94 13.3 13.7 14.06 13.7 15ZM7 8V6C7 3.24 9.24 1 12 1C14.76 1 17 3.24 17 6V8H15V6C15 4.34 13.66 3 12 3C10.34 3 9 4.34 9 6V8Z",
    )
}

/**
 * A view that draws an MDI glyph of its own ([MdiIconView], or a row that draws its icon itself): it looks its glyph
 * up again when the catalog arrives ([MdiIcons.redrawWhenReady]). Main thread.
 */
interface IconHost {
    fun reloadIcon()
}

/** Lightweight Android View drawing one crisp 24x24 MDI icon path in [tintColor]. */
class MdiIconView(context: Context) : View(context), IconHost {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** The glyph, looked up when first set or drawn (a view always given an icon never parses `home`). */
    private var iconPath: Path? = null
    private var iconName = "home"
    /** The icon's name as given to [setIcon] (`mdi:` prefix and all). */
    val icon: String get() = iconName

    var tintColor: Int = 0xFFFFFFFF.toInt()
        set(v) { if (field != v) { field = v; paint.color = v; invalidate() } }

    /** A picture drawn instead of the glyph, filling the box (HA's weather pictures); [setIcon] clears it. */
    var picture: dev.glassworks.engine.vitro.cards.MdSvg? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    fun setIcon(name: String, color: Int = tintColor) {
        // Read before the lookup: a catalog that finishes during it still redraws this view (redrawWhenReady
        // re-checks), and one already loaded costs no registration.
        val mayBeStandIn = MdiIcons.isLoading
        val path = MdiIcons.pathFor(name)
        if (mayBeStandIn) MdiIcons.redrawWhenReady(this)
        // The same glyph in the same color: nothing to draw again (a state update that keeps the icon).
        if (picture == null && path === iconPath && name == iconName && color == tintColor) return
        picture = null
        iconName = name
        iconPath = path
        tintColor = color
        paint.color = color
        invalidate()
    }

    /** The catalog arrived after this view drew a stand-in: look the icon up again. Main thread. */
    override fun reloadIcon() {
        iconPath = MdiIcons.pathFor(iconName)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val availH = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val scale = minOf(availW, availH) / 24f
        val dx = paddingLeft + (availW - 24f * scale) / 2f
        val dy = paddingTop + (availH - 24f * scale) / 2f
        picture?.let { pic ->
            val side = minOf(availW, availH)
            canvas.save()
            canvas.translate(paddingLeft + (availW - side) / 2f, paddingTop + (availH - side) / 2f)
            pic.draw(canvas, side, side, context)
            canvas.restore()
            return
        }
        val src = iconPath ?: MdiIcons.pathFor(iconName).also { iconPath = it }
        paint.color = tintColor
        canvas.drawPath(MdiIcons.placed(src, scale, dx, dy), paint)
    }
}
