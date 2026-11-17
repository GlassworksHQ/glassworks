package dev.glassworks.voice

/**
 * The microphone and wake word side of the assistant, and the wake word catalog Home Assistant picks
 * from. [VoiceEngine] is the real one; tests and apps without a microphone pass their own.
 */
public interface VoiceInput : WakeWordCatalog {
    /** Where a detection starts a run, and where the run's audio goes. Set before [start]. */
    public var controller: VoiceAssistantController?

    /** The wake word score (0 to 1) that counts as a detection. */
    public var threshold: Float

    /** Local failures (models missing, microphone), on a background thread. */
    public var onError: ((String) -> Unit)?

    /**
     * The level (0 to 1, -55 dBFS to -15 dBFS) of each microphone frame sent to Home Assistant, after the
     * gain, for an orb or a meter; 0 when the stream stops, and when the microphone is muted or stopped.
     * On the microphone's thread, or on the thread that mutes or stops it. [VoiceEngine]
     * reports it; an input that has no levels (a test's fake) keeps this default, which reads null and
     * ignores what is set.
     */
    public var onMicLevel: ((Float) -> Unit)?
        get() = null
        set(_) {}

    /**
     * Whether the wake word can listen now, and if not, why. An input that can't tell (a test's fake) keeps
     * this default, Ready.
     */
    public val readiness: VoiceReadiness
        get() = VoiceReadiness.Ready

    /** Gets [readiness] each time it changes, on a background thread. A fake that can't tell ignores it. */
    public var onReadiness: ((VoiceReadiness) -> Unit)?
        get() = null
        set(_) {}

    /** After a failure ([readiness]), tries the wake word again now instead of at its next retry. */
    public fun retryNow() {}

    /** Starts listening for the wake word; with [muted], the microphone stays closed until [setMuted] false. */
    public fun start(muted: Boolean)

    /** Stops listening and frees the microphone and the models. */
    public fun stop()

    /** Closes (true) or opens (false) the microphone. */
    public fun setMuted(m: Boolean)

    /** The linear gain on the microphone's samples (1 = unchanged). */
    public fun setGain(g: Float)
}
