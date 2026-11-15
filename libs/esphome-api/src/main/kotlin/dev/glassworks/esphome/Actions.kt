package dev.glassworks.esphome

/**
 * `ServiceArgType` (api.proto): the type of one action argument. It decides which [ActionCall] getter
 * reads the argument: [BOOL] with [ActionCall.bool], [INT_ARRAY] with [ActionCall.ints], and so on.
 */
public enum class ActionArgType(internal val value: Int) {
    /** `true` or `false`. */
    BOOL(0),
    /** A 32-bit integer. */
    INT(1),
    /** A 32-bit float. */
    FLOAT(2),
    /** Text. */
    STRING(3),
    /** A list of booleans. */
    BOOL_ARRAY(4),
    /** A list of 32-bit integers. */
    INT_ARRAY(5),
    /** A list of 32-bit floats. */
    FLOAT_ARRAY(6),
    /** A list of strings. */
    STRING_ARRAY(7),
}

/**
 * One argument of an [ApiAction]. Home Assistant makes every argument required.
 *
 * @property name The argument's name in Home Assistant's action call, and in [ActionCall.args].
 * @property type What the value is.
 */
public data class ActionArg(public val name: String, public val type: ActionArgType)

/**
 * `SupportsResponseType` (api.proto): whether Home Assistant waits for the action's result.
 * [NONE] fires and forgets. [STATUS] waits for success or the error. [OPTIONAL] also returns the
 * action's JSON when the caller asks for it (`response_variable`), and [ONLY] always does.
 */
public enum class ActionResponse(internal val value: Int) {
    /** Home Assistant doesn't wait: no result, and a failure goes unseen. */
    NONE(0),
    /** Home Assistant waits, and returns the handler's JSON only when the caller asks for it. */
    OPTIONAL(1),
    /** Home Assistant waits and always returns the handler's JSON. */
    ONLY(2),
    /** Home Assistant waits for success or the error message, without data. */
    STATUS(100),
}

/**
 * One call of an [ApiAction], with the arguments by name. A value is a `Boolean`, `Int`, `Float`,
 * `String` or a `List` of one of them, as the argument's [ActionArgType] says. The server makes these;
 * a test can make one to call a handler directly.
 *
 * The getters throw [IllegalArgumentException] when the action has no argument by that name, or it
 * has another type.
 *
 * @property action The action that was called.
 * @property args The arguments, by name.
 * @property returnResponse Home Assistant wants the JSON the handler returns.
 */
public class ActionCall(public val action: ApiAction, public val args: Map<String, Any>, public val returnResponse: Boolean) {
    /** The [ActionArgType.BOOL] argument [name]. */
    public fun bool(name: String): Boolean = arg(name)
    /** The [ActionArgType.INT] argument [name]. */
    public fun int(name: String): Int = arg(name)
    /** The [ActionArgType.FLOAT] argument [name]. */
    public fun float(name: String): Float = arg(name)
    /** The [ActionArgType.STRING] argument [name]. */
    public fun string(name: String): String = arg(name)
    /** The [ActionArgType.BOOL_ARRAY] argument [name]. */
    public fun bools(name: String): List<Boolean> = arg(name)
    /** The [ActionArgType.INT_ARRAY] argument [name]. */
    public fun ints(name: String): List<Int> = arg(name)
    /** The [ActionArgType.FLOAT_ARRAY] argument [name]. */
    public fun floats(name: String): List<Float> = arg(name)
    /** The [ActionArgType.STRING_ARRAY] argument [name]. */
    public fun strings(name: String): List<String> = arg(name)

    private inline fun <reified T> arg(name: String): T {
        val v = args[name] ?: throw IllegalArgumentException("${action.name} has no argument '$name'")
        return v as? T ?: throw IllegalArgumentException("${action.name}.$name is ${v::class.simpleName}")
    }
}

/**
 * A user-defined action (an ESPHome `api: actions:` entry). Home Assistant registers it as
 * `esphome.<node name, '-' → '_'>_<name>` and calls it with every argument in [args].
 *
 * [run] gets each call on the server's action thread, one call at a time, in the order they
 * arrived. It returns a JSON object as text, which Home Assistant gets back when [supportsResponse]
 * is [ActionResponse.OPTIONAL] or [ActionResponse.ONLY], or null for none. To fail the call, throw:
 * Home Assistant shows the exception's message (for every [supportsResponse] except NONE).
 *
 * Wire format: `ListEntitiesServicesResponse` (41), `ExecuteServiceRequest` (42) and
 * `ExecuteServiceResponse` (131) in aioesphomeapi 46.25.0's api.proto. The key is FNV-1a of [name], like
 * this library's other entities. ESPHome itself hashes user services with FNV-1; that doesn't matter
 * because Home Assistant echoes back whatever key the device reports.
 *
 * @param name The action's name, lowercase snake_case. It is also the entity's object id.
 * @property args The arguments Home Assistant passes, all required.
 * @property supportsResponse Whether Home Assistant waits for the result, and gets the JSON.
 * @param run The handler. Returns JSON text or null; throws to fail the call.
 */
public class ApiAction(
    name: String,
    public val args: List<ActionArg> = emptyList(),
    public val supportsResponse: ActionResponse = ActionResponse.NONE,
    private val run: (ActionCall) -> String?,
) : ApiEntity(name, name) {
    init {
        require(NAME.matches(name)) { "action name '$name' must be lowercase snake_case" }
        require(args.map { it.name }.toSet().size == args.size) { "duplicate argument names in $name" }
    }

    override val listType: Int = Msg.LIST_ENTITIES_SERVICES
    override val commandType: Int = Msg.EXECUTE_SERVICE_REQUEST

    override fun info(): ProtoWriter {
        val w = ProtoWriter().string(1, name, force = true).fixed32(2, key, force = true)
        for (a in args) w.message(3, ProtoWriter().string(1, a.name).uint32(2, a.type.value))
        return w.uint32(4, supportsResponse.value)
    }

    /** Decodes an `ExecuteServiceRequest`. Throws [IllegalArgumentException] if the arguments don't match. */
    internal fun decode(msg: ProtoMessage): ActionCall {
        val sent = msg.messages(2)
        require(sent.size == args.size) { "$name takes ${args.size} argument(s), got ${sent.size}" }
        val values = LinkedHashMap<String, Any>()
        for ((a, m) in args.zip(sent)) values[a.name] = decodeArg(a.type, m)
        return ActionCall(this, values, msg.bool(4))
    }

    /** Runs the handler and builds the `ExecuteServiceResponse` for [callId]. */
    internal fun execute(call: ActionCall, callId: Int): ProtoWriter {
        val result = runCatching { run(call) }
        val w = ProtoWriter().uint32(1, callId)
        result.onFailure { t ->
            EspHomeLog.w("action $name failed", t)
            return w.string(3, t.message ?: t::class.simpleName ?: "failed")
        }
        w.bool(2, true)
        val data = result.getOrNull()
        val wantsData = call.returnResponse &&
            (supportsResponse == ActionResponse.OPTIONAL || supportsResponse == ActionResponse.ONLY)
        if (wantsData && data != null) w.bytes(4, data.toByteArray(Charsets.UTF_8))
        return w
    }

    private companion object {
        private val NAME = Regex("[a-z0-9_]+")

        /** `ExecuteServiceArgument`: `int_` (sint32, API 1.3+) wins over `legacy_int` (int32). */
        private fun decodeArg(type: ActionArgType, m: ProtoMessage): Any = when (type) {
            ActionArgType.BOOL -> m.bool(1)
            ActionArgType.INT -> if (m.has(5)) ProtoMessage.zigzag(m.long(5)) else m.long(2).toInt()
            ActionArgType.FLOAT -> m.float(3)
            ActionArgType.STRING -> m.string(4)
            ActionArgType.BOOL_ARRAY -> m.longs(6).map { it != 0L }
            ActionArgType.INT_ARRAY -> m.longs(7).map(ProtoMessage::zigzag)
            ActionArgType.FLOAT_ARRAY -> m.fixed32s(8).map { Float.fromBits(it) }
            ActionArgType.STRING_ARRAY -> m.strings(9)
        }
    }
}
