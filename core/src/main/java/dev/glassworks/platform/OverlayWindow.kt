package dev.glassworks.platform

import android.os.Build
import android.view.WindowManager

/**
 * The window type for an overlay drawn over other apps (the app holds SYSTEM_ALERT_WINDOW).
 * TYPE_APPLICATION_OVERLAY exists only from Android 8 (API 26); Android 6 and 7 refuse it with
 * "permission denied for this window type", so they get TYPE_PHONE, its predecessor. Seen on the
 * Mirror (Android 6.0.1, 2026-11-17): the Manage code never showed (#450).
 */
object OverlayWindow {
    @Suppress("DEPRECATION")
    fun type(sdk: Int = Build.VERSION.SDK_INT): Int =
        if (sdk >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
}
