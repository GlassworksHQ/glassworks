package dev.glassworks.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.glassworks.KioskPrefs
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Android's media stream. Everything the panel plays goes through it: voice replies and announcements, timer
 * and notification sounds, Music Assistant (Sendspin) and the dashboard's own audio. `USAGE_ASSISTANT` and
 * `USAGE_MEDIA` both play on `STREAM_MUSIC`, and on Android 6 and 7 the unknown `USAGE_ASSISTANT` falls back to it.
 */
interface MediaStream {
    /** The stream's steps, 15 on most devices; 0 when it can't be read. */
    val max: Int

    /** The level now, 0 to [max]. Android reports 0 while another app has muted the stream. */
    var index: Int

    /** The device keeps its volume fixed (`AudioManager.isVolumeFixed`): setting [index] does nothing. */
    val fixed: Boolean
}

/** Where the master volume keeps what Android's stream can't tell it. */
interface VolumeStore {
    /** The level, 0 to 1, as last set or seen; negative before the first start. */
    var level: Float

    /** Muted: the stream is at 0 and [level] is what unmuting goes back to. */
    var muted: Boolean

    /** A critical alert has the stream at full. Kept on disk, so a start after a crash mid-alert puts [level] back. */
    var raised: Boolean

    /** The speaker volume builds before the master volume kept (0 to 1): read once, then gone. */
    fun takeOldVolume(): Float?
}

/**
 * The panel's master volume: one level for everything it plays, whichever panes are on. It is Android's media
 * stream ([MediaStream]), so the volume keys of a tablet move it too, and Home Assistant's *Speaker* entity, the
 * Sound page and the Manage page all set it. Music Assistant's own player volume (the Music page) sits under it.
 *
 * - Mute puts the stream at 0 and keeps the level. Turning the stream up from outside (the volume keys) unmutes,
 *   as on any Android device.
 * - A critical alert plays at full volume, unmuted ([full]); the level comes back after it.
 * - On a device whose volume is fixed, the stream doesn't move: the players the app owns scale by [gain] instead.
 *
 * Every method is safe from any thread. [onChange] runs after every change: the app's own, and one made outside
 * the app once a read sees it.
 */
class MasterVolume(
    private val stream: MediaStream,
    private val store: VolumeStore,
    /** Runs the action after the delay, on any thread: the latest end of a [full]. */
    private val after: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private val lock = Any()
    private var started = false
    /** The [full]s not yet ended. */
    private var raises = 0

    /** Hears each change: [set], [mute], [step], and one made outside the app (the volume keys) when a read sees it. Outside the lock. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Once per process, before the first read (the device calls it at start; reads call it too): moves an old
     * speaker volume over, takes back a raise a crash left, and reads the stream on a first start.
     */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val old = store.takeOldVolume()
            when {
                // The app stopped mid-alert: the stream is still at full.
                store.raised -> settle()
                // The old volume scaled voice and announcements on top of the stream: keep them as loud as they were.
                old != null -> { store.level = if (fixed()) old else streamLevel() * old.coerceIn(0f, 1f); push() }
                // A first start: Android's level is the panel's. A mute saved before (Home Assistant's) still holds.
                store.level < 0f -> { store.level = if (fixed()) 1f else streamLevel(); push() }
                else -> { pull(); push() }
            }
        }
    }

    /** The level, 0 to 1. While muted, the level unmuting goes back to. */
    val level: Float get() = read { store.level.coerceIn(0f, 1f) }

    val muted: Boolean get() = read { store.muted }

    /**
     * For the players the app owns: 1 while Android's stream carries the volume, the level itself where the volume
     * is fixed (0 muted), and 1 there too while a critical alert plays ([full]).
     */
    val gain: Float get() = read { if (!fixed() || raises > 0) 1f else if (store.muted) 0f else store.level.coerceIn(0f, 1f) }

    fun set(level: Float) = write { store.level = level.coerceIn(0f, 1f) }

    fun mute(on: Boolean) = write { store.muted = on }

    /** One step of the stream up or down (a tenth where the volume is fixed), as Home Assistant's volume buttons. */
    fun step(up: Boolean) = write {
        val l = store.level.coerceIn(0f, 1f)
        store.level = if (fixed()) (l + if (up) 0.1f else -0.1f).coerceIn(0f, 1f)
        else ((index(l) + if (up) 1 else -1).coerceIn(0, stream.max)) / stream.max.toFloat()
    }

    /**
     * Full volume, unmuted, until the returned call or [maxMs], whichever comes first (a sound cut short never calls
     * back). Overlapping raises share one; the stream goes back to the level, or to mute, when the last one ends.
     * Changes meanwhile are kept for then. On a device whose volume is fixed the stream stays put: [gain] is 1
     * instead, and [onChange] tells the players at the start and the end.
     */
    fun full(maxMs: Long): () -> Unit {
        start()
        val tell = synchronized(lock) {
            val first = raises == 0
            if (first && !fixed()) {
                // A restore Android refused is still pending: the stream is at full already, and the level is kept.
                if (!store.raised) pull()
                store.raised = true
                stream.index = stream.max
            }
            raises++
            first && fixed()
        }
        if (tell) onChange?.invoke()
        val ended = AtomicBoolean()
        val end = {
            if (ended.compareAndSet(false, true)) {
                val last = synchronized(lock) {
                    raises--
                    settle()
                    raises == 0 && fixed()
                }
                if (last) onChange?.invoke()
            }
        }
        after(maxMs, end)
        return end
    }

    private fun <T> read(get: () -> T): T {
        start()
        val (changed, v) = synchronized(lock) { pull() to get() }
        if (changed) onChange?.invoke()
        return v
    }

    private fun write(change: () -> Unit) {
        start()
        synchronized(lock) { pull(); change(); push() }
        onChange?.invoke()
    }

    // --- under lock

    private fun fixed() = stream.fixed || stream.max <= 0

    private fun index(level: Float) = (level * stream.max).roundToInt().coerceIn(0, stream.max)

    private fun streamLevel() = (stream.index.toFloat() / stream.max).coerceIn(0f, 1f)

    /**
     * Puts the stream back after the last alert, and clears the mark once it has left full. Until then (Android
     * refused, as Do Not Disturb can) the mark stays: reads don't take full for the level, and each one tries again,
     * as does the next start after a stop.
     */
    private fun settle() {
        if (raises > 0 || !store.raised) return
        push()
        if (fixed() || stream.index < stream.max || want() == stream.max) store.raised = false
    }

    private fun want() = if (store.muted) 0 else index(store.level)

    /** Takes in a change made outside the app. True when the level or mute changed. */
    private fun pull(): Boolean {
        settle()
        if (raises > 0 || store.raised || fixed()) return false
        val i = stream.index
        if (store.muted) {
            if (i == 0) return false
            store.muted = false
        } else if (i == index(store.level)) return false
        store.level = i / stream.max.toFloat()
        return true
    }

    /** Puts the stream where the level and mute say, unless an alert holds it at full. */
    private fun push() {
        if (raises > 0 || fixed()) return
        val want = want()
        if (stream.index != want) stream.index = want
    }

    companion object {
        private const val TAG = "Glassworks.Volume"

        /** The master volume of this app: Android's media stream, with what it can't hold kept in [KioskPrefs]. */
        fun of(context: Context): MasterVolume {
            val app = context.applicationContext
            val prefs = KioskPrefs(app)
            val main = Handler(Looper.getMainLooper())
            return MasterVolume(AndroidMediaStream(app), object : VolumeStore {
                override var level by prefs::masterLevel
                override var muted by prefs::speakerMuted
                override var raised by prefs::volumeRaised
                override fun takeOldVolume() = prefs.takeOldSpeakerVolume()
            }) { ms, action -> main.postDelayed(action, ms) }
        }
    }

    /** `STREAM_MUSIC` through [AudioManager]: every call is in the SDK from Android 6. */
    private class AndroidMediaStream(context: Context) : MediaStream {
        private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        override val max: Int get() = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)

        override var index: Int
            get() = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            // Android can refuse (Do Not Disturb's total silence on some devices): the level stays, and the next read sees it.
            set(v) { runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }.onFailure { Log.w(TAG, "can't set the media volume", it) } }

        override val fixed: Boolean get() = runCatching { audio.isVolumeFixed }.getOrDefault(false)
    }
}
