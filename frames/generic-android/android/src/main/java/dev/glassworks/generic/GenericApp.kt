/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.generic_android.BuildConfig
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import sh.edm.s1kiosk.GlassworksApp
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs

/** Glassworks for generic Android (#23): the core on any Android 8+ device, as its own app. */
class GenericApp : Application(), GlassworksApp {

    /** This APK's build, from its BuildConfig, under its own name. */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD, product = PRODUCT)

    override val glassworks: GlassworksRuntime by lazy {
        val frame = frame(this)
        Log.i(TAG, "Frame: ${frame.board} (${frame.hardware.manufacturer} ${frame.hardware.model})")
        runtime(frame, build)
    }

    companion object {
        /** The app's name, as it announces itself (AppBuild.product) and as its launcher label says. */
        const val PRODUCT = "Glassworks"
        const val TAG = "S1Kiosk"

        /** The device as this app sees it: [GenericAndroid], with Android's standard proximity and light sensors. */
        fun frame(app: Application): GenericAndroid =
            GenericAndroid(app, presence = ProximityPresence("S1Kiosk.Screen"), light = AmbientLight("S1Kiosk.Screen"))

        /** The runtime on [frame]: GeckoView with the kiosk's extension, and this app's panes ([GenericParts]). */
        internal fun runtime(frame: Frame, build: AppBuild): GlassworksRuntime = GlassworksRuntime(frame, build, engine(build), GenericParts())

        /** GeckoView with the kiosk's helper extension: the same engine, extension and page API as S1 Kiosk's. */
        private fun engine(build: AppBuild): Engine = GeckoEngine(
            settings = { context -> KioskPrefs(context).run { EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug)) } },
            extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
            logTag = TAG,
            generatedBy = PRODUCT,
        )
    }
}
