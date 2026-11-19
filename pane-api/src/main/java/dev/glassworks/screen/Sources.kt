/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context

/** Tells [ScreenPolicy] whether someone is near the panel. A frame supplies one when it has a sensor for it. */
interface PresenceSource {
    /** Starts reporting. [onReading] runs on the main thread for every reading, changed or not. [raw] is the sensor's own value. */
    fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit)

    /** Stops reporting. [start] may run again. */
    fun stop(context: Context)

    /**
     * Sets how far away still counts as near. 0 or less leaves the device's own setting alone. Null when
     * it took, else why not, in words for the person at the panel.
     */
    fun setRange(context: Context, metres: Float): String? = null

    /** Whether this device has the sensor. A frame's source can be wrong about a device it wasn't made for. */
    fun available(context: Context): Boolean = true

    /** Whether [setRange] can change how far it sees. A standard proximity sensor's range is fixed by its hardware. */
    val ranged: Boolean get() = false

    /**
     * The frame was made with this sensor (the S1's VI5300), so the Screen settings count it without asking the
     * device, whose sensor HAL can list it late after boot or a restart. A source for many devices asks ([available]).
     */
    val builtIn: Boolean get() = false
}

/** Ambient light for [ScreenPolicy]'s brightness curve. */
interface LightSource {
    /** Starts reporting. [onLux] runs on the main thread for every reading. */
    fun start(context: Context, onLux: (Float) -> Unit)

    /** Stops reporting. [start] may run again. */
    fun stop(context: Context)

    /** Whether this device has the sensor. */
    fun available(context: Context): Boolean = true
}

/** What [ScreenPolicy] reads on every decision. The app keeps these in its own settings file under its own keys. */
interface ScreenSettings {
    /** "presence": off after the idle timeout with nobody near. "always": never off. */
    val screenMode: String
    /** Seconds with nobody near and no touch before the screen turns off; 0 or less never. Home Assistant can change it. */
    val idleOffSec: Int
    /** Read only through [approachWakes]: alone it doesn't say whether approaching wakes the screen. */
    val wakeOnApproach: Boolean
    /** Passed to [PresenceSource.setRange]. */
    val presenceRangeM: Float
    var autoBrightness: Boolean
    val minBrightness: Float
    /** The most automatic brightness may reach, unless something holds the screen. Home Assistant can lower it, at night say. */
    val maxBrightness: Float
    /** 0..1 while [autoBrightness] is off. 0 or less means the system brightness. */
    var manualBrightness: Float

    /**
     * Whether someone approaching wakes the screen: the "presence" mode with [wakeOnApproach] on. "always" never wakes
     * on approach, so a screen Home Assistant turned off stays off (#601). The settings' wake switch shows this too.
     */
    val approachWakes: Boolean get() = screenMode == "presence" && wakeOnApproach
}
