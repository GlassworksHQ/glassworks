package dev.glassworks

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import dev.glassworks.screen.ScreenSettings
import dev.glassworks.esphome.NoiseKeyStore
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
    val dashboardRoute: LocalRoute get() = LocalName.route(urlSetting, haUrl, platform.resolvesLocalNames)

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

    /** The panel has been online over Wi-Fi at least once (the boot screen's "Connecting to Wi-Fi…" hint). */
    var wifiSeen: Boolean
        get() = sp.getBoolean(KEY_WIFI_SEEN, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_SEEN, v).apply()

    /** "Skip" on the setup screen's Add-to-Home-Assistant step. */
    var haSetupSkipped: Boolean
        get() = sp.getBoolean("setup_ha_skipped", false)
        set(v) = sp.edit().putBoolean("setup_ha_skipped", v).apply()

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
    val noiseKeyStore: NoiseKeyStore = object : ReadableNoiseKeyStore {
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

    /** Stable MAC reported to Home Assistant (its config entry is keyed by it). */
    var deviceMac: String
        get() = sp.getString("device_mac", "") ?: ""
        set(v) = sp.edit().putString("device_mac", v).apply()

    var speakerVolume: Float
        get() = sp.getFloat("speaker_volume", 1.0f)
        set(v) = sp.edit().putFloat("speaker_volume", v).apply()

    var speakerMuted: Boolean
        get() = sp.getBoolean("speaker_muted", false)
        set(v) = sp.edit().putBoolean("speaker_muted", v).apply()

    // ---- Improv Wi-Fi via BLE (see improv/ImprovController)

    /** Start Improv provisioning automatically when there's no network 30 s after boot. */
    var improvAuto: Boolean
        get() = sp.getBoolean("improv_auto", true)
        set(v) = sp.edit().putBoolean("improv_auto", v).apply()

    /** Also accept a proximity approach (VI5300 far -> near) as the Improv authorization. */
    var improvProximityAuth: Boolean
        get() = sp.getBoolean("improv_proximity_auth", false)
        set(v) = sp.edit().putBoolean("improv_proximity_auth", v).apply()

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

    companion object {
        const val AUTO = "auto"

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
        const val KEY_HA_SEEN = "ha_seen"
        const val KEY_WIFI_SEEN = "wifi_seen"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}

/** The HA sign-in couldn't be written: the key store can't seal it, or can't let the old one go, now. */
class SignInNotStored(message: String) : IllegalStateException(message)
