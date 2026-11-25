/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.WeatherCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** HA's weather card's line under the temperature: today's high and low with a forecast, else the humidity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WeatherCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun texts(card: View): List<String> {
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        // Displayed text only: the view and each ancestor up to the card visible (the card isn't attached here).
        fun shown(v: View): Boolean {
            var c: View? = v
            while (c != null) {
                if (c.visibility != View.VISIBLE) return false
                if (c === card) return true
                c = c.parent as? View
            }
            return true
        }
        return all(card).filterIsInstance<TextView>().filter(::shown).map { it.text.toString() }
    }

    private fun card(attributes: Map<String, Any?>, config: String = """{"type": "weather-forecast", "entity": "weather.home"}"""): WeatherCardView {
        val store = EntityStore()
        store.putLocal(EntityState("weather.home", "sunny", mapOf("temperature" to 12.2, "temperature_unit" to "°C", "humidity" to 76) + attributes))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return WeatherCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    private fun iso(daysFromNow: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, daysFromNow); set(Calendar.HOUR_OF_DAY, 12) }
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(c.time).let { it.substring(0, 22) + ":" + it.substring(22) }
    }

    @Test
    fun withAForecastItShowsTodaysHighAndLow() {
        val forecast = listOf(
            mapOf("datetime" to iso(0), "condition" to "sunny", "temperature" to 15.2, "templow" to 8.1),
            mapOf("datetime" to iso(1), "condition" to "rainy", "temperature" to 11.0, "templow" to 6.0),
        )
        val shown = texts(card(mapOf("forecast" to forecast)))
        assertTrue(shown.toString(), "15.2 °C / 8.1 °C" in shown)
        assertTrue(shown.toString(), "76%" !in shown)
    }

    @Test
    fun withoutAForecastItShowsTheHumidity() {
        val forecast = listOf(mapOf("datetime" to iso(0), "condition" to "sunny", "temperature" to 15.2, "templow" to 8.1))
        // show_forecast: false: HA hands its card no forecast, so the humidity shows.
        val shown = texts(card(mapOf("forecast" to forecast), """{"type": "weather-forecast", "entity": "weather.home", "show_forecast": false}"""))
        assertTrue(shown.toString(), "76%" in shown)
        assertTrue(shown.toString(), shown.none { " / " in it })
    }
}
