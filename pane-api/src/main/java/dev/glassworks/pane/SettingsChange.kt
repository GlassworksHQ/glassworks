/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Changing a setting, the same on every settings surface (#242). The panel's menu and the Manage page
 * both change an item through [change]: it checks the value as the panel's control limits it (a number
 * in the slider's range, one of the duration's choices, an option's id) and then calls the item's own
 * `save`. So the checks live here once, and a browser can't send what the panel wouldn't take.
 */
object SettingsChange {

    /** The key of a section's on/off ([SettingsSection.master]). */
    const val MASTER = "on"

    /**
     * The items of [section] a person can change, in page order, each with its key: [MASTER] for its
     * on/off, else the titles from the section down, `/`-joined ("Who may connect/IP addresses …").
     */
    fun changeable(section: SettingsSection): List<Pair<String, SettingsItem>> {
        fun walk(items: List<SettingsItem>, prefix: String): List<Pair<String, SettingsItem>> = items.flatMap { i ->
            when {
                i is SettingsItem.Group -> walk(i.items, prefix + i.title + "/")
                // A native view's typed twin: its items as if they sat here (#269).
                i is SettingsItem.Native -> walk(twinOf(i), prefix)
                i is SettingsItem.Link -> walk(twinOf(i), prefix + i.title + "/")
                canChange(i) -> listOf(prefix + titleOf(i) to i)
                else -> emptyList()
            }
        }
        return listOfNotNull(section.master?.let { MASTER to it }) + walk(section.items, "")
    }

    /** The item at [key] in [section], or null when the page has no such item now. */
    fun find(section: SettingsSection, key: String): SettingsItem? = changeable(section).firstOrNull { it.first == key }?.second

    /** [n]'s typed twin as it is now, or none when reading it fails. */
    fun twinOf(n: SettingsItem.Native): List<SettingsItem> = runCatching { n.twin() }.getOrDefault(emptyList())

    /** [l]'s typed twin as it is now, or none when reading it fails. */
    fun twinOf(l: SettingsItem.Link): List<SettingsItem> = runCatching { l.twin() }.getOrDefault(emptyList())

    /** A kind of item a person changes (a Text only while [SettingsItem.Text.enabled]). */
    fun canChange(i: SettingsItem): Boolean = when (i) {
        is SettingsItem.Text -> runCatching { i.enabled() }.getOrDefault(false)
        is SettingsItem.Switch, is SettingsItem.Slider, is SettingsItem.Duration, is SettingsItem.TimeRange, is SettingsItem.Choice,
        is SettingsItem.SignIn, is SettingsItem.Action, is SettingsItem.Wifi -> true
        else -> false
    }

    fun titleOf(i: SettingsItem): String = when (i) {
        is SettingsItem.Text -> i.title
        is SettingsItem.Switch -> i.title
        is SettingsItem.Slider -> i.title
        is SettingsItem.Duration -> i.title
        is SettingsItem.TimeRange -> i.title
        is SettingsItem.Choice -> i.title
        is SettingsItem.SignIn -> i.title
        is SettingsItem.Action -> i.title
        is SettingsItem.Wifi -> i.title
        is SettingsItem.Link -> i.title
        is SettingsItem.Group -> i.title
        is SettingsItem.States -> i.title
        is SettingsItem.Info -> runCatching { i.title() }.getOrDefault("")
        is SettingsItem.Native -> i.key
        is SettingsItem.Blueprints -> BLUEPRINTS
        is SettingsItem.Note, is SettingsItem.Status, is SettingsItem.Tiles -> ""
    }

    /**
     * Applies [raw], the value as text, to [item]: checked, then the item's `save`. [done] gets null when
     * it saved, else what went wrong, in words for the person. A switch takes `on` or `off`; a time range
     * `HH:MM-HH:MM` or empty for none; an action `run` ([SettingsItem.Action.RUN]). A save that throws is an answer too (#63 F8).
     */
    fun change(item: SettingsItem, raw: String, done: (String?) -> Unit) {
        val v = raw.trim()
        if (v.length > MAX_LENGTH) return done("That's too long.")
        if (v.any { it.code < 0x20 }) return done("That has characters a setting can't take.")
        try {
            when (item) {
                is SettingsItem.Text -> {
                    if (!canChange(item)) return done(CANT_NOW)
                    val ok = when (item.kind) {
                        SettingsItem.TextKind.NUMBER -> v.all(Char::isDigit)
                        SettingsItem.TextKind.DECIMAL -> v.isEmpty() || DECIMAL.matches(v)
                        SettingsItem.TextKind.COLOR -> COLOR.matches(v)
                        else -> true
                    }
                    if (!ok) return done(when (item.kind) {
                        SettingsItem.TextKind.NUMBER -> "Use digits only."
                        SettingsItem.TextKind.COLOR -> "Use a color like #38bdf8."
                        else -> "Use a number, like 1.5."
                    })
                    // A color is saved one way, `#rrggbb` in lower case, however it was typed.
                    item.save(if (item.kind == SettingsItem.TextKind.COLOR) "#" + v.removePrefix("#").lowercase() else v, done)
                }
                is SettingsItem.Switch -> when (v) {
                    "on" -> item.save(true, done)
                    "off" -> item.save(false, done)
                    else -> done("That isn't on or off.")
                }
                is SettingsItem.Slider -> {
                    val f = v.toFloatOrNull()?.takeIf { it.isFinite() } ?: return done("That isn't a number.")
                    if (f < item.min - EPS || f > item.max + EPS) return done("Pick from ${plain(item.min)} to ${plain(item.max)}.")
                    item.save(snap(f, item.min, item.max, item.step), done)
                }
                is SettingsItem.Duration -> {
                    val s = v.toIntOrNull()
                    if (s == null || (s !in item.choices && s != item.value())) return done(PICK)
                    item.save(s, done)
                }
                is SettingsItem.TimeRange -> {
                    if (v.isNotEmpty() && parseRange(v) == null) return done("Use times like 22:00, from 00:00 to 23:59.")
                    // On the item's steps, as the panel's pickers give them (a form can send any minute).
                    fun step(m: Int) = if (item.stepMin <= 0) m else Math.floorMod(((m + item.stepMin / 2) / item.stepMin) * item.stepMin, 24 * 60)
                    item.save(parseRange(v)?.let { (a, b) -> "${hhmm(step(a))}-${hhmm(step(b))}" }.orEmpty(), done)
                }
                is SettingsItem.Choice -> {
                    if (item.options().none { it.first == v }) return done(PICK)
                    item.save(v, done)
                }
                is SettingsItem.Action -> {
                    if (v != SettingsItem.Action.RUN) return done(CANT_HERE)
                    item.run(done)
                }
                else -> done(CANT_HERE)
            }
        } catch (e: Throwable) {
            done(couldNot(e))
        }
    }

    /**
     * Saves a sign-in's [user] and [password] after the checks a stream's sign-in needs (RTSP and ONVIF
     * put them in `user:password`): a name with no colon or spaces, a password, both within their length.
     * An empty [password] keeps the one stored, so the name can change alone; with none stored it is
     * refused. The password is never in what [done] says.
     */
    fun signIn(item: SettingsItem.SignIn, user: String, password: String, done: (String?) -> Unit) {
        val u = user.trim()
        val problem = when {
            u.isEmpty() -> "Enter a username."
            u.length > USER_MAX -> "Use at most $USER_MAX characters for the username."
            u.any { it == ':' || it.isWhitespace() || it.code < 0x20 } -> "A username can't contain spaces or a colon."
            password.isEmpty() && !runCatching { item.isSet() }.getOrDefault(false) -> TYPE_PASSWORD
            password.length > PASSWORD_MAX -> "Use at most $PASSWORD_MAX characters for the password."
            password.any { it.code < 0x20 || it.code == 0x7f } -> "That password has characters a sign-in can't take."
            else -> null
        }
        if (problem != null) return done(problem)
        try { item.save(u to password.ifEmpty { null }, done) } catch (e: Throwable) { done(COULD_NOT_SIGN_IN) }
    }

    /**
     * Joins the Wi-Fi network [network] with [password] ([SettingsItem.Wifi]) after the checks every surface makes: a
     * name of 1 to 32 bytes (Wi-Fi's limit), a password of at most 63 characters or 64 hex digits (empty for an open
     * network), neither with control characters. Whether the password fits the network's security is the join's to
     * say. The password is never in what [done] says, nor in a thrown exception's text.
     */
    fun wifi(item: SettingsItem.Wifi, network: String, password: String, done: (String?) -> Unit) {
        // The name goes to the join exactly as picked or typed: Android matches a network by its exact bytes, and a
        // name may really start or end with a space. A stray space typed by mistake fails the join, which goes back.
        wifiProblem(item, network, password)?.let { return done(it) }
        try { item.join(network, password, done) } catch (e: Throwable) { done(COULD_NOT_JOIN) }
    }

    /** What [wifi] refuses before it joins, or null: a surface checks this before asking for the panel's code. */
    fun wifiProblem(item: SettingsItem.Wifi, network: String, password: String): String? = when {
        !runCatching { item.open() }.getOrDefault(false) -> WIFI_BUSY
        network.isBlank() -> "Pick a network, or type its name."
        network.toByteArray(Charsets.UTF_8).size > WIFI_NAME_MAX -> "A Wi-Fi network's name has at most $WIFI_NAME_MAX characters."
        network.any { it.code < 0x20 || it.code == 0x7f } -> "That network name has characters Wi-Fi can't take."
        password.length > WIFI_PASSWORD_MAX -> "A Wi-Fi password has at most $WIFI_PASSWORD_MAX characters."
        password.any { it.code < 0x20 || it.code == 0x7f } -> "That password has characters Wi-Fi can't take."
        else -> null
    }

    /** Clear: the sign-in is removed. */
    fun clear(item: SettingsItem.SignIn, done: (String?) -> Unit) {
        try { item.save(null, done) } catch (e: Throwable) { done(COULD_NOT_SIGN_IN) }
    }

    /** A typed value as its row shows it: [SettingsItem.Text.shown], else its hint while it's empty. */
    fun shown(i: SettingsItem.Text): String {
        val v = i.value()
        return if (v.isBlank()) i.shown(v).ifBlank { i.hint } else i.shown(v)
    }

    /** The row's line for the picked option: its [SettingsItem.Choice.line], else its name, or its id when it has none. */
    fun shown(i: SettingsItem.Choice): String {
        val v = i.value()
        i.line?.let { return it(v) }
        return i.options().firstOrNull { it.first == v }?.second ?: v
    }

    /** The slider's value on its steps, as the panel's slider snaps it. */
    fun snap(v: Float, min: Float, max: Float, step: Float): Float {
        if (step <= 0f) return v.coerceIn(min, max)
        val n = ((v.coerceIn(min, max) - min) / step).roundToInt()
        return (min + n * step).coerceIn(min, max)
    }

    /** 2.5, 0.1, 100: as typed. */
    fun plain(f: Float): String = java.math.BigDecimal(f.toString()).stripTrailingZeros().toPlainString()

    /** What a save that threw says. */
    fun couldNot(e: Throwable): String = "That didn't save: ${e.message ?: e.javaClass.simpleName}."

    fun hhmm(min: Int): String = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    /** `22:00-07:00` as minutes after midnight, or null. */
    fun parseRange(w: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(w) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        if (m1.toInt() > 59 || m2.toInt() > 59) return null
        val a = h1.toInt() * 60 + m1.toInt(); val b = h2.toInt() * 60 + m2.toInt()
        return if (a < 24 * 60 && b < 24 * 60) a to b else null
    }

    /** A change that takes effect when the app starts again ([SettingsItem.Text.restartsApp]): the banner's words on both surfaces. */
    const val RESTART_TEXT = "This takes effect after the app restarts. It takes about 5 seconds."

    /** Under "In Home Assistant", when a row is dimmed. */
    const val OFF_BY_DEFAULT = "Dimmed: off by default in Home Assistant. Turn them on there to keep their history."

    /** Whether a saved change waits for the app to start again. */
    fun restarts(i: SettingsItem): Boolean = (i as? SettingsItem.Text)?.restartsApp == true || (i as? SettingsItem.Switch)?.restartsApp == true

    const val USER_MAX = 64
    const val PASSWORD_MAX = 128

    /** A sign-in's save that threw: no exception text, which could hold what was typed. */
    const val COULD_NOT_SIGN_IN = "That sign-in didn't save."
    const val TYPE_PASSWORD = "Enter a password."
    /** A set sign-in's password field: the stored one never shows, so it takes a new one. */
    const val NEW_PASSWORD = "Set a new password · leave empty to keep it"

    /** A Wi-Fi network's name: 32 bytes ([wifi]). */
    const val WIFI_NAME_MAX = 32
    /** A WPA passphrase is 8 to 63 characters, a raw key 64 hex digits ([wifi]). */
    const val WIFI_PASSWORD_MAX = 64
    /** The Wi-Fi sheet's fields and its button, on both surfaces. */
    const val WIFI_NAME = "Network name"
    const val WIFI_PASSWORD = "Password, if it has one"
    const val WIFI_JOIN = "Join"
    const val WIFI_BUSY = "A Wi-Fi change is under way. Wait for it to finish."
    /** A Wi-Fi join that threw: no exception text, which could hold what was typed. */
    const val COULD_NOT_JOIN = "The panel couldn't start joining that network."

    const val CANT_NOW = "This can't change now."
    const val CANT_HERE = "This can't be changed here."
    const val PICK = "Pick one of the choices."

    private const val MAX_LENGTH = 2000
    private const val EPS = 1e-4f
    private val DECIMAL = Regex("""^\d+(\.\d+)?$""")
    private val COLOR = Regex("""^#?[0-9a-fA-F]{6}$""")
}
