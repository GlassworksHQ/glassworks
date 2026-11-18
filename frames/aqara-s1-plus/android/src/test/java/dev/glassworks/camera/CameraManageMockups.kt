/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.Pane
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Manage page's camera page as the real server answers it, on a frame with touch and one without, for an
 * administrator, after a refused stream switch, and for the panel's code: HTML files to render at 390 px and
 * desktop width. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*CameraManageMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraManageMockups {
    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test fun mockups() {
        val dir = System.getProperty("s1.mockupDir")
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        val out = File(dir!!, "manage-camera").apply { mkdirs() }
        for (touch in listOf(true, false)) {
            val name = if (touch) "touch" else "no-touch"
            val file = app.getSharedPreferences("camera-manage-mockups-$name", Context.MODE_PRIVATE)
            val def = CameraDefinition(file, granted = { true }, pickers = { error("the panel's view isn't drawn here") }, touch = { touch })
            val running = object : Pane, CameraReadout {
                override val id = CameraPane.ID
                override fun reading() = CameraReading(def.prefs.use, def.prefs.privacy, false, false, true, null, true, false, null, false, null, false, emptyList())
                override fun address() = StreamAddress("192.0.2.40", 8554, 8899)
            }
            val f = ManageFixture()
            f.statusFn = { def.settings(running).single().let { sec -> f.status.copy(panes = listOf(paneRow(CameraPane.ID, sec)), sections = listOf(sec)) } }
            val admin = f.Browser().also { it.signInWithHa() }
            File(out, "$name-admin.html").writeBytes(admin.get("/camera").body)
            val refused = admin.post("/settings", mapOf("csrf" to admin.csrf(), "section" to CameraPane.ID, "key" to "ONVIF stream/Stream over ONVIF and RTSP", "value" to "on"))
            File(out, "$name-refused.html").writeBytes(refused.body)
            val code = f.Browser().also { b ->
                b.get("/")
                b.post("/manage/code", mapOf("next" to "/"))
                b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
            }
            File(out, "$name-code.html").writeBytes(code.get("/camera").body)
            file.edit().clear().commit()
        }
    }
}
