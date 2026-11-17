/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Context
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.generic_android.BuildConfig
import dev.glassworks.generic_android.R
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import sh.edm.s1kiosk.GlassworksApp
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.update.ReleaseLine

/** Glassworks for generic Android (#23): the core on any Android 8+ device, as its own app. */
class GenericApp : Application(), GlassworksApp {

    /** This APK's build, from its BuildConfig, under its own name and with the mark in its notification. */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD, product = PRODUCT,
        notificationIcon = R.drawable.ic_notification)

    override val glassworks: GlassworksRuntime by lazy {
        val frame = frame(this)
        Log.i(TAG, "Frame: ${frame.board} (${frame.hardware.manufacturer} ${frame.hardware.model})")
        runtime(frame, build)
    }

    companion object {
        /** The app's name, as it announces itself (AppBuild.product) and as its launcher label says. */
        const val PRODUCT = "Glassworks"
        const val TAG = "S1Kiosk"

        /**
         * The legacy build's id (#198): Android 6-7 on GeckoView 144, for older tablets and the lululemon Mirror.
         * Its own app next to this one, so neither ever installs over or updates the other.
         */
        const val LEGACY_APPLICATION_ID = "dev.glassworks.generic_android.legacy"

        /** The device as this app sees it: [GenericAndroid], with Android's standard proximity and light sensors. */
        fun frame(app: Application): GenericAndroid =
            GenericAndroid(app, presence = ProximityPresence("S1Kiosk.Screen"), light = AmbientLight("S1Kiosk.Screen"))

        /** The runtime on [frame]: GeckoView with the kiosk's extension, and this app's panes ([GenericParts]). */
        internal fun runtime(frame: Frame, build: AppBuild): GlassworksRuntime =
            GlassworksRuntime(frame, build, engine(build), GenericParts(), releases = releaseLine(BuildConfig.APPLICATION_ID))

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
         * origin the legacy engine stays on (#198); the current engine doesn't read it. As S1 Kiosk's (KioskApp),
         * but the legacy build never turns on remote debugging (#29), even with the setting left on from before.
         */
        internal fun engineSettings(context: Context, build: AppBuild, applicationId: String = BuildConfig.APPLICATION_ID): EngineSettings =
            KioskPrefs(context).run {
                EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug) && !legacy(applicationId), dashboard = url.ifEmpty { null })
            }

        /** GeckoView with the kiosk's helper extension: the same engine, extension and page API as S1 Kiosk's. */
        internal fun engine(build: AppBuild): Engine = GeckoEngine(
            settings = { context -> engineSettings(context, build) },
            extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
            logTag = TAG,
            generatedBy = PRODUCT,
        )
    }
}
