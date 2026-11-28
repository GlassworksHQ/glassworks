/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.sun.net.httpserver.HttpServer
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.InetSocketAddress

/**
 * Can one card config or one entity state Vitro doesn't expect throw on the main thread? Every native card type ×
 * every domain's entity × hostile attributes × hostile configs, built, laid out, drawn, then a state change bound and
 * drawn again. Each case runs in its own try/catch here, so the test lists every throwing case.
 *
 * Two kinds of failure count: a throw that escapes (on a panel it would end the process), and one a card contained
 * (VitroFailures: the card shows HA's error card). The first must never happen; the second is a card bug to fix, so
 * the fuzz still finds it behind the containment. `-DfuzzTypes=tile,gauge` runs only those card types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HostileConfigFuzzTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private val types = listOf(
        "alarm-panel", "area", "button", "calendar", "clock", "conditional", "entities", "entity", "entity-filter", "gauge",
        "glance", "heading", "history-graph", "humidifier", "light", "logbook", "markdown", "media-control", "picture",
        "picture-elements", "picture-entity", "picture-glance", "sensor", "statistics-graph", "thermostat", "tile",
        "todo-list", "weather-forecast", "grid", "vertical-stack", "horizontal-stack",
        "custom:auto-entities", "custom:bar-card", "custom:bubble-card", "custom:clock-weather-card", "custom:layout-card",
        "custom:mini-graph-card", "custom:mini-media-player", "custom:modern-circular-gauge",
        "custom:mushroom-alarm-control-panel-card", "custom:mushroom-chips-card", "custom:mushroom-climate-card",
        "custom:mushroom-cover-card", "custom:mushroom-entity-card", "custom:mushroom-fan-card",
        "custom:mushroom-humidifier-card", "custom:mushroom-light-card", "custom:mushroom-lock-card",
        "custom:mushroom-media-player-card", "custom:mushroom-number-card", "custom:mushroom-person-card",
        "custom:mushroom-select-card", "custom:mushroom-template-card", "custom:mushroom-title-card",
        "custom:mushroom-vacuum-card", "custom:stack-in-card", "custom:vertical-stack-in-card",
    )

    private val domains = listOf(
        "light", "climate", "cover", "fan", "media_player", "sensor", "weather", "humidifier", "lock",
        "alarm_control_panel", "vacuum", "number", "input_number", "select", "input_select", "camera", "person",
        "todo", "calendar", "switch", "water_heater", "update", "binary_sensor",
    )

    private val attrKeys = listOf(
        "friendly_name", "unit_of_measurement", "device_class", "brightness", "rgb_color", "hs_color", "xy_color",
        "color_temp_kelvin", "min_color_temp_kelvin", "max_color_temp_kelvin", "supported_color_modes", "color_mode",
        "effect_list", "supported_features", "current_temperature", "temperature", "target_temp_low", "target_temp_high",
        "min_temp", "max_temp", "target_temp_step", "hvac_modes", "hvac_action", "fan_modes", "fan_mode", "preset_modes",
        "current_position", "current_tilt_position", "percentage", "percentage_step", "preset_mode", "volume_level",
        "is_volume_muted", "media_title", "media_artist", "entity_picture", "media_duration", "media_position",
        "media_position_updated_at", "source_list", "min", "max", "step", "mode", "options", "humidity", "min_humidity",
        "max_humidity", "available_modes", "code_format", "forecast", "temperature_unit", "latitude", "longitude",
        "battery_level", "fan_speed_list", "state_class", "icon", "entity_id", "current_humidity", "wind_speed",
        "pressure", "precipitation_unit", "wind_bearing", "operation_list", "target_humidity", "installed_version",
        "latest_version", "in_progress", "message", "start_time", "end_time", "all_day",
    )

    /** Attribute maps HA can produce from a misbehaving integration (or a template entity). */
    private fun hostileAttrs(): List<Pair<String, Map<String, Any?>>> = listOf(
        "strings" to attrKeys.associateWith { "x" },
        "nulls" to attrKeys.associateWith { null },
        "inverted" to attrKeys.associateWith { k ->
            when {
                k.startsWith("min") -> 100.0
                k.startsWith("max") -> 0.0
                k == "step" || k.endsWith("_step") -> 0.0
                k.endsWith("_color") -> listOf(1)
                k.endsWith("s") || k.endsWith("_list") -> emptyList<Any>()
                else -> -1.0e12
            }
        },
        "objects" to attrKeys.associateWith { JSONObject().put("x", 1) },
        "lists" to attrKeys.associateWith { listOf(null, 1, "x", listOf<Any>()) },
        "empty" to emptyMap(),
    )

    private val states = listOf("on", "unavailable", "abc")

    /** Card configs with keys of the wrong type, out-of-range numbers, and nested junk. */
    private fun hostileConfigs(type: String, e: String): List<Pair<String, JSONObject>> {
        val base = { JSONObject().put("type", type) }
        return listOf(
            "bare" to base(),
            "entity" to base().put("entity", e),
            "wrongTypes" to base().put("entity", 42).put("entities", "x").put("name", JSONObject()).put("icon", 42)
                .put("cards", "x").put("card", "x").put("chips", "x").put("elements", "x").put("features", "x")
                .put("content", 42).put("state_content", 42).put("columns", "x").put("hours_to_show", "x")
                .put("min", "x").put("max", "x").put("segments", "x").put("severity", "x").put("grid_options", "x")
                .put("tap_action", "x").put("visibility", "x").put("card_mod", "x").put("aspect_ratio", "x")
                .put("show_state", "x").put("theme", 42).put("sub_button", "x").put("layout", 42).put("filter", "x"),
            "outOfRange" to base().put("entity", e).put("min", 100).put("max", 0).put("columns", 0).put("rows", -1)
                .put("hours_to_show", -5).put("points_per_hour", 0).put("line_width", -1).put("decimals", -3)
                .put("days_to_show", 0).put("aspect_ratio", "0").put("grid_options", JSONObject().put("columns", -3).put("rows", 0))
                .put("segments", JSONArray().put(JSONObject().put("from", "x")).put(JSONObject()))
                .put("severity", JSONObject().put("green", "x").put("red", JSONObject()))
                .put("chart_type", "x").put("period", JSONObject()).put("stat_types", "x").put("forecast_type", "x")
                .put("initial_view", "x").put("needle", true).put("vertical", 1).put("state_content", JSONArray().put(42).put("x"))
                .put("width", "-1px").put("height", "1e9px").put("max_columns", -1).put("column_span", 0),
            "nestedJunk" to base().put("entity", e)
                .put("entities", JSONArray().put(e).put(42).put(JSONObject.NULL).put("").put(JSONObject())
                    .put(JSONObject().put("type", "attribute").put("entity", e).put("attribute", "x"))
                    .put(JSONObject().put("type", "section")).put(JSONObject().put("entity", e).put("type", "buttons")))
                .put("features", JSONArray().put(JSONObject().put("type", "numeric-input")).put(JSONObject().put("type", "fan-speed"))
                    .put(JSONObject().put("type", "climate-hvac-modes").put("hvac_modes", "x"))
                    .put(JSONObject().put("type", "light-color-favorites")).put(JSONObject().put("type", "target-temperature"))
                    .put(JSONObject().put("type", "select-options")).put(JSONObject().put("type", "vacuum-commands").put("commands", "x"))
                    .put(JSONObject().put("type", "media-player-volume-slider")).put("x"))
                .put("chips", JSONArray().put(JSONObject().put("type", "entity")).put(JSONObject().put("type", "template").put("content", 42))
                    .put("x").put(JSONObject().put("type", "conditional").put("chip", "x")))
                .put("cards", JSONArray().put(JSONObject().put("type", "tile")).put("x").put(JSONObject()))
                .put("card", JSONObject().put("type", "tile").put("entity", e))
                .put("elements", JSONArray().put(JSONObject().put("type", "state-icon").put("entity", e))
                    .put(JSONObject().put("type", "state-label")).put(JSONObject().put("type", "service-button")).put("x")
                    .put(JSONObject().put("type", "icon").put("style", "x")))
                .put("sub_button", JSONArray().put(JSONObject()).put("x"))
                .put("filter", JSONObject().put("include", JSONArray().put(JSONObject().put("domain", "/[/")).put("x")))
                .put("buttons", JSONArray().put("x")),
        )
    }

    /** What the cards contained during the current case (VitroFailures), with where. */
    private val contained = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Throwable>>()

    @Before fun listen() {
        VitroFailures.listener = { where, _, t -> contained += where to t }
    }

    @After fun stopListening() {
        VitroFailures.listener = null
        ha.stop(0)
    }

    /**
     * Home Assistant for the cards' pictures (a media card's background, an image path): on loopback, with none, so
     * each of the run's thousands of loads ends at once instead of waiting out a timeout on a real address.
     */
    private val ha = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        start()
    }
    private val haBase = "http://127.0.0.1:${ha.address.port}"

    @Test fun everyNativeCardSurvivesHostileConfigsAndStates() {
        val failures = LinkedHashMap<String, String>()
        var cases = 0
        val only = System.getProperty("fuzzTypes")?.split(',')?.toSet()
        val pendingBefore = VitroCardContext.pendingImageLoads.get()
        for (type in types.filter { only == null || it in only }) for (domain in domains) {
            val e = "$domain.fuzz"
            val configs = hostileConfigs(type, e)
            // Every config once with a sane state, then the plain entity config with every hostile attribute map.
            val plan = configs.map { it to ("sane" to mapOf<String, Any?>("friendly_name" to "Fuzz")) } +
                hostileAttrs().map { configs[1] to it }
            for ((cfg, attrs) in plan) for (state in if (attrs.first == "sane") listOf("on") else states) {
                cases++
                val key = "$type / $domain / ${cfg.first} / ${attrs.first} / '$state'"
                contained.clear()
                val err = runCase(cfg.second, EntityState(e, state, attrs.second))
                val found = listOfNotNull(err?.let { "escaped" to it }) + contained.map { (where, t) -> "contained in $where" to t }
                for ((how, t) in found) {
                    val sig = "$how: ${t.javaClass.simpleName}: ${t.message?.take(80)} @ ${t.stackTrace.firstOrNull { it.className.contains("vitro") }}"
                    // One line per distinct failure, with the first case that hit it.
                    if (failures.values.none { it.endsWith(sig) }) failures[key] = sig
                }
            }
        }
        // The cards' picture loads, all answered: none is left to land in a later test.
        awaitImageLoads(pendingBefore, timeoutMs = 30_000)
        println("HostileConfigFuzzTest: $cases cases, ${failures.size} distinct failures")
        failures.forEach { (k, v) -> println("  $k -> $v") }
        assertTrue("${failures.size} distinct throws over $cases cases:\n" + failures.entries.joinToString("\n") { "${it.key} -> ${it.value}" }, failures.isEmpty())
    }

    private fun runCase(cfg: JSONObject, entity: EntityState): Throwable? {
        val store = EntityStore()
        store.putLocal(entity)
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = haBase, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, cb -> cb("x"); {} },
            )
        }
        return try {
            val dash = JSONObject().put("views", JSONArray()
                .put(JSONObject().put("title", "S").put("type", "sections").put("sections", JSONArray()
                    .put(JSONObject().put("type", "grid").put("cards", JSONArray().put(cfg)))))
                .put(JSONObject().put("title", "M").put("path", "m").put("cards", JSONArray().put(cfg))))
            vv.setDashboard(LovelaceParser.parseDashboard(dash), null)
            activity.setContentView(vv)
            drawAll(vv)
            store.putLocal(entity.copy(state = "off", attributes = entity.attributes + ("friendly_name" to 7)))
            ShadowLooper.idleMainLooper()
            drawAll(vv)
            vv.navigateToView("m")
            ShadowLooper.idleMainLooper()
            drawAll(vv)
            null
        } catch (t: Throwable) {
            t
        } finally {
            runCatching { vv.cancelPendingBuilds() }
        }
    }

    private fun drawAll(v: View) {
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1440, 720)
        val bmp = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        bmp.recycle()
        ShadowLooper.idleMainLooper()
    }
}
