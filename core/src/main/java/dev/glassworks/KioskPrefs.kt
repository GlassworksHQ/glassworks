package dev.glassworks

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import dev.glassworks.screen.ScreenSettings
import dev.glassworks.esphome.ReadableNoiseKeyStore
import dev.glassworks.esphome.StoredKey
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretStore
import dev.glassworks.web.LocalName
import dev.glassworks.web.LocalRoute
import dev.glassworks.auth.OAuth
import dev.glassworks.auth.StoredAuth

/**
 * The panel's core settings (SharedPreferences, private to the app). The app decides where each pane's
 * settings live (PanePrefs in the S1 app). Secrets (the ESPHome API encryption key in `kiosk.xml`,
 * which Home Assistant provisions, and the HA refresh token in `auth.xml`, see [auth]) are sealed
 * with the Keystore key ([SecretFiles]) and never logged.
 */
class KioskPrefs(
    context: Context,
    secrets: SecretStore = SecretStore.keystore(),
    private val platform: PlatformApi = Platform,
) : ScreenSettings {
    private val sp = SecretFiles.open(context, "kiosk", secrets)
    private val authSp = SecretFiles.open(context, "auth", secrets)

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /**
     * The dashboard URL setting: a URL, or [AUTO] (the default) to use Home Assistant's root on the
     * discovered [haUrl]. A URL saved by an earlier version is kept as is.
     */
    var urlSetting: String
        get() = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: AUTO
        // commit(), not apply(): a URL change can be followed by an app restart (secure-context hosts),
        // and an async write could be lost with the process, leaving the old URL ("auto").
        set(v) { sp.edit().putString(KEY_URL, v.trim().ifEmpty { AUTO }).commit() }

    val isAutoUrl: Boolean get() = urlSetting.equals(AUTO, ignoreCase = true)

    /**
     * The dashboard URL to load, or "" while [isAutoUrl] and Home Assistant hasn't been found. A set URL on a `.local`
     * host loads through Home Assistant's [haUrl] where Android can't resolve the name ([dashboardRoute], #449).
     */
    var url: String
        get() = if (isAutoUrl) haUrl.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: "" else dashboardRoute.url
        set(v) { urlSetting = v }

    /** How [url] reaches a set dashboard URL: as set, or through [haUrl] in place of its `.local` name. */
    val dashboardRoute: LocalRoute get() = route(urlSetting)

    /** How the panel reaches [url] now: as set, or through [haUrl] in place of a `.local` name Android can't resolve (#449). */
    fun route(url: String): LocalRoute = LocalName.route(url, haUrl, platform.resolvesLocalNames)

    /**
     * The host the connecting screen names for a page at [url]: the `.local` name the person entered where the panel
     * reaches it by address (#449), else the page's own host. "" for no web page.
     */
    fun shownHost(url: String?): String {
        val host = url?.takeIf { it.startsWith("http") }?.let { Uri.parse(it).host }.orEmpty()
        val r = dashboardRoute as? LocalRoute.ByAddress ?: return host
        return if (host.isNotEmpty() && host == Uri.parse(r.url).host) r.name else host
    }

    /**
     * The dashboard loads through the address discovery found, or waits for one: a load error may mean that address
     * changed, or that discovery hasn't found it yet.
     */
    val loadsFoundAddress: Boolean
        get() = isAutoUrl || dashboardRoute.let { it is LocalRoute.ByAddress || it is LocalRoute.NoAddressYet }

    /** Home Assistant base URL found by discovery or entered on the sign-in screen (`http://h:8123`). */
    val haUrl: String get() = sp.getString("ha_url", "") ?: ""

    /** How [haUrl] was learnt: `mdns`, `esphome`, `manual` (HaDiscovery.SOURCE_*), or "". */
    val haUrlSource: String get() = sp.getString("ha_url_source", "") ?: ""

    fun setHaUrl(url: String, source: String) =
        sp.edit().putString("ha_url", url).putString("ha_url_source", source).commit()

    /**
     * The Home Assistant the panel talks to (sign-in, relative media URLs): the origin of the
     * configured dashboard [url], else the discovered [haUrl]. Null if neither is known.
     */
    val haBase: String?
        get() = (if (isAutoUrl) null else OAuth.origin(url)) ?: haUrl.takeIf { it.isNotEmpty() }

    /**
     * [haBase] as the setting names it: a `.local` host stays one. For what compares with the setting or writes it
     * (the dashboard select), so a choice there keeps the name the person entered.
     */
    val settingBase: String?
        get() = (if (isAutoUrl) null else OAuth.origin(urlSetting)) ?: haUrl.takeIf { it.isNotEmpty() }

    // ---- first-boot setup screen + dashboard select (docs/onboarding.md, steps 4-5)

    /** Home Assistant has subscribed to the panel's ESPHome states at least once (it added the panel). */
    var haSeen: Boolean
        get() = sp.getBoolean(KEY_HA_SEEN, false)
        set(v) = sp.edit().putBoolean(KEY_HA_SEEN, v).apply()

    /**
     * Until when (wall clock, ms) pairing over the LAN is open again after Home Assistant adopted the panel: the
     * Home Assistant button or the installer's USB op re-arm it (dev.glassworks.setup.LanSetup, #34). 0 = not armed.
     */
    var lanSetupUntil: Long
        get() = sp.getLong("lan_setup_until", 0L)
        private set(v) = sp.edit().putLong("lan_setup_until", v).apply()

    /** Pairing over the LAN is open: Home Assistant hasn't adopted the panel yet, or it was re-armed lately. */
    val lanPairingOpen: Boolean
        get() = !haSeen || System.currentTimeMillis() < lanSetupUntil

    /** Opens pairing over the LAN for [dev.glassworks.setup.LanSetup.ARM_MS] from now. */
    fun armLanSetup() {
        lanSetupUntil = System.currentTimeMillis() + dev.glassworks.setup.LanSetup.ARM_MS
    }

    /** Ends a re-arm now: pairing over the LAN is closed again once Home Assistant has the panel. */
    fun closeLanSetup() {
        lanSetupUntil = 0L
    }

    /** Pairing over the LAN is open because someone re-armed it, not only because Home Assistant hasn't added the panel. */
    val lanSetupArmed: Boolean
        get() = haSeen && System.currentTimeMillis() < lanSetupUntil

    /**
     * Experimental native Lovelace rendering engine (Vitro): off by default, read once at process start. Written with
     * commit(): the app restarts for it, and an async write could be lost with the process. Turning it on clears
     * what [VitroSentinel] recorded, so Vitro starts again with a clean slate.
     */
    var vitroEnabled: Boolean
        get() = sp.getBoolean(KEY_VITRO_ENABLED, false)
        set(v) {
            val e = sp.edit().putBoolean(KEY_VITRO_ENABLED, v)
            if (v) e.remove(KEY_VITRO_STRIKES).remove(KEY_VITRO_OFF_REASON).remove(KEY_VITRO_OFF_AT).remove(KEY_VITRO_OFF_NOTICE)
            // On or off, the last start's marker goes: it belonged to the setting before this one.
            e.remove(KEY_VITRO_START).remove(KEY_VITRO_STARTS)
            e.commit()
        }

    /** Crashes in a row soon after a start with Vitro on ([VitroSentinel]); 0 once a start stays up. */
    var vitroCrashStrikes: Int
        get() = sp.getInt(KEY_VITRO_STRIKES, 0)
        set(v) {
            if (v == 0) sp.edit().remove(KEY_VITRO_STRIKES).commit() else sp.edit().putInt(KEY_VITRO_STRIKES, v).commit()
        }

    /**
     * One start with Vitro on, as [VitroSentinel] marks it before Vitro draws: start [n] of this setting, on boot [boot]
     * (null where Android can't tell one boot from the next), in process [pid], at [atMs] on the elapsed clock and
     * [atWallMs] on the wall clock. [drawn] once the first page was drawn.
     */
    class VitroStart(val n: Int, val boot: String?, val pid: Int, val atMs: Long, val atWallMs: Long, val drawn: Boolean = false) {
        internal fun encode(): String = listOf("1", n, if (drawn) 1 else 0, pid, atMs, atWallMs, boot.orEmpty()).joinToString("|")

        internal companion object {
            fun decode(s: String?): VitroStart? = runCatching {
                val f = s?.split('|', limit = 7)?.takeIf { it.size == 7 && it[0] == "1" } ?: return@runCatching null
                VitroStart(f[1].toInt(), f[6].ifEmpty { null }, f[3].toInt(), f[4].toLong(), f[5].toLong(), f[2] == "1")
            }.getOrNull()
        }
    }

    /** The last start's marker ([VitroStart]), while it is still open: begun, and not yet settled. */
    val vitroStart: VitroStart?
        get() = VitroStart.decode(sp.getString(KEY_VITRO_START, null))

    /** The number the next start with Vitro on gets (1, 2, …), counted from when the setting last changed. */
    val nextVitroStart: Int
        get() = sp.getInt(KEY_VITRO_STARTS, 0) + 1

    /** Marks [start] as begun, before Vitro draws anything. commit(): a native crash a moment later must find it. */
    fun beginVitroStart(start: VitroStart): Boolean =
        sp.edit().putString(KEY_VITRO_START, start.encode()).putInt(KEY_VITRO_STARTS, start.n).commit()

    /** The open start drew its first page: from here only a crash Android names ([VitroSentinel]) counts. */
    fun markVitroStartDrawn(): Boolean {
        val s = vitroStart ?: return false
        if (s.drawn) return true
        return sp.edit().putString(KEY_VITRO_START, VitroStart(s.n, s.boot, s.pid, s.atMs, s.atWallMs, drawn = true).encode()).commit()
    }

    /** The open start is accounted for (it stayed up, the app restarted itself, its end was counted): no marker left. */
    fun settleVitroStart(): Boolean = !sp.contains(KEY_VITRO_START) || sp.edit().remove(KEY_VITRO_START).commit()

    /** [strikes] crashes in a row ([vitroCrashStrikes]) and the open start settled, in one commit: never counted twice. */
    fun recordVitroStrikes(strikes: Int): Boolean {
        val e = sp.edit().remove(KEY_VITRO_START)
        if (strikes == 0) e.remove(KEY_VITRO_STRIKES) else e.putInt(KEY_VITRO_STRIKES, strikes)
        return e.commit()
    }

    /** [VitroSentinel] turned Vitro off: why (the exception and where it came from), and when (ms since the epoch). */
    class VitroTurnedOff(val reason: String, val atMs: Long)

    /** What [turnVitroOffAfterCrashes] recorded, while Vitro stays off; null once it's turned on again. */
    val vitroTurnedOff: VitroTurnedOff?
        get() = sp.getString(KEY_VITRO_OFF_REASON, null)?.takeUnless { vitroEnabled }?.let { VitroTurnedOff(it, sp.getLong(KEY_VITRO_OFF_AT, 0L)) }

    /**
     * Turns Vitro off after it crashed the app at start again and again ([VitroSentinel]), and keeps why for the
     * Labs page and a notice at the next start. One commit(), from the crashing thread: the process ends right after.
     */
    fun turnVitroOffAfterCrashes(reason: String, atMs: Long): Boolean =
        sp.edit().putBoolean(KEY_VITRO_ENABLED, false).remove(KEY_VITRO_STRIKES).remove(KEY_VITRO_START).remove(KEY_VITRO_STARTS)
            .putString(KEY_VITRO_OFF_REASON, reason).putLong(KEY_VITRO_OFF_AT, atMs).putBoolean(KEY_VITRO_OFF_NOTICE, true)
            .commit()

    /** Whether the panel still has to say that Vitro was turned off ([turnVitroOffAfterCrashes]), cleared as it's read. */
    fun takeVitroOffNotice(): Boolean {
        val pending = sp.getBoolean(KEY_VITRO_OFF_NOTICE, false)
        if (pending) sp.edit().remove(KEY_VITRO_OFF_NOTICE).commit()
        return pending
    }

    /** The panel has been online over Wi-Fi at least once (the boot screen's "Connecting to Wi-Fi…" hint). */
    var wifiSeen: Boolean
        get() = sp.getBoolean(KEY_WIFI_SEEN, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_SEEN, v).apply()

    /** The device has reported a dock at least once: Home Assistant keeps its *Docked* sensor from then on (dev.glassworks.dock.Docked). */
    var dockSeen: Boolean
        get() = sp.getBoolean("dock_seen", false)
        set(v) = sp.edit().putBoolean("dock_seen", v).apply()

    /** "Skip" on the setup screen's Add-to-Home-Assistant step. */
    var haSetupSkipped: Boolean
        get() = sp.getBoolean("setup_ha_skipped", false)
        set(v) = sp.edit().putBoolean("setup_ha_skipped", v).apply()

    /**
     * Whether the setup screen was up when the app restarted itself (Gecko reads Home Assistant's host only at startup):
     * the new process puts it up again at once, so nothing else shows in between. Written with commit(), as the
     * process exits right after; read once ([takeSetupUpAtRestart]).
     */
    fun markSetupUpAtRestart(up: Boolean) {
        sp.edit().putBoolean(SETUP_UP_AT_RESTART, up).commit()
    }

    /** [markSetupUpAtRestart]'s mark, cleared as it is read: only the first start after the restart has it. */
    fun takeSetupUpAtRestart(): Boolean {
        val up = sp.getBoolean(SETUP_UP_AT_RESTART, false)
        if (sp.contains(SETUP_UP_AT_RESTART)) sp.edit().remove(SETUP_UP_AT_RESTART).commit()
        return up
    }

    /** Setup's Silent updates step was answered: Continue or Not now, or the panel was set up before the step (#422). */
    var silentUpdatesAnswered: Boolean
        get() = sp.getBoolean("setup_silent_updates_answered", false)
        set(v) = sp.edit().putBoolean("setup_silent_updates_answered", v).apply()

    /**
     * Which releases the app updates to (#605): Stable, the default, or Beta, which also takes pre-releases. The update
     * check reads it every time; the Device page's *Update channel* row and the Manage page set it.
     */
    var updateChannel: dev.glassworks.update.UpdateChannel
        get() = dev.glassworks.update.UpdateChannel.of(sp.getString(KEY_UPDATE_CHANNEL, null))
        set(v) { sp.edit().putString(KEY_UPDATE_CHANNEL, v.id).apply() }

    /** The device's own setup steps had their once-per-install check (GlassworksRuntime.deviceSetupSteps). */
    var deviceStepsMigrated: Boolean
        get() = sp.getBoolean("setup_device_steps_migrated", false)
        set(v) = sp.edit().putBoolean("setup_device_steps_migrated", v).apply()

    /** Lovelace dashboards the "Dashboard" select was last built from (`[{url_path,title}]`). */
    var dashboardsJson: String
        get() = sp.getString("dashboards", "") ?: ""
        set(v) = sp.edit().putString("dashboards", v).apply()

    // ---- Home Assistant sign-in (QR + OAuth, see auth/HaAuth.kt). Never log these values.

    var auth: StoredAuth?
        get() {
            val rt = authSp.getString("refresh_token", null) ?: return null
            val base = authSp.getString("ha_base", null) ?: return null
            val cid = authSp.getString("client_id", null) ?: return null
            return StoredAuth(base, cid, rt)
        }
        /**
         * Writes the three keys (never `clear()`, which would drop a sealed token the key store can't read
         * now). Throws [SignInNotStored] when it wasn't stored: a sign-in during a key store outage fails
         * where the person sees it (the sign-in page says the panel couldn't store it), not silently.
         */
        set(v) {
            val e = authSp.edit()
            if (v == null) e.remove("refresh_token").remove("ha_base").remove("client_id")
            else e.putString("refresh_token", v.refreshToken).putString("ha_base", v.haBase).putString("client_id", v.clientId)
            if (!e.commit()) throw SignInNotStored(if (v == null) "The sign-in can't be removed right now" else "The panel can't store the sign-in right now")
        }

    val hasAuth: Boolean get() = authSp.contains("refresh_token")

    /**
     * The sign-ins the panel's sign-in page started and Home Assistant hasn't sent back yet ([dev.glassworks.auth.StateStore]),
     * so one survives the app restarting meanwhile. Sealed: each holds its PKCE verifier. Null for none. Throws
     * [SignInNotStored] when it couldn't be written (a key store outage).
     */
    var pendingSignIns: String?
        get() = authSp.getString("pending_signins", null)
        set(v) {
            val e = authSp.edit()
            if (v == null) e.remove("pending_signins") else e.putString("pending_signins", v)
            if (!e.commit()) throw SignInNotStored("The pending sign-ins can't be stored right now")
        }

    /** The refresh token as it reads now, for the installer's hand-over ([dev.glassworks.device.SecretHandover], #584). */
    fun refreshTokenSecret(): SecretPrefs.Secret = authSp.secret("refresh_token")

    /**
     * The refresh token an installer carried across a reinstall (#584), into the sign-in its data copy holds: the
     * Home Assistant address and client id are plain settings, so they came across, and only the sealed token didn't.
     * Null when there is no such sign-in here (the data didn't come across). See [SecretPrefs.carryOver].
     */
    fun carryOverRefreshToken(token: String): SecretPrefs.CarryOver? {
        if (authSp.getString("ha_base", null) == null || authSp.getString("client_id", null) == null) return null
        return authSp.carryOver("refresh_token", token)
    }

    /**
     * Page zoom as a CSS device-pixel ratio override (Gecko pref `layout.css.devPixelsPerPx`).
     * 0 = automatic (use the display density, 1.5 on the S1 Plus). Never change `wm density`
     * instead: on this panel it breaks GeckoView's surface sizing until reboot.
     */
    // commit(), not apply(), for the three settings the app restarts for (#185): an async write can be
    // lost with the process, as for urlSetting.
    var zoom: Float
        get() = sp.getFloat(KEY_ZOOM, 0f)
        set(v) { sp.edit().putFloat(KEY_ZOOM, v).commit() }

    /** Extra hosts (comma separated) to treat as secure contexts, besides the dashboard host. */
    var extraSecureHosts: String
        get() = sp.getString(KEY_SECURE_HOSTS, "") ?: ""
        set(v) { sp.edit().putString(KEY_SECURE_HOSTS, v.trim()).commit() }

    /** Gecko remote debugging. Unset, it follows the build type: [debugBuild] is the app's [dev.glassworks.platform.AppBuild.debug]. */
    fun remoteDebugging(debugBuild: Boolean): Boolean = sp.getBoolean(KEY_REMOTE_DEBUG, debugBuild)

    fun setRemoteDebugging(on: Boolean) { sp.edit().putBoolean(KEY_REMOTE_DEBUG, on).commit() }

    // ---- HA page tweaks (applied by the built-in web extension)

    var hideHeader: Boolean
        get() = sp.getBoolean("hide_header", true)
        set(v) = sp.edit().putBoolean("hide_header", v).apply()

    /**
     * The overlays' theme (#691): a [dev.glassworks.theme.Themes] pick id, `glassworks/dark` when none is saved. An id
     * this build doesn't know reads as the default. A factory reset clears it, as the other settings.
     */
    var overlayTheme: String
        get() = dev.glassworks.theme.Themes.pick(sp.getString("overlay_theme", null), customTheme, haTheme).id
        set(v) = sp.edit().putString("overlay_theme", dev.glassworks.theme.Themes.pick(v, customTheme, haTheme).id).apply()

    /**
     * The custom theme's palettes (#691's "Custom themes"), or null while none are saved: then `custom/…` reads as the
     * default. Stored as [dev.glassworks.theme.Themes.encode]'s text; a factory reset clears it.
     */
    var customTheme: dev.glassworks.theme.Themes.Theme?
        get() = dev.glassworks.theme.Themes.decode(sp.getString("overlay_custom", null))
        set(v) = sp.edit().apply { if (v == null) remove("overlay_custom") else putString("overlay_custom", dev.glassworks.theme.Themes.encode(v)) }.apply()

    /**
     * How a voice run shows (#691): `glow`, Spark's edge glow with the dashboard in view (the default), or `orb`, the
     * glass orb over a dimmed dashboard. Anything else, or nothing saved, reads as the edge glow.
     */
    var assistLook: String
        get() = sp.getString("assist_look", null).takeIf { it == ASSIST_ORB } ?: ASSIST_GLOW
        set(v) = sp.edit().putString("assist_look", if (v == ASSIST_ORB) ASSIST_ORB else ASSIST_GLOW).apply()

    /**
     * What the overlays draw with now: the saved pick, the custom theme's palettes for `custom/…`. While
     * [followHaDark] is on and Home Assistant has said which it is ([haDark]), the picked theme in HA's variant.
     */
    val overlayPick: dev.glassworks.theme.Themes.Pick
        get() {
            val saved = dev.glassworks.theme.Themes.pick(sp.getString("overlay_theme", null), customTheme, haTheme)
            // Home Assistant's own theme is already in HA's variant.
            if (saved.theme.id == dev.glassworks.theme.Themes.HA) return saved
            val dark = haDark?.takeIf { followHaDark } ?: return saved
            return dev.glassworks.theme.Themes.Pick(saved.theme, dark)
        }

    /**
     * The overlays' theme takes Home Assistant's light or dark (#691's "Follow Home Assistant's dark mode"): the picked
     * theme stays, its variant follows HA's dark mode. Off by default.
     */
    var followHaDark: Boolean
        get() = sp.getBoolean(KEY_FOLLOW_HA_DARK, false)
        set(v) = sp.edit().putBoolean(KEY_FOLLOW_HA_DARK, v).apply()

    /**
     * Home Assistant's theme (#691): the colors its dashboard shows now (primary, accent, background), as the kiosk
     * extension last read them, or null while it hasn't. Kept so the overlays have them from the start.
     */
    var haColors: dev.glassworks.theme.Themes.Palette?
        get() = dev.glassworks.theme.Themes.decodePalette(sp.getString(KEY_HA_COLORS, null))
        set(v) = sp.edit().apply { if (v == null) remove(KEY_HA_COLORS) else putString(KEY_HA_COLORS, dev.glassworks.theme.Themes.encode(v)) }.apply()

    /** [haColors] as a theme to pick, or null while the dashboard hasn't shown its colors. */
    val haTheme: dev.glassworks.theme.Themes.Theme? get() = haColors?.let(dev.glassworks.theme.Themes::ha)

    /**
     * Whether Home Assistant's frontend is in dark mode, as the dashboard last said (`hass.themes.darkMode`, read by the
     * kiosk extension), or null while it hasn't said. Kept so the overlays have it from the start.
     */
    var haDark: Boolean?
        get() = when (sp.getString(KEY_HA_DARK, null)) { "dark" -> true; "light" -> false; else -> null }
        set(v) = sp.edit().apply { if (v == null) remove(KEY_HA_DARK) else putString(KEY_HA_DARK, if (v) "dark" else "light") }.apply()

    var hideSidebar: Boolean
        get() = sp.getBoolean("hide_sidebar", true)
        set(v) = sp.edit().putBoolean("hide_sidebar", v).apply()

    // ---- screen policy (see dev.glassworks.screen.ScreenPolicy)

    /**
     * "presence" = on while someone is near / recently touched, off after idle; "always" = never off. "always" until
     * someone picks (#522): a frame with nothing that senses presence would otherwise just turn off when idle.
     */
    override var screenMode: String
        get() = sp.getString("screen_mode", "always") ?: "always"
        set(v) = sp.edit().putString("screen_mode", v).apply()

    override var idleOffSec: Int
        get() = sp.getInt("idle_off_sec", 120)
        set(v) = sp.edit().putInt("idle_off_sec", v).apply()

    override var wakeOnApproach: Boolean
        get() = sp.getBoolean("wake_on_approach", true)
        set(v) = sp.edit().putBoolean("wake_on_approach", v).apply()

    /** Presence range in metres for the VI5300 (0 = leave the firmware setting alone). */
    override var presenceRangeM: Float
        get() = sp.getFloat("presence_range_m", 0f)
        set(v) = sp.edit().putFloat("presence_range_m", v).apply()

    override var autoBrightness: Boolean
        get() = sp.getBoolean("auto_brightness", true)
        set(v) = sp.edit().putBoolean("auto_brightness", v).apply()

    override var minBrightness: Float
        get() = sp.getFloat("min_brightness", 0.10f)
        set(v) = sp.edit().putFloat("min_brightness", v).apply()

    override var maxBrightness: Float
        get() = sp.getFloat("max_brightness", 1.0f)
        set(v) = sp.edit().putFloat("max_brightness", v).apply()

    /** Fixed brightness (0..1) used while auto brightness is off; -1 = system default. */
    override var manualBrightness: Float
        get() = sp.getFloat("manual_brightness", -1f)
        set(v) = sp.edit().putFloat("manual_brightness", v).apply()

    // ---- ESPHome native API (see docs/esphome.md)

    /**
     * Noise encryption for the API. True once a key is set (by Home Assistant's provisioning or
     * `--es api_key`); false = no key yet: plaintext + zero-PSK Noise, key provisionable.
     */
    var apiEncryption: Boolean
        get() = sp.getBoolean("api_encryption", false)
        set(v) = sp.edit().putBoolean("api_encryption", v).apply()

    /** 32-byte Noise PSK, base64. */
    /** Setting it throws IllegalStateException when it wasn't stored (the key store is down). */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { if (!sp.edit().putString("api_key", v.trim()).commit()) throw IllegalStateException("The API key can't be stored right now") }

    /** The active Noise key: [apiKey] while [apiEncryption] is on, else null (unprovisioned). */
    fun effectiveApiKey(): ByteArray? = if (apiEncryption) decodeApiKey(apiKey) else null

    /** Persists the key HA provisions over the API (synchronously, like ESPHome's pref sync). */
    val noiseKeyStore: ReadableNoiseKeyStore = object : ReadableNoiseKeyStore {
        override fun load(): ByteArray? = effectiveApiKey()
        // Both writes in one edit: when the key isn't stored (the key store is down), neither is
        // api_encryption, and save() says false, so Home Assistant hears the key wasn't set.
        override fun save(key: ByteArray?): Boolean =
            if (key == null) sp.edit().remove("api_key").putBoolean("api_encryption", false).commit()
            else sp.edit().putString("api_key", Base64.encodeToString(key, Base64.NO_WRAP))
                .putBoolean("api_encryption", true).commit()
        override fun read(): StoredKey = if (!apiEncryption) StoredKey.Absent else when (val r = sp.secret("api_key")) {
            is SecretPrefs.Secret.Value -> decodeApiKey(r.text)?.let { StoredKey.Present(it) } ?: StoredKey.Absent
            SecretPrefs.Secret.Absent -> StoredKey.Absent
            SecretPrefs.Secret.Unavailable -> StoredKey.Unreadable
        }
    }

    /**
     * The API key an installer hands over ([dev.glassworks.device.SecretHandover]): into an empty store (a reflash,
     * #574), or over one sealed with a key this app no longer has (a reinstall that kept the data, #584). Never over a
     * key in use. See [SecretPrefs.carryOver].
     */
    fun carryOverApiKey(key: ByteArray): SecretPrefs.CarryOver = when {
        // Encryption off: whatever api_key holds (an `api_key` extra without `api_encryption`) isn't a key in use.
        !apiEncryption -> if (noiseKeyStore.save(key)) SecretPrefs.CarryOver.STORED else SecretPrefs.CarryOver.NOT_STORED
        else -> sp.carryOver("api_key", Base64.encodeToString(key, Base64.NO_WRAP)) { it.putBoolean("api_encryption", true) }
    }

    /** Stable MAC reported to Home Assistant (its config entry is keyed by it). */
    var deviceMac: String
        get() = sp.getString("device_mac", "") ?: ""
        set(v) = sp.edit().putString("device_mac", v).apply()

    /** The master volume, 0 to 1 ([dev.glassworks.audio.MasterVolume]); -1 until it first reads Android's media volume. */
    var masterLevel: Float
        get() = sp.getFloat("master_level", -1f)
        set(v) = sp.edit().putFloat("master_level", v).apply()

    /** The master volume is muted: Home Assistant's *Speaker* mute and the Sound page's switch. */
    var speakerMuted: Boolean
        get() = sp.getBoolean("speaker_muted", false)
        set(v) = sp.edit().putBoolean("speaker_muted", v).apply()

    /** A critical alert has the media volume at full. Written at once, so a start after a crash mid-alert puts the level back. */
    var volumeRaised: Boolean
        get() = sp.getBoolean("volume_raised", false)
        set(v) { sp.edit().putBoolean("volume_raised", v).commit() }

    /**
     * The speaker volume builds before the master volume kept for voice and announcements alone (0 to 1), or null
     * when there is none; reading it removes it ([dev.glassworks.audio.MasterVolume.start]).
     */
    fun takeOldSpeakerVolume(): Float? {
        if (!sp.contains("speaker_volume")) return null
        val v = sp.getFloat("speaker_volume", 1.0f)
        sp.edit().remove("speaker_volume").apply()
        return v
    }

    // ---- Improv Wi-Fi via BLE (see improv/ImprovController)

    /** Start Improv provisioning automatically when there's no network 30 s after boot. */
    var improvAuto: Boolean
        get() = sp.getBoolean("improv_auto", true)
        set(v) = sp.edit().putBoolean("improv_auto", v).apply()

    /**
     * Also accept an approach (the screen's presence going far to near: the S1's VI5300, or the camera's presence on a
     * device without a sensor) as the Improv authorization. Read [improvProximityAuthChosen]: unset, the frame decides.
     */
    var improvProximityAuth: Boolean
        get() = sp.getBoolean("improv_proximity_auth", false)
        set(v) = sp.edit().putBoolean("improv_proximity_auth", v).apply()

    /** [improvProximityAuth] once it was set (`--ez improv_proximity_auth`), else null (on without touch, ImprovSelfAuth). */
    val improvProximityAuthChosen: Boolean?
        get() = if (sp.contains("improv_proximity_auth")) improvProximityAuth else null

    /**
     * What the web engine starts with that the dashboard address can change: the secure-context hosts, and the
     * dashboard's origin (the legacy engine holds every page to it, #198). The engine reads both once per process,
     * so when this differs from before a change, the process must restart.
     */
    fun engineStart(): List<String> = engineStart(secureHosts(), url)

    /** Hosts that Gecko should treat as a secure context, so getUserMedia/AudioWorklet work over http. */
    fun secureHosts(): List<String> {
        val hosts = mutableListOf<String>()
        Uri.parse(url).host?.let { hosts += it }
        extraSecureHosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { hosts += it }
        return hosts.distinct()
    }

    /**
     * The settings part of a factory reset (#33, decided 2026-11-17): removes every setting in `kiosk.xml`, the core's
     * and the panes', except what [keptByReset] keeps. A secret that can't be opened now goes too. False when the file
     * couldn't be written, and then nothing changed.
     */
    fun resetSettings(): Boolean {
        val keys = sp.all.keys + SecretFiles.SECRETS.getValue("kiosk")
        val e = sp.edit()
        for (k in keys) if (!keptByReset(k)) e.remove(k)
        return e.commit()
    }

    companion object {
        private const val SETUP_UP_AT_RESTART = "setup_up_at_restart"
        const val AUTO = "auto"

        /**
         * What a factory reset keeps in `kiosk.xml` ([resetSettings]): the panel's identity in Home Assistant (its ESPHome
         * encryption key and MAC), so Home Assistant keeps the panel and its entities; the radio pane's settings, because
         * a reset mustn't take down the Zigbee or Thread network devices depend on (turning the radio off is its own
         * step, on the Radio page); and the once-per-install checks, so setup asks every step again as on a new install.
         */
        /** The Assist looks ([assistLook]). */
        const val ASSIST_ORB = "orb"
        const val ASSIST_GLOW = "glow"

        const val KEY_FOLLOW_HA_DARK = "overlay_follow_ha_dark"
        const val KEY_HA_DARK = "overlay_ha_dark"
        const val KEY_HA_COLORS = "overlay_ha_colors"

        /** The settings the overlays' theme reads ([overlayPick]): a change to one of them, and they take the theme again. */
        val THEME_KEYS = setOf("overlay_theme", "overlay_custom", KEY_FOLLOW_HA_DARK, KEY_HA_DARK, KEY_HA_COLORS)

        val RESET_KEEPS = setOf("api_key", "api_encryption", "device_mac", "setup_device_steps_migrated")
        private val RESET_KEEPS_PREFIXES = listOf("radio_", "setup_radio_")

        /** Whether a factory reset keeps the `kiosk.xml` setting [key] ([RESET_KEEPS]). */
        fun keptByReset(key: String): Boolean = key in RESET_KEEPS || RESET_KEEPS_PREFIXES.any { key.startsWith(it) }

        /** [engineStart] for [secureHosts] and a dashboard [url]: also what an engine [started][dev.glassworks.engine.Engine.started] with. */
        fun engineStart(secureHosts: List<String>, url: String?): List<String> =
            secureHosts + listOfNotNull(url?.let { OAuth.origin(it) ?: authority(it) })

        /** `scheme://authority` as Android's Uri reads it, for URLs java.net.URI refuses (a non-ASCII host). */
        private fun authority(url: String): String? =
            Uri.parse(url.trim()).let { u -> if (u.scheme.isNullOrEmpty() || u.authority.isNullOrEmpty()) null else "${u.scheme}://${u.authority}" }

        /** Decodes a base64 API key; null unless it is exactly 32 bytes. */
        fun decodeApiKey(b64: String): ByteArray? = try {
            Base64.decode(b64.trim(), Base64.DEFAULT).takeIf { it.size == 32 }
        } catch (e: IllegalArgumentException) {
            null
        }

        const val KEY_URL = "url"
        const val KEY_VITRO_ENABLED = "vitro_enabled"
        private const val KEY_VITRO_STRIKES = "vitro_crash_strikes"
        private const val KEY_VITRO_OFF_REASON = "vitro_off_reason"
        private const val KEY_VITRO_OFF_AT = "vitro_off_at"
        private const val KEY_VITRO_OFF_NOTICE = "vitro_off_notice"
        private const val KEY_VITRO_START = "vitro_start"
        private const val KEY_VITRO_STARTS = "vitro_starts"
        const val KEY_UPDATE_CHANNEL = "update_channel"
        const val KEY_HA_SEEN = "ha_seen"
        const val KEY_WIFI_SEEN = "wifi_seen"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}

/** The HA sign-in couldn't be written: the key store can't seal it, or can't let the old one go, now. */
class SignInNotStored(message: String) : IllegalStateException(message)
