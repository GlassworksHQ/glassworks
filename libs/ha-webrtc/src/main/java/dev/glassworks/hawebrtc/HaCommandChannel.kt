package dev.glassworks.hawebrtc

import org.json.JSONObject

/**
 * A Home Assistant WebSocket connection, as this library needs it: one command and its result, or one subscription
 * and its events. Any HA WebSocket client plugs in by implementing it (Glassworks' Vitro engine maps its dashboard
 * connection onto it); the tests use a fake one.
 *
 * Commands are HA's own messages without an `id`: the channel numbers them, sends them and matches the answers.
 * Callbacks may come on any thread. [HaWebRtcSignaling] and [HaWebRtcStream] take care of their own threading.
 */
public interface HaCommandChannel {
    /**
     * Sends [command] and answers once: [onResult] with the `result` of a successful answer (a `JSONObject`,
     * `JSONArray`, a plain value, or null), or [onError] when HA refuses it, it can't be sent or the connection is
     * lost before the answer.
     */
    public fun call(command: JSONObject, onResult: (result: Any?) -> Unit, onError: (HaError) -> Unit)

    /**
     * Subscribes with [command] (`camera/webrtc/offer`): each `event` of the subscription reaches [onEvent] until the
     * returned [Subscription] is cancelled. [onError] runs at most once, when HA refuses the command or it can't be
     * sent; once cancelled, neither callback is called again.
     */
    public fun subscribe(command: JSONObject, onEvent: (event: JSONObject) -> Unit, onError: (HaError) -> Unit): Subscription

    /** A running subscription. */
    public fun interface Subscription {
        /** Ends the subscription (HA's `unsubscribe_events`). Calling it again does nothing. */
        public fun unsubscribe()
    }
}

/**
 * An error from Home Assistant (its `error.code` and `error.message`), or from the connection, with a code of the
 * channel's own (for example [NOT_CONNECTED]).
 */
public data class HaError(
    /** HA's error code: `webrtc_offer_failed`, `not_found`, `home_assistant_error`, … */
    public val code: String,
    /** HA's message, for logs and the UI. */
    public val message: String,
) {
    /** Error codes of the channel's own, and reading HA's `error` object. */
    public companion object {
        /** The channel has no connection to Home Assistant, or lost it before the answer. */
        public const val NOT_CONNECTED: String = "not_connected"

        /** An answer this library couldn't read (a missing field, the wrong type). */
        public const val BAD_ANSWER: String = "bad_answer"

        /** HA's `error` object as an [HaError]; null or an empty object gives [fallbackCode]. */
        @JvmStatic
        public fun fromJson(error: JSONObject?, fallbackCode: String = NOT_CONNECTED): HaError = HaError(
            error?.optString("code")?.takeIf { it.isNotEmpty() } ?: fallbackCode,
            error?.optString("message").orEmpty(),
        )
    }
}
