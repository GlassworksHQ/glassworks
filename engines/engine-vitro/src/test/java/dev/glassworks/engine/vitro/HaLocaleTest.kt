/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.FrontendLocale
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaFormat.NumberOptions
import dev.glassworks.engine.vitro.data.HaIntl
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.TableIntlSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Vitro's text, numbers, dates and times follow the signed-in HA user's profile the way HA frontend 2026.11 does
 * (claim 4d of the exp/vitro review). The expected strings are what Chromium's `Intl` writes for HA's own options, the
 * same tables `tools/vitro-parity/locale-data.mjs` captured.
 */
class HaLocaleTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // 2023-01-05 21:07:03 UTC, a Thursday.
    private val evening = java.util.GregorianCalendar(utc).apply { clear(); set(2023, 0, 5, 21, 7, 3) }.timeInMillis
    private val savedDevice = HaLocale.deviceLocale
    private val savedZone = HaLocale.deviceTimeZone

    @Before fun setUp() {
        HaLocale.deviceLocale = { Locale.US }
        HaLocale.deviceTimeZone = { utc }
        HaLocale.reset()
        HaDisplay.reset()
    }

    /** HA's strings for [language] (the tests' copy of the pinned frontend's), as HaFrontend installs them. */
    private fun strings(language: String): Map<String, String> {
        val o = JSONObject(javaClass.getResourceAsStream("/dev/glassworks/engine/vitro/locale/strings-$language.json")!!.readBytes().toString(Charsets.UTF_8))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun installStrings(language: String) = HaText.install(language, strings(language))

    @After fun tearDown() {
        HaText.reset()
        HaLocale.deviceLocale = savedDevice
        HaLocale.deviceTimeZone = savedZone
        HaLocale.reset()
        HaDisplay.reset()
    }

    @Test fun theDeviceZoneIsReadOnceAndReplacedWhenTheDeviceSaysItChanged() {
        HaLocale.deviceTimeZone = savedZone
        HaLocale.deviceZoneChanged(null)
        // TimeZone.getDefault() copies the zone every time: it's read once and kept.
        val first = HaLocale.deviceTimeZone()
        org.junit.Assert.assertSame(first, HaLocale.deviceTimeZone())
        // The device's broadcast names the new zone.
        HaLocale.deviceZoneChanged("Asia/Tokyo")
        assertEquals("Asia/Tokyo", HaLocale.deviceTimeZone().id)
        // One that doesn't: the device's default, read afresh.
        HaLocale.deviceZoneChanged(null)
        assertEquals(TimeZone.getDefault().id, HaLocale.deviceTimeZone().id)
    }

    private fun locale(language: String = "en", number: String = "language", time: String = "language", date: String = "language", zone: String = "local", week: String = "language") =
        FrontendLocale(language, number, time, date, week, zone)

    // ---- which settings apply

    @Test
    fun beforeTheProfileAnswersTheLanguageIsTheBrowsersAndEveryFormatFollowsIt() {
        HaLocale.deviceLocale = { Locale.GERMANY }
        HaLocale.reset()
        assertEquals(FrontendLocale(language = "de"), HaLocale.current)
        HaLocale.deviceLocale = { Locale.forLanguageTag("xx-YY") }
        HaLocale.reset()
        assertEquals("en", HaLocale.current.language)
    }

    @Test
    fun theProfileReplacesOnlyWhatItSets() {
        assertTrue(HaLocale.applyUserData(JSONObject().put("language", "de").put("number_format", "decimal_comma")))
        assertEquals(locale("de", number = "decimal_comma"), HaLocale.current)
        // A later value without a language keeps it, as HA's hassConnected does; unknown languages are ignored.
        assertTrue(HaLocale.applyUserData(JSONObject().put("language", "xx").put("time_format", "24")))
        assertEquals(locale("de", number = "decimal_comma", time = "24"), HaLocale.current)
        assertFalse("nothing new", HaLocale.applyUserData(JSONObject().put("time_format", "24")))
        assertFalse(HaLocale.applyUserData(null))
        // An empty or null field is "not set".
        assertFalse(HaLocale.applyUserData(JSONObject().put("number_format", JSONObject.NULL).put("date_format", "")))
    }

    @Test
    fun aChangeTellsTheListenersAndCountsAVersion() {
        var heard = 0
        val l = { heard++; Unit }
        HaLocale.addListener(l)
        try {
            val v = HaLocale.version
            HaLocale.applyUserData(JSONObject().put("language", "fr"))
            assertEquals(1, heard)
            assertTrue(HaLocale.version > v)
            HaLocale.applyUserData(JSONObject().put("language", "fr"))
            assertEquals(1, heard)
        } finally {
            HaLocale.removeListener(l)
        }
    }

    @Test
    fun languagesAreMatchedAsTheFrontendMatchesThem() {
        assertEquals("pt-BR", HaLocale.findAvailableLanguage("pt-BR"))
        assertEquals("pt", HaLocale.findAvailableLanguage("pt-PT"))
        assertEquals("zh-Hans", HaLocale.findAvailableLanguage("zh-CN"))
        assertEquals("zh-Hant", HaLocale.findAvailableLanguage("zh-TW"))
        assertEquals("en-GB", HaLocale.findAvailableLanguage("en-gb"))
        assertEquals("de", HaLocale.findAvailableLanguage("de-AT"))
        assertNull(HaLocale.findAvailableLanguage("xx"))
        assertEquals("zh-CN", HaLocale.browserTag(Locale.forLanguageTag("zh-Hans-CN")))
        // Android before 15 reports Java's old codes: a browser says he and id.
        assertEquals("he-IL", HaLocale.browserTag(Locale("iw", "IL")))
        assertEquals("id", HaLocale.browserTag(Locale("in")))
    }

    @Test
    fun theTimeZoneIsThePanelsForLocalAndHasForServer() {
        HaLocale.applyConfig(JSONObject().put("time_zone", "America/New_York"))
        assertEquals("9:07 PM", HaFormat.formatTime(evening, locale()))
        assertEquals("4:07 PM", HaFormat.formatTime(evening, locale(zone = "server")))
    }

    // ---- numbers, one per number_format

    @Test
    fun numberFormatsWriteHasOwnLocales() {
        val n = "1234567.891"
        assertEquals("1,234,567.891", HaFormat.formatNumber(n, locale()))
        assertEquals("1,234,567.891", HaFormat.formatNumber(n, locale("de", "comma_decimal")))
        assertEquals("1.234.567,891", HaFormat.formatNumber(n, locale("en", "decimal_comma")))
        assertEquals("1 234 567,891", HaFormat.formatNumber(n, locale("en", "space_comma")))
        assertEquals("1’234’567.891", HaFormat.formatNumber(n, locale("en", "quote_decimal")))
        assertEquals("1234567.891", HaFormat.formatNumber(n, locale("de", "none")))
        assertEquals("1.234.567,891", HaFormat.formatNumber(n, locale("de")))
        // "system" is the browser's: the panel's locale.
        HaLocale.deviceLocale = { Locale.GERMANY }
        assertEquals("1.234.567,891", HaFormat.formatNumber(n, locale("en", "system")))
    }

    @Test
    fun formatNumberFollowsHasRules() {
        val en = locale()
        // A string keeps its decimals; a number gets up to two.
        assertEquals("0.10", HaFormat.formatNumber("0.10", en))
        assertEquals("3.14", HaFormat.formatNumber(3.14159, en))
        assertEquals("21.46", HaFormat.formatNumber("21.456", en, NumberOptions(2, 2)))
        // Intl rounds half away from zero.
        assertEquals("3", HaFormat.formatNumber("2.5", en, NumberOptions(maximumFractionDigits = 0)))
        assertEquals("-3", HaFormat.formatNumber("-2.5", en, NumberOptions(maximumFractionDigits = 0)))
        // Not a number: as it is; "none" leaves an empty string empty.
        assertEquals("abc", HaFormat.formatNumber("abc", en))
        assertEquals("", HaFormat.formatNumber("", locale(number = "none")))
        // Grouping: Spanish only from five digits, Hindi in twos after the first three, Swedish's minus sign.
        assertEquals("1234", HaFormat.formatNumber("1234", locale("es")))
        assertEquals("12.345", HaFormat.formatNumber("12345", locale("es")))
        assertEquals("12,34,567", HaFormat.formatNumber("1234567", locale("hi")))
        assertEquals("−1 234,5", HaFormat.formatNumber(-1234.5, locale("sv")))
        // Bengali's own digits.
        assertEquals("১,২৩৪.৫", HaFormat.formatNumber(1234.5, locale("bn")))
    }

    @Test
    fun getNumberFormatOptionsUsesThePrecisionThenAWholeStep() {
        assertEquals(NumberOptions(1, 1), HaFormat.numberOptions("21.46", null, 1))
        assertEquals(NumberOptions(maximumFractionDigits = 0), HaFormat.numberOptions("5", 1, null))
        assertNull(HaFormat.numberOptions("5.5", 1, null))
        assertNull(HaFormat.numberOptions("5", 0.5, null))
    }

    @Test
    fun theUnitFollowsBlankBeforeUnit() {
        assertEquals("45%", HaFormat.formatNumberWithUnit("45", "%", locale()))
        assertEquals("45 %", HaFormat.formatNumberWithUnit("45", "%", locale("de")))
        assertEquals("21,5 °C", HaFormat.formatNumberWithUnit("21.5", "°C", locale("de")))
        assertEquals("21°", HaFormat.formatNumberWithUnit("21", "°", locale("de")))
    }

    // ---- time formats

    @Test
    fun useAmPmFollowsTheLanguageTheBrowserOrTheChoice() {
        assertTrue(HaFormat.useAmPm(locale()))
        assertFalse(HaFormat.useAmPm(locale("de")))
        assertFalse(HaFormat.useAmPm(locale(time = "24")))
        assertTrue(HaFormat.useAmPm(locale("de", time = "12")))
        assertTrue(HaFormat.useAmPm(locale("de", time = "system")))
        HaLocale.deviceLocale = { Locale.GERMANY }
        assertFalse(HaFormat.useAmPm(locale(time = "system")))
    }

    @Test
    fun timesAndDatesAreTheLanguages() {
        assertEquals("9:07 PM", HaFormat.formatTime(evening, locale()))
        assertEquals("21:07", HaFormat.formatTime(evening, locale(time = "24")))
        assertEquals("21:07", HaFormat.formatTime(evening, locale("de")))
        assertEquals("9:07 PM", HaFormat.formatTime(evening, locale("de", time = "12")))
        assertEquals("January 5, 2023", HaFormat.formatDate(evening, locale()))
        assertEquals("5. Januar 2023", HaFormat.formatDate(evening, locale("de")))
        assertEquals("5 janvier 2023", HaFormat.formatDate(evening, locale("fr")))
        assertEquals("5. Januar 2023 um 21:07", HaFormat.formatDateTime(evening, locale("de")))
        assertEquals("Do", HaFormat.formatDateWeekdayShort(evening, locale("de")))
        assertEquals("jeu.", HaFormat.formatDateWeekdayShort(evening, locale("fr")))
    }

    @Test
    fun persianAndThaiDatesAreInTheirOwnCalendars() {
        // fa: the Persian calendar's day, month and year together (2026-03-21 is 1 Farvardin 1405).
        val nowruz = java.util.GregorianCalendar(utc).apply { clear(); set(2026, 2, 21, 12, 0) }.timeInMillis
        assertEquals(FA_NOWRUZ, HaFormat.formatDate(nowruz, locale("fa")))
        assertEquals(FA_EVENING, HaFormat.formatDate(evening, locale("fa")))
        // th: the Buddhist year with its era, also on a panel whose own calendar is Buddhist.
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("th", "TH"))
        try {
            assertEquals("พ.ศ. 2566", HaFormat.formatDateYear(evening, locale("th")))
            assertEquals("5 มกราคม 2566", HaFormat.formatDate(evening, locale("th")))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun aChineseSystemLocaleIsItsScriptsTable() {
        HaLocale.deviceLocale = { Locale.SIMPLIFIED_CHINESE }
        assertEquals("2023/1/5", HaFormat.formatDateNumeric(evening, locale(date = "system")))
        assertEquals("zh-Hans", TableIntlSource.resolve("zh-CN"))
        assertEquals("zh-Hant", TableIntlSource.resolve("zh-TW"))
        assertEquals("en", TableIntlSource.resolve("xx-YY"))
    }

    @Test
    fun dateFormatOrdersTheNumericDate() {
        assertEquals("1/5/2023", HaFormat.formatDateNumeric(evening, locale()))
        assertEquals("5.1.2023", HaFormat.formatDateNumeric(evening, locale("de")))
        assertEquals("5/1/2023", HaFormat.formatDateNumeric(evening, locale(date = "DMY")))
        assertEquals("2023/1/5", HaFormat.formatDateNumeric(evening, locale(date = "YMD")))
        assertEquals("1.5.2023", HaFormat.formatDateNumeric(evening, locale("de", date = "MDY")))
    }

    @Test
    fun theFirstWeekdayIsTheLanguagesOrTheChoice() {
        assertEquals(0, HaFormat.firstWeekdayIndex(locale()))
        assertEquals(1, HaFormat.firstWeekdayIndex(locale("de")))
        assertEquals(6, HaFormat.firstWeekdayIndex(locale(week = "saturday")))
    }

    @Test
    fun relativeTimesAreTheLanguages() {
        val now = evening
        assertEquals("gestern", HaFormat.relativeTime(now - 86_400_000L, now, locale("de")))
        assertEquals("il y a 3 heures", HaFormat.relativeTime(now - 3 * 3_600_000L, now, locale("fr")))
        assertEquals("vor 5 Minuten", HaFormat.relativeTime(now - 300_000L, now, locale("de")))
        assertEquals("jetzt", HaFormat.relativeTime(now, now, locale("de")))
        assertEquals("avant-hier", HaFormat.relativeTime(now - 2 * 86_400_000L, now, locale("fr")))
        assertEquals("5 Minuten", HaFormat.relativeTime(now - 300_000L, now, locale("de"), includeTense = false))
    }

    // ---- the frontend's own words

    @Test
    fun uiStringsAreHasInTheUsersLanguage() {
        assertEquals("Unavailable", HaText.ui("state.default.unavailable"))
        installStrings("de")
        assertEquals("Nicht verfügbar", HaText.ui("state.default.unavailable"))
        installStrings("fr")
        assertEquals("Indisponible", HaText.ui("state.default.unavailable"))
        // A key HA doesn't have is empty, as HA's localize writes it.
        assertEquals("", HaText.ui("not.a.key"))
        assertEquals("1 item", HaText.format("{count, plural, one {# item} other {# items}}", mapOf("count" to 1), "en"))
        assertEquals("1.234 items", HaText.format("{count, plural, one {# item} other {# items}}", mapOf("count" to 1234), "de"))
        assertEquals("Lamp on", HaText.format("{name} on", mapOf("name" to "Lamp"), "en"))
    }

    @Test
    fun everyKeyVitroUsesIsInHasEnglish() {
        val src = File("src/main/java")
        val key = Regex("\"((?:state|state_badge|ui)\\.[A-Za-z0-9_\\-]+(?:\\.[A-Za-z0-9_\\-]+)+)\"")
        val used = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("the scan found keys", used.size > 20)
        val english = strings("en")
        val missing = used.filterNot(english::containsKey)
        assertTrue("keys missing from strings-en.json (rerun tools/vitro-parity/locale-data.mjs): $missing", missing.isEmpty())
    }

    @Test
    fun everyFrontendLanguageHasItsTable() {
        assertTrue(TableIntlSource.languages.size > 50)
        for (l in TableIntlSource.languages) {
            assertEquals(l, TableIntlSource.resolve(l))
            assertTrue("$l has a time format", HaIntl.of(l).dateFormat("time.h23") != null)
        }
    }

    // ---- an entity's state, end to end

    @Test
    fun aStateIsWrittenInTheUsersLanguageAndFormat() {
        HaLocale.set(locale("de", number = "decimal_comma"), notify = false)
        installStrings("de")
        val temp = EntityState("sensor.t", "21.5", mapOf("unit_of_measurement" to "°C", "state_class" to "measurement"))
        assertEquals("21,5 °C", temp.formattedState())
        val hum = EntityState("sensor.h", "45", mapOf("unit_of_measurement" to "%"))
        assertEquals("45 %", hum.formattedState())
        assertEquals("Nicht verfügbar", EntityState("sensor.t", "unavailable", emptyMap()).formattedState())
        assertEquals("Unbekannt", EntityState("sensor.t", "unknown", emptyMap()).formattedState())
        HaLocale.set(locale("fr", number = "space_comma"), notify = false)
        assertEquals("1 012,5 hPa", EntityState("sensor.p", "1012.5", mapOf("unit_of_measurement" to "hPa")).formattedState())
    }

    @Test
    fun theStateWordsComeFromHasTranslationsInTheUsersLanguage() {
        HaLocale.set(locale("de"), notify = false)
        HaDisplay.applyTranslations(JSONObject().put("resources", JSONObject().put("component.light.entity_component._.state.off", "Aus")), true, listOf("light"))
        assertEquals("Aus", EntityState("light.a", "off", emptyMap()).formattedState())
    }

    @Test
    fun theIslandGetsTheUsersLocale() {
        val json = locale("de", number = "decimal_comma", time = "24").toJson()
        assertEquals("de", json.getString("language"))
        assertEquals("decimal_comma", json.getString("number_format"))
        assertEquals("24", json.getString("time_format"))
        assertEquals("local", json.getString("time_zone"))
    }

    private companion object {
        const val FA_NOWRUZ = "۱ فروردین ۱۴۰۵"
        const val FA_EVENING = "۱۵ دی ۱۴۰۱"
    }
}
