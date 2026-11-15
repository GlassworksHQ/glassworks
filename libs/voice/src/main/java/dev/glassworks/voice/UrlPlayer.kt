package dev.glassworks.voice

/**
 * Plays audio URLs one after another: the Assist response (TTS) and announcements. The app supplies it
 * ([VoiceAssistantController] takes one), so the app decides which output and player to use.
 */
public interface UrlPlayer {
    /** Plays [urls] in order. [onDone] runs once when they finished (true), failed or were stopped (false). */
    public fun play(urls: List<String>, onDone: (Boolean) -> Unit)

    /** Stops playing; the pending [play]'s `onDone` runs with false. */
    public fun stop()
}
