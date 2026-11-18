package dev.glassworks

import android.app.Activity
import android.view.View
import android.widget.FrameLayout

/**
 * A pane's layer over the dashboard in the app's activity: the voice orb, the timers, the notifications,
 * the camera's privacy dot. The app builds them ([AppParts.overlays]); the activity adds them in that order
 * and tells them about the screen, Back and its end.
 */
interface PaneOverlay {
    val view: View

    /** How the activity lays [view] out in its full-screen frame. */
    val layoutParams: FrameLayout.LayoutParams

    /** Above the settings menu and the Thread join screen, never covered (the camera's privacy dot). */
    val aboveMenus: Boolean get() = false

    /** The screen's state changed (on, off, dimmed). Main thread. */
    fun screenChanged() {}

    /** Back was pressed: true if this layer closed something of its own. Main thread. */
    fun back(): Boolean = false

    /** The activity is going away: let go of what the layer listens to. Main thread. */
    fun destroy() {}
}

/** What the activity gives the overlays the app builds for it. */
interface OverlayHost {
    val activity: Activity

    /** Shows a Home Assistant view full screen in the page, or goes back to the dashboard with null. Main thread. */
    fun openInPage(url: String?)

    /**
     * Where the page sits: in [area] (px, in the activity's window), or full screen with null. A
     * notification's half-screen view (#230) shrinks the page into its dialog. Main thread.
     */
    fun placePage(area: android.graphics.Rect?) {}

    /** A still picture of the page as it is now, or null ([dev.glassworks.engine.Page.capture]). Main thread. */
    fun capturePage(done: (android.graphics.Bitmap?) -> Unit) = done(null)

    /** Closes the settings menu if it is open. Main thread. */
    fun hideMenu()

    /** Runs [block] on the main thread. */
    fun post(block: () -> Unit)
}
