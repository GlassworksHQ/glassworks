package sh.edm.s1kiosk

import android.Manifest
import android.content.Intent
import dev.glassworks.testing.RepoFiles
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.KioskPrefs

/**
 * #374: another app on the panel must not change a setting through MainActivity's extras. The real MainActivity
 * runs under KioskApp (GeckoShadows stand in for GeckoView).
 *
 * Robolectric doesn't enforce an activity's permission (and this module's tests run without the merged manifest),
 * so the manifest half is pinned by reading the manifest:
 * MainActivity takes WRITE_SECURE_SETTINGS, which adb's shell holds and Android 10 waives for root, the system's
 * HOME start and the app itself (ActivityManager.checkComponentPermission); the launcher's alias takes none, and
 * its extras are dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class ExtrasGuardTest {
    private val app = RuntimeEnvironment.getApplication()
    private val before = "http://192.0.2.10:8123/"
    private val sent = "http://192.0.2.66/"
    private val main = MainActivity::class.java.name
    private val launcher = "sh.edm.s1kiosk.Launcher"

    private fun start(component: String) = Intent(Intent.ACTION_MAIN).setClassName(app.packageName, component).putExtra("url", sent)

    private fun url() = KioskPrefs(app).urlSetting

    private class Entry(val name: String, val target: String?, val permission: String?, val enabled: Boolean, val filters: List<Set<String>>)

    /** The manifest's activities and aliases, with each intent filter's actions and categories. */
    private val manifest: Map<String, Entry> by lazy {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(RepoFiles.s1App, "src/main/AndroidManifest.xml"))
        val elements = { parent: org.w3c.dom.Element, tag: String ->
            parent.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        }
        listOf("activity", "activity-alias").flatMap { elements(doc.documentElement, it) }.map { a ->
            val full = { n: String -> if (n.startsWith(".")) "sh.edm.s1kiosk$n" else n }
            val name = full(a.getAttributeNS(ns, "name"))
            val filters = elements(a, "intent-filter").map { f ->
                (elements(f, "action") + elements(f, "category")).map { it.getAttributeNS(ns, "name") }.toSet()
            }
            Entry(name, a.getAttributeNS(ns, "targetActivity").ifEmpty { null }?.let(full), a.getAttributeNS(ns, "permission").ifEmpty { null }, a.getAttributeNS(ns, "enabled") != "false", filters)
        }.associateBy { it.name }
    }

    /** The enabled activities with a filter that has all of [names]. */
    private fun answering(vararg names: String) =
        manifest.values.filter { e -> e.enabled && e.filters.any { it.containsAll(names.toList()) } }.map { it.name }

    @Test fun aStartThroughTheLauncherAliasChangesNoSettingAndSaysSo() {
        GeckoPinHarness(start(launcher), prefs = { it.urlSetting = before }).use { h ->
            assertEquals("the alias reaches MainActivity", launcher, h.activity.intent.component?.className)
            assertEquals(before, url())
            assertTrue(h.logs("Glassworks").any { it.startsWith("ignored the extras of a start through") })
        }
    }

    @Test fun aNewIntentThroughTheLauncherAliasChangesNoSetting() {
        GeckoPinHarness(prefs = { it.urlSetting = before }).use { h ->
            h.newIntent(start(launcher))
            assertEquals(before, url())
        }
    }

    /** adb (Kiln, Clarity), the root helper's `am start`, HOME and the app's own restart all name MainActivity. */
    @Test fun aStartOfMainActivityItselfChangesTheSetting() {
        GeckoPinHarness(start(main), prefs = { it.urlSetting = before }).use { assertEquals(sent, url()) }
        GeckoPinHarness(prefs = { it.urlSetting = before }).use { h ->
            h.newIntent(start(main))
            assertEquals(sent, url())
        }
    }

    @Test fun onlyAdbRootTheSystemAndTheAppMayStartMainActivity() {
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, manifest.getValue(main).permission)
    }

    /** Panels store HOME as sh.edm.s1kiosk/.MainActivity (Kiln's set-home-activity), and BootSplash names it. */
    @Test fun homeStaysMainActivityUnderItsName() {
        assertEquals("the enabled HOME; the alias is none", listOf(main), answering(Intent.ACTION_MAIN, Intent.CATEGORY_HOME, Intent.CATEGORY_DEFAULT))
    }

    @Test fun theLauncherIconStartsTheAliasWhichAnyAppMayUse() {
        assertEquals(listOf(launcher), answering(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER))
        val alias = manifest.getValue(launcher)
        assertEquals("the alias opens MainActivity", main, alias.target)
        assertEquals("a tablet's launcher holds no WRITE_SECURE_SETTINGS", null, alias.permission)
    }
}
