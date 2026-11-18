/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import org.junit.Assume.assumeTrue
import org.junit.Test
import dev.glassworks.ui.Mockup
import java.io.File

/**
 * #29: the Manage Overview of a legacy build (a Mirror), before (as `main` draws it: no chip, and the Engine tile's
 * detail read "Version only)") and after (the Legacy chip; its tooltip is forced open when rendered). Rendered with
 * headless Chrome (the #33 trail's render-manage.sh). Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*LegacyChipMockups*' -Ps1MockupDir=<dir>
 */
class LegacyChipMockups {
    private val legacyName = "GeckoView 144.0 (legacy, Home Assistant only)"

    private fun overview(name: String, engine: Tile, legacy: Boolean) {
        val f = ManageFixture()
        f.status = f.status.copy(
            frame = "lululemon Mirror", version = "0.1.0", legacy = legacy,
            tiles = f.status.tiles.map { if (it.label == "Engine") engine else it },
            panes = listOf(
                PaneRow("assist", "Voice", PaneState(PaneState.Kind.RUNNING, "Wake word “Okay Nabu” · Assist"), null),
                PaneRow("camera", "Camera", PaneState(PaneState.Kind.RUNNING, "Presence from the camera"), null),
            ),
        )
        val b = f.Browser()
        b.signInWithHa()
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "$name.html").writeBytes(b.get("/").body)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        overview("legacy-before", Tile("Engine", legacyName, "Version only)", Tile.Tone.OK), legacy = false)
        overview("legacy-after", AppManage.engineTile(legacyName), legacy = true)
    }
}
