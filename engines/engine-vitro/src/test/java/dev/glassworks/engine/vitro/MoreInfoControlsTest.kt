/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.AlarmModes
import dev.glassworks.engine.vitro.cards.ColorTempSliderView
import dev.glassworks.engine.vitro.cards.LightCaps
import dev.glassworks.engine.vitro.cards.RgbColorWheelView
import dev.glassworks.engine.vitro.cards.VerticalCoverSliderView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.LightColor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The math behind the light, cover and color controls of the More-Info sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoControlsTest {

    private fun light(vararg attrs: Pair<String, Any?>) = EntityState("light.desk", "on", attrs.toMap())

    @Test
    fun lightCapsComeFromColorModesOrAttributes() {
        assertEquals(LightCaps(brightness = false, color = false, colorTemp = false), LightCaps.of(light("supported_color_modes" to listOf("onoff"))))
        assertEquals(LightCaps(brightness = true, color = false, colorTemp = false), LightCaps.of(light("supported_color_modes" to listOf("brightness"))))
        assertEquals(LightCaps(brightness = true, color = false, colorTemp = true), LightCaps.of(light("supported_color_modes" to listOf("color_temp"))))
        assertEquals(LightCaps(brightness = true, color = true, colorTemp = true), LightCaps.of(light("supported_color_modes" to listOf("color_temp", "xy"))))
        assertEquals(LightCaps(brightness = true, color = true, colorTemp = false), LightCaps.of(light("rgb_color" to listOf(1, 2, 3))))
        assertEquals(LightCaps(brightness = true, color = false, colorTemp = true), LightCaps.of(light("color_temp" to 370)))
        assertEquals(LightCaps(brightness = true, color = false, colorTemp = false), LightCaps.of(light("brightness" to 128)))
        assertEquals(LightCaps(brightness = false, color = false, colorTemp = false), LightCaps.of(null))
    }

    @Test
    fun colorWheelPutsRedRightAndTheCenterWhite() {
        val r = 100f
        val (hRight, sRight) = RgbColorWheelView.hueSatAt(r, 0f, r)
        assertEquals(0f, hRight, 0.01f)
        assertEquals(1f, sRight, 0.001f)
        // Screen y grows downward, so straight up is 90° (yellow-green side), counter-clockwise from red.
        assertEquals(90f, RgbColorWheelView.hueSatAt(0f, -50f, r).first, 0.01f)
        assertEquals(0.5f, RgbColorWheelView.hueSatAt(0f, -50f, r).second, 0.001f)
        assertEquals(180f, RgbColorWheelView.hueSatAt(-r, 0f, r).first, 0.01f)
        assertEquals(270f, RgbColorWheelView.hueSatAt(0f, r, r).first, 0.01f)
        assertEquals(1f, RgbColorWheelView.hueSatAt(3 * r, 0f, r).second, 0.001f) // clamped at the rim
        assertEquals(0f, RgbColorWheelView.hueSatAt(0f, 0f, r).second, 0.001f)
    }

    @Test
    fun colorWheelOffsetsRoundTrip() {
        for (hue in listOf(0f, 45f, 120f, 200f, 300f)) {
            val (dx, dy) = RgbColorWheelView.offsetOf(hue, 0.6f, 120f)
            val (h, s) = RgbColorWheelView.hueSatAt(dx, dy, 120f)
            assertEquals(hue, h, 0.05f)
            assertEquals(0.6f, s, 0.001f)
        }
    }

    @Test
    fun colorTempPillRunsWarmAtTheTopToCoolAtTheBottom() {
        assertEquals(2000, ColorTempSliderView.kelvinAt(0f, 2000, 6500))
        assertEquals(6500, ColorTempSliderView.kelvinAt(1f, 2000, 6500))
        assertEquals(4250, ColorTempSliderView.kelvinAt(0.5f, 2000, 6500))
        assertEquals(2000, ColorTempSliderView.kelvinAt(-1f, 2000, 6500))
        assertEquals(0f, ColorTempSliderView.fractionOf(2000, 2000, 6500), 0.0001f)
        assertEquals(1f, ColorTempSliderView.fractionOf(9000, 2000, 6500), 0.0001f)
        assertEquals(0.5f, ColorTempSliderView.fractionOf(4000, 4000, 4000), 0.0001f)
    }

    @Test
    fun colorTempPillReachesTheDevicesOwnLimits() {
        // 50 K rounding would leave both ends inside the range.
        assertEquals(1538, ColorTempSliderView.kelvinAt(0f, 1538, 6520))
        assertEquals(6520, ColorTempSliderView.kelvinAt(1f, 1538, 6520))
        assertEquals(4050, ColorTempSliderView.kelvinAt(0.5f, 1538, 6520))
    }

    @Test
    fun alarmModesFollowTheSupportedFeatures() {
        fun alarm(features: Int?) = EntityState(
            "alarm_control_panel.home", "disarmed",
            if (features == null) emptyMap() else mapOf("supported_features" to features),
        )
        // HA's supportedAlarmModes, in ALARM_MODES' order, Disarmed always last.
        assertEquals(listOf("armed_away", "armed_night", "disarmed"), AlarmModes.supported(alarm(2 or 4)))
        assertEquals(listOf("armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass", "disarmed"), AlarmModes.supported(alarm(63)))
        // No supported_features: HA's supportsFeature is false for every arming mode.
        assertEquals(listOf("disarmed"), AlarmModes.supported(alarm(null)))
        assertEquals(listOf("disarmed"), AlarmModes.supported(alarm(8))) // trigger only
        assertEquals("alarm_arm_custom_bypass", AlarmModes.service("armed_custom_bypass"))
    }

    @Test
    fun coverPillFillsFromTheTopAsItCloses() {
        assertEquals(90f, VerticalCoverSliderView.fillBottom(70, 300f), 0.001f) // 70 % open fills the top 30 %
        assertEquals(300f, VerticalCoverSliderView.fillBottom(0, 300f), 0.001f)
        assertEquals(0f, VerticalCoverSliderView.fillBottom(100, 300f), 0.001f)
        assertEquals(100, VerticalCoverSliderView.positionAt(0f, 300f))
        assertEquals(0, VerticalCoverSliderView.positionAt(300f, 300f))
        assertEquals(70, VerticalCoverSliderView.positionAt(90f, 300f))
        assertEquals(0, VerticalCoverSliderView.positionAt(500f, 300f))
    }

    @Test
    fun coverHeaderReadsLikeHomeAssistant() {
        assertEquals("Closed", VerticalCoverSliderView.stateText(0))
        assertEquals("Open · 70%", VerticalCoverSliderView.stateText(70))
        assertEquals("Open", VerticalCoverSliderView.stateText(100))
    }

    @Test
    fun hsvRoundTripsThroughArgb() {
        assertEquals(LightColor.pack(255, 0, 0), LightColor.hsvToArgb(0f, 1f, 1f))
        assertEquals(LightColor.pack(0, 255, 0), LightColor.hsvToArgb(120f, 1f, 1f))
        assertEquals(LightColor.pack(0, 0, 255), LightColor.hsvToArgb(240f, 1f, 1f))
        assertEquals(LightColor.pack(255, 255, 255), LightColor.hsvToArgb(77f, 0f, 1f))
        val (h, s) = LightColor.argbToHueSat(LightColor.pack(255, 128, 0))
        assertEquals(30f, h, 0.5f)
        assertEquals(1f, s, 0.001f)
        assertEquals(0f, LightColor.argbToHueSat(LightColor.pack(200, 200, 200)).second, 0.001f)
    }
}
