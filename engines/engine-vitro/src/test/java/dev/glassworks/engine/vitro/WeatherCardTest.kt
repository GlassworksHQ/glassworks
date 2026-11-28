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
import dev.glassworks.engine.vitro.data.ForecastDay
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
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

    private fun card(
        attributes: Map<String, Any?>,
        config: String = """{"type": "weather-forecast", "entity": "weather.home"}""",
        state: String = "sunny",
        now: () -> Long = System::currentTimeMillis,
    ): WeatherCardView {
        val store = EntityStore()
        store.putLocal(EntityState("weather.home", state, mapOf("temperature" to 12.2, "temperature_unit" to "°C", "humidity" to 76) + attributes))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, now = now,
        )
        return WeatherCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    @Test
    fun theForecastFollowsEveryPushAndAnEmptyOneKeepsTheLast() {
        val store = EntityStore()
        store.putLocal(EntityState("weather.home", "sunny", mapOf("temperature" to 12.2, "temperature_unit" to "°C", "humidity" to 76)))
        var push: ((List<ForecastDay>) -> Unit)? = null
        var ended = false
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeForecast = { _, _, onForecast -> push = onForecast; { ended = true } },
        )
        val card = WeatherCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "weather-forecast", "entity": "weather.home"}""")))
        fun day(n: Int, high: Double, low: Double) = ForecastDay(iso(n), "sunny", high, low, null, null)
        push!!(listOf(day(0, 15.2, 8.1), day(1, 16.4, 9.3)))
        ShadowLooper.idleMainLooper()
        assertTrue(texts(card).toString(), texts(card).any { "16.4" in it })
        // The integration's next list (a new hour or day): drawn, the subscription stays.
        push!!(listOf(day(0, 11.7, 4.6), day(1, 13.9, 5.5)))
        ShadowLooper.idleMainLooper()
        assertTrue(texts(card).toString(), texts(card).any { "13.9" in it })
        assertFalse(texts(card).any { "16.4" in it })
        // An empty push keeps the last list on screen.
        push!!(emptyList())
        ShadowLooper.idleMainLooper()
        assertTrue(texts(card).toString(), texts(card).any { "13.9" in it })
        assertFalse(ended)
    }

    private fun iso(daysFromNow: Int, from: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.DAY_OF_MONTH, daysFromNow); set(Calendar.HOUR_OF_DAY, 12) }
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(c.time).let { it.substring(0, 22) + ":" + it.substring(22) }
    }

    @Test
    fun todayIsTheCardsNowNotTheDevicesClock() {
        // The card's clock (the harness's fixed instant; on a panel the same as the device's) decides which forecast
        // entries are today's: a forecast for a day 40 days off still shows its high and low on that day.
        val then = System.currentTimeMillis() + 40L * 86_400_000L
        val forecast = listOf(
            mapOf("datetime" to iso(0, then), "condition" to "sunny", "temperature" to 14.7, "templow" to 9.1, "precipitation" to 0.4),
            mapOf("datetime" to iso(1, then), "condition" to "rainy", "temperature" to 12.5, "templow" to 8.7, "precipitation" to 6.1),
        )
        val shown = texts(card(mapOf("forecast" to forecast, "precipitation_unit" to "mm"), now = { then }))
        assertTrue(shown.toString(), "14.7 °C / 9.1 °C" in shown)
        assertTrue(shown.toString(), shown.none { "0.4" in it })
    }

    @Test
    fun extremaKeepHasFalsyChecks() {
        val now = System.currentTimeMillis()
        fun day(n: Int, t: Double?, low: Double?) = ForecastDay(iso(n, now), "sunny", t, low, null, null)
        // Today's entries only, up to the first that isn't today.
        assertEquals(15.0 to 3.0, WeatherCardView.extrema(listOf(day(0, 12.0, 3.0), day(0, 15.0, 5.0), day(1, 30.0, -9.0)), now))
        // No templow: the temperature is the low.
        assertEquals(12.0 to 7.0, WeatherCardView.extrema(listOf(day(0, 12.0, null), day(0, 7.0, null)), now))
        // A 0 °C high or low is "none" in HA's JavaScript (`!tempHigh`), so it never shows.
        assertEquals(null to 4.0, WeatherCardView.extrema(listOf(day(0, 0.0, 4.0)), now))
        // A forecast that starts tomorrow has no extrema at all.
        assertEquals(null to null, WeatherCardView.extrema(listOf(day(1, 12.0, 3.0)), now))
    }

    @Test
    fun anUnavailableForecastCardShowsHasWarningInA100pxCard() {
        HaText.install("en", mapOf("ui.panel.lovelace.warning.entity_unavailable" to "Entity is currently unavailable: {entity}"))
        try {
            val card = card(mapOf("friendly_name" to "Home"), state = "unavailable")
            ShadowLooper.idleMainLooper()
            assertEquals(listOf("Entity is currently unavailable: Home (weather.home)"), texts(card))
            // Sized by its content (masonry), the card is 100 px tall, as `ha-card.unavailable`.
            val dp = activity.resources.displayMetrics.density
            card.measure(View.MeasureSpec.makeMeasureSpec((500 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals((100 * dp).toInt(), card.measuredHeight)
            // In a taller grid slot it keeps the slot, but its surface ends 100 px down.
            val tall = (248 * dp).toInt()
            card.measure(View.MeasureSpec.makeMeasureSpec((500 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            assertEquals(tall, card.height)
            // The view sets its background's bounds to its own size when it draws.
            val bg = card.background as android.graphics.drawable.InsetDrawable
            bg.setBounds(0, 0, card.width, card.height)
            val bounds = bg.drawable!!.bounds
            assertEquals((100 * dp).toInt(), bounds.height())
            // clock-weather-card (drawn by the same view) has no such warning.
            val clock = card(mapOf("friendly_name" to "Home"), """{"type": "custom:clock-weather-card", "entity": "weather.home"}""", state = "unavailable")
            assertTrue(texts(clock).toString(), texts(clock).none { "unavailable:" in it })
        } finally {
            HaText.reset()
        }
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

    @Test
    fun aHumidityReportedAsTextShowsWhenItReadsAsANumber() {
        // A template or custom weather entity: `humidity` as text. It threw ClassCastException on the main thread.
        val noForecast = """{"type": "weather-forecast", "entity": "weather.home", "show_forecast": false}"""
        assertTrue("65%" in texts(card(mapOf("humidity" to "65"), noForecast)))
        // Not a number at all: no humidity line, as when there's none.
        val shown = texts(card(mapOf("humidity" to "humid"), noForecast))
        assertTrue(shown.toString(), shown.none { it.endsWith("%") })
    }
}
