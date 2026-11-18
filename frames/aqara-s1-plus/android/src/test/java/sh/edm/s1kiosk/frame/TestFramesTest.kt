package sh.edm.s1kiosk.frame

import android.app.Application
import dev.glassworks.frame.Input
import dev.glassworks.frame.friendlyName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a test or a mockup stands in for (#497, #502): each frame goes by its own name and input, and a test may build
 * them in any order. Robolectric keeps the input devices a test adds, so the Mirror's keys are still listed when a
 * tablet is built after it: the tablet needs a touchscreen of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class TestFramesTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun eachFrameGoesByItsOwnName() {
        assertEquals("Aqara S1 Plus", TestFrames.s1().friendlyName())
        assertEquals("lululemon Studio Mirror", TestFrames.mirror(app).friendlyName())
        assertEquals("Pixel Tablet", TestFrames.tablet(app).friendlyName())
    }

    @Test fun theTabletTakesTouchThoughTheMirrorWasBuiltFirst() {
        assertEquals("the Mirror's buttons, none a touchscreen", Input.NONE, TestFrames.mirror(app).input)
        assertEquals("a touchscreen of its own", Input.TOUCH, TestFrames.tablet(app).input)
    }
}
