package sh.edm.s1kiosk.frame

import android.content.Context
import android.hardware.input.InputManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowInputDevice

/**
 * The devices tests and mockups stand for, each built as the app builds it there, so a screen shows that device's
 * name, input and orientation (#497). Never stand in for a device by overriding another frame's input.
 */
object TestFrames {
    /** The Mirror's system properties: lululemon's boot image on the community `system` (DeviceFrame.LULULEMON_MIRROR). */
    val MIRROR_PROPS = mapOf(
        "ro.board.platform" to "msm8916",
        "ro.bootimage.build.fingerprint" to "mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys",
    )

    fun s1(): Frame = AqaraS1Plus()

    /** The lululemon Mirror on the generic app: recognised by [MIRROR_PROPS], with its keys and no touchscreen. */
    fun mirror(context: Context, presence: PresenceSource? = null, light: LightSource? = null): Frame {
        ShadowBuild.setManufacturer("Android")
        ShadowBuild.setModel("MSM8916 for arm64")
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(ShadowInputDevice.makeInputDeviceNamed("gpio-keys"))
        return GenericAndroid(context, presence, light) { MIRROR_PROPS[it].orEmpty() }
    }

    /** A Pixel Tablet on the generic app: no frame of its own, so it goes by its maker and model. */
    fun tablet(context: Context, presence: PresenceSource? = null, light: LightSource? = null): Frame {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        return GenericAndroid(context, presence, light) { "" }
    }
}
