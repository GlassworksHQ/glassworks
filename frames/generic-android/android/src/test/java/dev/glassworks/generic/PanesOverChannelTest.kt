package dev.glassworks.generic

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.platform.AppBuild
import dev.glassworks.setup.ChannelState
import dev.glassworks.setup.SetupChannel
import dev.glassworks.setup.SetupChannelServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * Kiln's Panes step on generic Android (#621): the app reports its Panes step's cards over the setup channel, as the
 * channel is made on the device (SetupChannelServer.channels), and `panes.set` turns the panes on and off as the panel's
 * own Continue does. A pane the device lacks the hardware for is hidden there as everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanesOverChannelTest {
    private val app = RuntimeEnvironment.getApplication()
    private val build = AppBuild("1.0.0", 10000, debug = false, product = GenericApp.PRODUCT)

    private fun features(vararg on: String) {
        val pm = shadowOf(app.packageManager)
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            pm.setSystemFeature(f, f in on)
        }
    }

    @Before fun aTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
    }

    private object Core : SetupChannel.Backend {
        override fun state() = ChannelState(ip = "192.0.2.20", ha = ChannelState.Ha(null, "", false, null))
        override fun setHa(url: String) = SetupChannel.Change()
    }

    private fun channel(): Pair<dev.glassworks.GlassworksRuntime, SetupChannel> {
        val runtime = GenericApp.runtime(GenericApp.frame(app), build)
        return runtime to SetupChannelServer.channels(app, runtime, Core)({ true })
    }

    private fun ids(c: SetupChannel): List<String> {
        val cards = JSONObject(c.handle("""{"id":1,"op":"state"}""").first).getJSONObject("state").getJSONArray("panes")
        return (0 until cards.length()).map { cards.getJSONObject(it).getString("id") }
    }

    @Test fun theCardsAreTheTabletsPanesAndPanesSetSwitchesThem() {
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)
        val (runtime, c) = channel()
        val ids = ids(c)
        assertEquals(runtime.panesStep(app).panes.map { it.id }, ids)
        assertTrue(ids.toString(), ids.containsAll(listOf("assist", "sendspin", "notifications", "camera", "bluetooth-proxy")))
        assertFalse("no radio on a tablet", "radio" in ids)

        val r = JSONObject(c.handle("""{"id":2,"op":"panes.set","on":"notifications"}""").first)
        assertTrue(r.toString(), r.getBoolean("ok"))
        val defs = runtime.paneDefinitions(app)
        assertTrue(defs.single { it.id == "notifications" }.switch!!.on)
        assertFalse(defs.single { it.id == "assist" }.switch!!.on)
        assertTrue("the panel's own setup moves past Panes", runtime.panesStep(app).chosen)
    }

    @Test fun aPaneWithoutItsHardwareIsHidden() {
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE)
        val (_, c) = channel()
        assertFalse("no camera card without a camera", "camera" in ids(c))
        val r = JSONObject(c.handle("""{"id":2,"op":"panes.set","on":"camera"}""").first)
        assertEquals("This panel has no pane 'camera'.", r.getString("error"))
    }
}
