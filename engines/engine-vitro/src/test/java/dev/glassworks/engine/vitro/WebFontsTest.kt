/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import dev.glassworks.engine.vitro.fonts.Sfnt
import dev.glassworks.engine.vitro.fonts.WebFonts
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.ui.VitroFontContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.testing.RepoFiles
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Web fonts from `type: css` resources: read only when a family Vitro doesn't have is drawn, drawn in the fallback
 * until they arrive and then once more, kept on disk, capped, and WOFF unwrapped to sfnt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebFontsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val fonts = File(RepoFiles.root, "engines/engine-vitro/src/test/parity/fixtures/fonts")
    private val base = "http://homeassistant.local:8123"
    private val fetched = ArrayList<String>()
    private val served = HashMap<String, ByteArray>()
    /** A URL the fake server answers from another one (where the redirects end). */
    private val redirects = HashMap<String, String>()
    private val queued = ArrayList<Runnable>()
    private val posted = ArrayList<Runnable>()
    private var notified = 0
    private val listener: () -> Unit = { notified++ }
    private lateinit var cache: File

    private val realFetcher = WebFonts.fetcher
    private val realExecutor = WebFonts.executor
    private val realPost = WebFonts.postToMain

    @Before
    fun setUp() {
        cache = Files.createTempDirectory("vitro-fonts").toFile()
        WebFonts.reset()
        WebFonts.fetcher = WebFonts.Fetcher { url, max ->
            fetched += url
            served[url]?.takeIf { it.size <= max }?.let { WebFonts.Fetched(it, redirects[url] ?: url) }
        }
        WebFonts.executor = Executor { queued += it }
        WebFonts.postToMain = { posted += it }
        WebFonts.addListener(listener)
        VitroFonts.family = VitroFonts.HA_DEFAULT
    }

    @After
    fun tearDown() {
        // Nothing of this test's may stay queued: the loader is one object for the whole test JVM.
        drain()
        WebFonts.removeListener(listener)
        WebFonts.reset()
        WebFonts.fetcher = realFetcher
        WebFonts.executor = realExecutor
        WebFonts.postToMain = realPost
        WebFonts.pending.set(0)
        cache.deleteRecursively()
    }

    /** Runs the background work and then what it posts back, until nothing is left. */
    private fun drain() {
        while (queued.isNotEmpty() || posted.isNotEmpty()) {
            queued.toList().also { queued.clear() }.forEach { it.run() }
            posted.toList().also { posted.clear() }.forEach { it.run() }
        }
    }

    private fun css(name: String, text: String) {
        served["$base/local/$name"] = text.toByteArray()
    }

    private fun font(path: String, file: String) {
        served["$base/local/$path"] = File(fonts, file).readBytes()
    }

    private fun configure(vararg sheets: String) =
        WebFonts.configure(base, sheets.map { LovelaceResource("/local/$it", "css") } + LovelaceResource("/local/card.js", "module"), cache)

    private fun width(tf: Typeface) = Paint().apply { typeface = tf; textSize = 40f }.measureText("Entryway ceiling")

    @Test
    fun builtInFamiliesFetchNoFontAndNoStylesheetsMeansNoRequest() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        drain()
        // Built-in families draw at once; the sheets are read once (one may declare them), the unused font never.
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f))
        VitroFonts.typeface(activity, 500, 14f, fontFamily = "Roboto, sans-serif")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "monospace")
        drain()
        assertEquals(listOf("$base/local/fonts.css"), fetched)
        assertEquals("nothing a drawn family needs changed: no redraw", 0, notified)
        // A dashboard without a `type: css` resource never asks for anything.
        WebFonts.reset()
        fetched.clear()
        WebFonts.configure(base, listOf(LovelaceResource("/local/card.js", "module")), cache)
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif")
        drain()
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aSheetCanDeclareABuiltInName() {
        // A page's @font-face wins over an installed font of the same name, Roboto included.
        css("fonts.css", "@font-face { font-family: Roboto; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        drain()
        // Roboto while the sheet is read, the declared face after.
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f))
        drain()
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f))
        assertTrue(notified >= 1)
    }

    @Test
    fun aSourceThatFailsMovesOnToTheNext() {
        // The first source doesn't download; the second, with no format hint, is WOFF2 underneath; the third loads.
        css("fonts.css", "@font-face { font-family: P; src: url(gone.woff), url(p-noext), url(p.ttf) format('truetype') }")
        served["$base/local/p-noext"] = File(fonts, "bitter-vf.woff2").readBytes()
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "P, sans-serif")
        drain()
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "P, sans-serif"))
        assertEquals(listOf("$base/local/gone.woff", "$base/local/p-noext", "$base/local/p.ttf"), fetched.drop(1))
    }

    @Test
    fun anImportedSheetComesBeforeTheRulesThatImportIt() {
        // root.css imports base.css, then overrides its face: the override wins, as in the browser.
        css("root.css", "@import url(base.css); @font-face { font-family: F; src: url(override.ttf) }")
        css("base.css", "@font-face { font-family: F; src: url(base.ttf) }")
        font("override.ttf", "pacifico.ttf")
        font("base.ttf", "comic-neue-400.ttf")
        configure("root.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "F")
        drain()
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "F")
        drain()
        assertTrue("$base/local/override.ttf" in fetched)
        assertTrue("$base/local/base.ttf" !in fetched)
    }

    @Test
    fun aFailedJobLeavesTheLoaderWorking() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        val list = "Pacifico, sans-serif"
        // Reading the sheets throws: nothing is left waiting, and the family is skipped for the next.
        WebFonts.fetcher = WebFonts.Fetcher { _, _ -> throw IllegalStateException("network") }
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = list)
        drain()
        assertEquals(0, WebFonts.pending.get())
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = list))
        // Loading the face throws: it's failed, not loading forever, and the redraw still comes.
        WebFonts.reset()
        WebFonts.fetcher = WebFonts.Fetcher { url, max ->
            if (url.endsWith(".ttf")) throw IllegalStateException("network")
            served[url]?.takeIf { it.size <= max }?.let { WebFonts.Fetched(it, redirects[url] ?: url) }
        }
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = list)
        drain()
        assertEquals(0, WebFonts.pending.get())
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = list))
        // The face is failed, not still loading, and the view heard so (one redraw for the batch).
        assertTrue(WebFonts.lookup("Pacifico", 400, false) === WebFonts.Lookup.Absent)
        assertTrue("the listener ran", notified > 0)
    }

    @Test
    fun aWarmCacheKeepsTheSourceOrder() {
        // Brand lists a.ttf before b.ttf; Other already cached b.ttf. After a restart Brand must still try a.ttf first.
        css("fonts.css", "@font-face { font-family: Brand; src: url(a.ttf), url(b.ttf) } @font-face { font-family: Other; src: url(b.ttf) }")
        font("a.ttf", "comic-neue-400.ttf")
        font("b.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Other")
        drain()
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Other")
        drain()
        WebFonts.reset()
        fetched.clear()
        configure("fonts.css")
        drain()
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Brand")
        drain()
        assertTrue("a.ttf is tried first: $fetched", "$base/local/a.ttf" in fetched)
        val brand = VitroFonts.typeface(activity, 400, 14f, fontFamily = "Brand")
        assertNotEquals("Brand draws a.ttf, not the cached b.ttf", VitroFonts.typeface(activity, 400, 14f, fontFamily = "Other"), brand)
    }

    @Test
    fun aFontAndroidCantLoadIsAFailedSource() {
        // A TrueType signature, then nothing: Sfnt passes it, Android can't load it (Android 10 answers DEFAULT).
        css("fonts.css", "@font-face { font-family: P; src: url(cut.ttf), url(p.ttf) }")
        served["$base/local/cut.ttf"] = File(fonts, "pacifico.ttf").readBytes().copyOf(64)
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "P, sans-serif")
        drain()
        val tf = VitroFonts.typeface(activity, 400, 14f, fontFamily = "P, sans-serif")
        assertTrue("$base/local/p.ttf" in fetched)
        assertNotEquals(Typeface.DEFAULT, tf)
        assertNotEquals(VitroFonts.system(400), tf)
    }

    @Test
    fun aRedirectedSheetResolvesAgainstWhereItEnded() {
        // /local/fonts.css redirects to /assets/fonts/main.css: url(p.ttf) is /assets/fonts/p.ttf, as in the browser.
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        redirects["$base/local/fonts.css"] = "$base/assets/fonts/main.css"
        served["$base/assets/fonts/p.ttf"] = File(fonts, "pacifico.ttf").readBytes()
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif")
        drain()
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif"))
        assertTrue("$base/assets/fonts/p.ttf" in fetched)
        // After a restart, the cached sheet still resolves against where it came from.
        WebFonts.reset()
        fetched.clear()
        configure("fonts.css")
        drain()
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif"))
    }

    @Test
    fun aNameWithMonoInItIsNotTheSystemsMonospace() {
        // "Acme Mono" isn't installed and no sheet declares it: the list goes on to Pacifico.
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        val list = "\"Acme Mono\", Pacifico, serif"
        VitroFonts.typeface(activity, 400, 14f, fontFamily = list)
        drain()
        assertNotEquals(VitroFonts.typeface(activity, 400, 14f, fontFamily = "monospace"), VitroFonts.typeface(activity, 400, 14f, fontFamily = list))
        assertNotEquals(VitroFonts.typeface(activity, 400, 14f, fontFamily = "serif"), VitroFonts.typeface(activity, 400, 14f, fontFamily = list))
    }

    @Test
    fun aBareGenericIsThePlatformsAQuotedOneIsAFamily() {
        // A sheet declares a family named "serif": bare serif is still the platform's serif, "serif" is the sheet's.
        css("fonts.css", "@font-face { font-family: \"serif\"; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "\"serif\", sans-serif")
        drain()
        val platformSerif = VitroFonts.typeface(activity, 400, 14f, fontFamily = "serif")
        val declared = VitroFonts.typeface(activity, 400, 14f, fontFamily = "\"serif\", sans-serif")
        assertNotEquals(platformSerif, declared)
        assertNotEquals(VitroFonts.system(400), declared)
    }

    @Test
    fun aSheetImportedTwiceCountsAtBothPlaces() {
        // a.css imports common.css and overrides F; b.css imports common.css again: common's F wins, as in the cascade.
        css("common.css", "@font-face { font-family: F; src: url(common.ttf) }")
        css("a.css", "@import url(common.css); @font-face { font-family: F; src: url(a.ttf) }")
        css("b.css", "@import url(common.css);")
        font("common.ttf", "pacifico.ttf")
        font("a.ttf", "comic-neue-400.ttf")
        configure("a.css", "b.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "F")
        drain()
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "F")
        drain()
        assertTrue("$base/local/common.ttf" in fetched)
        assertTrue("$base/local/a.ttf" !in fetched)
        // Read once, used twice.
        assertEquals(1, fetched.count { it.endsWith("/common.css") })
    }

    @Test
    fun cursiveAndFantasyAreThePlatformsGenerics() {
        configure()
        assertSame(VitroFonts.typeface(activity, 400, 14f, fontFamily = "cursive"), VitroFonts.typeface(activity, 400, 14f, fontFamily = "\"Nope\", cursive"))
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "cursive"))
        // Android has no fantasy family: its sans-serif, and the list ends there (no serif after it).
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "fantasy, serif"))
    }

    @Test
    fun theDiskCacheStaysUnderItsLimit() {
        val dir = File(cache, "vitro-fonts").apply { mkdirs() }
        // Old files fill the cache to its limit: a new font pushes the oldest out, not past the limit.
        repeat(3) { i ->
            File(dir, "old$i.font").apply { writeBytes(ByteArray((WebFonts.MAX_DISK_BYTES / 3).toInt())); setLastModified(1_000L * (i + 1)) }
        }
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico")
        drain()
        assertNotEquals(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= WebFonts.MAX_DISK_BYTES)
        assertTrue("the oldest went first", !File(dir, "old0.font").exists())
    }

    @Test
    fun aWebFontDrawsTheFallbackFirstThenSwapsIn() {
        css("fonts.css", "@font-face { font-family: \"Pacifico\"; src: url(p.woff2) format('woff2'), url(p.ttf) format('truetype'); font-weight: 400 }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        drain()
        val list = "Pacifico, sans-serif"
        val first = VitroFonts.typeface(activity, 400, 14f, fontFamily = list)
        // The fallback (the next family) while the sheet and the font load.
        assertSame(VitroFonts.system(400), first)
        drain()
        assertEquals(listOf("$base/local/fonts.css", "$base/local/p.ttf"), fetched)
        assertTrue("the view hears the font arrived", notified >= 1)
        val swapped = VitroFonts.typeface(activity, 400, 14f, fontFamily = list)
        assertNotEquals(first, swapped)
        assertNotEquals("Pacifico's glyphs, not Roboto's", width(first), width(swapped), 0.5f)
        // Made once per face, weight and style.
        assertSame(swapped, VitroFonts.typeface(activity, 400, 14f, fontFamily = list))
        // 500 from a family with only 400: the 400 face, as is; 700 synthesizes bold.
        assertSame(swapped, VitroFonts.typeface(activity, 500, 14f, fontFamily = list))
        val bold = VitroFonts.typeface(activity, 700, 14f, fontFamily = list)
        assertTrue(bold.isBold)
        assertTrue(VitroFonts.typeface(activity, 400, 14f, italic = true, fontFamily = list).isItalic)
    }

    @Test
    fun aFamilyNoSheetDeclaresIsSkippedForTheNext() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "\"Google Sans\", sans-serif")
        drain()
        // Read once, then known: Google Sans isn't there, sans-serif is drawn, and nothing more is fetched.
        val notifiedBefore = notified
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "\"Google Sans\", sans-serif")
        drain()
        assertEquals(listOf("$base/local/fonts.css"), fetched)
        assertEquals(notifiedBefore, notified)
    }

    @Test
    fun aCardsOwnFamilyComesFromItsContext() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        val card = VitroFontContext(activity, "Pacifico, cursive")
        VitroFonts.typeface(card, 400, 14f)
        drain()
        val inCard = VitroFonts.typeface(card, 400, 14f)
        assertNotEquals(VitroFonts.system(400), inCard)
        // Outside the card, the theme's font.
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f))
        // A card inside that card, on a wrapper of its context, inherits it.
        assertSame(inCard, VitroFonts.typeface(android.view.ContextThemeWrapper(card, 0), 400, 14f))
    }

    @Test
    fun anImportedSheetAndAWoffFont() {
        css("fonts.css", "@import url(\"more/bitter.css\");")
        css("more/bitter.css", "@font-face { font-family: Bitter; src: url(bitter.woff) format('woff'); font-weight: 100 900 }")
        font("more/bitter.woff", "bitter-vf.woff")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Bitter")
        drain()
        val regular = VitroFonts.typeface(activity, 400, 14f, fontFamily = "Bitter")
        val bold = VitroFonts.typeface(activity, 700, 14f, fontFamily = "Bitter")
        assertNotEquals(VitroFonts.system(400), regular)
        // A variable font: 700 is the font's own bold instance, not a synthesized one, and draws wider.
        assertTrue(width(bold) > width(regular) + 1f)
        assertTrue(File(cache, "vitro-fonts").listFiles()!!.any { it.name.endsWith(".font") })
    }

    @Test
    fun aRestartDrawsFromDiskWithNoRequest() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico")
        drain()
        // A new run: everything forgotten but the disk.
        WebFonts.reset()
        fetched.clear()
        configure("fonts.css")
        drain()
        val tf = VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico")
        assertNotEquals("ready before the first view asks", VitroFonts.system(400), tf)
        assertTrue("the font itself comes from disk: $fetched", "$base/local/p.ttf" !in fetched)
        drain()
        // The sheet is read again once, in the background, in case it changed.
        assertEquals(listOf("$base/local/fonts.css"), fetched)
    }

    @Test
    fun aFontPastTheCapOrNotAFontDrawsTheFallback() {
        css("fonts.css", "@font-face { font-family: Big; src: url(big.ttf) } @font-face { font-family: Html; src: url(page.ttf) }")
        served["$base/local/big.ttf"] = ByteArray(WebFonts.MAX_FONT_BYTES + 1)
        served["$base/local/page.ttf"] = "<html>Not found</html>".toByteArray()
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Big, Html, serif")
        drain()
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Big, Html, serif")
        drain()
        // Both failed: the next family, and no second request for either.
        assertSame(VitroFonts.typeface(activity, 400, 14f, fontFamily = "serif"), VitroFonts.typeface(activity, 400, 14f, fontFamily = "Big, Html, serif"))
        assertEquals(1, fetched.count { it.endsWith("big.ttf") })
        assertEquals(1, fetched.count { it.endsWith("page.ttf") })
    }

    @Test
    fun noMoreThanTheCapOfFaces() {
        val rules = (1..WebFonts.MAX_FACES + 3).joinToString(" ") { "@font-face { font-family: F$it; src: url(f$it.ttf) }" }
        css("fonts.css", rules)
        (1..WebFonts.MAX_FACES + 3).forEach { font("f$it.ttf", "comic-neue-400.ttf") }
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "F1")
        drain()
        (1..WebFonts.MAX_FACES + 3).forEach { VitroFonts.typeface(activity, 400, 14f, fontFamily = "F$it") }
        drain()
        assertEquals(WebFonts.MAX_FACES, fetched.count { it.endsWith(".ttf") })
    }

    @Test
    fun aDifferentServerForgetsTheFonts() {
        css("fonts.css", "@font-face { font-family: Pacifico; src: url(p.ttf) }")
        font("p.ttf", "pacifico.ttf")
        configure("fonts.css")
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico")
        drain()
        val first = VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif")
        assertNotSame(VitroFonts.system(400), first)
        WebFonts.configure("http://192.0.2.20:8123", emptyList(), cache)
        assertSame(VitroFonts.system(400), VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif"))
        // A server whose sheet names Pacifico too, from its own file: its face loads, never the first server's.
        val other = "http://192.0.2.21:8123"
        served["$other/local/fonts.css"] = "@font-face { font-family: Pacifico; src: url(p.ttf) }".toByteArray()
        served["$other/local/p.ttf"] = File(fonts, "comic-neue-400.ttf").readBytes()
        WebFonts.configure(other, listOf(LovelaceResource("/local/fonts.css", "css")), cache)
        VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif")
        drain()
        val second = VitroFonts.typeface(activity, 400, 14f, fontFamily = "Pacifico, sans-serif")
        assertTrue("$other/local/p.ttf" in fetched)
        assertNotSame(first, second)
        assertNotSame(VitroFonts.system(400), second)
    }

    @Test
    fun aCardsFontContextKeepsEverythingElse() {
        // withFontFamily copies the context: every field but the Android context must be the very same object, so a
        // parameter added to VitroCardContext later (with a default) can't be dropped silently for cards with a font.
        val ctx = dev.glassworks.engine.vitro.ui.VitroCardContext(
            context = activity,
            haBase = base,
            store = dev.glassworks.engine.vitro.data.EntityStore(),
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(),
            accessToken = { null },
            callService = { _, _, _, _ -> },
            callServiceThen = { _, _, _, _, done -> done(true) },
            navigate = {},
            openMoreInfo = {},
            fetchHistory = { _, _, _ -> },
            subscribeTemplate = { _, _ -> {} },
        ).apply { cardVars = mapOf("x" to "1"); cardLayout = "grid" }
        val copy = ctx.withFontFamily("Pacifico")
        assertEquals("Pacifico", (copy.context as VitroFontContext).fontFamily)
        val own = setOf("context", "main", "dp")
        for (f in ctx.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name in own) continue
            f.isAccessible = true
            assertSame("VitroCardContext.${f.name} is copied", f.get(ctx), f.get(copy))
        }
    }

    @Test
    fun woffUnwrapsToTheSfntItWraps() {
        val woff = File(fonts, "bitter-vf.woff").readBytes()
        assertEquals(Sfnt.Kind.WOFF, Sfnt.kind(woff))
        val sfnt = Sfnt.toSfnt(woff)
        assertNotNull(sfnt)
        val b = ByteBuffer.wrap(sfnt!!)
        assertEquals(0x00010000, b.getInt(0))
        val n = b.getShort(4).toInt()
        assertTrue(n > 5)
        // Every table is where the directory says, and its bytes sum to the checksum the WOFF carried over.
        for (i in 0 until n) {
            val dir = 12 + 16 * i
            val tag = String(sfnt, dir, 4)
            val offset = b.getInt(dir + 8)
            val length = b.getInt(dir + 12)
            assertEquals(0, offset % 4)
            var sum = 0L
            var p = offset
            while (p < offset + length) {
                var word = 0L
                for (k in 0 until 4) word = (word shl 8) or (if (p + k < offset + length) (sfnt[p + k].toLong() and 0xFF) else 0L)
                // `head`'s checksum is taken with checkSumAdjustment (bytes 8..11) as zero.
                if (tag == "head" && p - offset == 8) word = 0
                sum = (sum + word) and 0xFFFFFFFFL
                p += 4
            }
            assertEquals("checksum of $tag", b.getInt(dir + 4).toLong() and 0xFFFFFFFFL, sum)
        }
        // Android loads it.
        val file = File(cache, "bitter.ttf").apply { writeBytes(sfnt) }
        assertNotEquals(width(Typeface.DEFAULT), width(Typeface.createFromFile(file)), 0.5f)
    }

    /** A WOFF 1.0 with [tables] (tag, data placed at its own offset, the length the sfnt table has). */
    private fun woffOf(tables: List<Triple<String, Int, ByteArray>>, origLengths: List<Int>? = null): ByteArray {
        val end = tables.maxOf { it.second + it.third.size }
        val b = ByteBuffer.allocate(end)
        b.putInt(0, 0x774F4646); b.putInt(4, 0x00010000); b.putInt(8, end); b.putShort(12, tables.size.toShort())
        b.putInt(16, 12 + 16 * tables.size + tables.sumOf { (it.third.size + 3) and 3.inv() } + 64)
        tables.forEachIndexed { i, (tag, offset, data) ->
            val e = 44 + 20 * i
            b.putInt(e, ByteBuffer.wrap(tag.toByteArray()).int); b.putInt(e + 4, offset); b.putInt(e + 8, data.size)
            b.putInt(e + 12, origLengths?.get(i) ?: data.size); b.putInt(e + 16, 0)
            b.position(offset); b.put(data)
        }
        return b.array()
    }

    @Test
    fun aWoffIsRebuiltInItsOwnDataOrderAndAMalformedOneRefused() {
        val dir = 44 + 20 * 2
        // The directory is in tag order; the data puts "bbbb" first: the sfnt keeps that layout.
        val ordered = Sfnt.unwrapWoff(woffOf(listOf(Triple("aaaa", dir + 8, ByteArray(8) { 1 }), Triple("bbbb", dir, ByteArray(8) { 2 }))))
        val ob = ByteBuffer.wrap(ordered)
        assertTrue(ob.getInt(12 + 16 + 8) < ob.getInt(12 + 8))
        // A table inside the directory, and two tables sharing bytes.
        assertNull(Sfnt.toSfnt(woffOf(listOf(Triple("aaaa", 0, ByteArray(8)), Triple("bbbb", dir, ByteArray(8))))))
        assertNull(Sfnt.toSfnt(woffOf(listOf(Triple("aaaa", dir, ByteArray(8)), Triple("bbbb", dir + 4, ByteArray(8))))))
        // A compressed table whose stream goes on past its declared length.
        val deflated = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(ByteArray(200)) } }.toByteArray()
        assertNull(Sfnt.toSfnt(woffOf(listOf(Triple("aaaa", 44 + 20, deflated)), origLengths = listOf(100))))
        assertNotNull(Sfnt.toSfnt(woffOf(listOf(Triple("aaaa", 44 + 20, deflated)), origLengths = listOf(200))))
    }

    @Test
    fun sfntPassesThroughAndTheRestIsRefused() {
        val ttf = File(fonts, "pacifico.ttf").readBytes()
        assertArrayEquals(ttf, Sfnt.toSfnt(ttf))
        assertNull(Sfnt.toSfnt(File(fonts, "bitter-vf.woff2").readBytes()))
        assertNull(Sfnt.toSfnt("<html></html>".toByteArray()))
        // A WOFF cut short, or one that would unwrap past the cap.
        val woff = File(fonts, "bitter-vf.woff").readBytes()
        assertNull(Sfnt.toSfnt(woff.copyOf(woff.size / 2)))
        assertNull(Sfnt.toSfnt(woff, maxBytes = 1024))
    }

    @Test
    fun dataUris() {
        assertEquals("wOFF", String(WebFonts.decodeDataUri("data:font/woff;base64,d09GRg==", 1024)!!))
        assertEquals("a b", String(WebFonts.decodeDataUri("data:text/plain,a%20b", 1024)!!))
        assertNull(WebFonts.decodeDataUri("data:font/woff;base64,d09GRg==", 2))
    }
}
