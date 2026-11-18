package dev.glassworks.sendspin

import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Socket

/** [WsClient.connect] fails with an [IOException] only, whatever goes wrong before the upgrade. */
class WsClientConnectTest {
    @Test fun aPortOutOfRangeIsAnIoException() {
        try { WsClient.connect("ws://127.0.0.1:65536/"); fail() } catch (_: IOException) {}
    }

    @Test fun anOpenerThatThrowsSomethingElseIsAnIoException() {
        val opener: (String, Int, Int) -> Socket = { _, _, _ -> throw IllegalStateException("no network") }
        try { WsClient.connect("ws://127.0.0.1:1/", 1_000, 1_000, opener); fail() } catch (_: IOException) {}
    }
}
