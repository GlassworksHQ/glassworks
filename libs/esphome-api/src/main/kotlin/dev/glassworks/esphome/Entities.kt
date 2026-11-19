package dev.glassworks.esphome

/** `EntityCategory` (api.proto): where Home Assistant lists an entity on the device page. */
public enum class EntityCategory(
    /** The wire value, for an [ApiEntity.info] written by hand. */
    public val value: Int,
) {
    /** A main control or sensor. */
    NONE(0),
    /** A setting of the device, under Configuration. */
    CONFIG(1),
    /** Information about the device, under Diagnostic. */
    DIAGNOSTIC(2),
}

/**
 * One entity exposed over the native API. Field numbers for each `ListEntities*Response`,
 * `*StateResponse` and `*CommandRequest` come from aioesphomeapi `api.proto`.
 *
 * Home Assistant names entities `<device name> <entity name>` (has_entity_name). It makes an entity id
 * once, when it first sees the entity: up to HA 2025.11 from the node name (`aqara-s1-plus-a1b2c3` +
 * "Screen" → `light.aqara_s1_plus_a1b2c3_screen`), from 2025.12 from the device's name as HA shows it
 * ("Aqara S1 Plus" + "Screen" → `light.aqara_s1_plus_screen`; 2026.8+ may put the area first). Renaming the device
 * later never changes it. The unique id is
 * `<mac>/<device_id>/<domain>/<name>` (aioesphomeapi `build_unique_id` v3); `object_id` is the
 * ESPHome-style snake_case name, and the key is FNV-1a of `object_id` so it never changes.
 *
 * Getters/setters run on API connection threads: implementations must be thread-safe.
 *
 * The library ships the common entity types. For another one, subclass this and write its messages
 * with [ProtoWriter], using the field numbers from api.proto.
 *
 * @property objectId The snake_case id, unique on the device. Home Assistant's unique id and the [key]
 *   come from it, so never change it.
 * @property name The name Home Assistant shows after the device's name, such as `Screen`.
 * @property icon An `mdi:` icon, or "" for the default of the entity's type.
 * @property category Where Home Assistant lists it.
 * @property disabledByDefault Home Assistant adds it disabled; the user can enable it.
 */
public abstract class ApiEntity(
    public val objectId: String,
    public val name: String,
    public val icon: String = "",
    public val category: EntityCategory = EntityCategory.NONE,
    public val disabledByDefault: Boolean = false,
) {
    /** The entity's key in the protocol: FNV-1a of [objectId]. */
    public val key: Int = fnv1a(objectId)

    /**
     * Commands only from an authenticated connection (Noise with the provisioned key): for actions that
     * must not come from anyone on the LAN while the panel is unprovisioned (plaintext or zero-PSK API),
     * e.g. flashing the radio.
     */
    public var authOnly: Boolean = false

    /** The [Msg] id of its `ListEntities*Response`. */
    public abstract val listType: Int

    /** The [Msg] id of its `*StateResponse`, or 0 for an entity without state. */
    public open val stateType: Int = 0

    /** The [Msg] id of its `*CommandRequest`, or 0 for an entity Home Assistant can't control. */
    public open val commandType: Int = 0

    /** Its `ListEntities*Response`: what Home Assistant learns about it once per connection. */
    public abstract fun info(): ProtoWriter

    /**
     * Its `*StateResponse` now, or null for none. The server sends it when a client subscribes, and
     * after [ApiServer.publishStates] when it changed.
     */
    public open fun state(): ProtoWriter? = null

    /**
     * The state as Home Assistant shows it, for a device's own screens: `51.5 °C`, `On`, the text. Null
     * when it has none now (unknown), or when the entity has no state (a button, an event).
     */
    public open fun shownState(): String? = null

    /** Handles its `*CommandRequest` from Home Assistant. Ignores it by default. */
    public open fun command(msg: ProtoMessage) {}

    /** The fields every `ListEntities*Response` starts with: object id (1), key (2) and name (3). */
    protected fun base(): ProtoWriter = ProtoWriter().string(1, objectId, force = true).fixed32(2, key, force = true)
        .string(3, name, force = true)

    internal companion object {
        fun fnv1a(s: String): Int {
            var h = 0x811C9DC5.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }
    }
}

/**
 * A binary sensor (`binary_sensor`): on or off, read-only.
 *
 * The common parameters are [ApiEntity]'s.
 * @param deviceClass Home Assistant's binary sensor device class, such as `occupancy`, or "".
 * @param get The state now: true is on, null is unknown.
 */
public class BinarySensorEntity(
    objectId: String, name: String, icon: String = "", private val deviceClass: String = "",
    category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> Boolean?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_BINARY_SENSOR
    override val stateType: Int = Msg.BINARY_SENSOR_STATE
    override fun info(): ProtoWriter = base().string(5, deviceClass).bool(7, disabledByDefault).string(8, icon)
        .uint32(9, category.value)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).bool(2, v == true).bool(3, v == null)
    }
    override fun shownState(): String? = get()?.let(::onOff)
}

/**
 * A sensor (`sensor`): a number, read-only.
 *
 * The common parameters are [ApiEntity]'s.
 * @param unit The unit Home Assistant shows, such as `°C`, or "".
 * @param accuracy The decimals to show.
 * @param deviceClass Home Assistant's sensor device class, such as `temperature`, or "". With
 *   `timestamp`, the value is Unix seconds.
 * @param stateClass `SensorStateClass`: 0 none, 1 measurement, 2 total_increasing, 3 total.
 * @param get The value now, or null (or NaN) for unknown.
 */
public class SensorEntity(
    objectId: String, name: String, icon: String = "",
    private val unit: String = "", private val accuracy: Int = 0, private val deviceClass: String = "",
    private val stateClass: Int = 1,
    category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> Float?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_SENSOR
    override val stateType: Int = Msg.SENSOR_STATE
    override fun info(): ProtoWriter = base().string(5, icon).string(6, unit).int32(7, accuracy).string(9, deviceClass)
        .uint32(10, stateClass).bool(12, disabledByDefault).uint32(13, category.value)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { !it.isNaN() }
        return ProtoWriter().fixed32(1, key, true).float(2, v ?: 0f).bool(3, v == null)
    }
    override fun shownState(): String? {
        val v = get()?.takeIf { !it.isNaN() } ?: return null
        return if (deviceClass == "timestamp") timestamp(v) else number(v, accuracy, unit)
    }
}

/**
 * A text sensor (`sensor` with text): read-only text.
 *
 * The common parameters are [ApiEntity]'s.
 * @param get The text now, or null for unknown.
 */
public class TextSensorEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> String?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_TEXT_SENSOR
    override val stateType: Int = Msg.TEXT_SENSOR_STATE
    override fun info(): ProtoWriter = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
    override fun shownState(): String? = get()
}

/**
 * A switch (`switch`): on or off, set from Home Assistant.
 *
 * The common parameters are [ApiEntity]'s.
 * @param deviceClass Home Assistant's switch device class (`switch`, `outlet`), or "".
 * @param get The state now.
 * @param set Called when Home Assistant turns it on or off.
 */
public class SwitchEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val deviceClass: String = "",
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_SWITCH
    override val stateType: Int = Msg.SWITCH_STATE
    override val commandType: Int = Msg.SWITCH_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).bool(7, disabledByDefault).uint32(8, category.value)
        .string(9, deviceClass)
    override fun state(): ProtoWriter = ProtoWriter().fixed32(1, key, true).bool(2, get())
    override fun command(msg: ProtoMessage): Unit = set(msg.bool(2))
    override fun shownState(): String? = onOff(get())
}

/**
 * A number (`number`): a value Home Assistant sets, from `min` to `max` in steps of `step`.
 *
 * The common parameters are [ApiEntity]'s.
 * @param min The lowest value.
 * @param max The highest value.
 * @param step The step between values; the decimals shown follow it.
 * @param unit The unit Home Assistant shows, or "".
 * @param mode `NumberMode`: 0 auto, 1 box, 2 slider.
 * @param deviceClass Home Assistant's number device class, or "".
 * @param get The value now, or null (or NaN) for unknown.
 * @param set Called with the value Home Assistant set, clamped to `min`..`max`.
 */
public class NumberEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    disabledByDefault: Boolean = false,
    private val min: Float, private val max: Float, private val step: Float,
    private val unit: String = "",
    private val mode: Int = 0,
    private val deviceClass: String = "",
    private val get: () -> Float?,
    private val set: (Float) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_NUMBER
    override val stateType: Int = Msg.NUMBER_STATE
    override val commandType: Int = Msg.NUMBER_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).float(6, min).float(7, max).float(8, step)
        .bool(9, disabledByDefault).uint32(10, category.value).string(11, unit).uint32(12, mode)
        .string(13, deviceClass)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { !it.isNaN() }
        return ProtoWriter().fixed32(1, key, true).float(2, v ?: 0f).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage): Unit = set(msg.float(2).coerceIn(min, max))
    override fun shownState(): String? = get()?.takeIf { !it.isNaN() }?.let { number(it, decimals(step), unit) }
}

/**
 * A select (`select`): one of [options], chosen in Home Assistant.
 *
 * The common parameters are [ApiEntity]'s.
 * @param get The option now, or null for unknown. A value outside [options] counts as unknown.
 * @param set Called with the option Home Assistant chose, always one of [options].
 */
public class SelectEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    disabledByDefault: Boolean = false,
    private val options: List<String>,
    private val get: () -> String?,
    private val set: (String) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_SELECT
    override val stateType: Int = Msg.SELECT_STATE
    override val commandType: Int = Msg.SELECT_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).strings(6, options).bool(7, disabledByDefault)
        .uint32(8, category.value)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { it in options }
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage) {
        val v = msg.string(2)
        if (v in options) set(v)
    }
    override fun shownState(): String? = get()?.takeIf { it in options }
}

/**
 * A text (`text`): a string Home Assistant sets.
 *
 * The common parameters are [ApiEntity]'s.
 * @param minLength The shortest text Home Assistant may set.
 * @param maxLength The longest text Home Assistant may set.
 * @param pattern A regular expression the text must match, or "" for any.
 * @param mode ESPHome's `TextMode`: 0 text, 1 password (Home Assistant masks the field, and
 *   [shownState] shows dots).
 * @param get The text now, or null for unknown.
 * @param set Called with a text that fits the limits; others are dropped.
 */
public class TextEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    disabledByDefault: Boolean = false,
    private val minLength: Int = 0, private val maxLength: Int = 255, private val pattern: String = "",
    private val mode: Int = 0,
    private val get: () -> String?,
    private val set: (String) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_TEXT
    override val stateType: Int = Msg.TEXT_STATE
    override val commandType: Int = Msg.TEXT_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .uint32(8, minLength).uint32(9, maxLength).string(10, pattern).uint32(11, mode)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage) {
        val v = msg.string(2)
        if (v.length in minLength..maxLength && (pattern.isEmpty() || Regex(pattern).matches(v))) set(v)
    }
    override fun shownState(): String? = get()?.let { if (mode == 1) "••••••" else it }
}

/**
 * A button (`button`): Home Assistant presses it; it has no state.
 *
 * The common parameters are [ApiEntity]'s.
 * @param deviceClass Home Assistant's button device class (`restart`, `update`, `identify`), or "".
 * @param press Called on each press.
 */
public class ButtonEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val deviceClass: String = "",
    private val press: () -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_BUTTON
    override val commandType: Int = Msg.BUTTON_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass)
    override fun command(msg: ProtoMessage): Unit = press()
}

/**
 * An event entity (`ListEntitiesEventResponse` 107 / `EventResponse` 108). Events have no state:
 * nothing is sent on subscribe; [ApiServer.sendEvent] pushes one `EventResponse` per occurrence and
 * Home Assistant fires `event_type` (which must be one of [eventTypes]).
 *
 * The common parameters are [ApiEntity]'s.
 * @param deviceClass Home Assistant's event device class (`button`, `doorbell`, `motion`), or "".
 * @property eventTypes Every event type it can fire.
 */
public class EventEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val deviceClass: String = "",
    public val eventTypes: List<String>,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_EVENT
    override val stateType: Int = Msg.EVENT_RESPONSE
    override fun info(): ProtoWriter = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass).strings(9, eventTypes)
    internal fun event(type: String): ProtoWriter = ProtoWriter().fixed32(1, key, true).string(2, type)
}

/**
 * The state of an [UpdateEntity]: the `UpdateStateResponse` fields (api.proto 117).
 *
 * @property missingState The state is unknown; Home Assistant ignores the other fields.
 * @property inProgress An install is running.
 * @property hasProgress [progress] means something.
 * @property progress How far the install is, in percent (0 to 100).
 * @property currentVersion The installed version.
 * @property latestVersion The newest version; Home Assistant offers an update when it differs.
 * @property title The release's title.
 * @property releaseSummary A short summary of the release, shown in Home Assistant's update dialog.
 * @property releaseUrl A link to the full release notes.
 */
public data class UpdateStateData(
    public val missingState: Boolean,
    public val inProgress: Boolean,
    public val hasProgress: Boolean,
    public val progress: Float,
    public val currentVersion: String,
    public val latestVersion: String,
    public val title: String,
    public val releaseSummary: String,
    public val releaseUrl: String,
)

/**
 * An update entity (`ListEntitiesUpdateResponse` 116 / `UpdateStateResponse` 117 /
 * `UpdateCommandRequest` 118). HA's ESPHome update platform offers install, progress and the
 * release summary/URL; [command] gets the `UpdateCommand` (1 install, 2 check).
 *
 * The common parameters are [ApiEntity]'s.
 * @param deviceClass Home Assistant's update device class, `firmware` by default.
 * @param get The state now.
 * @param onCommand Called with the `UpdateCommand`: 1 install, 2 check for updates.
 */
public class UpdateEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    disabledByDefault: Boolean = false,
    private val deviceClass: String = "firmware",
    private val get: () -> UpdateStateData,
    private val onCommand: (Int) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_UPDATE
    override val stateType: Int = Msg.UPDATE_STATE
    override val commandType: Int = Msg.UPDATE_COMMAND
    override fun info(): ProtoWriter = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass)
    override fun state(): ProtoWriter {
        val s = get()
        return ProtoWriter().fixed32(1, key, true).bool(2, s.missingState).bool(3, s.inProgress)
            .bool(4, s.hasProgress).float(5, s.progress).string(6, s.currentVersion).string(7, s.latestVersion)
            .string(8, s.title).string(9, s.releaseSummary).string(10, s.releaseUrl)
    }
    override fun command(msg: ProtoMessage): Unit = onCommand(msg.uint32(2))
}

/**
 * A light (`light`) with on/off and brightness only (`COLOR_MODE_BRIGHTNESS`), such as a screen's
 * backlight.
 *
 * @param objectId See [ApiEntity.objectId].
 * @param name See [ApiEntity.name].
 * @param icon See [ApiEntity.icon].
 * @param category See [ApiEntity.category]. Home Assistant leaves a light with a category out of area,
 *   floor, device and label targets (a room's "lights off") and out of Assist.
 * @param disabledByDefault See [ApiEntity.disabledByDefault].
 * @param get On or off, and the brightness from 0 to 1.
 * @param set Called with the new on/off state and brightness (0 to 1); null for either one that
 *   didn't change.
 */
public class BrightnessLightEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> Pair<Boolean, Float>,
    private val set: (Boolean?, Float?) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType: Int = Msg.LIST_ENTITIES_LIGHT
    override val stateType: Int = Msg.LIGHT_STATE
    override val commandType: Int = Msg.LIGHT_COMMAND
    override fun info(): ProtoWriter = base().uint32(12, COLOR_MODE_BRIGHTNESS).bool(13, disabledByDefault)
        .string(14, icon).uint32(15, category.value)
    override fun state(): ProtoWriter {
        val (on, b) = get()
        // Round so tiny auto-brightness changes don't spam Home Assistant.
        val rounded = Math.round(b.coerceIn(0f, 1f) * 100) / 100f
        return ProtoWriter().fixed32(1, key, true).bool(2, on).float(3, rounded)
            .uint32(11, COLOR_MODE_BRIGHTNESS)
    }
    override fun command(msg: ProtoMessage) {
        set(if (msg.bool(2)) msg.bool(3) else null, if (msg.bool(4)) msg.float(5).coerceIn(0f, 1f) else null)
    }

    private companion object { const val COLOR_MODE_BRIGHTNESS = 3 }
}

/** `MediaPlayerState` (api.proto): the [MediaPlayerStateData.state] values this library uses. */
public object MediaState {
    /** Nothing is playing. */
    public const val IDLE: Int = 1
    /** Playing. */
    public const val PLAYING: Int = 2
    /** Paused. */
    public const val PAUSED: Int = 3
    /** Playing an announcement. */
    public const val ANNOUNCING: Int = 4
}

/** `MediaPlayerCommand` (api.proto): the [MediaPlayerCommandData.command] values this library knows. */
public object MediaCommand {
    /** Play, or resume. */
    public const val PLAY: Int = 0
    /** Pause. */
    public const val PAUSE: Int = 1
    /** Stop. */
    public const val STOP: Int = 2
    /** Mute. */
    public const val MUTE: Int = 3
    /** Unmute. */
    public const val UNMUTE: Int = 4
    /** Play if paused, pause if playing. */
    public const val TOGGLE: Int = 5
    /** One step louder. */
    public const val VOLUME_UP: Int = 6
    /** One step quieter. */
    public const val VOLUME_DOWN: Int = 7
    /** Add the media to the queue instead of playing it now. */
    public const val ENQUEUE: Int = 8
    /** Empty the queue. */
    public const val CLEAR_PLAYLIST: Int = 11
}

/**
 * A `MediaPlayerCommandRequest` from Home Assistant, decoded. Each field is null when the request
 * doesn't set it.
 *
 * @property command A [MediaCommand] value.
 * @property volume The volume to set, from 0 to 1.
 * @property mediaUrl The URL of the media to play.
 * @property announcement The media is an announcement: play it over what is playing, then resume.
 */
public data class MediaPlayerCommandData(
    public val command: Int?,
    public val volume: Float?,
    public val mediaUrl: String?,
    public val announcement: Boolean,
)

/**
 * The state of a [MediaPlayerEntity].
 *
 * @property state A [MediaState] value.
 * @property volume The volume, from 0 to 1.
 * @property muted Whether it is muted.
 */
public data class MediaPlayerStateData(public val state: Int, public val volume: Float, public val muted: Boolean)

/**
 * A media player (`media_player`). Home Assistant sends media URLs and commands; the device plays.
 *
 * @param objectId See [ApiEntity.objectId].
 * @param name See [ApiEntity.name].
 * @param icon See [ApiEntity.icon].
 * @param formats The audio formats it plays, as `MediaPlayerSupportedFormat`s: (format such as `flac`,
 *   purpose: 0 default, 1 announcement).
 * @param featureFlags The `FEATURE_*` flags of what it supports.
 * @param get The state now.
 * @param handle Called with each command.
 */
public class MediaPlayerEntity(
    objectId: String, name: String, icon: String = "",
    private val formats: List<Pair<String, Int>>,
    private val featureFlags: Int,
    private val get: () -> MediaPlayerStateData,
    private val handle: (MediaPlayerCommandData) -> Unit,
) : ApiEntity(objectId, name, icon) {
    override val listType: Int = Msg.LIST_ENTITIES_MEDIA_PLAYER
    override val stateType: Int = Msg.MEDIA_PLAYER_STATE
    override val commandType: Int = Msg.MEDIA_PLAYER_COMMAND
    override fun info(): ProtoWriter {
        val w = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
            .bool(8, featureFlags and FEATURE_PAUSE != 0)
        for ((format, purpose) in formats) w.message(9, ProtoWriter().string(1, format).uint32(4, purpose))
        return w.uint32(11, featureFlags)
    }
    override fun state(): ProtoWriter {
        val s = get()
        return ProtoWriter().fixed32(1, key, true).uint32(2, s.state)
            .float(3, Math.round(s.volume * 100) / 100f).bool(4, s.muted)
    }
    override fun command(msg: ProtoMessage): Unit = handle(
        MediaPlayerCommandData(
            command = if (msg.bool(2)) msg.uint32(3) else null,
            volume = if (msg.bool(4)) msg.float(5) else null,
            mediaUrl = if (msg.bool(6)) msg.string(7) else null,
            announcement = msg.bool(8) && msg.bool(9),
        )
    )

    /** `MediaPlayerEntityFeature` flags (aioesphomeapi model.py), for `featureFlags`. */
    public companion object {
        /** Pause and resume. */
        public const val FEATURE_PAUSE: Int = 1 shl 0
        /** Set the volume. */
        public const val FEATURE_VOLUME_SET: Int = 1 shl 2
        /** Mute and unmute. */
        public const val FEATURE_VOLUME_MUTE: Int = 1 shl 3
        /** Play a media URL. */
        public const val FEATURE_PLAY_MEDIA: Int = 1 shl 9
        /** Volume up and down in steps. */
        public const val FEATURE_VOLUME_STEP: Int = 1 shl 10
        /** Stop. */
        public const val FEATURE_STOP: Int = 1 shl 12
        /** Play. */
        public const val FEATURE_PLAY: Int = 1 shl 14
        /** Browse media in Home Assistant's media browser. */
        public const val FEATURE_BROWSE_MEDIA: Int = 1 shl 17
        /** Play announcements over what is playing. */
        public const val FEATURE_MEDIA_ANNOUNCE: Int = 1 shl 20
    }
}

/** `On` or `Off`, as Home Assistant shows a switch or a binary sensor. */
private fun onOff(v: Boolean) = if (v) "On" else "Off"

/** [v] with [decimals] places and its unit after a space (`51.5 °C`, `23 %`), as Home Assistant shows it. */
private fun number(v: Float, decimals: Int, unit: String): String {
    val n = String.format(java.util.Locale.ROOT, "%.${decimals.coerceIn(0, 6)}f", v)
    return if (unit.isEmpty()) n else "$n $unit"
}

/** The places a number with this [step] needs: 0.01 has two. */
private fun decimals(step: Float): Int =
    java.math.BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 6)

/** A timestamp sensor's Unix seconds as the device's local date and time (`2026-11-10 09:12`). */
private fun timestamp(seconds: Float): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(seconds.toLong() * 1000))
