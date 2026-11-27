/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HA's `FrontendLocaleData` (data/translation.ts): the signed-in user's language and formats, as HA's frontend holds
 * them in `hass.locale`. Every field keeps HA's own values: [numberFormat] `language`, `system`, `comma_decimal`,
 * `decimal_comma`, `quote_decimal`, `space_comma` or `none`; [timeFormat] `language`, `system`, `12` or `24`;
 * [dateFormat] `language`, `system`, `DMY`, `MDY` or `YMD`; [firstWeekday] `language` or a day name; [timeZone]
 * `local` or `server`.
 */
data class FrontendLocale(
    val language: String = "en",
    val numberFormat: String = "language",
    val timeFormat: String = "language",
    val dateFormat: String = "language",
    val firstWeekday: String = "language",
    val timeZone: String = "local",
) {
    /** `hass.locale` as the frontend's custom cards read it (the island shim). */
    fun toJson(): JSONObject = JSONObject()
        .put("language", language)
        .put("number_format", numberFormat)
        .put("time_format", timeFormat)
        .put("date_format", dateFormat)
        .put("first_weekday", firstWeekday)
        .put("time_zone", timeZone)
}

/**
 * Which [FrontendLocale] applies, read the way HA's frontend 2026.11 reads it (translations-mixin.ts and
 * util/common-translation.ts):
 *
 * 1. Before HA answers, the language is the browser's (`getLocalLanguage`: `navigator.languages` matched against the
 *    languages the frontend has translations for, else English), and every format is `language`, the time zone
 *    `local`.
 * 2. Once connected, `frontend/subscribe_user_data` with key `language` (the user's profile) replaces each field it
 *    has, live: a language when the frontend has it, each format when it is set.
 *
 * HA's frontend doesn't use the core config's `language` for any of this (that is the backend's default for its own
 * texts), so neither does Vitro. A panel has no browser: what HA calls the browser's locale ("system", and the
 * language before the profile says otherwise) is the panel's Android locale here, which is what HA's own page in the
 * panel's web engine sees as `navigator.languages`. The time zone `local` is the panel's; `server` is HA's
 * (`get_config`'s `time_zone`, [serverTimeZone]).
 *
 * One HA per process, like [HaDisplay]: a process-wide holder the client writes off the main thread. [version]
 * counts changes; [addListener] hears them.
 */
object HaLocale {
    /** The locale in use; the browser's defaults until HA's profile answers (set in `init`, after the providers). */
    @Volatile var current: FrontendLocale = FrontendLocale()
        private set

    /** HA's own time zone (`get_config`), for `time_zone: server`; null until it arrives. */
    @Volatile var serverTimeZone: String? = null
        private set

    @Volatile var version: Long = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The panel's locale, as the browser HA's frontend runs in would report it. Tests replace it. */
    @Volatile var deviceLocale: () -> Locale = { Locale.getDefault() }

    /**
     * The panel's time zone (HA's `LOCAL_TIME_ZONE`). Read once and kept: `TimeZone.getDefault()` copies the zone,
     * its whole transition table included, on every call, and the store reads it for timestamps as states land.
     * [deviceZoneChanged] replaces it when the device's zone changes ([watchDeviceZone]). Tests replace it.
     */
    @Volatile var deviceTimeZone: () -> TimeZone = {
        // Read and stored under [zoneLock], as [deviceZoneChanged] stores: a read that found nothing kept can't write
        // the old default back over a newer zone the broadcast stored meanwhile (Greptile on #956).
        deviceZone ?: synchronized(zoneLock) { deviceZone ?: TimeZone.getDefault().also { deviceZone = it } }
    }

    @Volatile private var deviceZone: TimeZone? = null
    private val zoneLock = Any()

    /**
     * The device's zone changed (`ACTION_TIMEZONE_CHANGED`, its new zone's [id] when the broadcast names it): the zone
     * kept is that one, or the device's default read afresh at the next use.
     */
    fun deviceZoneChanged(id: String?) {
        val zone = id?.takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) }
        synchronized(zoneLock) { deviceZone = zone }
    }

    /** The application the zone receiver is registered with ([watchDeviceZone]). */
    private var zoneWatchedBy: java.lang.ref.WeakReference<android.content.Context>? = null

    /**
     * Hears the device's zone change from now on, for the process: once per application (a process has one; tests
     * make a new one per test, and a receiver on an earlier one would hear nothing). Main thread.
     */
    fun watchDeviceZone(context: android.content.Context) {
        val app = context.applicationContext ?: context
        if (zoneWatchedBy?.get() === app) return
        zoneWatchedBy = java.lang.ref.WeakReference(app)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                deviceZoneChanged(intent?.getStringExtra(android.content.Intent.EXTRA_TIMEZONE))
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        // A protected system broadcast: Android 14's exported flag isn't needed for a receiver that hears only those
        // (as ScreenPolicy's screen on and off).
        app.registerReceiver(receiver, filter)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Back to the browser's defaults: a new connection, or tests. */
    fun reset() {
        synchronized(zoneLock) { deviceZone = null }
        synchronized(lock) {
            write(FrontendLocale(language = deviceLanguage()))
            serverTimeZone = null
            languageFromProfile = false
        }
    }

    /**
     * Guards every read-then-write of the locale (the client's socket thread, HaFrontend's thread, tests): a profile
     * language can't be replaced by a browser language computed before it. Listeners run after it is released.
     */
    private val lock = Any()

    private fun write(locale: FrontendLocale) {
        current = locale
        version++
    }

    private fun notifyListeners() = listeners.forEach { runCatching { it() } }

    /** True once the profile has set the language; until then it is the browser's. */
    @Volatile private var languageFromProfile = false

    /**
     * HA's frontend told which languages it has ([HaFrontend.languages]): a language still the browser's is matched
     * against them again, as `getLocalLanguage` would have on HA's page. Tells the listeners when it changes.
     */
    fun languagesKnown() {
        val changed = synchronized(lock) {
            if (languageFromProfile) return
            val lang = deviceLanguage()
            if (lang == current.language) return
            write(current.copy(language = lang))
            true
        }
        if (changed) notifyListeners()
    }

    /**
     * The value of `frontend/subscribe_user_data` (or `get_user_data`) key `language`, as HA's `hassConnected`
     * applies it: each field it sets replaces the one in use, a field it leaves out stays. A language the frontend
     * has no translations for is ignored (the frontend's `findAvailableLanguage`). Returns true when anything changed.
     */
    fun applyUserData(value: JSONObject?): Boolean {
        if (value == null) return false
        val changed = synchronized(lock) { applyLocked(value) }
        if (changed) notifyListeners()
        return changed
    }

    private fun applyLocked(value: JSONObject): Boolean {
        fun str(key: String): String? = if (value.isNull(key)) null else value.optString(key).takeIf { it.isNotEmpty() }
        val cur = current
        val next = cur.copy(
            language = str("language")?.let { findAvailableLanguage(it) } ?: cur.language,
            numberFormat = str("number_format") ?: cur.numberFormat,
            timeFormat = str("time_format") ?: cur.timeFormat,
            dateFormat = str("date_format") ?: cur.dateFormat,
            firstWeekday = str("first_weekday") ?: cur.firstWeekday,
            timeZone = str("time_zone") ?: cur.timeZone,
        )
        if (str("language")?.let { findAvailableLanguage(it) } != null) languageFromProfile = true
        if (next == cur) return false
        write(next)
        return true
    }

    /** `get_config`'s `time_zone`. */
    fun applyConfig(config: JSONObject?) {
        val tz = config?.optString("time_zone")?.takeIf { it.isNotEmpty() } ?: return
        val server = synchronized(lock) {
            if (tz == serverTimeZone) return
            serverTimeZone = tz
            version++
            current.timeZone == "server"
        }
        if (server) notifyListeners()
    }

    /** For tests and tools: use [locale] as it is. */
    fun set(locale: FrontendLocale, notify: Boolean = true) {
        synchronized(lock) { write(locale) }
        if (notify) notifyListeners()
    }

    /**
     * HA's `resolveTimeZone(option, serverTimeZone)`: the panel's zone for `local` (HA's frontend falls back to the
     * server's when the browser's isn't a known IANA zone), else HA's own, else the panel's.
     */
    fun timeZone(locale: FrontendLocale = current): TimeZone {
        val server = serverTimeZone?.let { TimeZone.getTimeZone(it) }
        if (locale.timeZone == "local" || server == null) return deviceTimeZone()
        return server
    }

    /** The panel's language as HA's `getLocalLanguage` would pick it from `navigator.languages`, else English. */
    fun deviceLanguage(): String = findAvailableLanguage(browserTag(deviceLocale())) ?: "en"

    /**
     * [locale] as a browser reports it in `navigator.language`: language and region ("zh-CN", "pt-BR"), without the
     * script Android's own tags carry ("zh-Hans-CN").
     */
    fun browserTag(locale: Locale): String {
        // Android before 15 keeps Java's old codes for Hebrew, Indonesian and Yiddish; a browser says he, id, yi.
        val language = when (val l = locale.language) { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> l }.ifEmpty { "en" }
        return if (locale.country.isEmpty()) language else "$language-${locale.country}"
    }

    internal val CHINESE = mapOf(
        "zh-cn" to "zh-Hans", "zh-sg" to "zh-Hans", "zh-my" to "zh-Hans",
        "zh-tw" to "zh-Hant", "zh-hk" to "zh-Hant", "zh-mo" to "zh-Hant", "zh" to "zh-Hant",
    )

    /**
     * HA's `findAvailableLanguage`: [tag] when the frontend has it, the Chinese script mapping, a case-insensitive
     * match, then the tag's first subtag; null when none of those is a frontend language.
     */
    fun findAvailableLanguage(tag: String): String? {
        // HA's own list once its frontend has answered, else the one the bundled locale tables were captured for.
        val languages = HaFrontend.languages.ifEmpty { TableIntlSource.languages }
        if (tag in languages) return tag
        val lower = tag.lowercase(Locale.ROOT)
        CHINESE[lower]?.let { return it }
        languages.firstOrNull { it.lowercase(Locale.ROOT) == lower }?.let { return it }
        return if ('-' in tag) findAvailableLanguage(tag.substringBefore('-')) else null
    }

    // Last: deviceLanguage() needs the providers and CHINESE above.
    init { current = FrontendLocale(language = deviceLanguage()) }
}
