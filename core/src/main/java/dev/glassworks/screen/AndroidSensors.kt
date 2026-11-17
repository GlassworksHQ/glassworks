/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Presence from Android's standard proximity sensor (`TYPE_PROXIMITY`), for a device with no frame of its
 * own (#23). Android reports a distance in cm, and many sensors report only 0 and their maximum range. Near
 * is what Android's own screen-off-in-a-call uses: less than the sensor's range, and under [NEAR_CM]. So it
 * sees a hand over the sensor, not a person across the room. Many tablets have no proximity sensor at all
 * (the Pixel Tablet has none, read 2026-11-15); then [available] is false and the screen follows the idle
 * timeout and touches only.
 */
class ProximityPresence(private val logTag: String) : PresenceSource {
    private var registered: SensorEventListener? = null

    override fun available(context: Context): Boolean = sensor(context, Sensor.TYPE_PROXIMITY) != null

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        val s = sensor(context, Sensor.TYPE_PROXIMITY) ?: return
        Log.i(logTag, "proximity: ${s.name} vendor=${s.vendor} max=${s.maximumRange}")
        registered = register(context, s) { cm -> onReading(near(cm, s.maximumRange), cm) }
    }

    override fun stop(context: Context) = unregister(context, registered).also { registered = null }

    /** A standard proximity sensor's range is fixed by its hardware. */
    override fun setRange(context: Context, metres: Float): String? = if (metres <= 0f) null else FIXED_RANGE

    companion object {
        /** Android's threshold for "near" (PowerManager's proximity wake lock), in cm. */
        const val NEAR_CM = 5f
        const val FIXED_RANGE = "This device's proximity sensor has a fixed range: it only sees something right in front of it."

        /** Whether a reading of [cm] is near, for a sensor whose range is [maxRange] cm. */
        fun near(cm: Float, maxRange: Float): Boolean = cm >= 0f && cm < minOf(maxRange, NEAR_CM)
    }
}

/** Ambient light in lux from Android's standard light sensor (`TYPE_LIGHT`), for the brightness curve (#23). */
class AmbientLight(private val logTag: String) : LightSource {
    private var registered: SensorEventListener? = null

    override fun start(context: Context, onLux: (Float) -> Unit) {
        val s = sensor(context, Sensor.TYPE_LIGHT) ?: return
        Log.i(logTag, "light: ${s.name} vendor=${s.vendor} max=${s.maximumRange}")
        registered = register(context, s, onLux)
    }

    override fun stop(context: Context) = unregister(context, registered).also { registered = null }

    override fun available(context: Context): Boolean = sensor(context, Sensor.TYPE_LIGHT) != null
}

private fun sensor(context: Context, type: Int): Sensor? =
    (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(type)

private fun register(context: Context, s: Sensor, onValue: (Float) -> Unit): SensorEventListener {
    val l = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == s.type) onValue(e.values[0])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    return l
}

private fun unregister(context: Context, l: SensorEventListener?) {
    if (l != null) (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(l)
}
