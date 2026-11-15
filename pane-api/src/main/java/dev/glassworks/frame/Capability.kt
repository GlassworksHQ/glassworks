/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.pm.PackageManager

/**
 * Hardware a pane needs and a frame has: the words of a pane.json's `requires` and a frame.json's
 * `provides` ([id]). A pane that needs one the frame lacks is hidden ([dev.glassworks.pane.CantRun]).
 */
enum class Capability(val id: String) {
    MICROPHONE("microphone"),
    SPEAKER("speaker"),
    SILABS_RADIO("silabs-radio"),
    S1_WALL_BASE("s1-wall-base"),
    BLUETOOTH_LE("bluetooth-le"),
    CAMERA("camera");

    companion object {
        /** The ones a device says it has, by its `PackageManager` features ([hasFeature]). No feature names a Silicon Labs radio or an S1 wall base. */
        fun ofDevice(hasFeature: (String) -> Boolean): Set<Capability> = buildSet {
            if (hasFeature(PackageManager.FEATURE_MICROPHONE)) add(MICROPHONE)
            if (hasFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) add(SPEAKER)
            if (hasFeature(PackageManager.FEATURE_BLUETOOTH_LE)) add(BLUETOOTH_LE)
            if (hasFeature(PackageManager.FEATURE_CAMERA_ANY)) add(CAMERA)
        }
    }
}
