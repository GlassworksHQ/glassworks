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
 * against its sources) and the Gecko extension, and their pane and page JSON and CSS. Manage's pages are Kotlin in the
 * core. The S1 app's own tests and mockups are checked too: a shot that shows the Mirror is drawn from its frame
 * (`theMirrorShotsAreDrawnFromTheMirrorsFrame`).
 *
 * Frame-only code may name its own device: everything under `frames/<id>/`, and a pane in [FRAME_ONLY_PANES].
 */
class DeviceNamesTest {
    /**
     * A device or its maker, as a person would read it. Lowercase "mirror" and ids such as `aqara-s1-plus` don't count.
     *
     * The list is today's devices, spelled out: **a new frame's name has to be added here** (a Sonoff panel, a Galaxy
     * Tab, …), or this scan reads past it and the shared app may name it (#502). `DeviceFrame`'s devices are here too.
     */
    private val name = Regex("""\b(Aqara|S1|lululemon|Mirror|Pixel|[Tt]ablets?|A133|Allwinner|NSPanel|Echo Show)\b""")

    /** Not a device: Material Design icon ids (`mdi:tablet-dashboard`). */
    private val notADevice = listOf(Regex("""mdi:[a-z0-9-]+"""))

    /** A block comment in markup. Taken out before a file's lines are read, its newlines kept. */
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** A shot's name as a test or mockup saves it: `Mockup.save("…")`, `shoot("""…""")`, `shot ("…")`, `setupShot("…")`. */
    private val shot = Regex("""\b(?:save|shoot|shot|setupShot)\s*\(\s*"{1,3}([^"]*)""")

    /**
     * A function's line, the name it gives when that is an identifier, and the indent it starts at (see [functions]).
     * `@Test` and the like may sit on the same line.
     */
    private val FUNCTION = Regex(
        """(?m)^([ \t]*)(?:(?:private|internal|public|override|suspend|inline|operator)\s+|@[A-Za-z_][\w.]*(?:\([^)]*\))?\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)?""",
    )

    /** A name something is called with: `helper(`, `helper<…>(`. */
    private val CALL = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*[<(]""")

    /** `TestFrames.mirror(…)` or `TestFrames::mirror`: the Mirror's own frame. */
    private val theMirror = Regex("""TestFrames(\.|::)mirror""")

    /** Anything that builds a frame in a test: `TestFrames.…`, `TestFrames::s1`, `AqaraS1Plus (…)`, `GenericAndroid(…)`. */
    private val FRAME = Regex("""TestFrames[.:]|AqaraS1Plus\s*\(|GenericAndroid\s*\(""")

    /** A frame that isn't the Mirror's: `TestFrames.s1`, `TestFrames::s1`, `AqaraS1Plus (…)`, `GenericAndroid(…)`. */
    private val ANOTHER_FRAME = Regex("""TestFrames(?:\.|::)(?!mirror)|AqaraS1Plus\s*\(|GenericAndroid\s*\(""")

    /** A quoted string, so a name inside one isn't read as a variable. */
    private val Quoted = Regex("\"[^\"]*\"")

    /** A name, so an argument that isn't the Mirror's frame can be read from the `val` it comes from. */
    private val CALL_NAME = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

    /** A shot's name that says the Mirror: `mirror`, `mirror-settings`, `ways-no-touch-mirror`. */
    private val MIRROR_SHOT = Regex("""(^|-)(?i:mirror)(-|$)""")

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
        // Vitro's bundled Material Design icons, keyed by id without the `mdi:` prefix.
        "dev/glassworks/engine/vitro/ui/MdiIcons.kt" to setOf("tablet", "tablet-dashboard"),
    )

    private val shared: List<File> get() = RepoFiles.modules.filter { m ->
        val rel = RepoFiles.rel(m)
        !rel.startsWith("frames/") && rel !in FRAME_ONLY_PANES
    }

    /** The shared app's files the scan reads: code, the web assets, and the pane and page JSON and CSS. */
    private fun scanned(): List<File> = RepoFiles.sources(atLeast = 250, inModules = shared) {
        it.extension in setOf("kt", "java", "js", "mjs", "html", "xml", "json", "css")
    }

    /** What [text] names, once the product name and icon ids are taken out. */
    private fun names(text: String): List<String> =
        name.findAll(notADevice.fold(text) { t, r -> r.replace(t, " ") }).map { it.value }.toList()

    /** [f]'s texts a person could see, each with its line: string literals in code, markup without its comments. */
    private fun texts(f: File): List<Pair<Int, String>> = when (f.extension) {
        "kt" -> Literals.of(f.readText(), Literals.Language.KOTLIN)
        "java" -> Literals.of(f.readText(), Literals.Language.JAVA)
        "js", "mjs" -> Literals.of(f.readText(), Literals.Language.JS)
        "css" -> lines(cssWithoutComments(f.readText()))
        // JSON has no comments ("<!-- … -->" in a value is text a person reads), and its escapes are read per line,
        // so an escape that stands for a line break doesn't move the numbers the file has.
        "json" -> f.readText().lines().mapIndexed { i, l -> i + 1 to unescapedJson(l) }
        else -> lines(blanked(f.readText(), HTML_COMMENT))
    }

    /** [text]'s lines, each with its number. */
    private fun lines(text: String): List<Pair<Int, String>> = text.lines().mapIndexed { i, l -> i + 1 to l }

    /** [text] with each [comments] match blanked and its newlines kept, so the line numbers stay the file's. */
    private fun blanked(text: String, comments: Regex): String =
        text.replace(comments) { m -> "\n".repeat(m.value.count { it == '\n' }) }

    /**
     * [text] with JSON's escapes read as a person sees them: `\u004dirror` is `Mirror`, `\n` a space, and `\\` stays one
     * backslash, so a value that spells an escape is the text it shows.
     */
    private fun unescapedJson(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val escape = text.getOrNull(i + 1)
            if (c != '\\' || escape == null) { out.append(c); i++; continue }
            val hex = text.substring((i + 2).coerceAtMost(text.length), (i + 6).coerceAtMost(text.length))
            when {
                escape == 'u' && hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> {
                    out.append(hex.toInt(16).toChar()); i += 6
                }
                escape in "nrt" -> { out.append(' '); i += 2 }
                else -> { out.append(escape); i += 2 } // \\ is one backslash, \" one quote
            }
        }
        return out.toString()
    }

    /**
     * [text] without its CSS comments, and with the text a person sees inside a quoted string left alone: a rule's
     * `content: "…"` may hold comment characters on the screen, which are not a comment. A `\` escapes the character
     * after it, so an escaped quote doesn't end the string.
     */
    private fun cssWithoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == '\\' -> {
                    out.append(c)
                    if (i + 1 < text.length) out.append(text[i + 1])
                    i += 2
                }
                quote != null -> { out.append(c); if (c == quote) quote = null; i++ }
                c == '"' || c == '\'' -> { quote = c; out.append(c); i++ }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    out.append("\n".repeat(text.substring(i, end).count { it == '\n' }))
                    i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun `the shared app names no device`() {
        val files = scanned()
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
    fun `the scan reads the setup page, the extension, Manage and the pane and page JSON`() {
        val files = scanned().map(RepoFiles::rel)
        for (f in listOf(
            "core/src/main/assets/setup/setup.js", "engines/engine-gecko/src/main/assets/extension/content.js",
            "core/src/main/assets/setup/setup.css", "core/src/main/java/dev/glassworks/screen/page.json",
            "panes/camera/src/main/java/dev/glassworks/camera/pane.json",
        )) {
            assertTrue(f, f in files)
        }
        assertTrue(files.any { it.endsWith("dev/glassworks/manage/ManagePages.kt") })
        assertTrue("the setup page's literals, not one blob", Literals.of(RepoFiles.file("core/src/main/assets/setup/setup.js").readText(), Literals.Language.JS).size > 1000)
        val css = File.createTempFile("device-names", ".css").apply {
            deleteOnExit()
            writeText("/* the Mirror, a Pixel Tablet */\n.a::after { content: \"on an Echo Show\" }\n")
        }
        assertEquals("a CSS string's name is read, a comment's isn't", listOf("Echo Show"), texts(css).flatMap { names(it.second) })
        val json = File.createTempFile("device-names", ".json").apply {
            deleteOnExit()
            writeText("""{"label":"<!-- Mirror -->","note":"the Aqara S1 Plus"}""")
        }
        assertEquals("a JSON value is read whole, even a markup-looking one", listOf("Mirror", "Aqara", "S1"), texts(json).flatMap { names(it.second) })
        val jsonEscapes = File.createTempFile("device-names", ".json").apply {
            deleteOnExit()
            writeText("""{"a":"\u004dirror","b":"\\u004dirror","c":"\u000aMirror"}""")
        }
        assertEquals(
            "a JSON escape names what it shows, one that spells an escape shows nothing, and the line stays the file's",
            listOf(1 to listOf("Mirror", "Mirror")),
            texts(jsonEscapes).map { (line, text) -> line to names(text) },
        )
        val escaped = File.createTempFile("device-names", ".css").apply {
            deleteOnExit()
            writeText(""".a::after { content: "a\"b" }
                |/* the Mirror */""".trimMargin())
        }
        assertEquals(
            "an escaped quote doesn't end a CSS string, so the comment after it stays a comment",
            emptyList<String>(), texts(escaped).flatMap { names(it.second) },
        )
    }

    /**
     * A test or mockup that saves a shot named for the Mirror draws it from `TestFrames.mirror` (#502), never from
     * `TestFrames.s1()`: `shoot("mirror")` would otherwise show the S1's name and touch. The frame is the one the shot's
     * call names, or the one its function builds — a helper it calls (`makeMirror()`) or the function around it, so a
     * file may draw the S1 in one function and the Mirror in another. A file that builds no frame draws no device and is
     * out of scope (the voice overlay, which stands a view group up instead).
     */
    @Test
    fun theMirrorShotsAreDrawnFromTheMirrorsFrame() {
        val tests = RepoFiles.sources(atLeast = 120, sourceSet = "test", inModules = listOf(RepoFiles.s1App)) { it.extension == "kt" }
        val found = tests.flatMap { f ->
            mirrorShotsWithoutTheFrame(f.readText()).map { "${RepoFiles.rel(f)}: $it drawn without TestFrames.mirror" }
        }
        assertTrue(
            "A shot named for the Mirror, drawn from another frame. Build it with TestFrames.mirror(context):\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    /** [code]'s Mirror shots that aren't drawn from the Mirror's frame; a file that builds no frame has none. */
    private fun mirrorShotsWithoutTheFrame(code: String): List<String> {
        if (!FRAME.containsMatchIn(code)) return emptyList()
        val functions = functions(code)
        val bodies = functions.mapNotNull { f -> f.name?.let { it to f.body } }.toMap()
        return functions.flatMap { f ->
            val around = functions.filter { it.start <= f.start && it.end >= f.end && it !== f }.joinToString("\n") { it.body }
            shotCalls(f.body)
                .filter { (name, args) -> MIRROR_SHOT.containsMatchIn(name) && !drawnFromTheMirror(args, f.body, around, bodies, code) }
                .map { it.first }
        }.distinct()
    }

    @Test
    fun `a shot named for the Mirror is read however it is spelled and however its test is written`() {
        val raw = "\"\"\""
        val snippets = listOf(
            """fun capture(context: Context) = shoot("mirror-settings", TestFrames.s1(context))""",
            """fun capture(context: Context) = shoot(${raw}mirror-settings${raw}, TestFrames.s1(context))""",
            """@Test fun capture(context: Context) = shoot ("mirror-settings", TestFrames.s1(context))""",
            """fun capture(context: Context) = GenericAndroid (context, prop = { "" }).let { shoot("mirror-settings", it) }""",
            // A comment naming the Mirror doesn't decide what the call draws.
            """fun capture(context: Context) = shoot("mirror-settings", /* use TestFrames.mirror */ TestFrames.s1(context))""",
            // A frame of its own, in a val the call passes by name, while the function builds the Mirror for a preview.
            """private val frame = TestFrames.s1(context)
                |fun makeMirror() = TestFrames.mirror(context)
                |fun capture() = shoot("mirror-settings", frame)""".trimMargin(),
            // A ")" inside a string is not the end of the call.
            """fun capture(context: Context) = shoot("mirror-settings", run { println(")"); TestFrames.s1(context) })""",
            // The S1 handed over as a callable reference.
            """fun makeMirror() = TestFrames.mirror(context)
                |fun capture(context: Context) = shoot("mirror-settings", (TestFrames::s1)(context))""".trimMargin(),
        )
        assertEquals(List(snippets.size) { "mirror-settings" }, snippets.flatMap(::mirrorShotsWithoutTheFrame))
        assertEquals(
            "the Mirror's own frame, in the call, its function, a helper it calls or a val it passes",
            emptyList<String>(),
            listOf(
                """fun capture(context: Context) = shoot("mirror-settings", TestFrames.mirror(context))""",
                """fun makeMirror(context: Context) = TestFrames.mirror(context)
                    |fun capture(context: Context) = shoot("mirror-settings", makeMirror(context))""".trimMargin(),
                // A local shot function uses the frame its enclosing function built.
                """fun capture(context: Context) {
                    |    val frame = TestFrames.mirror(context)
                    |    fun saveMirror() = shoot("mirror-settings", frame)
                    |    saveMirror()
                    |}""".trimMargin(),
                // The frame is a property of the test class, and the call passes it by name.
                """private val frame = TestFrames.mirror(context)
                    |fun capture() = shoot("mirror-settings", frame)""".trimMargin(),
            ).flatMap(::mirrorShotsWithoutTheFrame),
        )
        assertEquals(
            "a function that saves no shot has none",
            emptyList<String>(),
            mirrorShotsWithoutTheFrame("""fun capture(context: Context) = loadSnapshot ("mirror-settings", TestFrames.s1(context))"""),
        )
    }

    /**
     * Whether a shot with [args] is drawn from the Mirror. What the call names decides: the last frame it names, so a
     * comment before another frame doesn't count; a name it passes is read from the val [file] gives it. A call that
     * names no frame is its function's ([body], a helper in [functions], or the function around it, [around]).
     *
     * This reads source text, not Kotlin: the `val`'s initializer is one line, and a name is followed by the text rather
     * than by its scope, so a shadowed or reassigned name, or a frame another function returns, is beyond it. A guard
     * that needs those is a Kotlin parser, and that is its own change.
     */
    private fun drawnFromTheMirror(args: String, body: String, around: String, functions: Map<String, String>, file: String): Boolean {
        val mirror = theMirror.findAll(args).map { it.range.first }
        val other = ANOTHER_FRAME.findAll(args).map { it.range.first }
        val last = (mirror + other).maxOrNull()
        if (last != null) return last in mirror
        val names = args.replace(Quoted, " ").let { code -> CALL_NAME.findAll(code).map { it.value }.toList() }
        val assigned = names.mapNotNull { name -> assignedIn(name, file) }
        if (assigned.any { theMirror.containsMatchIn(it) }) return true
        if (assigned.any { FRAME.containsMatchIn(it) }) return false
        return drawsTheMirror(body, functions) || drawsTheMirror(around, functions)
    }

    /** What [file] gives [name]: the right-hand side of the `val`/`var` it declares, or null when it declares none. */
    private fun assignedIn(name: String, file: String): String? =
        Regex("""(?m)^[ \t]*(?:(?:private|internal|public)\s+)?(?:val|var)\s+${Regex.escape(name)}\b[^\n=]*=\s*([^\n]*)""")
            .find(file)?.groupValues?.get(1)

    /** The shot names [text] saves. */
    private fun shotsIn(text: String): List<String> = shot.findAll(text).map { it.groupValues[1] }.toList()

    /** Each shot [text] saves: its name, and the text between its call's parentheses. */
    private fun shotCalls(text: String): List<Pair<String, String>> = shot.findAll(text).map { m ->
        m.groupValues[1] to argsAt(text, text.indexOf('(', m.range.first))
    }.toList()

    /** [text] from the parenthesis at [open] to the one that closes it, or to the end; a `)` in a string doesn't count. */
    private fun argsAt(text: String, open: Int): String {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null -> if (c == quote) quote = null
                c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
            i++
        }
        return text.substring((open + 1).coerceAtMost(text.length))
    }

    /**
     * Whether [body] draws the Mirror: it says `TestFrames.mirror`, or it calls one of the file's own [functions], as a
     * mockup's `makeMirror()` helper does.
     */
    private fun drawsTheMirror(body: String, functions: Map<String, String>, seen: Set<String> = emptySet()): Boolean =
        theMirror.containsMatchIn(body) ||
            CALL.findAll(body).map { it.groupValues[1] }.filter { it !in seen }
                .any { name -> functions[name]?.let { drawsTheMirror(it, functions, seen + name) } == true }

    /** One of [functions]: its name when that is an identifier, its text, and where it starts and ends in the file. */
    private class Fn(val name: String?, val body: String, val start: Int, val end: Int)

    /**
     * [code]'s functions, each with its own text: from a function's line to the next one at the same or a lower indent,
     * so a shot is read against the frame its function builds. Only spaces and tabs count as the indent, so a blank line
     * before a function is not part of its indent.
     */
    private fun functions(code: String): List<Fn> {
        val starts = FUNCTION.findAll(code)
            .map { Triple(it.range.first, it.groupValues[1].length, it.groupValues[2]) }.toList()
        return starts.mapIndexed { i, (start, indent, name) ->
            val end = starts.drop(i + 1).firstOrNull { it.second <= indent }?.first ?: code.length
            Fn(name.ifEmpty { null }, code.substring(start, end), start, end)
        }
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
            val e = "Set up ${'$'}tablet, a reset tablet"
            /* outer /* inner */ val d = "Echo Show" */
        """.trimIndent()
        val texts = Literals.of(kotlin, Literals.Language.KOTLIN)
        assertEquals("a template's name is code, not text", listOf("Aqara", "S1", "lululemon", "Mirror", "tablet"), texts.flatMap { names(it.second) })
        assertEquals("the inner literal's line", 3, texts.single { "Aqara" in it.second }.first)

        val js = """var r=/["'`]/g,t=`Not supported by ${'$'}{n} on the Mirror`,u=a/b/c,v="Glassworks ${'$'}{v}",w='Aqara',x=n++/2,y="Pixel""""
        assertEquals(listOf("Mirror", "Aqara", "Pixel"), Literals.of(js, Literals.Language.JS).flatMap { names(it.second) })
        assertEquals("a JavaScript comment ends at the first */", listOf("NSPanel"), Literals.of("""/* a /* b */ x="NSPanel" """, Literals.Language.JS).flatMap { names(it.second) })
        val java = """class C { /* a /* b */ String label = "Aqara"; String block = ""${'"'}
            on the Mirror""${'"'}; }"""
        assertEquals("a Java comment ends at the first */", listOf("Aqara", "Mirror"), Literals.of(java, Literals.Language.JAVA).flatMap { names(it.second) })
        assertEquals(listOf("tablet"), names("on a reset tablet with mdi:tablet-dashboard"))
        val raw = "\"\"\""
        assertEquals(
            "a shot's name, quoted or raw",
            listOf("s1", "mirror-settings"),
            shotsIn("""val a = shoot("s1"); val b = shoot(${raw}mirror-settings${raw});"""),
        )
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
                if (language == Language.KOTLIN && templates && s[i] == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                    text.append(' ')
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    continue
                }
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
