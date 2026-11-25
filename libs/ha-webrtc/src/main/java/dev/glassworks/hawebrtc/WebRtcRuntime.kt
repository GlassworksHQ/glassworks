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
 * libwebrtc's process-wide parts, shared by every player and freed with the last one: the native library, one EGL
 * context (the decoders draw into textures of it, the renderers read them), and one [PeerConnectionFactory].
 *
 * Decoding is [DefaultVideoDecoderFactory]: the device's hardware decoders through MediaCodec first (H.264, what
 * cameras send, and VP8/VP9 where the chip has them), then libwebrtc's own software VP8, VP9 and AV1, then the
 * platform's software H.264. Nothing is sent, so the encoder factory is software-only and never used.
 */
internal object WebRtcRuntime {
    class Handle(val factory: PeerConnectionFactory, val egl: EglBase.Context)

    private var initialized = false
    private var refs = 0
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    // Made with the application context only, and released with the last player.
    @SuppressLint("StaticFieldLeak")
    private var audio: JavaAudioDeviceModule? = null

    @Synchronized
    fun acquire(context: Context): Handle {
        val app = context.applicationContext ?: context
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            initialized = true
        }
        if (refs == 0) {
            // Kept local until all of it is made: a failure part-way (an exception, or a LinkageError from the native
            // library) releases what was made, and the shared fields stay empty.
            val eglBase = EglBase.create()
            var adm: JavaAudioDeviceModule? = null
            try {
                // Playout only (recvonly audio), as media: a camera's sound is not a call.
                adm = JavaAudioDeviceModule.builder(app)
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
                audio = adm
                factory = made
                egl = eglBase
            } catch (e: Throwable) {
                adm?.let { runCatching { it.release() } }
                runCatching { eglBase.release() }
                throw e
            }
        }
        refs++
        return Handle(factory!!, egl!!.eglBaseContext)
    }

    @Synchronized
    fun release() {
        if (refs == 0) return
        if (--refs > 0) return
        factory?.dispose()
        audio?.release()
        egl?.release()
        factory = null
        audio = null
        egl = null
    }
}
