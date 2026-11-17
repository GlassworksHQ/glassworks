/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared app names no device (#497). Every name a person sees comes from the frame ([friendlyName],
 * [Frame.shortName], [Frame.hardware]), so the Mirror never reads "Aqara S1 Plus". The scan reads the string literals
 * (not the comments) of every module outside `frames/`: the core, the pane API, the engines, the panes and the
 * libraries, with their web assets, which include the setup page's built bundle (`npm run build:lan`, which CI checks
 * against its sources) and the Gecko extension. Manage's pages are Kotlin in the core.
 *
 * Frame-only code may name its own device: everything under `frames/<id>/`, and a pane in [FRAME_ONLY_PANES].
 */
class DeviceNamesTest {
    /** A device or its maker, as a person would read it. Lowercase "mirror" and ids such as `aqara-s1-plus` don't count. */
    private val name = Regex("""\b(Aqara|S1|lululemon|Mirror|Pixel|[Tt]ablets?|A133|Allwinner|NSPanel|Echo Show)\b""")

    /** Not a device: the app's product name (AppBuild.S1_KIOSK) and Material Design icon ids (`mdi:tablet-dashboard`). */
    private val notADevice = listOf(Regex("""S1 Kiosk"""), Regex("""mdi:[a-z0-9-]+"""))

    /**
     * Panes only one frame can run, by the capability only that frame provides (frame.json's `provides`). They may name
     * that frame's device: the radio pane shows the S1's stock Aqara firmware.
     */
    private val FRAME_ONLY_PANES = mapOf("panes/zigbee-thread" to "silabs-radio")

    /**
     * Literals that name a device on purpose, by package path: the device frames the generic app recognises, and the
     * `Build.MODEL` of a device whose frame says how it stands. These are frame data that live in the core.
     */
    private val ALLOWED = mapOf(
        "dev/glassworks/frame/DeviceFrame.kt" to setOf("lululemon-mirror", "lululemon", "Studio Mirror"),
        "dev/glassworks/frame/GenericAndroid.kt" to setOf("Pixel Tablet"),
    )

    private val shared: List<File> get() = RepoFiles.modules.filter { m ->
        val rel = RepoFiles.rel(m)
        !rel.startsWith("frames/") && rel !in FRAME_ONLY_PANES
    }

    /** What [text] names, once the product name and icon ids are taken out. */
    private fun names(text: String): List<String> =
        name.findAll(notADevice.fold(text) { t, r -> r.replace(t, " ") }).map { it.value }.toList()

    /** [f]'s texts a person could see, each with its line: string literals in code, markup without its comments. */
    private fun texts(f: File): List<Pair<Int, String>> = when (f.extension) {
        "kt" -> Literals.of(f.readText(), Literals.Language.KOTLIN)
        "java" -> Literals.of(f.readText(), Literals.Language.JAVA)
        "js", "mjs" -> Literals.of(f.readText(), Literals.Language.JS)
        else -> f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            .lines().mapIndexed { i, l -> i + 1 to l }
    }

    @Test
    fun `the shared app names no device`() {
        val files = RepoFiles.sources(atLeast = 250, inModules = shared) { it.extension in setOf("kt", "java", "js", "mjs", "html", "xml") }
        val found = files.flatMap { f ->
            val allowed = runCatching { ALLOWED[RepoFiles.packagePath(f)] }.getOrNull().orEmpty()
            texts(f).filter { (_, text) -> text !in allowed && names(text).isNotEmpty() }
                .map { (line, text) -> "${RepoFiles.rel(f)}:$line: ${names(text).distinct()} in \"${text.take(160)}\"" }
        }
        assertTrue(
            "A device's name in the shared app. Take it from the frame (friendlyName(), shortName, hardware), or move " +
                "the code into frames/<id>/ (#497):\n${found.joinToString("\n")}",
            found.isEmpty(),
        )
    }

    @Test
    fun `the scan reads the setup page, the extension and Manage`() {
        val files = RepoFiles.sources(atLeast = 250, inModules = shared).map(RepoFiles::rel)
        for (f in listOf("core/src/main/assets/setup/setup.js", "engines/engine-gecko/src/main/assets/extension/content.js")) {
            assertTrue(f, f in files)
        }
        assertTrue(files.any { it.endsWith("dev/glassworks/manage/ManagePages.kt") })
        assertTrue("the setup page's literals, not one blob", Literals.of(RepoFiles.file("core/src/main/assets/setup/setup.js").readText(), Literals.Language.JS).size > 1000)
    }

    @Test
    fun `each frame-only pane needs what only one frame provides`() {
        val provides = File(RepoFiles.root, "frames").listFiles().orEmpty().mapNotNull { d ->
            val json = File(d, "design/frame.json").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val list = Regex(""""provides"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1).orEmpty()
            d.name to Regex(""""([^"]+)"""").findAll(list).map { it.groupValues[1] }.toSet()
        }
        for ((pane, capability) in FRAME_ONLY_PANES) {
            assertEquals(pane, 1, provides.count { capability in it.second })
            val code = RepoFiles.code(atLeast = 1, inModules = listOf(RepoFiles.file(pane))).joinToString("\n") { it.readText() }
            assertTrue("$pane requires $capability", Regex("""requires\s*=\s*setOf\([^)]*Capability\.${capability.uppercase().replace('-', '_')}""").containsMatchIn(code))
        }
    }

    @Test
    fun `the scan sees names in strings and templates, and none in comments`() {
        val kotlin = """
            // Aqara in a comment
            /* the Mirror, in a block */
            val a = "Settings → Devices & services, where ${'$'}{if (x) "Aqara S1 Plus" else name} shows up"
            val b = ""${'"'}
                on a lululemon Mirror
            ""${'"'}
            val c = '"' + "https://example.com/x" // Pixel Tablet
            /* outer /* inner */ val d = "Echo Show" */
        """.trimIndent()
        val texts = Literals.of(kotlin, Literals.Language.KOTLIN)
        assertEquals(listOf("Aqara", "S1", "lululemon", "Mirror"), texts.flatMap { names(it.second) })
        assertEquals("the inner literal's line", 3, texts.single { "Aqara" in it.second }.first)

        val js = """var r=/["'`]/g,t=`Not supported by ${'$'}{n} on the Mirror`,u=a/b/c,v="S1 Kiosk ${'$'}{v}",w='Aqara',x=n++/2,y="Pixel""""
        assertEquals(listOf("Mirror", "Aqara", "Pixel"), Literals.of(js, Literals.Language.JS).flatMap { names(it.second) })
        assertEquals("a JavaScript comment ends at the first */", listOf("NSPanel"), Literals.of("""/* a /* b */ x="NSPanel" """, Literals.Language.JS).flatMap { names(it.second) })
        val java = """class C { /* a /* b */ String label = "Aqara"; String block = ""${'"'}
            on the Mirror""${'"'}; }"""
        assertEquals("a Java comment ends at the first */", listOf("Aqara", "Mirror"), Literals.of(java, Literals.Language.JAVA).flatMap { names(it.second) })
        assertEquals(listOf("tablet"), names("on a reset tablet with mdi:tablet-dashboard"))
    }
}

/** The string literals of Kotlin, Java or JavaScript source, comments left out, each with the line it starts on. */
internal object Literals {
    /**
     * What differs between the three: Kotlin's block comments nest, and its quoted and raw strings take templates. Java's
     * strings take none. JavaScript's templates are its backtick strings, and it has regular expression literals.
     */
    enum class Language { KOTLIN, JAVA, JS }

    fun of(src: String, language: Language): List<Pair<Int, String>> = Lexer(src, language).run { code(untilBrace = false); found }

    private class Lexer(private val s: String, private val language: Language) {
        private val js = language == Language.JS

        val found = ArrayList<Pair<Int, String>>()
        private var i = 0
        private val lineStarts = listOf(0) + s.indices.filter { s[it] == '\n' }.map { it + 1 }

        private fun lineOf(p: Int): Int = lineStarts.binarySearch(p).let { if (it >= 0) it + 1 else -it - 1 }

        /** Code up to the end, or (in a template) up to the brace that closes `${`. */
        fun code(untilBrace: Boolean) {
            var depth = 0
            var prev = '('
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> { i++; continue }
                    s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> blockComment()
                    !js && s.startsWith("\"\"\"", i) -> { string("\"\"\""); prev = 'a' }
                    c == '"' || c == '\'' || (js && c == '`') -> { string(c.toString()); prev = 'a' }
                    js && c == '/' && prev in "(,=:[!&|?{};+-*%<>~^" && !postfix() -> { regex(); prev = 'a' }
                    c == '{' -> { depth++; i++; prev = c }
                    c == '}' -> { i++; if (untilBrace && depth == 0) return; depth--; prev = c }
                    else -> { i++; prev = c }
                }
            }
        }

        /** A block comment. Kotlin's nest, and end at the matching close; Java's and JavaScript's end at the first close. */
        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) && (depth == 0 || language == Language.KOTLIN) -> { depth++; i += 2 }
                    s.startsWith("*/", i) -> { i += 2; if (--depth == 0) return }
                    else -> i++
                }
            }
        }

        /** The `/` at [i] follows `++` or `--` (`n++/2`): a division, not a regular expression. */
        private fun postfix(): Boolean {
            var p = i - 1
            while (p >= 0 && s[p].isWhitespace()) p--
            return p >= 1 && s[p] == s[p - 1] && s[p] in "+-"
        }

        private fun string(delim: String) {
            val start = i
            i += delim.length
            val templates = when (language) {
                Language.KOTLIN -> delim != "'"
                Language.JAVA -> false
                Language.JS -> delim == "`"
            }
            val escapes = delim != "\"\"\"" || language == Language.JAVA
            val text = StringBuilder()
            while (i < s.length) {
                if (escapes && s[i] == '\\') { text.append(' '); i += 2; continue }
                if (delim == "\"\"\"" && s.startsWith(delim, i)) {
                    i += delim.length
                    while (i < s.length && s[i] == '"') i++
                    break
                }
                if (delim != "\"\"\"" && s.startsWith(delim, i)) { i++; break }
                if (templates && s.startsWith("\${", i)) { text.append(' '); i += 2; code(untilBrace = true); continue }
                text.append(s[i])
                i++
            }
            found += lineOf(start) to text.toString()
        }

        private fun regex() {
            i++
            var inClass = false
            while (i < s.length && s[i] != '\n') {
                val c = s[i]
                i++
                when {
                    c == '\\' -> i++
                    c == '[' -> inClass = true
                    c == ']' -> inClass = false
                    c == '/' && !inClass -> break
                }
            }
            while (i < s.length && s[i].isLetter()) i++
        }
    }
}
