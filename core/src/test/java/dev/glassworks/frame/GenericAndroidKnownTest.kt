/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GenericAndroid.KNOWN] holds Kiln's models that have a frame, each standing as its frame.json's `display`. */
class GenericAndroidKnownTest {
    private val kilnModels: Map<String, String> = Regex("""code: /\^([^/$]+)\$/,[^\n]*\bframe: '([a-z0-9-]+)'""")
        .findAll(RepoFiles.file("frames/generic-android/installer/src/devices.ts").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun theSameModelsAsKilns() {
        assertTrue("devices.ts has no model with a frame: the pattern no longer reads it", "Pixel Tablet" in kilnModels)
        assertEquals(kilnModels.keys, GenericAndroid.KNOWN.keys)
    }

    @Test fun eachStandsAsItsFrameJsonsDisplay() {
        for ((model, frame) in kilnModels) {
            val d = JSONObject(RepoFiles.file("frames/$frame/design/frame.json").readText()).getJSONObject("display")
            val stands = if (d.getInt("height") > d.getInt("width")) Orientation.PORTRAIT else Orientation.LANDSCAPE
            assertEquals("$model ($frame)", stands, GenericAndroid.KNOWN[model])
        }
    }
}
