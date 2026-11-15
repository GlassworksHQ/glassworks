package sh.edm.s1kiosk.esphome

import dev.glassworks.pane.PaneContext
import sh.edm.s1kiosk.voice.ExoAudioPlayer
import sh.edm.s1kiosk.voice.TtsPlayer
import java.util.concurrent.ScheduledExecutorService

/**
 * What the panel's device ([PanelDevice]) gives the panes the app builds for it: their context, the
 * device's thread, the speaker's players and the chimes. The app builds its panes from it, in order;
 * the device drops the ones the frame can't run, then starts, stops and lists the rest.
 */
interface DeviceHost {
    /** The context every pane gets: its settings, the device identity, and the ways to reach Home Assistant. */
    val paneContext: PaneContext

    /** esphome-main, the device's thread. */
    val exec: ScheduledExecutorService

    /** The ids of the panes the frame can't run ([dev.glassworks.pane.CantRun]): the device drops them after the app builds them. */
    val cantRun: Set<String>

    /** The speaker's player for the assistant's replies and Home Assistant's announcements. */
    val voicePlayer: ExoAudioPlayer

    /** The speaker's player for music and other media. */
    val mediaPlayer: ExoAudioPlayer

    /** An announcement plays on [voicePlayer] now. */
    val announcing: Boolean

    /** The chime player, made on first use (from the ESPHome, voice or main thread). */
    fun chimes(): TtsPlayer

    /** Cuts the chime player's sound, if it has been made. */
    fun silenceChimes()

    /** A media URL from Home Assistant, made absolute against the dashboard's Home Assistant. */
    fun resolveUrl(url: String): String

    /** The panel's LAN address, or null without one. */
    fun ipAddress(): String?

    /** Runs [block] when the device stops, after its panes have stopped. */
    fun onStop(block: () -> Unit)

    /** Cancels the current voice run ([PanelDevice.cancelVoice]); esphome-main. Null while no pane can. The device clears it at stop. */
    var cancelVoice: (() -> Unit)?

    /**
     * Hears each change of whether [voicePlayer] plays. The app sets it when it builds its panes: the voice
     * overlay's speaking pulse follows it, so without it the overlay loses the pulse. The device clears it at stop.
     */
    var onVoicePlaying: ((Boolean) -> Unit)?
}
