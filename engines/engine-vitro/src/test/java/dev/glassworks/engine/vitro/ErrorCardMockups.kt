/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * A sections view at the S1's size with one card that can't be built, for a PR's screenshots: HA's error card in its
 * slot, the other cards as they are. Only with `-PvitroMockupDir=<dir>`
 * (`./gradlew :engine-vitro:testDebugUnitTest --tests '*ErrorCardMockups*' -PvitroMockupDir=<dir>`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1440dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ErrorCardMockups {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val dir: String? = System.getProperty("vitro.mockupDir")

    private fun shot(name: String, dark: Boolean) {
        val store = EntityStore()
        store.putLocal(EntityState("light.kitchen", "on", mapOf("friendly_name" to "Kitchen", "brightness" to 180)))
        store.putLocal(EntityState("light.hall", "off", mapOf("friendly_name" to "Hall")))
        store.putLocal(EntityState("sensor.living_temp", "21.5", mapOf("friendly_name" to "Living room", "unit_of_measurement" to "°C", "device_class" to "temperature")))
        store.putLocal(EntityState("lock.front", "locked", mapOf("friendly_name" to "Front door")))
        val bg = if (dark) 0xFF111111.toInt() else 0xFFFAFAFA.toInt()
        val vv = VitroView(activity, store, bg)
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setThemes(ThemeCatalog())
        vv.setUserTheme(null, dark)
        // The card a dashboard holds that can't be built (an exception in its setup).
        vv.testCards = { card, _ -> if (card.type == "custom:broken-card") throw IllegalStateException("broken") else null }
        val dash = JSONObject("""{"views": [{"title": "Home", "type": "sections", "sections": [
            {"type": "grid", "cards": [
              {"type": "heading", "heading": "Living room"},
              {"type": "tile", "entity": "light.kitchen"},
              {"type": "tile", "entity": "light.hall"},
              {"type": "custom:broken-card"},
              {"type": "tile", "entity": "sensor.living_temp"}
            ]},
            {"type": "grid", "cards": [
              {"type": "heading", "heading": "Entrance"},
              {"type": "tile", "entity": "lock.front"}
            ]}
        ]}]}""")
        vv.setDashboard(LovelaceParser.parseDashboard(dash), null)
        activity.setContentView(vv)
        ShadowLooper.idleMainLooper()
        vv.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1440, 720)
        ShadowLooper.idleMainLooper()
        vv.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1440, 720)
        val bmp = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(bg)
        vv.draw(Canvas(bmp))
        File(dir!!).also { it.mkdirs() }.let { out -> File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun errorCardInASectionsView() {
        assumeTrue("only with -PvitroMockupDir", dir != null)
        shot("vitro-error-card-dark", dark = true)
        shot("vitro-error-card-light", dark = false)
    }
}
