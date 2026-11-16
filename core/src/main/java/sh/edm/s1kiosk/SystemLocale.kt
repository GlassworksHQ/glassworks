package sh.edm.s1kiosk

import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import dev.glassworks.platform.Platform
import java.util.Locale

/**
 * Sets the panel's system language, as the Settings app does: `IActivityManager.updatePersistentConfiguration`
 * with `userSetLocale`, which also persists it (persist.sys.locale) across reboots. Needs
 * CHANGE_CONFIGURATION, a development permission the installer grants over adb
 * (`pm grant sh.edm.s1kiosk android.permission.CHANGE_CONFIGURATION`). The panel has no Android Settings
 * app and no shell command for this on Android 10, hence the app does it.
 *
 * Reached by reflection (hidden API). If the platform refuses, it logs why and changes nothing.
 */
object SystemLocale {
    private const val TAG = "S1Locale"

    /** Parses a BCP 47 tag such as `en-US`; null if it isn't a plain language[-region] tag. */
    fun parse(tag: String): Locale? {
        if (!Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(tag)) return null
        return Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() }
    }

    /** The system's activity manager service (hidden API): `getService()` from Android 8, `ActivityManagerNative` below. */
    private fun activityManagerService(): Any =
        if (Platform.hasActivityManagerGetService) Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        else Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null)!!

    /** [config]'s first language: its LocaleList from Android 7, its single `locale` below. */
    @Suppress("DEPRECATION")
    internal fun current(config: Configuration): Locale? =
        if (Platform.hasLocaleList) config.locales.takeIf { it.size() > 0 }?.get(0) else config.locale

    /** Sets [config]'s language to [locale] alone, and its layout direction with it (an RTL language turns it). */
    internal fun setLocale(config: Configuration, locale: Locale) {
        if (Platform.hasLocaleList) config.setLocales(LocaleList(locale)) else config.setLocale(locale)
    }

    /** Returns true if the language was changed (false if it already was, or on failure). */
    fun set(tag: String): Boolean {
        val locale = parse(tag) ?: run { Log.w(TAG, "not a language tag: $tag"); return false }
        return try {
            val am = activityManagerService()
            val config = am.javaClass.getMethod("getConfiguration").invoke(am) as Configuration
            if (current(config) == locale) return false
            setLocale(config, locale)
            Configuration::class.java.getField("userSetLocale").setBoolean(config, true)
            am.javaClass.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(am, config)
            Log.i(TAG, "system language set to ${locale.toLanguageTag()}")
            true
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "could not set the system language to $tag: $cause")
            false
        }
    }
}
