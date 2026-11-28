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
        // Nothing queued runs on into the next test.
        HaFrontend.awaitIdle()
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
    fun ensureAsksAgainForALanguageWhoseLovelaceFragmentDidntCome() {
        val client = Any()
        HaFrontend.claim(client, origin)
        served.remove("$origin/static/translations/lovelace/de-d1.json")
        val first = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de") { first.countDown() }
        assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        // The fragment is back: the same ensure (no reconnect) asks again and gets the whole table.
        served["$origin/static/translations/lovelace/de-d1.json"] = """{"ui":{"panel":{"lovelace":{"cards":{"todo-list":{"add_item":"Eintrag hinzufügen"}}}}}}"""
        val second = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de") { second.countDown() }
        assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Eintrag hinzufügen", HaText.ui("ui.panel.lovelace.cards.todo-list.add_item"))
        // Whole now: asking once more fetches nothing.
        asked.clear()
        HaFrontend.ensure(client, origin, "de") {}
        Thread.sleep(200)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aRetiredClientsLateDownloadLeavesTheStringsAlone() {
        val retired = Any()
        val current = Any()
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        HaFrontend.fetch = { url -> release.await(); asked += url; served[url]?.toByteArray() }
        HaFrontend.claim(retired, origin)
        HaFrontend.ensure(retired, origin, "de") { done.countDown() }
        // A new client takes over while the old one's download is still on its way.
        HaFrontend.claim(current, origin)
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
    fun aReadQueuedForAClientThatWasReplacedFetchesNothing() {
        // A slow or unreachable Home Assistant: a reconnect's read queues behind one still waiting for HA. When another
        // client takes over meanwhile, the queued read fetches nothing, so it can't hold the new client's reads up.
        val old = Any()
        val gate = java.util.concurrent.CountDownLatch(1)
        val indexReads = java.util.concurrent.atomic.AtomicInteger(0)
        HaFrontend.fetch = { url ->
            if (url == "$origin/") { indexReads.incrementAndGet(); gate.await() }
            served[url]?.toByteArray()
        }
        HaFrontend.claim(old, origin)
        HaFrontend.ensure(old, origin, "de") {}
        awaitCondition("the first read waits for HA") { indexReads.get() == 1 }
        HaFrontend.ensure(old, origin, "de", revalidate = true) {}
        HaFrontend.claim(Any(), origin)
        gate.countDown()
        assertTrue(HaFrontend.awaitIdle())
        assertEquals("the replaced client's queued read never asked HA", 1, indexReads.get())
    }

    @Test
    fun anOlderReadEndingFirstLeavesTheNewerOneWanted() {
        // D22: two reconnects in a row queue two reads of the same strings. The first one ending must not mark them as
        // no longer on their way while the second is still queued, or the next ensure queues a third.
        val client = Any()
        HaFrontend.claim(client, origin)
        // Without the Lovelace fragment nothing counts as installed, so only `wanted` stops a duplicate.
        served.remove("$origin/static/translations/lovelace/de-d1.json")
        val indexReads = java.util.concurrent.atomic.AtomicInteger(0)
        val gates = List(3) { java.util.concurrent.CountDownLatch(1) }
        HaFrontend.fetch = { url ->
            if (url == "$origin/") gates.getOrNull(indexReads.getAndIncrement())?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            served[url]?.toByteArray()
        }
        HaFrontend.ensure(client, origin, "de", revalidate = true) {}
        HaFrontend.ensure(client, origin, "de", revalidate = true) {}
        gates[0].countDown()
        // The second read has started: the first one is over, its end included.
        awaitCondition("the second read") { indexReads.get() == 2 }
        HaFrontend.ensure(client, origin, "de") {}
        gates[1].countDown()
        gates[2].countDown()
        assertTrue(HaFrontend.awaitIdle())
        assertEquals("no third read while the second was on its way", 2, indexReads.get())
    }

    private fun await(latch: java.util.concurrent.CountDownLatch) =
        assertTrue("in time", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))

    @Test
    fun aLanguageTheUserHasLeftIsNotInstalled() {
        val client = Any()
        HaFrontend.claim(client, origin)
        val german = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        HaFrontend.fetch = { url -> if (url.endsWith("de-d1.json")) gate.await(); served[url]?.toByteArray() }
        HaFrontend.ensure(client, origin, "de") { german.countDown() }
        // French is picked while German is still coming (this frontend has no French: English serves it).
        val french = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "fr") { french.countDown() }
        gate.countDown()
        assertFalse(german.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(HaText.ui("state.default.unavailable") == "Nicht verfügbar")
    }

    @Test
    fun aReconnectReadsHasIndexAgainAndFindsAnUpgradedFrontend() {
        val client = Any()
        HaFrontend.claim(client, origin)
        val first = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de") { first.countDown() }
        await(first)
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        // HA is upgraded: a new app bundle and a new German file.
        served["$origin/"] = """<script type="module" src="/frontend_latest/app.def456.js"></script>"""
        served["$origin/frontend_latest/app.def456.js"] = """JSON.parse('{"fragments":[],"translations":{"en":{"nativeName":"English","hash":"e2"},"de":{"nativeName":"Deutsch","hash":"d2"}}}')"""
        served["$origin/static/translations/de-d2.json"] = """{"state":{"default":{"unavailable":"Nicht erreichbar"}}}"""
        served["$origin/static/translations/lovelace/de-d2.json"] = "{}"
        // The same request again changes nothing; a reconnect's revalidation does.
        HaFrontend.ensure(client, origin, "de") {}
        val second = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de", revalidate = true) { second.countDown() }
        await(second)
        assertEquals("Nicht erreichbar", HaText.ui("state.default.unavailable"))
    }

    @Test
    fun aReconnectToAnUnchangedFrontendInstallsNothingAndTellsNobody() {
        // A start installs the cached strings, then HA's own a moment later; every reconnect reads HA's again. The same
        // files each time are no change: HaText's version stays, so the dashboard isn't built again (A §3, G L1).
        val client = Any()
        HaFrontend.claim(client, origin)
        val first = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de", revalidate = true) { first.countDown() }
        await(first)
        val version = HaText.version
        // The next start of the same HA: the cache first, then HA itself, then two reconnects.
        val next = Any()
        HaFrontend.claim(next, origin)
        val told = java.util.concurrent.atomic.AtomicInteger(0)
        HaFrontend.ensureCached(next, origin, "de") { told.incrementAndGet() }
        asked.clear()
        assertTrue(HaFrontend.awaitIdle())
        repeat(3) { n ->
            HaFrontend.ensure(next, origin, "de", revalidate = true) { told.incrementAndGet() }
            // Each reconnect's work done, the install (or not) included, before anything is read.
            assertTrue(HaFrontend.awaitIdle())
            assertEquals("HA's index read again", n + 1, asked.count { it == "$origin/" })
        }
        assertEquals("nobody is told of unchanged strings", 0, told.get())
        assertEquals("HaText keeps its version", version, HaText.version)
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
    }

    @Test
    fun anUpgradedFrontendStillInstallsAfterAnUnchangedReconnect() {
        val client = Any()
        HaFrontend.claim(client, origin)
        val first = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de", revalidate = true) { first.countDown() }
        await(first)
        served["$origin/static/translations/de-d2.json"] = """{"state":{"default":{"unavailable":"Nicht erreichbar"}}}"""
        served["$origin/static/translations/lovelace/de-d2.json"] = "{}"
        served["$origin/"] = """<script type="module" src="/frontend_latest/app.def456.js"></script>"""
        served["$origin/frontend_latest/app.def456.js"] = """JSON.parse('{"fragments":[],"translations":{"en":{"nativeName":"English","hash":"e2"},"de":{"nativeName":"Deutsch","hash":"d2"}}}')"""
        val second = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de", revalidate = true) { second.countDown() }
        await(second)
        assertEquals("Nicht erreichbar", HaText.ui("state.default.unavailable"))
        // Strings someone else put in HaText (a test's, a reset) since: the same files go in again.
        HaText.reset()
        val third = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(client, origin, "de", revalidate = true) { third.countDown() }
        await(third)
        assertEquals("Nicht erreichbar", HaText.ui("state.default.unavailable"))
    }

    @Test
    fun anotherHasStringsAreDroppedWhenANewClientClaims() {
        val first = Any()
        HaFrontend.claim(first, origin)
        val done = java.util.concurrent.CountDownLatch(1)
        HaFrontend.ensure(first, origin, "de") { done.countDown() }
        await(done)
        assertEquals("de", HaText.language)
        // The same HA again keeps them until its own arrive; another HA starts from the built-in words.
        HaFrontend.claim(Any(), origin)
        assertEquals("de", HaText.language)
        HaFrontend.claim(Any(), "http://192.0.2.20:8123")
        assertEquals(null, HaText.language)
    }

    @Test
    fun aClaimCantComeBetweenAnInstallsCheckAndItsStrings() {
        val old = Any()
        HaFrontend.claim(old, origin)
        val inside = java.util.concurrent.CountDownLatch(1)
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        var claimer: Thread? = null
        HaFrontend.beforeInstall = {
            // A new client claims while the old one's install is between its check and its strings.
            claimer = Thread { HaFrontend.claim(Any(), origin); claimed.set(true) }.apply { start() }
            Thread.sleep(200)
            assertFalse("the claim waits for the install", claimed.get())
            inside.countDown()
        }
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            HaFrontend.ensure(old, origin, "de") { done.countDown() }
            await(done)
            await(inside)
            claimer!!.join(5_000)
            assertTrue(claimed.get())
        } finally {
            HaFrontend.beforeInstall = null
        }
    }

    @Test
    fun theMetadataIsReadOutOfTheAppBundle() {
        val m = HaFrontend.metadataOf("""x=JSON.parse('{"fragments":[],"translations":{"fr":{"nativeName":"Français","hash":"f1"},"it":{"nativeName":"L\'italiano","hash":"i1"}}}');""")!!
        assertEquals("f1", m.getJSONObject("translations").getJSONObject("fr").getString("hash"))
        assertEquals("L'italiano", m.getJSONObject("translations").getJSONObject("it").getString("nativeName"))
        assertEquals(null, HaFrontend.metadataOf("no metadata here"))
    }
}
