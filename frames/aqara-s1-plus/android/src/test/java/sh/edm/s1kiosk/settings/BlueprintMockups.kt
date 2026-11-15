package sh.edm.s1kiosk.settings

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.BLUEPRINTS
import dev.glassworks.pane.Blueprint
import dev.glassworks.pane.BlueprintAccess
import dev.glassworks.pane.HaBlueprint
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.blueprintCard
import dev.glassworks.platform.AppBuild
import dev.glassworks.testing.RepoFiles
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.ui.Mockup
import java.io.File

/**
 * Mock-ups of the panes' Blueprints cards, from the app's real list ([Blueprint.ASSET]): the S1's
 * Notifications page for an administrator (one blueprint already in Home Assistant), someone who isn't one
 * (and the QR sheet), and a panel that isn't signed in; a Pixel Tablet's Camera page (the generic frame);
 * and the Manage pages as HTML, signed in with Home Assistant and with the panel's code. v0.5.0 stands for
 * the next release. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*BlueprintMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlueprintMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val ha = "http://homeassistant.local:8123"
    private val catalog = Blueprint.parse(File(RepoFiles.s1App, "src/main/assets/${Blueprint.ASSET}").readText()).groupBy { it.pane }

    private val admin = BlueprintAccess.Admin(listOf(HaBlueprint("GlassworksHQ/ask-panels.yaml", null), HaBlueprint("GlassworksHQ/panel-motion-snapshot.yaml", null)))
    private val notAdmin = BlueprintAccess.NotAdmin("Kitchen Panel")
    private val notSignedIn = BlueprintAccess.Off(PanelBlueprints.NOT_SIGNED_IN)

    private fun cards(access: BlueprintAccess): (String) -> SettingsItem.Blueprints? = { id ->
        catalog[id]?.let { list -> SettingsItem.Blueprints(list, { s -> blueprintCard(list, s, access, ha, "v0.5.0") }, {}) }
    }

    /** [frame]'s settings, every pane on, with the Blueprints cards at [access]. */
    private fun model(frame: Frame, access: BlueprintAccess): KioskSettingsOverlay.Model {
        KioskPrefs(app).apply { urlSetting = "$ha/lovelace/panel" }
        val radio = radioDefinition(app, FixedReadout(null, running = false))
        val definitions = GlassworksRuntime(frame, build = AppBuild("0.5.0", 500, debug = false)).paneDefinitions(app).map { if (it.id == "radio") radio else it }
        definitions.forEach { it.switch?.on = true }
        return MenuFixture.model(app, radio, status = "screen on", panes = definitions, blueprints = cards(access))
    }

    private fun panel(name: String, frame: Frame, pane: String, access: BlueprintAccess, then: (KioskSettingsOverlay) -> Unit = {}) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(model(frame, access))
        o.previewSection(pane)
        Mockup.idle()
        Mockup.layout(o)
        o.previewScrollTo(Mockup.label(o, BLUEPRINTS))
        then(o)
        Mockup.idle()
        Mockup.save("blueprints/$name", o)
    }

    private fun manage(name: String, frame: Frame, pane: String, access: BlueprintAccess, withCode: Boolean = false) {
        val f = ManageFixture()
        f.statusFn = { model(frame, access).let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
        val b = f.Browser()
        if (withCode) {
            b.post("/manage/code", mapOf("next" to "/"))
            b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        } else b.signInWithHa()
        File(Mockup.dir!!, "blueprints").apply { mkdirs() }.resolve("$name.html").writeBytes(b.get("/$pane").body)
    }

    private fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.interFromSources()
        File(Mockup.dir!!, "blueprints").mkdirs()
        val s1 = AqaraS1Plus()
        panel("bp-01-s1-notifications-admin", s1, "notifications", admin)
        panel("bp-02-s1-notifications-not-admin", s1, "notifications", notAdmin)
        panel("bp-03-s1-notifications-qr", s1, "notifications", notAdmin) { it.previewBlueprintQr() }
        panel("bp-04-s1-notifications-not-signed-in", s1, "notifications", notSignedIn)
        manage("bp-manage-notifications-ha", s1, "notifications", admin)
        manage("bp-manage-notifications-code", s1, "notifications", admin, withCode = true)
        aPixelTablet()
        val tablet = GenericAndroid(app)
        panel("bp-05-tablet-camera-admin", tablet, "camera", admin)
        panel("bp-06-tablet-camera-not-admin", tablet, "camera", notAdmin)
        manage("bp-manage-camera-tablet-ha", tablet, "camera", admin)
    }
}
