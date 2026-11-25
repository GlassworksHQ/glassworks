package dev.glassworks.device

import android.app.Application
import android.os.Looper
import dev.glassworks.KioskPrefs
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.Msg
import dev.glassworks.esphome.ProtoWriter
import dev.glassworks.platform.AppBuild
import dev.glassworks.s1.KioskApp
import dev.glassworks.s1.frame.AqaraS1Plus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.Socket

/**
 * What a plaintext ESPHome client may do (#847). A panel with no key yet talks plaintext to anyone on the LAN,
 * so the controls that change what the page is, who it's signed in as, or what's on screen are Home Assistant's
 * only once it holds the key (`authOnly`). The everyday ones (the speaker, reload, restart, network setup, which
 * still needs the code on the screen) keep working for a Home Assistant too old to send a key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaintextControlsTest {
    private val identity = DeviceIdentity(
        name = "aqara-s1-plus-000001", friendlyName = "Aqara S1 Plus", macAddress = "02:00:00:00:00:01",
        esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Aqara",
        model = "Panel Hub S1 Plus (Glassworks)", voiceFeatures = PanelDevice.VOICE_FEATURES,
    )

    private fun <T> withEntities(block: (List<ApiEntity>) -> T): T {
        val app = RuntimeEnvironment.getApplication()
        val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
        val device = PanelDevice(app, runtime, runtime::devicePanes)
        try {
            device.initEntities(identity)
            return block(device.entityList())
        } finally {
            device.stop()
        }
    }

    @Test fun theControlsOnlyHomeAssistantWithTheKeyMayUse() = withEntities { entities ->
        val byId = entities.associateBy { it.objectId }
        assertEquals(
            "need the key",
            listOf(true, true, true, true),
            listOf("dashboard_url", "sign_out", "improv_ble", "open_settings").map { byId.getValue(it).authOnly },
        )
        assertEquals(
            "work without encryption",
            listOf(false, false, false, false),
            listOf("speaker", "reload_dashboard", "restart_app", "lan_setup").map { byId.getValue(it).authOnly },
        )
    }

    @Test fun aPlaintextDashboardUrlIsRefusedAndAnOpenButtonStillWorks() {
        val prefs = KioskPrefs(RuntimeEnvironment.getApplication())
        prefs.urlSetting = "http://192.0.2.10:8123/lovelace/0"
        withEntities { entities ->
            val url = entities.single { it.objectId == "dashboard_url" }
            val reload = entities.single { it.objectId == "reload_dashboard" }
            val server = ApiServer(0, { identity }, entities, psk = null) // plaintext: never authenticated
            server.start()
            val app = shadowOf(RuntimeEnvironment.getApplication())
            val started = mutableListOf<android.content.Intent>()
            try {
                Socket("127.0.0.1", server.localPort).use { s ->
                    val out = s.getOutputStream()
                    out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "lan stranger").uint32(2, 1).uint32(3, 10).toByteArray()))
                    out.write(frame(Msg.TEXT_COMMAND, ProtoWriter().fixed32(1, url.key, force = true).string(2, "http://192.0.2.66/").toByteArray()))
                    // Commands run in order: once the reload reaches the activity, the URL before it was handled too.
                    out.write(frame(Msg.BUTTON_COMMAND, ProtoWriter().fixed32(1, reload.key, force = true).toByteArray()))
                    out.flush()
                    val until = System.currentTimeMillis() + 5_000
                    while (started.none { it.hasExtra("reload") } && System.currentTimeMillis() < until) {
                        shadowOf(Looper.getMainLooper()).idle()
                        generateSequence { app.nextStartedActivity }.forEach { started += it }
                        Thread.sleep(20)
                    }
                }
            } finally {
                server.stop()
            }
            assertEquals("only the reload reached the app", listOf(true), started.map { it.getBooleanExtra("reload", false) })
            assertEquals(false, started.any { it.hasExtra("url") })
            assertEquals("http://192.0.2.10:8123/lovelace/0", prefs.urlSetting)
        }
    }

    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0); varint(o, payload.size); varint(o, type); o.write(payload)
        return o.toByteArray()
    }
}
