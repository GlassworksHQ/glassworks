package dev.glassworks.hawebrtc

import android.view.View
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.VideoSink

/** Which view the player draws its picture into ([HaWebRtcPlayer.Options.view]). */
public enum class VideoViewKind {
    /**
     * A `TextureView`: the picture is part of the view tree, so it fades, clips and transforms with it on every
     * Android, but each frame makes the app's window draw again.
     */
    TEXTURE_VIEW,

    /**
     * A `SurfaceView`: the system composites the picture under the window, as a browser composites `<video>`, so a
     * frame doesn't make the window draw again. From Android 7 (API 24), where the surface moves with the view as it
     * scrolls; before that it lags a frame behind, so [AUTO] uses a [TEXTURE_VIEW] there. The picture shows through
     * the hole the view leaves in the window: what the window draws over it (a sheet, a rounded corner's clip) covers
     * it, and the view is clear (no hole, whatever is behind it shows) until its first frame. [VideoScaling.FIT] isn't
     * applied by the view: size it to the picture ([HaWebRtcPlayer.videoWidth]) or use [VideoScaling.STRETCH].
     */
    SURFACE_VIEW,

    /** [SURFACE_VIEW] from API 24, [TEXTURE_VIEW] before. */
    AUTO;

    /** The kind this one is on this device ([api]: the running Android's, [Compat]). */
    internal fun resolved(api: CompatApi = Compat): VideoViewKind =
        if (this == AUTO) (if (api.surfaceFollowsScroll) SURFACE_VIEW else TEXTURE_VIEW) else this
}

/**
 * A picture on screen, with its size ([VideoOutput.onDrawn]). An interface of its own, not a Kotlin function type,
 * whose call would box both sizes on every frame.
 */
internal fun interface FrameDrawn {
    fun onDrawn(width: Int, height: Int)
}

/**
 * What the player draws libwebrtc's frames into, a [VideoTextureView] or a [VideoSurfaceView]: the frames go in as a
 * [VideoSink], [onDrawn] says each one is on screen.
 */
internal interface VideoOutput : VideoSink {
    /** The view to place. */
    val view: View

    /** How the picture fills the view. Main thread. */
    var scaling: VideoScaling

    /**
     * Called on the main thread each time a new picture is on screen, with its size (rotation applied). A frame the
     * renderer drops, with no surface to draw on, isn't one.
     */
    var onDrawn: FrameDrawn?

    /** False: frames aren't drawn (off screen), once one has been. Any thread reads it. */
    var rendering: Boolean

    /** The view's surface exists: a frame can be drawn on it. Main thread. */
    val surfaceReady: Boolean

    /** Called on the main thread each time the view gets a surface (true) or loses it (false). */
    var onSurface: ((ready: Boolean) -> Unit)?

    /** Starts the render thread on [egl]'s context (the decoders' textures). Any thread. */
    fun init(egl: EglBase.Context)

    /** Stops the render thread. Any thread but the render thread; blocks until it has stopped. */
    fun release()
}
