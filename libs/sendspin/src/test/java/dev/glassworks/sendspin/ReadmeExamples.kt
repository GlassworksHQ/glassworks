package dev.glassworks.sendspin

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The README's examples, compiled. Keep in sync with README.md. */
internal object ReadmeExamples {
    fun player(context: Context): SendspinPlayer {
        val player = SendspinPlayer(
            context,
            MemoryStore(), // your own SsStore keeps the pairing across restarts
            SendspinPlayer.Identity(
                name = "Kitchen panel", productName = "My Panel", manufacturer = "Example", softwareVersion = "My Panel 1.0.0",
            ),
            object : SendspinPlayer.Settings {
                override val server = SendspinPlayer.AUTO // mDNS, then Music Assistant on the Home Assistant host
                override val flac = true
                override val unpairedAccess = false
            },
            homeAssistantUrl = { "http://homeassistant.local:8123" },
        )
        player.start()
        return player
    }

    fun pairAndWatch(player: SendspinPlayer) {
        // Music Assistant pairs with this token (Home Assistant hands it over). A credential: never log it.
        val token = player.pairingToken()
        println(player.statusText()) // "ready, paired with Music Assistant", "playing from Music Assistant (FLAC): …"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadmeExamplesTest {
    @Test fun thePlayerStartsAndStops() {
        val player = ReadmeExamples.player(RuntimeEnvironment.getApplication())
        assertTrue(player.started)
        assertTrue(player.pairingToken().startsWith("SP:0"))
        ReadmeExamples.pairAndWatch(player)
        player.stop("shutdown")
        assertEquals("off", player.statusText())
    }
}
