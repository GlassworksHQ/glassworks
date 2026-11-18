package sh.edm.s1kiosk

import android.app.Application
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.frame.AqaraS1Plus
import dev.glassworks.setup.LoadingOverlay

/**
 * What the direct-boot splash draws. The S1 continues its boot animation's last frame, with the
 * "On Aqara S1 Plus" line under the stack. Any other device has no such frame, so it shows the live
 * stack, as MainActivity's first frame does there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootSplashLookTest {
    private fun firstLine(): String {
        val activity = Robolectric.buildActivity(BootSplashActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as LoadingOverlay).apply { previewBoot(0) }.lineText
    }

    @Test fun theS1ContinuesItsBootFrame() {
        ShadowBuild.setHardware(AqaraS1Plus.HARDWARE)
        assertEquals("ON AQARA S1 PLUS", firstLine())
    }

    @Test fun anotherDeviceNeverNamesTheS1() {
        ShadowBuild.setHardware("tangorpro")
        assertEquals("", firstLine())
    }
}
