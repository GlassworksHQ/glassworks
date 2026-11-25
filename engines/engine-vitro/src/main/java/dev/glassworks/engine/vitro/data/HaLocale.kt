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
    @Volatile var current: FrontendLocale = FrontendLocale(language = deviceLanguage())
        private set

    /** HA's own time zone (`get_config`), for `time_zone: server`; null until it arrives. */
    @Volatile var serverTimeZone: String? = null
        private set

    @Volatile var version: Long = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The panel's locale, as the browser HA's frontend runs in would report it. Tests replace it. */
    @Volatile var deviceLocale: () -> Locale = { Locale.getDefault() }

    /** The panel's time zone (HA's `LOCAL_TIME_ZONE`). Tests replace it. */
    @Volatile var deviceTimeZone: () -> TimeZone = { TimeZone.getDefault() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Back to the browser's defaults: a new connection, or tests. */
    fun reset() {
        set(FrontendLocale(language = deviceLanguage()), notify = false)
        serverTimeZone = null
    }

    /**
     * The value of `frontend/subscribe_user_data` (or `get_user_data`) key `language`, as HA's `hassConnected`
     * applies it: each field it sets replaces the one in use, a field it leaves out stays. A language the frontend
     * has no translations for is ignored (the frontend's `findAvailableLanguage`). Returns true when anything changed.
     */
    fun applyUserData(value: JSONObject?): Boolean {
        if (value == null) return false
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
        if (next == cur) return false
        set(next, notify = true)
        return true
    }

    /** `get_config`'s `time_zone`. */
    fun applyConfig(config: JSONObject?) {
        val tz = config?.optString("time_zone")?.takeIf { it.isNotEmpty() } ?: return
        if (tz == serverTimeZone) return
        serverTimeZone = tz
        version++
        if (current.timeZone == "server") listeners.forEach { runCatching { it() } }
    }

    /** For tests and tools: use [locale] as it is. */
    fun set(locale: FrontendLocale, notify: Boolean = true) {
        current = locale
        version++
        if (notify) listeners.forEach { runCatching { it() } }
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
    fun browserTag(locale: Locale): String =
        if (locale.country.isEmpty()) locale.language.ifEmpty { "en" } else "${locale.language}-${locale.country}"

    private val CHINESE = mapOf(
        "zh-cn" to "zh-Hans", "zh-sg" to "zh-Hans", "zh-my" to "zh-Hans",
        "zh-tw" to "zh-Hant", "zh-hk" to "zh-Hant", "zh-mo" to "zh-Hant", "zh" to "zh-Hant",
    )

    /**
     * HA's `findAvailableLanguage`: [tag] when the frontend has it, the Chinese script mapping, a case-insensitive
     * match, then the tag's first subtag; null when none of those is a frontend language.
     */
    fun findAvailableLanguage(tag: String): String? {
        val languages = HaIntl.languages
        if (tag in languages) return tag
        val lower = tag.lowercase(Locale.ROOT)
        CHINESE[lower]?.let { return it }
        languages.firstOrNull { it.lowercase(Locale.ROOT) == lower }?.let { return it }
        return if ('-' in tag) findAvailableLanguage(tag.substringBefore('-')) else null
    }
}
