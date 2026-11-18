package dev.glassworks.s1

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.s1.BootSplash.Home
import dev.glassworks.s1.BootSplash.Step
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class BootSplashTest {
    private val pkg = "sh.edm.s1kiosk"
    private val main = "sh.edm.s1kiosk.MainActivity"
    private val splash = "dev.glassworks.s1.BootSplashActivity"
    private val kiosk = Home(pkg, main, 0)
    private val ourSplash = Home(pkg, splash, BootSplash.PRIORITY)
    private val fallback = Home("com.lumi.systemmanager", "com.lumi.systemmanager.activity.FallbackHome", -1000)
    private val launcher = Home("com.aqara.inhome.launcher", "com.aqara.inhome.launcher.MainActivity", 0)
    private val factory = Home("com.lumi.factory", "com.lumi.factory.activity.MainActivity", 0)
    private val chooser = Home("android", "com.android.internal.app.ResolverActivity", 0)

    @Test
    fun waitsWhileLocked() {
        assertEquals(Step.WAIT, BootSplash.step(false, kiosk, pkg, main))
        assertEquals(Step.WAIT, BootSplash.step(false, ourSplash, pkg, main))
        assertEquals(Step.WAIT, BootSplash.step(false, null, pkg, main))
    }

    @Test
    fun opensTheKioskOnceUnlockedAndItIsHome() =
        assertEquals(Step.OPEN_KIOSK, BootSplash.step(true, kiosk, pkg, main))

    @Test
    fun retriesWhenAndroidCantResolveHomeYet() =
        assertEquals(Step.WAIT, BootSplash.step(true, null, pkg, main))

    @Test
    fun neverStaysHomeAfterUnlock() {
        // someone else's HOME, the chooser, or (MainActivity gone) the splash itself: leave, no loop
        listOf(launcher, chooser, fallback, ourSplash).forEach {
            assertEquals(it.cls, Step.LEAVE, BootSplash.step(true, it, pkg, main))
        }
    }

    @Test
    fun enabledOnADebloatedPanel() {
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, fallback), pkg, main))
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, ourSplash, fallback), pkg, main)) // already on
    }

    @Test
    fun notWithAnotherPriorityZeroHome() {
        // Aqara's launcher or factory app still enabled: they tie with MainActivity, the preferred HOME
        // decides, and adding a HOME component would make Android drop it.
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, launcher, fallback), pkg, main))
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, factory, fallback), pkg, main))
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, Home("x", "x.Home", BootSplash.PRIORITY), fallback), pkg, main))
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, Home("x", "x.Home", BootSplash.PRIORITY - 1), fallback), pkg, main))
    }

    @Test
    fun notWithoutTheKioskAsHome() =
        assertFalse(BootSplash.safeToEnable(listOf(ourSplash, fallback, launcher), pkg, main))

    @Test
    fun priorityBetweenFallbackHomeAndMainActivity() {
        assertTrue(BootSplash.PRIORITY > fallback.priority)
        assertTrue(BootSplash.PRIORITY < 0) // a non-system app's priority is capped at 0: it would tie with MainActivity
    }

    @Test
    fun manifestMatches() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(RepoFiles.s1App, "src/main/AndroidManifest.xml"))
        fun activity(name: String): Element = checkNotNull(doc.getElementsByTagName("activity").let { list ->
            (0 until list.length).map { list.item(it) as Element }.firstOrNull { it.getAttributeNS(ANDROID, "name") == name }
        }) { "the manifest declares $name" }
        fun names(e: Element, tag: String) = e.getElementsByTagName(tag).let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttributeNS(ANDROID, "name") }
        }
        // The activity's MAIN + HOME + DEFAULT filter: the one that makes it a home screen.
        fun homeFilter(e: Element): Element = checkNotNull(e.getElementsByTagName("intent-filter").let { list ->
            (0 until list.length).map { list.item(it) as Element }.singleOrNull { f ->
                "android.intent.action.MAIN" in names(f, "action") &&
                    names(f, "category").containsAll(listOf("android.intent.category.HOME", "android.intent.category.DEFAULT"))
            }
        }) { "${e.getAttributeNS(ANDROID, "name")} has one MAIN/HOME/DEFAULT filter" }

        val boot = activity(splash)
        assertEquals("splash ships disabled", "false", boot.getAttributeNS(ANDROID, "enabled"))
        assertEquals("direct-boot aware", "true", boot.getAttributeNS(ANDROID, "directBootAware"))
        assertEquals("own process", ":boot", boot.getAttributeNS(ANDROID, "process"))
        assertEquals("HOME filter priority", "${BootSplash.PRIORITY}", homeFilter(boot).getAttributeNS(ANDROID, "priority"))
        val kioskMain = activity(main)
        assertEquals("MainActivity stays credential-encrypted only", "", kioskMain.getAttributeNS(ANDROID, "directBootAware"))
        assertEquals("MainActivity HOME keeps the default priority 0", "", homeFilter(kioskMain).getAttributeNS(ANDROID, "priority"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
