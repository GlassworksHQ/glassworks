package dev.glassworks.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the README's "Compared with ESPHome" entity table honest: the entity types it marks
 * Supported or Partial are exactly the ones a server built from every shipped entity class lists,
 * and it has a row for every entity type in ESPHome's api.proto.
 */
class ParityTableTest {
    /** `ListEntities*Response` id -> ESPHome component, from api.proto at ESPHome 2026.10.2. */
    private val entityTypes = mapOf(
        12 to "binary_sensor", 13 to "cover", 14 to "fan", 15 to "light", 16 to "sensor", 17 to "switch",
        18 to "text_sensor", 43 to "camera", 46 to "climate", 49 to "number", 52 to "select", 55 to "siren",
        58 to "lock", 61 to "button", 63 to "media_player", 94 to "alarm_control_panel", 97 to "text",
        100 to "date", 103 to "time", 107 to "event", 109 to "valve", 112 to "datetime", 116 to "update",
        132 to "water_heater", 135 to "infrared", 148 to "radio_frequency",
    )

    private val shipped: List<ApiEntity> = listOf(
        BinarySensorEntity("a", "A", get = { true }),
        SensorEntity("b", "B", get = { 1f }),
        TextSensorEntity("c", "C", get = { "x" }),
        SwitchEntity("d", "D", get = { true }, set = {}),
        NumberEntity("e", "E", min = 0f, max = 1f, step = 1f, get = { 0f }, set = {}),
        SelectEntity("f", "F", options = listOf("x"), get = { "x" }, set = {}),
        TextEntity("g", "G", get = { "x" }, set = {}),
        ButtonEntity("h", "H", press = {}),
        EventEntity("i", "I", eventTypes = listOf("x")),
        UpdateEntity("j", "J", get = { UpdateStateData(true, false, false, 0f, "", "", "", "", "") }, onCommand = {}),
        BrightnessLightEntity("k", "K", get = { true to 1f }, set = { _, _ -> }),
        MediaPlayerEntity("l", "L", formats = emptyList(), featureFlags = 0, get = {
            MediaPlayerStateData(MediaState.IDLE, 1f, false)
        }, handle = {}),
        CameraEntity("m", "M") { null },
        ApiAction("n") { null },
    )

    @Test fun everyShippedEntityClassIsListedHere() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val declared = Regex("""public class (\w+)\((?:(?!public class).)*?\) : ApiEntity\(""", RegexOption.DOT_MATCHES_ALL)
            .findAll(sources).map { it.groupValues[1] }.toSet()
        assertEquals("add new entity classes to `shipped`", declared, shipped.map { it::class.simpleName }.toSet())
    }

    @Test fun supportedAndPartialRowsAreTheTypesTheServerLists() {
        val server = ApiServer(0, { identity }, shipped, psk = null)
        server.start()
        val listed = try {
            PlainClient(server.localPort).use { c ->
                c.send(Msg.LIST_ENTITIES_REQUEST)
                ArrayList<Frame>().also { c.await(Msg.LIST_ENTITIES_DONE, it) }.map { it.type }
            }
        } finally { server.stop() }
        val unknown = listed.filter { it != Msg.LIST_ENTITIES_SERVICES && it !in entityTypes }
        assertTrue("list types missing from entityTypes: $unknown", unknown.isEmpty())

        val rows = entityRows()
        assertEquals("one README row per api.proto entity type", entityTypes.values.toSet(), rows.keys)
        val claimed = rows.filterValues { it.startsWith("Supported") || it.startsWith("Partial") }.keys
        assertEquals("README Supported/Partial entity rows", listed.mapNotNull(entityTypes::get).toSet(), claimed)
    }

    /** component -> the esphome-api status cell, from the README's "### Entities" table. */
    private fun entityRows(): Map<String, String> {
        val lines = File("README.md").readLines()
        val start = lines.indexOf("### Entities")
        assertTrue("README has a ### Entities section", start >= 0)
        val table = lines.drop(start + 1).dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") }
        val header = table.first().trim('|').split('|').map { it.trim() }
        val status = header.indexOf("esphome-api")
        return table.drop(2).associate { line ->
            val cells = line.trim('|').split('|').map { it.trim() }
            val names = Regex("`(\\w+)`").findAll(cells[0]).map { it.groupValues[1] }.toList()
            names to cells[status]
        }.flatMap { (names, s) -> names.map { it to s } }.toMap()
    }

    private val identity = DeviceIdentity(
        name = "parity", friendlyName = "Parity", macAddress = "02:00:00:00:00:01", esphomeVersion = "2026.10.2",
        compilationTime = "test", manufacturer = "Test", model = "Test", voiceFeatures = 0,
    )
}
