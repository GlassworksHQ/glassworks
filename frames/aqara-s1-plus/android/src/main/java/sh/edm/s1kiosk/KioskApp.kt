package sh.edm.s1kiosk

import android.app.Application
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.frame.AqaraS1Plus
import dev.glassworks.update.ReleaseLine
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.glassworks

class KioskApp : Application(), GlassworksApp {

    /** This APK's build, from its BuildConfig. Code that can leave this module gets it from here, never from BuildConfig (BuildConfigConfinementTest). */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD)

    /** Made on first use, when the device is known: the frame is the S1's on its hardware, else the generic one. */
    override val glassworks: GlassworksRuntime by lazy {
        val frame = AqaraS1Plus.orElse(Platform.hardware) { GenericAndroid(this) }
        // Once per process, so a device that got the wrong frame shows it in logcat.
        Log.i("S1Kiosk", "Frame: ${frame.board} (${Platform.hardware})")
        runtime(frame, build)
    }

    companion object {
        /**
         * The S1 app's runtime on [frame]: GeckoView with the kiosk's extension, and the S1 app's panes. Tests
         * pass a fake [paneFrame]. Internal: the app makes one per process (one GeckoView runtime, one hub).
         */
        internal fun runtime(frame: Frame, build: AppBuild, paneFrame: Frame = frame): GlassworksRuntime =
            GlassworksRuntime(frame, build, engine(build), S1Panes(), paneFrame, ReleaseLine.S1_KIOSK)

        /**
         * What the engine starts with, from the settings: read once, at the first open. The dashboard is the
         * configured URL (none while Home Assistant isn't found): the legacy engine holds pages to its origin (#198).
         */
        internal fun engineSettings(context: android.content.Context, build: AppBuild): EngineSettings =
            KioskPrefs(context).run { EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug), dashboard = url.ifEmpty { null }) }

        /**
         * GeckoView, with the kiosk's helper extension (assets/extension). Its settings are read once, when
         * MainActivity first opens a page.
         */
        private fun engine(build: AppBuild): Engine = GeckoEngine(
            settings = { context -> engineSettings(context, build) },
            extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
            logTag = "S1Kiosk",
            generatedBy = "S1 Kiosk",
        )
    }
}
