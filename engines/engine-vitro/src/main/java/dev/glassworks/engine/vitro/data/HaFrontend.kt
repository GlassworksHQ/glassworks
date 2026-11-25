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

    /** The (origin, language) last installed into [HaText] and the one being fetched. */
    @Volatile private var installed: Pair<String, String>? = null
    @Volatile private var wanted: Pair<String, String>? = null

    private val APP_JS = Regex("""/frontend_latest/app\.([0-9a-f]+)\.js""")

    /**
     * The strings for [language] from HA at [origin] (`http(s)://host:port`), fetched off the caller's thread;
     * [done] runs after they are installed. Asking again for what is installed or on its way does nothing.
     */
    fun ensure(origin: String, language: String, done: () -> Unit) {
        val key = origin to language
        if (installed == key || wanted == key) return
        wanted = key
        executor.execute {
            val ok = runCatching { load(origin, language) }.getOrDefault(false) ||
                runCatching { loadCached(origin, language) }.getOrDefault(false)
            if (wanted == key) wanted = null
            if (ok) { installed = key; done() }
        }
    }

    /** The cached strings for [language] from the last frontend [origin] served, without the network. */
    fun ensureCached(origin: String, language: String, done: () -> Unit) {
        executor.execute { if (runCatching { loadCached(origin, language) }.getOrDefault(false)) done() }
    }

    /** Forgets what is installed (a new server, or tests). */
    fun reset() {
        installed = null
        wanted = null
        languages = emptyList()
    }

    /** Fetches and installs [language]'s strings from HA; false when HA's files can't be read. Blocking. */
    internal fun load(origin: String, language: String): Boolean {
        val index = fetch("$origin/")?.toString(Charsets.UTF_8) ?: return false
        val app = APP_JS.find(index)?.groupValues?.get(1) ?: return false
        val meta = cached("meta-$app.json") ?: run {
            val js = fetch("$origin/frontend_latest/app.$app.js")?.toString(Charsets.UTF_8) ?: return false
            val m = metadataOf(js) ?: return false
            store("meta-$app.json", m.toString())
            m
        }
        val strings = stringsFor(origin, meta, language) ?: return false
        apply(meta, language, strings)
        store("current-${key(origin)}.json", JSONObject().put("app", app).toString())
        return true
    }

    /** Installs [language]'s strings from the cache of the frontend [origin] served last; false when there is none. */
    internal fun loadCached(origin: String, language: String): Boolean {
        val app = cached("current-${key(origin)}.json")?.optString("app")?.takeIf { it.isNotEmpty() } ?: return false
        val meta = cached("meta-$app.json") ?: return false
        val (lang, hash) = fileOf(meta, language) ?: return false
        val strings = cached("strings-$lang-$hash.json")?.let(::flatMap) ?: return false
        apply(meta, language, strings)
        return true
    }

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

    private fun stringsFor(origin: String, meta: JSONObject, language: String): Map<String, String>? {
        val (lang, hash) = fileOf(meta, language) ?: return null
        cached("strings-$lang-$hash.json")?.let { return flatMap(it) }
        val core = fetch("$origin/static/translations/$lang-$hash.json")?.let { JSONObject(it.toString(Charsets.UTF_8)) } ?: return null
        val out = JSONObject()
        flatten("", core, out)
        // The Lovelace fragment (a dashboard's own words: to-do, cards); core keys win, as the page merges them.
        fetch("$origin/static/translations/lovelace/$lang-$hash.json")?.let { b ->
            val frag = JSONObject()
            flatten("", JSONObject(b.toString(Charsets.UTF_8)), frag)
            for (k in frag.keys()) if (!out.has(k)) out.put(k, frag.get(k))
        }
        store("strings-$lang-$hash.json", out.toString())
        return flatMap(out)
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
