package dev.glassworks.device

/** Plays audio URLs (TTS responses, announcements) one after another. */
interface UrlPlayer {
    /** Plays [urls] in order; [onDone] runs once (success = all played) when done, failed or stopped. */
    fun play(urls: List<String>, onDone: (Boolean) -> Unit)
    fun stop()
}
