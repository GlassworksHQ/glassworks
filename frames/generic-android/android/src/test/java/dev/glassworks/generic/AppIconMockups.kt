/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.KioskService

/**
 * The app's icons as Android 8+ draws them, for the PR's screenshots (-Ps1MockupDir=<dir>): the launcher icon (the
 * adaptive icon, under Android's default mask) on a dark and a light wallpaper, Android 13's themed icon (the
 * monochrome layer), and the notification's small icon in the status bar. Android 6-7 show the PNGs in mipmap-*dpi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GenericApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconMockups {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun icons() {
        val dir = System.getProperty("s1.mockupDir")
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        val out = File(dir!!, "app-icon").apply { mkdirs() }
        val icon = app.getDrawable(app.applicationInfo.icon)!!
        val px = 192
        val sheet = Bitmap.createBitmap(px * 4 + 50, px + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        tile(c, 0, px, Color.parseColor("#1F2937"), icon)
        tile(c, 1, px, Color.parseColor("#E5E7EB"), icon)
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome
        if (mono != null) {
            val themed = AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#D3E3FD")), null, null)
            tile(c, 2, px, Color.parseColor("#1F2937"), themed)
            mono.mutate().setTint(Color.parseColor("#0B57D0"))
            val inset = px * 18 / 72
            mono.setBounds(10 + 2 * (px + 10) - inset, 10 - inset, 10 + 2 * (px + 10) + px + inset, 10 + px + inset)
            mono.draw(c)
        }
        // The status bar draws a small icon's alpha only, in white.
        val small = notification().smallIcon.loadDrawable(app)!!
        tile(c, 3, px, Color.BLACK, small.mutate().apply { setTint(Color.WHITE) })
        File(out, "android-8-plus.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun notification(): Notification {
        val service = Robolectric.buildService(KioskService::class.java).get()
        val build = dev.glassworks.KioskService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        return build.invoke(service) as Notification
    }

    private fun tile(c: Canvas, i: Int, px: Int, background: Int, d: Drawable) {
        val x = 10 + i * (px + 10)
        c.save()
        c.clipRect(x - 5, 5, x + px + 5, px + 15)
        c.drawColor(background)
        c.restore()
        d.setBounds(x, 10, x + px, 10 + px)
        d.draw(c)
    }
}
