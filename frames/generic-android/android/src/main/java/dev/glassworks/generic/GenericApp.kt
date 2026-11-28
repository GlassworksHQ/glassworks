/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Context
import android.util.Log
import dev.glassworks.auth.HaAuth
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.vitro.VitroEngine
import dev.glassworks.engine.vitro.webrtc.VitroWebRtc
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.generic_android.BuildConfig
import dev.glassworks.generic_android.R
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import dev.glassworks.screen.TestHold
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.VitroSentinel
import dev.glassworks.update.ReleaseLine

/** Glassworks for generic Android (#23): the core on any Android 8+ device, as its own app. */
class GenericApp : Application(), GlassworksApp {

    /** This APK's build, from its BuildConfig, under its own name and with the mark in its notification. */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD, product = PRODUCT,
        notificationIcon = R.drawable.ic_notification)

    override val glassworks: GlassworksRuntime by lazy {
        val frame = frame(this)
        Log.i(TAG, "Frame: ${frame.board} (${frame.hardware.manufacturer} ${frame.hardware.model})")
        // Vitro is read once, here: the engine is chosen for the whole process, so a change waits for a restart. While
        // it's on, VitroSentinel turns it off for the next start if it keeps crashing the app just after it starts.
        runtime(frame, build, vitro = VitroSentinel.vitroAtStart(this))
    }

    companion object {
        /** The app's name, as it announces itself (AppBuild.product) and as its launcher label says. */
        const val PRODUCT = "Glassworks"
        const val TAG = "Glassworks"

        /**
         * The legacy build's id (#198): Android 6-7 on GeckoView 144, for older tablets and the lululemon Mirror.
         * Its own app next to this one, so neither ever installs over or updates the other.
         */
        const val LEGACY_APPLICATION_ID = "dev.glassworks.generic_android.legacy"

        /** The device as this app sees it: [GenericAndroid], with Android's standard proximity and light sensors. */
        fun frame(app: Application): GenericAndroid =
            GenericAndroid(app, presence = ProximityPresence("Glassworks.Screen"), light = AmbientLight("Glassworks.Screen"))

        /**
         * The runtime on [frame]: GeckoView with the kiosk's extension, and this app's panes ([GenericParts]). [vitro]:
         * Settings → Labs had Vitro on when the process started ([engine]).
         */
        internal fun runtime(frame: Frame, build: AppBuild, vitro: Boolean = false): GlassworksRuntime =
            GlassworksRuntime(frame, build, engine(build, vitro = vitro), GenericParts(), releases = releaseLine(BuildConfig.APPLICATION_ID))

        /**
         * The releases this build updates from: the legacy build's own line (#198), its debug build's
         * (`applicationIdSuffix ".debug"`) included, else the generic app's. A debug legacy build on the generic
         * line would be offered releases that need Android 8.
         */
        internal fun releaseLine(applicationId: String): ReleaseLine =
            if (legacy(applicationId)) ReleaseLine.GENERIC_ANDROID_LEGACY else ReleaseLine.GENERIC_ANDROID

        private fun legacy(applicationId: String) = applicationId.removeSuffix(".debug") == LEGACY_APPLICATION_ID

        /**
         * What the engine starts with, from the settings: read once, at the first open. The dashboard is the
         * origin the legacy engine stays on (#198), and a test's own dashboard while its hold lasts
         * (ScreenPolicy.testDashboard, Clarity's fake Home Assistant); the current engine doesn't read it. As the S1
         * app's (KioskApp), but the legacy build never turns on remote debugging (#29), even with the setting left on
         * from before.
         */
        internal fun engineSettings(context: Context, build: AppBuild, applicationId: String = BuildConfig.APPLICATION_ID): EngineSettings =
            KioskPrefs(context).run {
                val test = if (legacy(applicationId)) TestHold.dashboard(context) else null
                EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug) && !legacy(applicationId), dashboard = test ?: url.ifEmpty { null })
            }

        /**
         * What GeckoView starts with. As [engineSettings], except on the legacy build under Vitro ([vitro] gives the
         * engine, null without one): its engine holds pages to the dashboard's origin (#198), and Vitro's islands come
         * from the loopback island server, so that origin is the one more it is told ([EngineSettings.localOrigin]).
         * Home Assistant's own pages (sign-in, settings, history) still load from Home Assistant.
         */
        internal fun geckoSettings(
            context: Context, build: AppBuild, applicationId: String, vitro: () -> VitroEngine?,
        ): EngineSettings {
            val settings = engineSettings(context, build, applicationId)
            val v = if (legacy(applicationId)) vitro() else null
            return if (v != null) settings.copy(localOrigin = v.islandOrigin(context)) else settings
        }

        /**
         * GeckoView with the kiosk's helper extension: the same engine, extension and page API as the S1 app's. With
         * [vitro] on (Settings → Labs, at process start), Vitro wraps it and draws Lovelace dashboards natively; off,
         * no [VitroEngine] is made and the app runs on GeckoView alone.
         */
        internal fun engine(build: AppBuild, applicationId: String = BuildConfig.APPLICATION_ID, vitro: Boolean = false): Engine {
            var wrapper: VitroEngine? = null
            val gecko = GeckoEngine(
                settings = { context -> geckoSettings(context, build, applicationId) { wrapper } },
                extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "glassworks"),
                logTag = TAG,
                generatedBy = PRODUCT,
            )
            if (!vitro) return gecko
            // Its cameras play over WebRTC first, as HA's frontend does, then HLS and MJPEG.
            VitroWebRtc.register()
            return VitroEngine(
                fallback = gecko,
                settings = { context -> engineSettings(context, build, applicationId) },
                tokenProvider = { force, done ->
                    HaAuth.accessToken(force) { a -> done((a as? HaAuth.Access.Ok)?.accessToken) }
                },
            ).also { wrapper = it }
        }
    }
}
