package dev.glassworks.platform

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayWindowTest {
    @Test
    @Suppress("DEPRECATION")
    fun `Android 6 and 7 get TYPE_PHONE, which they accept`() {
        assertEquals(WindowManager.LayoutParams.TYPE_PHONE, OverlayWindow.type(23))
        assertEquals(WindowManager.LayoutParams.TYPE_PHONE, OverlayWindow.type(25))
    }

    @Test
    fun `Android 8 and later get TYPE_APPLICATION_OVERLAY`() {
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OverlayWindow.type(26))
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OverlayWindow.type(34))
    }
}
