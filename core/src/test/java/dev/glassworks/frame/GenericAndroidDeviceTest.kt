/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #198: the generic frame asks the device whether its screen takes a tap, so the lululemon Mirror (no touchscreen)
 * needs no frame code of its own.
 */
class GenericAndroidDeviceTest {
    @Test fun noTouchOnlyWhenAndroidListsDevicesAndNoneIsATouchscreen() {
        assertEquals("the Mirror: keys, no touchscreen", Input.NONE, GenericAndroid.inputOf(intArrayOf(1, 2)) { false })
        assertEquals(Input.TOUCH, GenericAndroid.inputOf(intArrayOf(1, 2)) { it == 2 })
        assertEquals("no devices listed: can't tell", Input.TOUCH, GenericAndroid.inputOf(intArrayOf()) { false })
    }
}

/** The same rule on Robolectric's device. MainActivity's orientation, from this frame and the S1's: `OrientationTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericAndroidDisplayTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** Robolectric lists no touchscreen device that the frame can count on: it keeps touch. */
    @Test fun onRobolectricItKeepsTouch() = assertEquals(Input.TOUCH, GenericAndroid(app).input)
}
