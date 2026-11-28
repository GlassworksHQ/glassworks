/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.glassworks.KioskPrefs
import dev.glassworks.platform.AppBuild
import dev.glassworks.settings.KioskSettingsOverlay
import dev.glassworks.settings.S1Settings
import dev.glassworks.testing.RepoFiles
import dev.glassworks.ui.Inter
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild

/**
 * This app's settings menu, for the PR's screenshots (-Ps1MockupDir=<dir>): the rail's Panes, open (only the panes
 * the device can run), and each pane's page that the S1 app shares with it. A made-up Pixel Tablet with a microphone,
 * a speaker, Bluetooth LE and a camera, at its 1280x800 px (mdpi). A page this build has no pane for is skipped, so
 * the same class gives the *before* on an older build. Skipped unless asked:
 *   ./gradlew :generic-android:testDebugUnitTest --tests '*GenericSettingsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenericSettingsMockups {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun aPixelTablet() {
        val font = RepoFiles.inModule("src/main/res/font/inter.ttf")
        Inter.load = { font.readBytes() }
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
        KioskPrefs(app).urlSetting = "http://homeassistant.local:8123/lovelace/panel"
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    /** The menu on the generic app's real runtime and pane definitions, with [section] open. */
    private fun menu(section: String): KioskSettingsOverlay? {
        val runtime = GenericApp.runtime(GenericApp.frame(app), AppBuild("0.2.0", 200, debug = false, product = GenericApp.PRODUCT))
        val model = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = true, fullScreen = {}, context = app)
        if (model.sections.none { it.id == section }) return null
        val a: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(model)
        o.previewSection(section)
        idle()
        return o
    }

    private fun save(name: String, v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(dir!!, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val dir: File? get() = System.getProperty("s1.mockupDir")?.let { File(it, "generic-settings").apply { mkdirs() } }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        for ((name, section) in listOf("01-panes" to "diagnostics", "02-notifications" to "notifications", "03-bluetooth-proxy" to "bluetooth-proxy",
                "06-dashboard" to "dashboard", "07-labs" to "labs")) {
            menu(section)?.let { save(name, it) }
        }
        // Turned on, as from the page's switch: each page as the S1 app draws it.
        val parts = GenericParts()
        for ((id, key) in listOf("notifications" to "notifications_enabled", "bluetooth-proxy" to "bluetooth_proxy_enabled")) {
            if (id !in GenericParts.PANES) continue
            parts.paneFile(app, id).edit().putBoolean(key, true).commit()
        }
        for ((name, section) in listOf("04-notifications-on" to "notifications", "05-bluetooth-proxy-on" to "bluetooth-proxy")) {
            menu(section)?.let { save(name, it) }
        }
    }

    private companion object {
        const val W = 1280
        const val H = 800
    }
}
