/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.HaFrontend
import dev.glassworks.engine.vitro.data.HaText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HA's frontend strings come from the signed-in HA, the way HA's page loads them: the app bundle's
 * `translationMetadata`, then `/static/translations/<lang>-<hash>.json` and the Lovelace fragment; cached per frontend
 * version and language, and read from that cache when HA can't be reached.
 */
class HaFrontendTest {
    private val origin = "http://homeassistant.local:8123"
    private val served = HashMap<String, String>()
    private val asked = ArrayList<String>()
    private lateinit var cache: File

    @Before fun setUp() {
        cache = Files.createTempDirectory("vitro-frontend").toFile()
        HaFrontend.cacheDir = cache
        HaFrontend.reset()
        HaText.reset()
        HaFrontend.fetch = { url -> asked += url; served[url]?.toByteArray() }
        served["$origin/"] = """<html><script type="module" crossorigin src="/frontend_latest/core.c0ffee.js"></script>
            <script type="module" crossorigin src="/frontend_latest/app.abc123.js"></script></html>"""
        served["$origin/frontend_latest/app.abc123.js"] = """let a=1;const r=JSON.parse('{"fragments":["lovelace"],"translations":{""" +
            """"en":{"nativeName":"English","hash":"e1"},"de":{"nativeName":"Deutsch","hash":"d1"},"pt-BR":{"nativeName":"Português","hash":"p1"}}}');"""
        served["$origin/static/translations/de-d1.json"] = """{"state":{"default":{"unavailable":"Nicht verfügbar"}},"ui":{"common":{"cancel":"Abbrechen"}}}"""
        served["$origin/static/translations/lovelace/de-d1.json"] =
            """{"ui":{"panel":{"lovelace":{"cards":{"todo-list":{"add_item":"Eintrag hinzufügen"}}}},"common":{"cancel":"(fragment)"}}}"""
        served["$origin/static/translations/en-e1.json"] = """{"state":{"default":{"unavailable":"Unavailable"}}}"""
    }

    @After fun tearDown() {
        HaFrontend.reset()
        HaFrontend.cacheDir = null
        HaText.reset()
        cache.deleteRecursively()
    }


    @Test
    fun theStringsAreHasOwnFilesForTheUsersLanguage() {
        assertTrue(HaFrontend.load(origin, "de"))
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        assertEquals("Eintrag hinzufügen", HaText.ui("ui.panel.lovelace.cards.todo-list.add_item"))
        // The core file's key wins over the fragment's.
        assertEquals("Abbrechen", HaText.ui("ui.common.cancel"))
        assertEquals(setOf("en", "de", "pt-BR"), HaFrontend.languages.toSet())
    }

    @Test
    fun aLanguageTheFrontendLacksIsEnglish() {
        assertTrue(HaFrontend.load(origin, "xx"))
        assertEquals("Unavailable", HaText.ui("state.default.unavailable"))
        assertTrue("$origin/static/translations/en-e1.json" in asked)
    }

    @Test
    fun theCacheIsPerFrontendVersionAndLanguageAndServesAnHaThatCantBeReached() {
        assertTrue(HaFrontend.load(origin, "de"))
        assertTrue(File(cache, "meta-abc123.json").isFile)
        assertTrue(File(cache, "strings-de-d1.json").isFile)
        // The same frontend again: only its index is fetched, the rest is the cache's.
        asked.clear()
        HaText.reset()
        assertTrue(HaFrontend.load(origin, "de"))
        assertEquals(listOf("$origin/"), asked)
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        // HA can't be reached: the last frontend it served, from the cache.
        HaFrontend.fetch = { null }
        HaText.reset()
        assertFalse(HaFrontend.load(origin, "de"))
        assertTrue(HaFrontend.loadCached(origin, "de"))
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        // Another HA has no cache of its own.
        assertFalse(HaFrontend.loadCached("http://192.0.2.20:8123", "de"))
    }

    @Test
    fun beforeHaHasEverBeenReadOnlyTheBuiltInWordsAreThere() {
        HaFrontend.fetch = { null }
        assertFalse(HaFrontend.load(origin, "de"))
        assertEquals("Unavailable", HaText.ui("state.default.unavailable"))
        assertEquals("Unknown", HaText.ui("state.default.unknown"))
    }

    @Test
    fun withoutItsLovelaceFragmentTheTableIsUsedButNotKept() {
        served.remove("$origin/static/translations/lovelace/de-d1.json")
        assertTrue(HaFrontend.load(origin, "de"))
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        assertFalse(File(cache, "strings-de-d1.json").exists())
        // The next load asks for both files again, and keeps the whole table.
        served["$origin/static/translations/lovelace/de-d1.json"] = """{"ui":{"panel":{"lovelace":{"cards":{"todo-list":{"add_item":"Eintrag hinzufügen"}}}}}}"""
        asked.clear()
        assertTrue(HaFrontend.load(origin, "de"))
        assertTrue("$origin/static/translations/lovelace/de-d1.json" in asked)
        assertTrue(File(cache, "strings-de-d1.json").isFile)
        assertEquals("Eintrag hinzufügen", HaText.ui("ui.panel.lovelace.cards.todo-list.add_item"))
    }

    @Test
    fun aRetiredClientsLateDownloadLeavesTheStringsAlone() {
        val retired = Any()
        val current = Any()
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        HaFrontend.fetch = { url -> release.await(); asked += url; served[url]?.toByteArray() }
        HaFrontend.claim(retired)
        HaFrontend.ensure(retired, origin, "de") { done.countDown() }
        // A new client takes over while the old one's download is still on its way.
        HaFrontend.claim(current)
        release.countDown()
        HaFrontend.ensure(current, origin, "xx") {}
        assertFalse("the retired client's strings are never installed", done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(null, HaText.language.takeIf { it == "de" })
        // A client that isn't the owner can't start a download at all.
        val stranger = Any()
        asked.clear()
        HaFrontend.ensure(stranger, origin, "de") { done.countDown() }
        Thread.sleep(200)
        assertTrue(asked.none { it.endsWith("de-d1.json") })
    }

    @Test
    fun theMetadataIsReadOutOfTheAppBundle() {
        val m = HaFrontend.metadataOf("""x=JSON.parse('{"fragments":[],"translations":{"fr":{"nativeName":"Français","hash":"f1"},"it":{"nativeName":"L\'italiano","hash":"i1"}}}');""")!!
        assertEquals("f1", m.getJSONObject("translations").getJSONObject("fr").getString("hash"))
        assertEquals("L'italiano", m.getJSONObject("translations").getJSONObject("it").getString("nativeName"))
        assertEquals(null, HaFrontend.metadataOf("no metadata here"))
    }
}
