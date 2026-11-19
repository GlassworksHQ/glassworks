package dev.glassworks.s1

import dev.glassworks.brand.Tokens
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The launch screen. Android 12+ draws its own splash from the theme: the Glassworks stack on the
 * screen's night, not the app icon on a plain colour. Below that the S1's theme is as it was: the
 * window background is the boot's last frame and there is no splash.
 *
 * Unit tests run without the app's merged resources, so this reads the theme files and picks the
 * style as Android does: from the highest `values-vN` at or below the API level, then up its parents.
 */
class LaunchSplashThemeTest {
    private class Style(val parent: String?, val items: Map<String, String>)

    private val res = File(RepoFiles.s1App, "src/main/res")

    /** API level (0 for plain `values`) → style name → style. */
    private val themes: Map<Int, Map<String, Style>> = res.listFiles { f -> f.name == "values" || f.name.matches(Regex("values-v\\d+")) }!!
        .filter { File(it, "themes.xml").isFile }
        .associate { dir -> (dir.name.substringAfter("values-v", "0").toInt()) to read(File(dir, "themes.xml")) }

    private fun read(f: File): Map<String, Style> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val styles = doc.getElementsByTagName("style")
        return (0 until styles.length).map { styles.item(it) as Element }.associate { s ->
            val items = s.getElementsByTagName("item")
            s.getAttribute("name") to Style(
                s.getAttribute("parent").ifEmpty { null },
                (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() },
            )
        }
    }

    private fun resolve(name: String, api: Int): Map<String, String> {
        val style = themes.filterKeys { it <= api }.toSortedMap().values.mapNotNull { it[name] }.last()
        val parent = style.parent?.takeUnless { it.startsWith("android:") }?.let { resolve(it, api) }.orEmpty()
        return parent + style.items
    }

    @Test
    fun android12AndLaterShowTheStackOnNight() {
        for (api in listOf(31, 34, 37)) {
            val t = resolve("Theme.Kiosk", api)
            assertEquals("API $api", "#%06X".format(Tokens.Screen.bg.argb and 0xFFFFFF), t["android:windowSplashScreenBackground"])
            assertEquals("API $api", "@drawable/glassworks_mark", t["android:windowSplashScreenAnimatedIcon"])
            assertEquals("API $api", "@drawable/launch_backdrop", t["android:windowBackground"])
        }
        assertTrue(File(res, "drawable/glassworks_mark.xml").isFile)
    }

    @Test
    fun theS1KeepsItsLaunchBackdropAndNoSplash() {
        assertEquals(
            mapOf(
                "android:windowBackground" to "@drawable/launch_backdrop",
                "android:windowNoTitle" to "true",
                "android:windowFullscreen" to "true",
                "android:windowDisablePreview" to "false",
            ),
            resolve("Theme.Kiosk", 29),
        )
    }
}
