package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only the files that stay in the S1 APK module read its BuildConfig (#21, S-version). Everything else
 * gets the build from [GlassworksRuntime.build] or [dev.glassworks.pane.PaneContext.build]: a library
 * module has no access to the APK's BuildConfig, so a file that reads it can't move out. The allowed
 * files are the ones the module split keeps in `:kiosk`. The scan matches the text, as
 * `EngineConfinementTest` does, so a comment or a fully qualified name trips it too.
 */
class BuildConfigConfinementTest {
    private val appRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "libs").isDirectory && File(it, "kiosk/src/main").isDirectory }
    private val main = File(appRoot, "kiosk/src/main/java")

    /** What stays in `:kiosk`: the app class, the boot splash, the S1 frame, the S1 Manage page, the Bluetooth proxy page. */
    private val apkFiles = listOf("KioskApp.kt", "BootSplash.kt", "BootSplashActivity.kt", "frame/", "manage/S1Manage.kt", "settings/BluetoothProxyPage.kt")
        .map { "sh/edm/s1kiosk/$it" }

    /**
     * The import, or a bare `BuildConfig.` (same package or a wildcard import), also with the dot on the next
     * line. Not another library's, such as GeckoView's.
     */
    private val use = Regex("""sh\.edm\.s1kiosk\.BuildConfig\b|(?<![\w.])BuildConfig\s*\.""")

    /** The 1-based lines of [text] where a read starts. The whole text is scanned, so a read split over lines counts. */
    private fun reads(text: String): List<Int> = use.findAll(text).map { m -> text.substring(0, m.range.first).count { it == '\n' } + 1 }.toList()

    private fun sources(): List<File> = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test
    fun `only the APK module's own files read its BuildConfig`() {
        assertTrue("scan found no sources", sources().size > 100)
        val found = sources().filter { f -> apkFiles.none { f.relativeTo(main).path.startsWith(it) } }.flatMap { f ->
            val lines = f.readLines()
            reads(f.readText()).map { n -> "${f.relativeTo(appRoot).path}:$n: ${lines[n - 1].trim()}" }
        }
        assertTrue("BuildConfig outside the APK module's files; use glassworks.build or PaneContext.build:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the file that hands the build in`() {
        assertTrue(reads(File(main, "sh/edm/s1kiosk/KioskApp.kt").readText()).isNotEmpty())
    }

    @Test
    fun `the scan sees reads split over lines and skips other libraries' BuildConfig`() {
        assertEquals(listOf(2), reads("val a = 1\nval v = BuildConfig\n    .VERSION_NAME\n"))
        assertEquals(listOf(1), reads("import sh.edm.s1kiosk.BuildConfig\n"))
        assertEquals(emptyList<Int>(), reads("val g = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION\n"))
    }
}
