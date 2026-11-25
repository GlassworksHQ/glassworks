package dev.glassworks.s1

import android.app.Application
import android.util.Log
import dev.glassworks.auth.HaAuth
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.vitro.VitroEngine
import dev.glassworks.engine.vitro.webrtc.VitroWebRtc
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import dev.glassworks.platform.Platform
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.update.ReleaseLine
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.VitroSentinel
import dev.glassworks.glassworks

class KioskApp : Application(), GlassworksApp {

    /** This APK's build, from its BuildConfig. Code that can leave this module gets it from here, never from BuildConfig (BuildConfigConfinementTest). */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD,
        notificationIcon = R.drawable.ic_notification)

    /** Made on first use, when the device is known: the frame is the S1's on its hardware, else the generic one. */
    override val glassworks: GlassworksRuntime by lazy {
        val frame = AqaraS1Plus.orElse(Platform.hardware) { GenericAndroid(this) }
        // Once per process, so a device that got the wrong frame shows it in logcat.
        Log.i("Glassworks", "Frame: ${frame.board} (${Platform.hardware})")
        // Vitro is read once, here: the engine is chosen for the whole process, so a change waits for a restart. While
        // it's on, VitroSentinel turns it off for the next start if it keeps crashing the app just after it starts.
        runtime(frame, build, vitro = VitroSentinel.vitroAtStart(this))
    }

    companion object {
        /**
         * The S1 app's runtime on [frame]: GeckoView with the kiosk's extension, and the S1 app's panes. Tests
         * pass a fake [paneFrame]. Internal: the app makes one per process (one GeckoView runtime, one hub).
         * [vitro]: Settings → Labs had Vitro on when the process started ([engine]).
         */
        internal fun runtime(frame: Frame, build: AppBuild, paneFrame: Frame = frame, vitro: Boolean = false): GlassworksRuntime =
            GlassworksRuntime(frame, build, engine(build, vitro), S1Panes(), paneFrame, ReleaseLine.AQARA_S1_PLUS)

        /**
         * What the engine starts with, from the settings: read once, at the first open. The dashboard is the
         * configured URL (none while Home Assistant isn't found): the legacy engine holds pages to its origin (#198).
         */
        internal fun engineSettings(context: android.content.Context, build: AppBuild): EngineSettings =
            KioskPrefs(context).run { EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug), dashboard = url.ifEmpty { null }) }

        /**
         * GeckoView, with the kiosk's helper extension (assets/extension). Its settings are read once, when
         * MainActivity first opens a page. With [vitro] on (Settings → Labs, at process start), Vitro wraps it and
         * draws Lovelace dashboards natively; off, no [VitroEngine] is made and the app runs on GeckoView alone.
         */
        internal fun engine(build: AppBuild, vitro: Boolean = false): Engine {
            val gecko = GeckoEngine(
                settings = { context -> engineSettings(context, build) },
                extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "glassworks"),
                logTag = "Glassworks",
                generatedBy = build.product,
            )
            if (!vitro) return gecko
            // Its cameras play over WebRTC first, as HA's frontend does, then HLS and MJPEG.
            VitroWebRtc.register()
            return VitroEngine(
                fallback = gecko,
                settings = { context -> engineSettings(context, build) },
                tokenProvider = { force, done ->
                    HaAuth.accessToken(force) { a -> done((a as? HaAuth.Access.Ok)?.accessToken) }
                },
            )
        }
    }
}
