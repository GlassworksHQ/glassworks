package dev.glassworks.voice

import android.content.Context
import java.io.File

/**
 * Configuration of the on-device wake word and voice assistant ([VoiceEngine]).
 *
 * @property threshold     Wake word score (0..1) that counts as a detection.
 * @property refractoryMs  After a detection, ignore further detections for this long.
 * @property modelsDir     Directory holding the models: microWakeWord's in `micro/` (fetched on first use)
 *                         and any legacy openWakeWord `*.tflite`. `null` means [defaultModelsDir], the app's
 *                         external files directory (`.../files/models/`, writable with `adb push`, no root needed).
 * @property micGain       Linear gain applied to microphone samples (1.0 = unchanged), used both
 *                         for wake word detection and for the audio streamed to Home Assistant.
 * @property maxStreamMs   Safety cap on how long one command is streamed to Home Assistant.
 */
public data class VoiceConfig(
    public val threshold: Float = 0.5f,
    public val refractoryMs: Long = 3_000L,
    public val modelsDir: File? = null,
    public val micGain: Float = 1.0f,
    public val maxStreamMs: Long = 15_000L,
) {
    /** [modelsDir], or the default app-specific external directory. */
    public fun resolveModelsDir(context: Context): File = modelsDir ?: defaultModelsDir(context)

    /** Where the models are when [modelsDir] is null. */
    public companion object {
        /** `models/` in the app's external files directory, else in its internal files directory. */
        public fun defaultModelsDir(context: Context): File =
            context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    }
}

/**
 * Status updates from the voice assistant ([VoiceAssistantController], [VoiceEngine]), for UI. Delivered
 * on a background thread: post to the main thread before touching views.
 */
public sealed class VoiceEvent {
    /** The local wake word fired. */
    public data object WakeDetected : VoiceEvent()

    /** Streaming the user's speech to Home Assistant. */
    public data object Listening : VoiceEvent()

    /** Speech ended; Home Assistant is transcribing / running the agent / synthesizing. */
    public data object Thinking : VoiceEvent()

    /** Playing the TTS response (or an announcement). */
    public data object Speaking : VoiceEvent()

    /** Waiting for the wake word. */
    public data object Idle : VoiceEvent()

    /**
     * Something went wrong (missing models, mic failure, pipeline error, ...). [local] errors come
     * from the device itself (microphone, wake word models), not from a pipeline run.
     */
    public data class Error(
        /** What went wrong, in a short line. */
        public val message: String,
        /** From the device (microphone, models), not from Home Assistant's pipeline. */
        public val local: Boolean = false,
    ) : VoiceEvent()

    /** Home Assistant's final transcript of what the user said (STT_END). Not a state change. */
    public data class Heard(
        /** The transcript. */
        public val text: String,
    ) : VoiceEvent()

    /** The text being spoken: a response (TTS_START) or an [announcement]. Not a state change. */
    public data class Reply(
        /** What is spoken. */
        public val text: String,
        /** An announcement, not the answer to a request. */
        public val announcement: Boolean,
    ) : VoiceEvent()
}
