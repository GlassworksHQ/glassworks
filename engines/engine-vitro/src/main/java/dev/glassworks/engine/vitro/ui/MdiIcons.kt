/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.glassworks.engine.vitro.data.EntityState
import java.util.concurrent.ConcurrentHashMap

/**
 * Crisp 24x24 Material Design Icon vector renderer with zero icon-font overhead.
 * Works on Android 6+ (API 23) and scales cleanly from 720p wall switches to 40" 1080p mirrors.
 */
object MdiIcons {
    private val cache = ConcurrentHashMap<String, Path>()

    /** Picks the best built-in MDI icon name for [explicitIcon] or [entity]. */
    fun resolveIconName(explicitIcon: String?, entity: EntityState?): String {
        if (!explicitIcon.isNullOrBlank()) return explicitIcon.lowercase()
        entity?.icon?.let { return it.lowercase() }
        val domain = entity?.domain.orEmpty()
        val dc = entity?.deviceClass.orEmpty()
        val on = entity?.isActive == true
        return when (domain) {
            "light" -> if (on) "mdi:lightbulb-on" else "mdi:lightbulb"
            "switch", "input_boolean" -> "mdi:power"
            "climate" -> "mdi:thermometer"
            "fan" -> "mdi:fan"
            "cover" -> if (on) "mdi:window-open" else "mdi:window-closed"
            "lock" -> if (on) "mdi:lock-open" else "mdi:lock"
            "camera" -> "mdi:cctv"
            "media_player" -> if (on) "mdi:play" else "mdi:speaker"
            "weather" -> weatherIcon(entity?.state.orEmpty())
            "person", "device_tracker" -> "mdi:account"
            "binary_sensor" -> when (dc) {
                "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
                "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
                "motion", "occupancy", "presence" -> "mdi:motion-sensor"
                else -> "mdi:checkbox-blank-circle"
            }
            "sensor" -> when (dc) {
                "temperature" -> "mdi:thermometer"
                "humidity" -> "mdi:water-percent"
                "power", "energy", "voltage", "current" -> "mdi:flash"
                "battery" -> "mdi:battery"
                "illuminance" -> "mdi:white-balance-sunny"
                else -> "mdi:gauge"
            }
            else -> "mdi:home"
        }
    }

    fun weatherIcon(condition: String): String = when (condition.lowercase()) {
        "sunny", "clear-night" -> "mdi:white-balance-sunny"
        "partlycloudy", "cloudy" -> "mdi:weather-cloudy"
        "rainy", "pouring" -> "mdi:weather-rainy"
        "snowy", "snowy-rainy", "hail" -> "mdi:snowflake"
        "lightning", "lightning-rainy" -> "mdi:flash"
        else -> "mdi:weather-cloudy"
    }

    /** Returns the raw 24x24 SVG path data string for [name] (also served to Tier 3 `<ha-icon>` shims). */
    fun pathDataFor(name: String): String {
        val key = name.lowercase().removePrefix("mdi:")
        return PATHS[key] ?: PATHS.getValue("home")
    }

    fun pathFor(name: String): Path {
        val key = name.lowercase().removePrefix("mdi:")
        return cache.computeIfAbsent(key) { parseSvgPath(PATHS[it] ?: PATHS.getValue("home")) }
    }

    /** Minimal SVG path data parser (M/m/L/l/H/h/V/v/C/c/Q/q/Z/z) for 24x24 MDI paths. */
    fun parseSvgPath(d: String): Path {
        val path = Path()
        val tokens = tokenize(d)
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f

        fun nextF(): Float = if (i < tokens.size) tokens[i++].toFloatOrNull() ?: 0f else 0f
        fun hasNum(): Boolean = i < tokens.size && tokens[i].firstOrNull()?.let { it.isDigit() || it == '-' || it == '.' || it == '+' } == true

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.close()
                    cx = sx; cy = sy
                    continue
                }
            }
            when (cmd) {
                'M' -> {
                    cx = nextF(); cy = nextF(); sx = cx; sy = cy
                    path.moveTo(cx, cy)
                    cmd = 'L'
                }
                'm' -> {
                    cx += nextF(); cy += nextF(); sx = cx; sy = cy
                    path.moveTo(cx, cy)
                    cmd = 'l'
                }
                'L' -> { cx = nextF(); cy = nextF(); path.lineTo(cx, cy) }
                'l' -> { cx += nextF(); cy += nextF(); path.lineTo(cx, cy) }
                'H' -> { cx = nextF(); path.lineTo(cx, cy) }
                'h' -> { cx += nextF(); path.lineTo(cx, cy) }
                'V' -> { cy = nextF(); path.lineTo(cx, cy) }
                'v' -> { cy += nextF(); path.lineTo(cx, cy) }
                'C' -> {
                    val x1 = nextF(); val y1 = nextF()
                    val x2 = nextF(); val y2 = nextF()
                    cx = nextF(); cy = nextF()
                    path.cubicTo(x1, y1, x2, y2, cx, cy)
                }
                'c' -> {
                    val x1 = cx + nextF(); val y1 = cy + nextF()
                    val x2 = cx + nextF(); val y2 = cy + nextF()
                    cx += nextF(); cy += nextF()
                    path.cubicTo(x1, y1, x2, y2, cx, cy)
                }
                'Q' -> {
                    val x1 = nextF(); val y1 = nextF()
                    cx = nextF(); cy = nextF()
                    path.quadTo(x1, y1, cx, cy)
                }
                'q' -> {
                    val x1 = cx + nextF(); val y1 = cy + nextF()
                    cx += nextF(); cy += nextF()
                    path.quadTo(x1, y1, cx, cy)
                }
                'A', 'a' -> {
                    // Approximate arc endpoint with a smooth line for compact icon shapes.
                    nextF(); nextF(); nextF(); nextF(); nextF()
                    if (cmd == 'A') { cx = nextF(); cy = nextF() } else { cx += nextF(); cy += nextF() }
                    path.lineTo(cx, cy)
                }
                else -> {
                    if (!hasNum()) i++ else nextF()
                }
            }
        }
        return path
    }

    private fun tokenize(d: String): List<String> {
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
                        else break
                    }
                    if (i > start) out += d.substring(start, i) else i++
                }
            }
        }
        return out
    }

    // Standard 24x24 geometric icon paths (original clean vector paths for HA domains).
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
        "snowflake" to "M11 2H13V11H22V13H13V22H11V13H2V11H11V2Z",
        "battery" to "M9 2H15V4H17V22H7V4H9V2ZM9 6V20H15V6H9Z",
        "gauge" to "M12 4C6.5 4 2 8.5 2 14C2 16.2 2.7 18.2 4 19.9L5.5 18.5C4.6 17.2 4 15.7 4 14C4 9.6 7.6 6 12 6C16.4 6 20 9.6 20 14C20 15.7 19.4 17.2 18.5 18.5L20 19.9C21.3 18.2 22 16.2 22 14C22 8.5 17.5 4 12 4ZM12 12L16 8L17.4 9.4L13.4 13.4C13.8 14.3 13.2 15.5 12 15.5C10.9 15.5 10.2 14.3 10.7 13.3L12 12Z",
        "account" to "M12 4C9.8 4 8 5.8 8 8C8 10.2 9.8 12 12 12C14.2 12 16 10.2 16 8C16 5.8 14.2 4 12 4ZM12 14C8.7 14 4 15.7 4 19V21H20V19C20 15.7 15.3 14 12 14Z",
        "checkbox-blank-circle" to "M12 4C7.6 4 4 7.6 4 12C4 16.4 7.6 20 12 20C16.4 20 20 16.4 20 12C20 7.6 16.4 4 12 4Z",
    )
}

/** Lightweight Android View drawing one crisp 24x24 MDI icon path in [tintColor]. */
class MdiIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val drawPath = Path()
    private val matrix = Matrix()
    private var iconPath: Path = MdiIcons.pathFor("home")

    var tintColor: Int = 0xFFFFFFFF.toInt()
        set(v) { if (field != v) { field = v; paint.color = v; invalidate() } }

    fun setIcon(name: String, color: Int = tintColor) {
        iconPath = MdiIcons.pathFor(name)
        tintColor = color
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val availH = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val scale = minOf(availW, availH) / 24f
        val dx = paddingLeft + (availW - 24f * scale) / 2f
        val dy = paddingTop + (availH - 24f * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        drawPath.set(iconPath)
        drawPath.transform(matrix)
        paint.color = tintColor
        canvas.drawPath(drawPath, paint)
    }
}
