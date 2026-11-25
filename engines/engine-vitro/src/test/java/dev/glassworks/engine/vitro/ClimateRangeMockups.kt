/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MushroomClimateCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
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
 * Renders the climate controls with a heat/cool range, for a PR's screenshots: only with `-PvitroMockupDir=<dir>`
 * (`./gradlew :engine-vitro:testDebugUnitTest --tests '*Mockups*' -PvitroMockupDir=<dir>`). It builds the cards from
 * their configs alone, so the same file renders a "before" on an older branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1440dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClimateRangeMockups {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val dir: String? = System.getProperty("vitro.mockupDir")

    private fun context(store: EntityStore, dark: Boolean) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), dark), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun store(action: String) = EntityStore().also {
        it.putLocal(EntityState(
            "climate.hall", "heat_cool",
            mapOf(
                "friendly_name" to "Hall", "hvac_modes" to listOf("off", "heat_cool"), "supported_features" to 386,
                "min_temp" to 10.0, "max_temp" to 30.0, "target_temp_step" to 0.5, "current_temperature" to 18.5,
                "target_temp_low" to 20.0, "target_temp_high" to 24.0, "hvac_action" to action,
            ),
        ))
    }

    private fun card(json: String) = JSONObject(json).let { CardConfig(it.getString("type"), it) }

    private fun save(name: String, view: View, width: Int, height: Int?, dark: Boolean) {
        val out = File(dir!!).also { it.mkdirs() }
        ShadowLooper.idleMainLooper()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            height?.let { View.MeasureSpec.makeMeasureSpec(it, View.MeasureSpec.EXACTLY) }
                ?: View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val pad = 16
        val bmp = Bitmap.createBitmap(view.measuredWidth + 2 * pad, view.measuredHeight + 2 * pad, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(if (dark) 0xFF111111.toInt() else 0xFFFAFAFA.toInt())
        val canvas = Canvas(bmp)
        canvas.translate(pad.toFloat(), pad.toFloat())
        view.draw(canvas)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun climateRange() {
        assumeTrue("only with -PvitroMockupDir", dir != null)
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            val heating = store("heating")
            save("thermostat-$theme", ThermostatCardView(context(heating, dark), card(
                """{"type": "thermostat", "entity": "climate.hall", "features": [{"type": "climate-hvac-modes"}]}""",
            )), 340, null, dark)
            save("mushroom-$theme", MushroomClimateCardView(context(heating, dark), card(
                """{"type": "custom:mushroom-climate-card", "entity": "climate.hall", "show_temperature_control": true}""",
            )), 340, null, dark)
            save("tile-$theme", TileCardView(context(heating, dark), card(
                """{"type": "tile", "entity": "climate.hall", "features": [{"type": "target-temperature"}]}""",
            )), 340, null, dark)
            // The sheet plays HA's entrance once it's laid out on screen: attach it and let that finish first.
            val sheet = MoreInfoSheet(context(store("idle"), dark)) {}
            activity.setContentView(sheet, android.view.ViewGroup.LayoutParams(720, 720))
            sheet.show("climate.hall")
            sheet.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            sheet.layout(0, 0, 720, 720)
            sheet.viewTreeObserver.dispatchOnPreDraw()
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
            save("more-info-$theme", sheet, 720, 720, dark)
        }
    }
}
