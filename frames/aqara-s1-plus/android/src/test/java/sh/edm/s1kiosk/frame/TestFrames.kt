package sh.edm.s1kiosk.frame

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
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
 *
 * A frame reads the input devices listed when its input is read, and a test's devices are one list (Robolectric keeps
 * them until it resets): build a device and read it before standing another up, as a test can't have the Mirror's keys
 * and a tablet's touchscreen at once (#502).
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
        touchscreen(context)
        return GenericAndroid(context, presence, light) { "" }
    }

    /**
     * A touchscreen of the device's own, with an id of its own. Robolectric keeps the input devices a test adds until
     * it resets, so [tablet] after [mirror] in one test would otherwise read only the Mirror's keys and no touchscreen
     * (#502), and it adds a device only under an id it doesn't have. The device API sets an id and sources only through
     * its hidden builder, so this sets them as Robolectric's own shadow sets a name.
     */
    private fun touchscreen(context: Context) {
        val manager = context.getSystemService(InputManager::class.java)
        val device = ShadowInputDevice.makeInputDeviceNamed("touchscreen")
        val field = { name: String -> InputDevice::class.java.getDeclaredField(name).apply { isAccessible = true } }
        field("mId").setInt(device, (manager.inputDeviceIds.maxOrNull() ?: -1) + 1)
        field("mSources").setInt(device, InputDevice.SOURCE_TOUCHSCREEN)
        shadowOf(manager).addInputDevice(device)
    }
}
