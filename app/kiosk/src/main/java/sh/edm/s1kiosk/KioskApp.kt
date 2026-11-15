package sh.edm.s1kiosk

import android.app.Application
import android.util.Log
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.frame.AqaraS1Plus

class KioskApp : Application() {

    /** This APK's build, from its BuildConfig. Code that can leave this module gets it from here, never from BuildConfig (BuildConfigConfinementTest). */
    val build = AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.TEST_BUILD, BuildConfig.DEBUG)

    /** Made on first use, when the device is known: the frame is the S1's on its hardware, else the generic one. */
    val glassworks by lazy {
        val frame = AqaraS1Plus.orElse(Platform.hardware) { GenericAndroid(this) }
        // Once per process, so a device that got the wrong frame shows it in logcat.
        Log.i("S1Kiosk", "Frame: ${frame.board} (${Platform.hardware})")
        GlassworksRuntime(frame, build)
    }
}
