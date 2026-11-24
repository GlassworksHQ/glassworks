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
            "light" -> "mdi:lightbulb"
            "switch", "input_boolean" -> "mdi:power"
            "climate" -> "mdi:thermometer"
            "fan" -> "mdi:fan"
            "cover" -> when (dc) {
                "garage" -> "mdi:garage"
                "blind", "shade", "shutter" -> "mdi:blinds"
                "curtain" -> "mdi:curtains"
                else -> if (on) "mdi:window-open" else "mdi:window-closed"
            }
            "lock" -> if (on) "mdi:lock-open" else "mdi:lock"
            "camera" -> "mdi:cctv"
            "media_player" -> if (on) "mdi:play" else "mdi:speaker"
            "weather" -> weatherIcon(entity?.state.orEmpty())
            "person", "device_tracker" -> "mdi:account"
            "scene" -> "mdi:palette"
            "script" -> "mdi:script-text"
            "todo" -> "mdi:checkbox-marked"
            "schedule", "timer" -> "mdi:clock"
            "calendar" -> "mdi:calendar"
            "alarm_control_panel" -> if (entity?.state.orEmpty().startsWith("armed")) "mdi:shield-lock" else "mdi:shield-home"
            "select", "input_select" -> "mdi:format-list-bulleted"
            "number", "input_number" -> "mdi:tune"
            "text", "input_text" -> "mdi:text-box"
            "button", "input_button" -> "mdi:gesture-tap-button"
            "vacuum" -> "mdi:robot-vacuum"
            "image" -> "mdi:image"
            "assist_satellite" -> "mdi:microphone"
            "humidifier" -> "mdi:water"
            "siren" -> "mdi:bell"
            "binary_sensor" -> when (dc) {
                "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
                "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
                "garage_door" -> "mdi:garage"
                "motion", "occupancy", "presence" -> "mdi:motion-sensor"
                "smoke", "heat" -> "mdi:fire"
                "moisture" -> "mdi:water"
                "problem", "safety", "tamper" -> "mdi:alert"
                "connectivity" -> "mdi:wifi"
                else -> "mdi:checkbox-blank-circle"
            }
            "sensor" -> when (dc) {
                "temperature" -> "mdi:thermometer"
                "humidity" -> "mdi:water-percent"
                "power", "energy", "voltage", "current" -> "mdi:flash"
                "battery" -> "mdi:battery"
                "moisture" -> "mdi:water"
                "illuminance" -> "mdi:white-balance-sunny"
                else -> "mdi:gauge"
            }
            else -> "mdi:home"
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
        else -> "mdi:weather-cloudy"
    }

    /** Returns the raw 24x24 SVG path data string for [name] (also served to Tier 3 `<ha-icon>` shims). */
    fun pathDataFor(name: String): String {
        val key = name.lowercase().removePrefix("mdi:").removePrefix("hass:")
        return PATHS[key] ?: PATHS.getValue("home")
    }

    fun pathFor(name: String): Path {
        val key = name.lowercase().removePrefix("mdi:").removePrefix("hass:")
        return cache.computeIfAbsent(key) { parseSvgPath(PATHS[it] ?: PATHS.getValue("home")) }
    }

    /** Minimal SVG path data parser (M/m/L/l/H/h/V/v/C/c/Q/q/Z/z) for 24x24 MDI paths. */
    fun parseSvgPath(d: String): Path {
        // Even-odd so nested sub-paths (door panes, glyph cut-outs) render as holes regardless of winding.
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
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
