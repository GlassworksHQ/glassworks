package dev.glassworks.s1

import android.content.Context
import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment

/**
 * A new [KioskApp] with a base context, as Android gives it before the app's first use: its runtime reads the settings
 * when it is made (which engine to run, [EngineChoiceTest]). Robolectric's own application is the base.
 */
internal fun attachedKioskApp(base: Context = RuntimeEnvironment.getApplication()): KioskApp = KioskApp().also { app ->
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(app, base)
}
