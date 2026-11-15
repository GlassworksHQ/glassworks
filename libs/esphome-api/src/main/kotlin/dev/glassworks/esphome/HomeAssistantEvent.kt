package dev.glassworks.esphome

/**
 * An event the device fires on Home Assistant's event bus, as ESPHome's `homeassistant.event` does.
 * Home Assistant fires it as [event] with [data] and the device's `device_id`
 * (`esphome/manager.py` `async_on_service_call`, 2026.11). It allows only names in the `esphome.`
 * domain, and it needs no "Allow the device to perform Home Assistant actions" option: that option
 * guards actions, not events.
 *
 * Wire format: `HomeassistantActionRequest` (35) with `is_event` set, in aioesphomeapi 46.25.0's
 * api.proto. Home Assistant gets these only after it sent `SubscribeHomeassistantServicesRequest`
 * (34), which it does on every connection.
 *
 * [ApiServer.sendHomeAssistantEvent] sends it. The notifications pane (#118) reports a tapped button
 * this way.
 *
 * @property event The event type, `esphome.` followed by snake_case, such as `esphome.button_pressed`.
 * @property data The event's data. Home Assistant adds `device_id`.
 */
public class HomeAssistantEvent(public val event: String, public val data: Map<String, String> = emptyMap()) {
    init {
        require(NAME.matches(event)) { "event '$event' must be esphome.<snake_case>" }
    }

    internal fun encode(): ProtoWriter {
        val w = ProtoWriter().string(1, event, force = true)
        for ((k, v) in data) w.message(2, ProtoWriter().string(1, k).string(2, v))
        return w.bool(5, true)
    }

    /** The event and its data's keys, without the values, so it is safe to log. */
    override fun toString(): String = "HomeAssistantEvent($event, keys=${data.keys})"

    private companion object {
        val NAME = Regex("esphome\\.[a-z0-9_]+")
    }
}
