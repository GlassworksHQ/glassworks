package dev.glassworks

import android.app.Application
import android.content.res.Configuration
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The language on Android 7 and up (#380's modern branch): through LocaleList. Api23FallbacksTest pins the
 * Android 6 branch; this one fails if the modern one stops setting or reading the language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemLocaleModernTest {
    @Test fun `the language is set and read through the LocaleList`() {
        val config = Configuration()
        SystemLocale.setLocale(config, Locale.forLanguageTag("es-ES"))
        assertEquals(1, config.locales.size())
        assertEquals(Locale.forLanguageTag("es-ES"), config.locales[0])
        assertEquals(Locale.forLanguageTag("es-ES"), SystemLocale.current(config))
        SystemLocale.setLocale(config, Locale.forLanguageTag("ar"))
        assertEquals(Locale.forLanguageTag("ar"), SystemLocale.current(config))
        assertEquals(View.LAYOUT_DIRECTION_RTL, config.layoutDirection)
    }
}

/**
 * Setting the system language on Android 6 reaches the activity manager through the hidden
 * `ActivityManagerNative.getDefault()`. Robolectric doesn't give a usable one, so this only proves the failure is
 * safe: false, nothing thrown. Whether the call works on a real Android 6 is a device check (#198 Phase D).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class SystemLocaleApi23SetTest {
    @Test fun `setting the language fails safe when the activity manager isn't reachable`() {
        assertFalse(SystemLocale.set("es-ES"))
    }
}
