/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * HA's frontend UI strings, from the signed-in Home Assistant itself, the way HA's page loads them
 * (util/common-translation.ts `getTranslation`):
 *
 * 1. HA's `/` names the frontend build (`/frontend_latest/app.<hash>.js`), and that file holds `translationMetadata`:
 *    the languages the frontend has and each one's file hash.
 * 2. The language's files are `/static/translations/<lang>-<hash>.json` and the Lovelace fragment
 *    `/static/translations/lovelace/<lang>-<hash>.json` (a language the frontend lacks is English's, as there). The
 *    frontend's build already fills a language's missing keys with English.
 *
 * Both are public (HA serves them before sign-in) and cached in [cacheDir] by those hashes, so a cache is per HA
 * frontend version and language, and a panel that can't reach HA starts from the last ones it read. [HaText] serves
 * the strings; [languages] is the frontend's list, for [HaLocale.findAvailableLanguage].
 */
object HaFrontend {
    /** Where fetched files are kept (the engine sets it to the app's cache); null keeps nothing. */
    @Volatile var cacheDir: File? = null

    /** The languages HA's frontend has translations for (`translationMetadata`), empty until known. */
    @Volatile var languages: List<String> = emptyList()
        private set

    /** Fetches one URL; replaced by tests. Null when it fails. */
    @Volatile var fetch: (String) -> ByteArray? = ::httpGet

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-frontend").apply { isDaemon = true } }

    /**
     * One lock for who owns the requests, what is installed, wanted and asked for last, and the install itself: a
     * new client claiming the requests can't come between an old one's check and its install.
     */
    private val lock = Any()

    /** The (origin, language) last installed into [HaText], the one being fetched, and the one asked for last. */
    private var installed: Pair<String, String>? = null
    private var wanted: Pair<String, String>? = null
    private var latest: Pair<String, String>? = null

    /**
     * Counts every read [ensure] queues; [wantedRead] is the one [wanted] waits for. A read that ends clears [wanted]
     * only when it is that one: an older read of the same key ending first would leave [wanted] empty while the newer
     * one is still queued, and the next [ensure] would queue a third.
     */
    private var reads = 0L
    private var wantedRead = 0L

    /**
     * The client whose requests may install strings ([claim]): a retired client's download, finishing late, leaves
     * [HaText] alone.
     */
    private var owner: Any? = null

    /**
     * What [HaText] holds now as this object put it there: the strings' source ([Loaded.source] with its origin and
     * language) and the [HaText.version] the install left. The same source again is no change: nothing is installed and
     * nobody is told, so a reconnect that reads HA's unchanged files again draws nothing again.
     */
    private var applied: Pair<String, Long>? = null

    /** Test hook: runs inside the install lock, after the ownership check and before the strings go in. */
    @Volatile internal var beforeInstall: (() -> Unit)? = null

    /**
     * [requester] is the client now, for HA at [origin]: only its requests install strings from here on. Strings
     * another HA served are dropped (the built-in words serve until this one's are read or found in the cache).
     */
    fun claim(requester: Any, origin: String) {
        synchronized(lock) {
            owner = requester
            if (installed?.first != origin) HaText.reset()
            installed = null
            wanted = null
            latest = null
        }
    }

    /**
     * One language's strings as read, with the frontend's metadata, before they are installed. [source] names what they
     * were read from (the frontend build and the language file's hash): the same source holds the same strings.
     */
    internal class Loaded(val meta: JSONObject, val strings: Map<String, String>, val complete: Boolean = true, val source: String? = null)

    private val APP_JS = Regex("""/frontend_latest/app\.([0-9a-f]+)\.js""")

    /**
     * The strings for [language] from HA at [origin] (`http(s)://host:port`), fetched off the caller's thread;
     * [done] runs after they are installed. Asking again for what is installed or on its way does nothing, unless
     * [revalidate] (a reconnect: HA may have been upgraded) has HA's index read again; an unchanged frontend reuses
     * its cached files.
     */
    fun ensure(requester: Any, origin: String, language: String, revalidate: Boolean = false, done: () -> Unit) {
        val key = origin to language
        val mine = synchronized(lock) {
            if (owner !== requester) return
            latest = key
            if (!revalidate && (installed == key || wanted == key)) return
            wanted = key
            wantedRead = ++reads
            wantedRead
        }
        executor.execute {
            val r = runCatching { read(origin, language) }.getOrNull() ?: runCatching { readCached(origin, language) }.getOrNull()
            synchronized(lock) { if (wanted == key && wantedRead == mine) wanted = null }
            if (r != null && install(requester, key, r)) done()
        }
    }

    /** The cached strings for [language] from the last frontend [origin] served, without the network. */
    fun ensureCached(requester: Any, origin: String, language: String, done: () -> Unit) {
        val key = origin to language
        synchronized(lock) {
            if (owner !== requester) return
            if (latest == null) latest = key
        }
        executor.execute {
            val r = runCatching { readCached(origin, language) }.getOrNull()
            if (r != null && install(requester, key, r)) done()
        }
    }

    /**
     * Installs [r] into [HaText] if [requester] still owns the requests and [key] is still what it asked for last (a
     * language the user has left since isn't installed); true when it did.
     */
    private fun install(requester: Any, key: Pair<String, String>, r: Loaded): Boolean = synchronized(lock) {
        if (owner !== requester || latest != key) return false
        // Only a whole table counts as installed: without its Lovelace fragment, the next ensure asks again.
        val id = r.source?.let { "${key.first}|${key.second}|$it|${r.complete}" }
        val same = id != null && applied?.first == id && applied?.second == HaText.version
        installed = if (r.complete) key else null
        // What HaText already holds, from the same files: a start's cached strings and HA's own a moment later, or a
        // reconnect to an unchanged HA. Installing them again would only draw every card again.
        if (same) return false
        beforeInstall?.invoke()
        apply(r.meta, key.second, r.strings)
        applied = id?.let { it to HaText.version }
        true
    }

    /**
     * Waits until every read asked for before this call has run, installs included (tests: one executor runs them in
     * order, so a no-op queued now runs after them). False when they didn't finish in [timeoutMs].
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Forgets what is installed (a new server, or tests). */
    fun reset() {
        synchronized(lock) {
            installed = null
            wanted = null
            latest = null
            owner = null
            applied = null
        }
        languages = emptyList()
    }

    /** Fetches and installs [language]'s strings from HA; false when HA's files can't be read. Blocking (tests). */
    internal fun load(origin: String, language: String): Boolean =
        read(origin, language)?.also { apply(it.meta, language, it.strings) } != null

    /** Installs [language]'s strings from the cache of the frontend [origin] served last; false without one (tests). */
    internal fun loadCached(origin: String, language: String): Boolean =
        readCached(origin, language)?.also { apply(it.meta, language, it.strings) } != null

    /** Reads [language]'s strings from HA (the cache standing in for files it has); null when HA can't be read. */
    private fun read(origin: String, language: String): Loaded? {
        val index = fetch("$origin/")?.toString(Charsets.UTF_8) ?: return null
        val app = APP_JS.find(index)?.groupValues?.get(1) ?: return null
        val meta = cached("meta-$app.json") ?: run {
            val js = fetch("$origin/frontend_latest/app.$app.js")?.toString(Charsets.UTF_8) ?: return null
            val m = metadataOf(js) ?: return null
            store("meta-$app.json", m.toString())
            m
        }
        val (strings, complete) = stringsFor(origin, meta, language) ?: return null
        store("current-${key(origin)}.json", JSONObject().put("app", app).toString())
        return Loaded(meta, strings, complete, sourceOf(app, meta, language))
    }

    /** The cached strings of the frontend [origin] served last; null when there are none. */
    private fun readCached(origin: String, language: String): Loaded? {
        val app = cached("current-${key(origin)}.json")?.optString("app")?.takeIf { it.isNotEmpty() } ?: return null
        val meta = cached("meta-$app.json") ?: return null
        val (lang, hash) = fileOf(meta, language) ?: return null
        val strings = cached("strings-$lang-$hash.json")?.let(::flatMap) ?: return null
        return Loaded(meta, strings, source = sourceOf(app, meta, language))
    }

    /** Where [language]'s strings come from in frontend build [app]: the build and the language file it names. */
    private fun sourceOf(app: String, meta: JSONObject, language: String): String? =
        fileOf(meta, language)?.let { (lang, hash) -> "$app/$lang-$hash" }

    private fun apply(meta: JSONObject, language: String, strings: Map<String, String>) {
        val t = meta.optJSONObject("translations")
        languages = t?.keys()?.asSequence()?.toList().orEmpty()
        HaText.install(language, strings)
        HaLocale.languagesKnown()
    }

    /** The language whose files HA's page loads for [language] (English for one it lacks) and their hash. */
    private fun fileOf(meta: JSONObject, language: String): Pair<String, String>? {
        val t = meta.optJSONObject("translations") ?: return null
        val lang = if (t.has(language)) language else "en"
        val hash = t.optJSONObject(lang)?.optString("hash")?.takeIf { it.isNotEmpty() } ?: return null
        return lang to hash
    }

    /** [language]'s strings, and whether they are whole (the Lovelace fragment came too). */
    private fun stringsFor(origin: String, meta: JSONObject, language: String): Pair<Map<String, String>, Boolean>? {
        val (lang, hash) = fileOf(meta, language) ?: return null
        cached("strings-$lang-$hash.json")?.let { return flatMap(it) to true }
        val core = fetch("$origin/static/translations/$lang-$hash.json")?.let { JSONObject(it.toString(Charsets.UTF_8)) } ?: return null
        val out = JSONObject()
        flatten("", core, out)
        // The Lovelace fragment (a dashboard's own words: to-do, cards); core keys win, as the page merges them.
        val fragment = fetch("$origin/static/translations/lovelace/$lang-$hash.json")
        fragment?.let { b ->
            val frag = JSONObject()
            flatten("", JSONObject(b.toString(Charsets.UTF_8)), frag)
            for (k in frag.keys()) if (!out.has(k)) out.put(k, frag.get(k))
        }
        // Kept only whole: without the fragment, the core words serve now and the next load asks for both again.
        if (fragment != null) store("strings-$lang-$hash.json", out.toString())
        return flatMap(out) to (fragment != null)
    }

    /** `translationMetadata` out of the frontend's app bundle: `JSON.parse('{"fragments":[…],"translations":{…}}')`. */
    internal fun metadataOf(js: String): JSONObject? {
        val start = js.indexOf("{\"fragments\":")
        if (start < 0) return null
        val end = js.indexOf("}')", start)
        if (end < 0) return null
        // The JSON sits in a single-quoted JavaScript string: undo its escapes.
        val raw = js.substring(start, end + 1).replace("\\'", "'").replace("\\\\", "\\")
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.has("translations") }
    }

    private fun flatten(prefix: String, o: JSONObject, out: JSONObject) {
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is JSONObject -> flatten("$prefix$k.", v, out)
                is String -> out.put("$prefix$k", v)
            }
        }
    }

    private fun flatMap(o: JSONObject): Map<String, String> =
        HashMap<String, String>(o.length() * 2).apply { for (k in o.keys()) put(k, o.optString(k)) }

    private fun key(origin: String): String = Integer.toHexString(origin.hashCode())

    private fun cached(name: String): JSONObject? {
        val f = cacheDir?.let { File(it, name) }?.takeIf { it.isFile } ?: return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun store(name: String, text: String) {
        val dir = cacheDir ?: return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        }
    }

    private const val MAX_BYTES = 4 * 1024 * 1024

    private fun httpGet(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@runCatching null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
