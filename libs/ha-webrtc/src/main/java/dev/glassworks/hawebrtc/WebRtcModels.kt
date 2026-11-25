package dev.glassworks.hawebrtc

import org.json.JSONArray
import org.json.JSONObject

/**
 * `camera/webrtc/get_client_config`'s result: what the peer connection is made with. HA builds it from the camera's
 * WebRTC provider (go2rtc adds its STUN servers, Nest and others their own) as webrtc-models' `RTCConfiguration`, plus
 * the name of a data channel some cameras need.
 */
public data class WebRtcClientConfig(
    /** The STUN and TURN servers (`configuration.iceServers`). Credentials: never log them. */
    public val iceServers: List<IceServer> = emptyList(),
    /** `configuration.iceTransportPolicy`: `"relay"` uses TURN only, null or `"all"` every candidate. */
    public val iceTransportPolicy: String? = null,
    /** `dataChannel`: when set, the offer opens a data channel of this name, as HA's frontend does. */
    public val dataChannel: String? = null,
) {
    /** One `RTCIceServer`. */
    public data class IceServer(
        /** `urls`: one or more `stun:`, `turn:` or `turns:` URLs. */
        public val urls: List<String>,
        /** TURN `username`, if any. */
        public val username: String? = null,
        /** TURN `credential`, if any. */
        public val credential: String? = null,
    ) {
        /** Leaves the credential out, so a config can be logged. */
        override fun toString(): String = "IceServer(urls=$urls, username=${if (username == null) "null" else "…"}, credential=${if (credential == null) "null" else "…"})"
    }

    /** Reading HA's configuration. */
    public companion object {
        /**
         * Reads `camera/webrtc/get_client_config`'s result (HA's `WebRTCClientConfiguration.to_frontend_dict()`). A
         * server without `urls` is skipped; anything else unknown is ignored.
         */
        @JvmStatic
        public fun fromJson(result: JSONObject): WebRtcClientConfig {
            val configuration = result.optJSONObject("configuration") ?: JSONObject()
            val servers = configuration.optJSONArray("iceServers") ?: JSONArray()
            val iceServers = (0 until servers.length()).mapNotNull { i ->
                val s = servers.optJSONObject(i) ?: return@mapNotNull null
                val urls = when (val u = s.opt("urls")) {
                    is String -> listOf(u)
                    is JSONArray -> (0 until u.length()).mapNotNull { j -> u.opt(j) as? String }
                    else -> emptyList()
                }.filter { it.isNotBlank() }
                if (urls.isEmpty()) null else IceServer(urls, s.optStringOrNull("username"), s.optStringOrNull("credential"))
            }
            return WebRtcClientConfig(iceServers, configuration.optStringOrNull("iceTransportPolicy"), result.optStringOrNull("dataChannel"))
        }
    }
}

/**
 * One ICE candidate as `RTCIceCandidateInit`: what `camera/webrtc/candidate` takes and what a `candidate` event of
 * `camera/webrtc/offer` carries.
 */
public data class IceCandidateInit(
    /** The candidate line, `candidate:…` without `a=`. Empty means the end of candidates. */
    public val candidate: String,
    /** The media section's `mid`, if known. */
    public val sdpMid: String? = null,
    /** The media section's index, if known. */
    public val sdpMLineIndex: Int? = null,
    /** The ICE username fragment, if known. */
    public val usernameFragment: String? = null,
) {
    /** As HA reads it (webrtc-models' `RTCIceCandidateInit.from_dict`): only the fields that are set. */
    public fun toJson(): JSONObject = JSONObject().apply {
        put("candidate", candidate)
        sdpMid?.let { put("sdpMid", it) }
        sdpMLineIndex?.let { put("sdpMLineIndex", it) }
        usernameFragment?.let { put("usernameFragment", it) }
    }

    /** Reading HA's candidates. */
    public companion object {
        /** Reads a `candidate` from HA, or null when it has no `candidate` string. */
        @JvmStatic
        public fun fromJson(o: JSONObject?): IceCandidateInit? {
            val candidate = o?.opt("candidate") as? String ?: return null
            val index = o.opt("sdpMLineIndex").let { if (it is Number) it.toInt() else null }
            return IceCandidateInit(candidate, o.optStringOrNull("sdpMid"), index, o.optStringOrNull("usernameFragment"))
        }
    }
}

/** A string field, or null when it is missing or JSON null (org.json's optString would give "null" or ""). */
internal fun JSONObject.optStringOrNull(key: String): String? = opt(key) as? String
