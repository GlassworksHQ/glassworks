package dev.glassworks.hawebrtc

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android version differences this library handles, in one file: the player asks for a capability here instead of
 * reading `Build.VERSION.SDK_INT`. The library stays standalone, so this is its own copy, not the app's
 * `dev.glassworks.platform`. `@ChecksSdkIntAtLeast` lets lint (NewApi) treat each check as a version guard.
 */
internal val Compat = CompatApi(Build.VERSION.SDK_INT)

/** The capabilities for one API [level]; [Compat] is the running device's. Tests pass a level. */
internal class CompatApi(private val level: Int) {
    /**
     * A `SurfaceView`'s surface moves in step with its view as it scrolls (Android 7: the render thread positions it).
     * Before, it lags a frame behind, so [VideoViewKind.AUTO] draws into a `TextureView` there.
     */
    @get:ChecksSdkIntAtLeast(api = 24)
    val surfaceFollowsScroll: Boolean get() = level >= 24
}
