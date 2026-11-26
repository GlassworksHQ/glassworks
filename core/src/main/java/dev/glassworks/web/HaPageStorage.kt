/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.auth.OAuth
import org.json.JSONObject

/**
 * The panel's copy of what Home Assistant's web UI keeps in `localStorage` on this panel, for cards that run outside
 * Home Assistant's origin (Vitro's web islands, #881). Cards keep UI state there (a snoozed row, a collapsed section,
 * the selected tab); HA's frontend shows a card with what that panel's browser holds for Home Assistant's origin, so an
 * island starts from the same.
 *
 * The helper extension reads it on Home Assistant's own page ([clean] what it sends) and the app keeps the latest copy
 * for the Home Assistant the panel uses, one origin at a time. What could sign in is never kept: a key that names a
 * token, a password, a secret or auth (HA's own `hassTokens` among them). Values past [MAX_VALUE] and keys past
 * [MAX_KEYS] or [MAX_TOTAL] are left out.
 */
class HaPageStorage(private val prefs: SharedPreferences) {

    /**
     * Keeps [json] (the page's `localStorage` as one object of strings) as [url]'s origin's copy, replacing the one
     * before, also one for another origin. False when [url] has no origin or [json] isn't an object.
     */
    fun put(url: String, json: String): Boolean {
        val origin = OAuth.origin(url) ?: return false
        val items = clean(json) ?: return false
        // The origin and its items in one record: a read never pairs one origin with another's items.
        val text = JSONObject().put("origin", origin).put("items", items).toString()
        if (prefs.getString(KEY_COPY, null) == text) return true
        prefs.edit().putString(KEY_COPY, text).apply()
        return true
    }

    /**
     * Removes the copy (the factory reset, [HaPageData]), after any write already sent with [putLater], and keeps no
     * copy again until the process ends ([closed]): the page still open behind the reset can't bring it back before
     * the restart. False when it couldn't be written. Off the main thread: it waits for the writes before it.
     */
    fun clear(): Boolean {
        closed = true
        return runCatching { writes.submit<Boolean> { prefs.edit().clear().commit() }.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrDefault(false)
    }

    /** The copy for [url]'s origin, or null when the panel has none for it (it never ran HA's web UI there). */
    fun items(url: String?): JSONObject? {
        val origin = url?.let(OAuth::origin) ?: return null
        val copy = prefs.getString(KEY_COPY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (copy.optString("origin") != origin) return null
        return copy.optJSONObject("items")
    }

    companion object {
        private const val PREFS = "ha_page_storage"
        private const val KEY_COPY = "copy"

        /** One writer for every report, so copies are kept in the order the page sent them. */
        private val writes = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ha-page-storage").apply { isDaemon = true }
        }

        /** The longest value kept, in chars. */
        internal const val MAX_VALUE = 16 * 1024

        /** The most keys kept. */
        internal const val MAX_KEYS = 256

        /** What all keys and values kept may add up to, in chars. */
        internal const val MAX_TOTAL = 128 * 1024

        /** A key that may hold a credential: never kept. */
        private val SECRET = Regex("token|passw|secret|auth|credential|session|cookie", RegexOption.IGNORE_CASE)

        fun of(context: Context): HaPageStorage =
            HaPageStorage(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        /**
         * [put] off the caller's thread (a copy is parsed and cleaned, up to a few hundred KiB), on one writer for the
         * whole app: a later report is never overtaken by an earlier one.
         */
        fun putLater(context: Context, url: String, json: String) {
            if (closed) return
            val app = context.applicationContext
            // A report queued before a reset's [clear] runs before it; one that comes after it is dropped here.
            writes.execute { if (!closed) runCatching { of(app).put(url, json) } }
        }

        /**
         * True from a factory reset's [clear] until the process ends: the app keeps no copy of HA's storage then, so the
         * reset leaves none behind. The reset restarts the process, and the next one starts open.
         */
        @Volatile
        var closed = false

        /** True for a key that is never kept ([SECRET]). */
        fun secret(key: String): Boolean = SECRET.containsMatchIn(key)

        /**
         * [json]'s string values without the keys never kept, in key order, within [MAX_KEYS], [MAX_VALUE] and
         * [MAX_TOTAL]; null when [json] isn't a JSON object.
         */
        fun clean(json: String): JSONObject? {
            val raw = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val out = JSONObject()
            var total = 0
            for (key in raw.keys().asSequence().sorted()) {
                if (out.length() >= MAX_KEYS) break
                val value = raw.opt(key) as? String ?: continue
                if (key.isEmpty() || key.length > 256 || secret(key) || value.length > MAX_VALUE) continue
                if (total + key.length + value.length > MAX_TOTAL) continue
                total += key.length + value.length
                out.put(key, value)
            }
            return out
        }
    }
}
