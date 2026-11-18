package dev.glassworks.settings

import android.app.Application
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.Tile
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.KioskPrefs
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.ui.Mockup
import java.io.File

/**
 * #467: what Manage's Overview and the settings menu's Dashboard page show for a panel whose Home Assistant moved,
 * before (as `main` reads it: still signed in) and after (not signed in). The tiles are the ones AppManage.haTile draws
 * for a connected Home Assistant; Manage's HTML is rendered with headless Chrome (the #33 trail's render-manage.sh).
 * Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SigninMovedMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SigninMovedMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun dashboardPage(name: String, account: String?) {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).urlSetting = "http://homeassistant.local:8123/lovelace/panel"
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(MenuFixture.model(app, radioDefinition(app, FixedReadout(null, running = false)), account = account))
        Mockup.idle()
        o.previewSection("dashboard")
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    private fun overview(name: String, ha: Tile) {
        val f = ManageFixture()
        f.status = f.status.copy(tiles = listOf(ha) + f.status.tiles.drop(1))
        val b = f.Browser()
        b.signInWithHa()
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "$name.html").writeBytes(b.get("/").body)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        dashboardPage("settings-dashboard-before", account = "Signed in as Wall panel")
        dashboardPage("settings-dashboard-after", account = null)
        overview("manage-overview-before", Tile("Home Assistant", "Connected", "homeassistant.local", Tile.Tone.OK))
        overview("manage-overview-after",
            Tile("Home Assistant", "Connected", "Dashboard not signed in · Sign in", Tile.Tone.WORKING, href = "/signin"))
    }
}
