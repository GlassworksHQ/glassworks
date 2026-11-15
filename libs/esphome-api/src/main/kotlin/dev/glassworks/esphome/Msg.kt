package dev.glassworks.esphome

/**
 * ESPHome native API message type ids (`option (id)` in aioesphomeapi `api.proto`, checked against
 * aioesphomeapi v46.25.0, the version pinned by Home Assistant 2026.11.0).
 */
public object Msg {
    /** `HelloRequest`. */
    public const val HELLO_REQUEST: Int = 1
    /** `HelloResponse`. */
    public const val HELLO_RESPONSE: Int = 2
    /** `AuthenticationRequest`. */
    public const val AUTHENTICATION_REQUEST: Int = 3
    /** `DisconnectRequest`. */
    public const val DISCONNECT_REQUEST: Int = 5
    /** `DisconnectResponse`. */
    public const val DISCONNECT_RESPONSE: Int = 6
    /** `PingRequest`. */
    public const val PING_REQUEST: Int = 7
    /** `PingResponse`. */
    public const val PING_RESPONSE: Int = 8
    /** `DeviceInfoRequest`. */
    public const val DEVICE_INFO_REQUEST: Int = 9
    /** `DeviceInfoResponse`. */
    public const val DEVICE_INFO_RESPONSE: Int = 10
    /** `ListEntitiesRequest`. */
    public const val LIST_ENTITIES_REQUEST: Int = 11
    /** `ListEntitiesBinarySensorResponse`. */
    public const val LIST_ENTITIES_BINARY_SENSOR: Int = 12
    /** `ListEntitiesLightResponse`. */
    public const val LIST_ENTITIES_LIGHT: Int = 15
    /** `ListEntitiesSensorResponse`. */
    public const val LIST_ENTITIES_SENSOR: Int = 16
    /** `ListEntitiesSwitchResponse`. */
    public const val LIST_ENTITIES_SWITCH: Int = 17
    /** `ListEntitiesTextSensorResponse`. */
    public const val LIST_ENTITIES_TEXT_SENSOR: Int = 18
    /** `ListEntitiesDoneResponse`. */
    public const val LIST_ENTITIES_DONE: Int = 19
    /** `SubscribeStatesRequest`. */
    public const val SUBSCRIBE_STATES_REQUEST: Int = 20
    /** `BinarySensorStateResponse`. */
    public const val BINARY_SENSOR_STATE: Int = 21
    /** `LightStateResponse`. */
    public const val LIGHT_STATE: Int = 24
    /** `SensorStateResponse`. */
    public const val SENSOR_STATE: Int = 25
    /** `SwitchStateResponse`. */
    public const val SWITCH_STATE: Int = 26
    /** `TextSensorStateResponse`. */
    public const val TEXT_SENSOR_STATE: Int = 27
    /** `SubscribeLogsRequest`. */
    public const val SUBSCRIBE_LOGS_REQUEST: Int = 28
    /** `LightCommandRequest`. */
    public const val LIGHT_COMMAND: Int = 32
    /** `SwitchCommandRequest`. */
    public const val SWITCH_COMMAND: Int = 33
    /** `SubscribeHomeassistantServicesRequest`. */
    public const val SUBSCRIBE_HA_SERVICES_REQUEST: Int = 34
    /** `HomeassistantActionRequest`. */
    public const val HOMEASSISTANT_ACTION_REQUEST: Int = 35
    /** `GetTimeRequest`. */
    public const val GET_TIME_REQUEST: Int = 36
    /** `GetTimeResponse`. */
    public const val GET_TIME_RESPONSE: Int = 37
    /** `SubscribeHomeAssistantStatesRequest`. */
    public const val SUBSCRIBE_HA_STATES_REQUEST: Int = 38
    /** `ListEntitiesServicesResponse`. */
    public const val LIST_ENTITIES_SERVICES: Int = 41
    /** `ExecuteServiceRequest`. */
    public const val EXECUTE_SERVICE_REQUEST: Int = 42
    /** `ListEntitiesCameraResponse`. */
    public const val LIST_ENTITIES_CAMERA: Int = 43
    /** `CameraImageResponse`. */
    public const val CAMERA_IMAGE_RESPONSE: Int = 44
    /** `CameraImageRequest`. */
    public const val CAMERA_IMAGE_REQUEST: Int = 45
    /** `ListEntitiesNumberResponse`. */
    public const val LIST_ENTITIES_NUMBER: Int = 49
    /** `NumberStateResponse`. */
    public const val NUMBER_STATE: Int = 50
    /** `NumberCommandRequest`. */
    public const val NUMBER_COMMAND: Int = 51
    /** `ListEntitiesSelectResponse`. */
    public const val LIST_ENTITIES_SELECT: Int = 52
    /** `SelectStateResponse`. */
    public const val SELECT_STATE: Int = 53
    /** `SelectCommandRequest`. */
    public const val SELECT_COMMAND: Int = 54
    /** `ListEntitiesButtonResponse`. */
    public const val LIST_ENTITIES_BUTTON: Int = 61
    /** `ButtonCommandRequest`. */
    public const val BUTTON_COMMAND: Int = 62
    /** `ListEntitiesMediaPlayerResponse`. */
    public const val LIST_ENTITIES_MEDIA_PLAYER: Int = 63
    /** `MediaPlayerStateResponse`. */
    public const val MEDIA_PLAYER_STATE: Int = 64
    /** `MediaPlayerCommandRequest`. */
    public const val MEDIA_PLAYER_COMMAND: Int = 65
    /** `SubscribeVoiceAssistantRequest`. */
    public const val SUBSCRIBE_VOICE_ASSISTANT_REQUEST: Int = 89
    /** `VoiceAssistantRequest`. */
    public const val VOICE_ASSISTANT_REQUEST: Int = 90
    /** `VoiceAssistantResponse`. */
    public const val VOICE_ASSISTANT_RESPONSE: Int = 91
    /** `VoiceAssistantEventResponse`. */
    public const val VOICE_ASSISTANT_EVENT_RESPONSE: Int = 92
    /** `ListEntitiesTextResponse`. */
    public const val LIST_ENTITIES_TEXT: Int = 97
    /** `TextStateResponse`. */
    public const val TEXT_STATE: Int = 98
    /** `TextCommandRequest`. */
    public const val TEXT_COMMAND: Int = 99
    /** `VoiceAssistantAudio`. */
    public const val VOICE_ASSISTANT_AUDIO: Int = 106
    /** `ListEntitiesEventResponse`. */
    public const val LIST_ENTITIES_EVENT: Int = 107
    /** `EventResponse`. */
    public const val EVENT_RESPONSE: Int = 108
    /** `VoiceAssistantTimerEventResponse`. */
    public const val VOICE_ASSISTANT_TIMER_EVENT: Int = 115
    /** `ListEntitiesUpdateResponse`. */
    public const val LIST_ENTITIES_UPDATE: Int = 116
    /** `UpdateStateResponse`. */
    public const val UPDATE_STATE: Int = 117
    /** `UpdateCommandRequest`. */
    public const val UPDATE_COMMAND: Int = 118
    /** `VoiceAssistantAnnounceRequest`. */
    public const val VOICE_ASSISTANT_ANNOUNCE_REQUEST: Int = 119
    /** `VoiceAssistantAnnounceFinished`. */
    public const val VOICE_ASSISTANT_ANNOUNCE_FINISHED: Int = 120
    /** `VoiceAssistantConfigurationRequest`. */
    public const val VOICE_ASSISTANT_CONFIGURATION_REQUEST: Int = 121
    /** `VoiceAssistantConfigurationResponse`. */
    public const val VOICE_ASSISTANT_CONFIGURATION_RESPONSE: Int = 122
    /** `VoiceAssistantSetConfiguration`. */
    public const val VOICE_ASSISTANT_SET_CONFIGURATION: Int = 123
    /** `NoiseEncryptionSetKeyRequest`. */
    public const val NOISE_ENCRYPTION_SET_KEY_REQUEST: Int = 124
    /** `NoiseEncryptionSetKeyResponse`. */
    public const val NOISE_ENCRYPTION_SET_KEY_RESPONSE: Int = 125
    /** `ExecuteServiceResponse`. */
    public const val EXECUTE_SERVICE_RESPONSE: Int = 131
    /** `DeviceCapabilitiesRequest`. */
    public const val DEVICE_CAPABILITIES_REQUEST: Int = 149
    /** `DeviceCapabilitiesResponse`. */
    public const val DEVICE_CAPABILITIES_RESPONSE: Int = 150
    /** `SendspinPairingTokenRequest`. */
    public const val SENDSPIN_PAIRING_TOKEN_REQUEST: Int = 159
    /** `SendspinPairingTokenResponse`. */
    public const val SENDSPIN_PAIRING_TOKEN_RESPONSE: Int = 160
}

/**
 * Sendspin over the native API: the `SendspinFeature` flag for `DeviceCapabilitiesResponse` and the
 * `SendspinPairingTokenStatus` values (aioesphomeapi 46.25.0 model.py and api.proto).
 * [SendspinTokenSource.pairingToken] returns one of the `STATUS_*` values.
 */
public object SendspinApi {
    /** The device hands Home Assistant a Sendspin pairing token. */
    public const val FEATURE_PAIRING_TOKEN: Int = 1 shl 0
    /** The Sendspin client hasn't started yet, so there is no token. Home Assistant asks again later. */
    public const val STATUS_NOT_READY: Int = 0
    /** The token is in the response. */
    public const val STATUS_OK: Int = 1
    /** The device has no API key, so it won't send the token. The server answers this by itself. */
    public const val STATUS_ENCRYPTION_REQUIRED: Int = 2
    /** Sendspin is off and hasn't started since boot. */
    public const val STATUS_DISABLED: Int = 3
    /** The Sendspin client failed to start and won't start until the device reboots. */
    public const val STATUS_FAILED: Int = 4
}

/**
 * `VoiceAssistantFeature` flags (aioesphomeapi model.py, ESPHome voice_assistant.h), for
 * [DeviceIdentity.voiceFeatures]. Home Assistant offers a voice satellite only when [VOICE_ASSISTANT]
 * is set.
 */
public object VoiceFeature {
    /** The device is a voice satellite. */
    public const val VOICE_ASSISTANT: Int = 1 shl 0
    /** The device plays the reply audio itself. */
    public const val SPEAKER: Int = 1 shl 1
    /** Audio goes over the API connection (`VoiceAssistantAudio`), not UDP. */
    public const val API_AUDIO: Int = 1 shl 2
    /** The device shows timers (`VoiceAssistantTimerEventResponse`). */
    public const val TIMERS: Int = 1 shl 3
    /** The device plays announcements (`VoiceAssistantAnnounceRequest`). */
    public const val ANNOUNCE: Int = 1 shl 4
    /** An announcement can start a conversation (listen right after it). */
    public const val START_CONVERSATION: Int = 1 shl 5
}

/** `VoiceAssistantRequestFlag` (api.proto): the `flags` of a `VoiceAssistantRequest`. */
public object VoiceRequestFlag {
    /** Asks for voice activity detection. */
    public const val USE_VAD: Int = 1
    /** Starts the pipeline at wake word detection: Home Assistant listens for the wake word first. */
    public const val USE_WAKE_WORD: Int = 2
}

/**
 * `VoiceAssistantEvent` (api.proto): the `event_type` that [VoiceAssistantHandler.onEvent] gets.
 * Each name is the proto value without its `VOICE_ASSISTANT_` prefix.
 */
public object VoiceEventType {
    /** The pipeline failed; `code` and `message` are in the data. */
    public const val ERROR: Int = 0
    /** A pipeline run started. */
    public const val RUN_START: Int = 1
    /** The run ended, after an error too. */
    public const val RUN_END: Int = 2
    /** Speech-to-text started. */
    public const val STT_START: Int = 3
    /** Speech-to-text finished; the text is under `text`. */
    public const val STT_END: Int = 4
    /** Intent recognition started. */
    public const val INTENT_START: Int = 5
    /** Intent recognition finished. */
    public const val INTENT_END: Int = 6
    /** Text-to-speech started; the reply text is under `text`. */
    public const val TTS_START: Int = 7
    /** Text-to-speech finished; the audio's URL is under `url`. */
    public const val TTS_END: Int = 8
    /** Wake word detection started (Home Assistant listens for it). */
    public const val WAKE_WORD_START: Int = 9
    /** The wake word was heard. */
    public const val WAKE_WORD_END: Int = 10
    /** Speech started. */
    public const val STT_VAD_START: Int = 11
    /** Speech ended. */
    public const val STT_VAD_END: Int = 12
    /** Streaming text-to-speech started. */
    public const val TTS_STREAM_START: Int = 98
    /** Streaming text-to-speech ended. */
    public const val TTS_STREAM_END: Int = 99
    /** Partial intent output, such as streamed reply text. */
    public const val INTENT_PROGRESS: Int = 100
}

/** `VoiceAssistantTimerEvent` (api.proto): the `type` that [VoiceAssistantHandler.onTimer] gets. */
public object TimerEventType {
    /** A timer was started. */
    public const val STARTED: Int = 0
    /** A timer was changed, paused or resumed. */
    public const val UPDATED: Int = 1
    /** A timer was cancelled. */
    public const val CANCELLED: Int = 2
    /** A timer ran out. */
    public const val FINISHED: Int = 3
}
