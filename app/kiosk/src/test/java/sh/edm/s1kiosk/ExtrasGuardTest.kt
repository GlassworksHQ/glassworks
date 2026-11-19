package sh.edm.s1kiosk

import android.Manifest
import android.app.Application
import android.content.Intent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #374: another app on the panel must not change a setting through MainActivity's extras. Robolectric doesn't
 * enforce an activity's permission, so the manifest half is pinned by reading the manifest: MainActivity takes
 * WRITE_SECURE_SETTINGS, which adb's shell holds and Android 10 waives for root, the system's HOME start and the
 * app itself; the launcher's alias takes none, and MainActivity drops its extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExtrasGuardTest {
    private val pkg = "sh.edm.s1kiosk"
    private val main = MainActivity::class.java.name
    private val launcher = "sh.edm.s1kiosk.Launcher"

    private fun start(component: String) = Intent(Intent.ACTION_MAIN).setClassName(pkg, component).putExtra("url", "http://192.0.2.66/")

    private class Entry(val name: String, val target: String?, val permission: String?, val enabled: Boolean, val filters: List<Set<String>>)

    private val manifest: Map<String, Entry> by lazy {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val elements = { parent: org.w3c.dom.Element, tag: String ->
            parent.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        }
        listOf("activity", "activity-alias").flatMap { elements(doc.documentElement, it) }.map { a ->
            val full = { n: String -> if (n.startsWith(".")) "$pkg$n" else n }
            val filters = elements(a, "intent-filter").map { f ->
                (elements(f, "action") + elements(f, "category")).map { it.getAttributeNS(ns, "name") }.toSet()
            }
            Entry(full(a.getAttributeNS(ns, "name")), a.getAttributeNS(ns, "targetActivity").ifEmpty { null }?.let(full),
                a.getAttributeNS(ns, "permission").ifEmpty { null }, a.getAttributeNS(ns, "enabled") != "false", filters)
        }.associateBy { it.name }
    }

    private fun answering(vararg names: String) =
        manifest.values.filter { e -> e.enabled && e.filters.any { it.containsAll(names.toList()) } }.map { it.name }

    @Test fun aStartThroughTheLauncherAliasLosesItsExtras() {
        val i = start(launcher)
        assertTrue(MainActivity.screenExtras(i))
        assertNull(i.getStringExtra("url"))
    }

    /** adb (Kiln, install.sh), the root helper's `am start`, HOME and the app's own restarts all name MainActivity. */
    @Test fun aStartOfMainActivityKeepsItsExtras() {
        val i = start(main)
        assertFalse(MainActivity.screenExtras(i))
        assertEquals("http://192.0.2.66/", i.getStringExtra("url"))
    }

    @Test fun onlyAdbRootTheSystemAndTheAppMayStartMainActivity() {
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, manifest.getValue(main).permission)
    }

    /** Panels store HOME as sh.edm.s1kiosk/.MainActivity (the installers' set-home-activity), and BootSplash names it. */
    @Test fun homeStaysMainActivityUnderItsName() {
        assertEquals(listOf(main), answering(Intent.ACTION_MAIN, Intent.CATEGORY_HOME, Intent.CATEGORY_DEFAULT))
    }

    @Test fun theLauncherIconStartsTheAliasWhichAnyAppMayUse() {
        assertEquals(listOf(launcher), answering(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER))
        val alias = manifest.getValue(launcher)
        assertEquals(main, alias.target)
        assertNull(alias.permission)
    }
}
