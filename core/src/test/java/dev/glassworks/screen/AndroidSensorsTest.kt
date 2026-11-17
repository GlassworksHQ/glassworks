/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.SensorEventBuilder

/** #23: presence and light from Android's standard sensors, for the generic app's frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidSensorsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun add(type: Int, maxRange: Float): Sensor =
        ShadowSensor.newInstance(type).also { shadowOf(it).setMaximumRange(maxRange); shadowOf(sm).addSensor(it) }

    private fun send(sensor: Sensor, value: Float) =
        shadowOf(sm).sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(value)).setTimestamp(1).build())

    @Test fun nearIsUnderFiveCentimetresAndTheSensorsRange() {
        // A binary sensor reports 0 or its range; a ranging one a distance in cm.
        assertTrue(ProximityPresence.near(0f, 1f))
        assertFalse(ProximityPresence.near(1f, 1f))
        assertTrue(ProximityPresence.near(4.9f, 10f))
        assertFalse(ProximityPresence.near(5f, 10f))
        assertFalse("a negative reading is no reading", ProximityPresence.near(-1f, 10f))
    }

    @Test fun aDeviceWithoutAProximitySensorHasNoPresence() {
        val p = ProximityPresence("test")
        assertFalse(p.available(app))
        var readings = 0
        p.start(app) { _, _ -> readings++ }
        assertEquals(0, shadowOf(sm).listeners.size)
        p.stop(app)
    }

    @Test fun readingsBecomeNearAndFar() {
        val s = add(Sensor.TYPE_PROXIMITY, 5f)
        val p = ProximityPresence("test")
        assertTrue(p.available(app))
        val seen = ArrayList<Pair<Boolean, Float>>()
        p.start(app) { near, raw -> seen += near to raw }
        send(s, 0f)
        send(s, 5f)
        assertEquals(listOf(true to 0f, false to 5f), seen)
        p.stop(app)
        assertEquals("stop unregisters", 0, shadowOf(sm).listeners.size)
    }

    @Test fun theRangeCantBeSet() {
        assertNull("nothing asked, nothing to say", ProximityPresence("test").setRange(app, 0f))
        assertEquals(ProximityPresence.FIXED_RANGE, ProximityPresence("test").setRange(app, 1.5f))
    }

    @Test fun lightReportsLux() {
        val s = add(Sensor.TYPE_LIGHT, 10000f)
        val lux = ArrayList<Float>()
        val l = AmbientLight("test")
        l.start(app) { lux += it }
        send(s, 320f)
        assertEquals(listOf(320f), lux)
        l.stop(app)
        assertEquals(0, shadowOf(sm).listeners.size)
    }
}
