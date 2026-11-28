package dev.glassworks.hawebrtc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/**
 * libwebrtc's process-wide parts, shared by every player: the native library, one EGL context (the decoders draw into
 * textures of it, the renderers read them), and one [PeerConnectionFactory]. They stay [LINGER_MS] after the last
 * player gives them back, so a player that comes soon after (a view rebuilt, a dialog opened over the dashboard, a
 * camera shown again) doesn't make them again; [prewarm] makes them ahead of a player.
 *
 * Decoding is [DefaultVideoDecoderFactory]: the device's hardware decoders through MediaCodec first (H.264, what
 * cameras send, and VP8/VP9 where the chip has them), then libwebrtc's own software VP8, VP9 and AV1, then the
 * platform's software H.264. Nothing is sent, so the encoder factory is software-only and never used.
 */
internal object WebRtcRuntime {
    class Handle(val factory: PeerConnectionFactory, val egl: EglBase.Context)

    /** How long the parts stay after the last player gave them back. */
    const val LINGER_MS = 30_000L

    /**
     * Why a renderer couldn't start its EGL context in this process, once one couldn't (null until then). libwebrtc
     * M150's `EglThread.create()` starts its thread before it makes the context and leaves the thread running when that
     * fails, out of reach. So after one failure the players don't try again, and at most one thread is left behind.
     */
    @Volatile
    var rendererUnavailable: String? = null

    /** Held by a player while it checks [rendererUnavailable], starts its renderer and records a failure. */
    val rendererLock = Any()

    private class Parts(val factory: PeerConnectionFactory, val audio: JavaAudioDeviceModule, val egl: EglBase)

    private var initialized = false
    @SuppressLint("StaticFieldLeak")
    private var app: Context? = null

    private val parts = Lingering(LINGER_MS, ::make, ::dispose, Lingering.thread("ha-webrtc-linger"))

    @Synchronized
    fun acquire(context: Context): Handle {
        val appContext = context.applicationContext ?: context
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
            initialized = true
        }
        app = appContext
        val p = parts.acquire()
        return Handle(p.factory, p.egl.eglBaseContext)
    }

    @Synchronized
    fun release() = parts.release()

    /** A [prewarm] runs: another one meanwhile does nothing. */
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Makes the parts on a short-lived thread of its own, and gives them back at once: they linger for a player. One
     * at a time, and none while the parts are kept already (several cameras ask at once).
     */
    fun prewarm(context: Context) {
        // Neither check waits for the lock a make holds: the main thread asks, once per camera, while the first
        // camera's prewarm is making the parts (a 77-88 ms wait on the S1, 2026-11-28).
        if (warming.get() || parts.alive || !warming.compareAndSet(false, true)) return
        Thread({
            try {
                runCatching {
                    acquire(context)
                    release()
                }
            } finally {
                warming.set(false)
            }
        }, "ha-webrtc-warm").apply { isDaemon = true }.start()
    }

    private fun make(): Parts {
        val appContext = requireNotNull(app)
        // Kept local until all of it is made: a failure part-way (an exception, or a LinkageError from the native
        // library) releases what was made, and nothing is kept.
        val eglBase = EglBase.create()
        var adm: JavaAudioDeviceModule? = null
        try {
            // Playout only (recvonly audio), as media: a camera's sound is not a call.
            adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                )
                .createAudioDeviceModule()
            val made = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                .createPeerConnectionFactory()
            return Parts(made, adm, eglBase)
        } catch (e: Throwable) {
            adm?.let { runCatching { it.release() } }
            runCatching { eglBase.release() }
            throw e
        }
    }

    private fun dispose(p: Parts) {
        p.factory.dispose()
        p.audio.release()
        p.egl.release()
    }
}

/**
 * A shared value made on the first [acquire] and kept [lingerMs] after the last [release], so one asked for again
 * soon is the same one. [later] runs a task after a delay, on any thread. Thread-safe.
 */
internal class Lingering<T : Any>(
    private val lingerMs: Long,
    private val make: () -> T,
    private val dispose: (T) -> Unit,
    private val later: (delayMs: Long, task: () -> Unit) -> Unit,
) {
    /** Written under the lock; read without it by [alive]. */
    @Volatile private var value: T? = null
    private var refs = 0

    /** Bumped by each [acquire] and each last [release]: a disposal scheduled before either is dropped. */
    private var generation = 0

    /** The value, made now if there is none (a throw from [make] keeps none and counts nothing). */
    @Synchronized
    fun acquire(): T {
        val v = value ?: make().also { value = it }
        refs++
        generation++
        return v
    }

    /** Gives one back; the last one disposes of the value [lingerMs] later, unless it's taken again meanwhile. */
    fun release() {
        val g = synchronized(this) {
            if (refs == 0 || --refs > 0) return
            ++generation
        }
        later(lingerMs) { expire(g) }
    }

    /** Under the lock, so an [acquire] meanwhile waits for the old value to go before it makes a new one. */
    @Synchronized
    private fun expire(g: Int) {
        if (g != generation || refs > 0) return
        val v = value ?: return
        value = null
        dispose(v)
    }

    /**
     * True while a value is kept. Never waits: while one is being made (under the lock, which may take a while), it is
     * false until it's there, so a caller on the main thread isn't held up by a make on another thread.
     */
    val alive: Boolean get() = value != null

    companion object {
        /** Runs each task on a daemon thread named [name] that sleeps the delay first, then ends. */
        fun thread(name: String): (Long, () -> Unit) -> Unit = { delayMs, task ->
            Thread({
                try {
                    Thread.sleep(delayMs)
                    task()
                } catch (_: InterruptedException) {
                }
            }, name).apply { isDaemon = true }.start()
        }
    }
}
