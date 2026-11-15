package sh.edm.s1kiosk

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only each app's Application ([KioskApp], the generic app's GenericApp) reads its APK's BuildConfig (#21, S-version). It hands the values in once as
 * [GlassworksRuntime.build], and everything else gets them from there or from
 * [dev.glassworks.pane.PaneContext.build]. A library module has no access to the APK's BuildConfig, so a
 * file that reads it can't move out. Files that stay in `:kiosk` (the frame, S1Manage) could read it,
 * but none needs to, so they get no exception either and a new read fails now rather than at the split.
 * The scan matches the text, as `EngineConfinementTest` does, so a comment or a fully qualified name
 * trips it too. It covers every module but the libraries, wherever they sit ([RepoFiles]).
 */
class BuildConfigConfinementTest {
    /** The one file per app that reads it: S1 Kiosk's, and the generic app's (#23). */
    private val readers = setOf("sh/edm/s1kiosk/KioskApp.kt", "dev/glassworks/generic/GenericApp.kt")

    /**
     * The import, or a bare `BuildConfig.` (same package or a wildcard import), also with the dot on the next
     * line. Not another library's, such as GeckoView's.
     */
    private val use = Regex("""(sh\.edm\.s1kiosk|dev\.glassworks\.generic_android)\.BuildConfig\b|(?<![\w.])BuildConfig\s*\.""")

    /** The 1-based lines of [text] where a read starts. The whole text is scanned, so a read split over lines counts. */
    private fun reads(text: String): List<Int> = use.findAll(text).map { m -> text.substring(0, m.range.first).count { it == '\n' } + 1 }.toList()

    @Test
    fun `only KioskApp reads the APK's BuildConfig`() {
        val sources = RepoFiles.code(atLeast = 100, inModules = RepoFiles.appModules)
        val found = sources.filter { RepoFiles.packagePath(it) !in readers }.flatMap { f ->
            val lines = f.readLines()
            reads(f.readText()).map { n -> "${RepoFiles.rel(f)}:$n: ${lines[n - 1].trim()}" }
        }
        assertTrue("BuildConfig outside KioskApp; use glassworks.build or PaneContext.build:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the files that hand the build in`() {
        for (reader in readers) assertTrue(reader, reads(RepoFiles.source(reader).readText()).isNotEmpty())
    }

    @Test
    fun `the scan sees reads split over lines and skips other libraries' BuildConfig`() {
        assertEquals(listOf(2), reads("val a = 1\nval v = BuildConfig\n    .VERSION_NAME\n"))
        assertEquals(listOf(1), reads("import sh.edm.s1kiosk.BuildConfig\n"))
        assertEquals(emptyList<Int>(), reads("val g = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION\n"))
    }
}
