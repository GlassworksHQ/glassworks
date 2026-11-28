/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.settings.CoreSettings
import dev.glassworks.settings.VitroDrawingMockup
import dev.glassworks.settings.VitroDrawingRows
import dev.glassworks.ui.Mockup
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Manage page's Labs and Overview while Vitro draws the dashboard (maintainer, 2026-11-28): before (as `main` draws
 * them: the switch alone, and the Engine tile's version) and after (how Vitro draws the view on screen, its cards in
 * browser views and why, each view; the tile says how and opens Labs). Writes HTML, rendered with headless Chrome.
 * Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*VitroDrawingManageMockups*' -Ps1MockupDir=<dir>
 */
class VitroDrawingManageMockups {
    private val name = "Vitro 0.0.1 (experimental)"

    private fun write(file: String, path: String, after: Boolean) {
        val f = ManageFixture()
        val drawing = if (after) VitroDrawingMockup.drawing else null
        val labs = SettingsSection(CoreSettings.LABS, CoreSettings.LABS_TITLE, listOf(
            SettingsItem.Note { CoreSettings.LABS_NOTE },
            SettingsItem.Switch(CoreSettings.VITRO_TITLE, CoreSettings.VITRO_DETAIL, { true }, restartsApp = true) { _, d -> d(null) },
        ) + VitroDrawingRows.items { drawing })
        f.status = f.status.copy(
            tiles = f.status.tiles.map { if (it.label == "Engine") AppManage.engineTile(name, drawing = drawing) else it },
            sections = f.status.sections + labs,
        )
        val b = f.Browser()
        b.signInWithHa()
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "$file.html").writeBytes(b.get(path).body)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        write("vitro-labs-before", "/${CoreSettings.LABS}", after = false)
        write("vitro-labs-after", "/${CoreSettings.LABS}", after = true)
        write("vitro-overview-before", "/", after = false)
        write("vitro-overview-after", "/", after = true)
    }
}
