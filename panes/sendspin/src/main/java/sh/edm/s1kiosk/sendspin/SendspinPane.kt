package sh.edm.s1kiosk.sendspin

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.EntityCategory
import dev.glassworks.esphome.SendspinApi
import dev.glassworks.esphome.SendspinTokenSource
import dev.glassworks.esphome.TextSensorEntity
import dev.glassworks.sendspin.SendspinPlayer

/**
 * The Music Assistant player (Sendspin, docs/sendspin.md) as a pane: its two entities, its lifecycle and its settings.
 * [haBase] is the Home Assistant the panel talks to, for the `auto` server address.
 */
class SendspinPane(ctx: PaneContext, haBase: () -> String?) : Pane {
    override val id = "sendspin"

    private val prefs = SendspinPrefs(ctx.prefs(id))

    /** The engine (libs/sendspin). Music Assistant shows the panel under its Home Assistant name. Public for tests. */
    val player = SendspinPlayer(
        ctx.context, PrefsSsStore(ctx.context),
        SendspinPlayer.Identity(
            name = ctx.device.friendlyName, productName = ctx.device.model, manufacturer = ctx.device.manufacturer,
            softwareVersion = "S1 Kiosk ${ctx.build.versionName}", macAddress = ctx.device.macAddress,
        ),
        prefs, haBase, ctx.publishStates,
    )

    /** What the settings page ([SendspinDefinition]) shows and sets: the player's, or a still one in the mock-ups. */
    var view: MusicView = object : MusicView {
        override val status get() = player.status
        override val serverUrl get() = player.serverUrl
        override val nowPlayingText get() = player.nowPlayingText
        override val volume get() = player.volume
        override fun setVolume(volume: Int) = player.setVolume(volume)
    }

    // Its on/off (`sendspin`) is the core's, from SendspinDefinition.switch.
    override fun entities(): List<ApiEntity> = listOf(
        TextSensorEntity(
            "sendspin_status", "Music Assistant player status", "mdi:speaker-wireless", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { player.statusText() },
        ),
    )

    override fun bind(server: ApiServer) {
        server.sendspin = object : SendspinTokenSource {
            override fun clientIdIfStarted() = player.takeIf { it.started }?.clientId ?: ""
            override fun pairingToken(): Pair<Int, String?> {
                val status = pairingStatus(prefs.enabled, player.started, player.pairingReady)
                return status to if (status == SendspinApi.STATUS_OK) player.pairingToken() else null
            }
        }
    }

    /** The core starts it while the switch is on, and stops it when it is turned off. */
    override fun start() = player.start()

    // client/goodbye's reason: the person turned the player off, or the panel is stopping.
    override fun stop() = player.stop(if (prefs.enabled) "shutdown" else "user_request")

    override fun onEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.SettingChanged -> when (event.key) {
                // A new client/hello must carry the changed setting: reconnect.
                SendspinPrefs.KEY_UNPAIRED, SendspinPrefs.KEY_SERVER -> player.restartSession()
                SendspinPrefs.KEY_FLAC -> player.onFlacSettingChanged()
            }
            // Sendspin keeps its timeline (the group plays on); the panel just goes quiet locally.
            is PanelEvent.SpeakerClaimed -> player.setDucked(event.claimed)
        }
    }
}

/** The player as the settings page sees it ([SendspinPlayer] has each of these). */
interface MusicView {
    val status: SendspinPlayer.Status
    val serverUrl: String?
    val nowPlayingText: String?
    val volume: Int

    /** Writes to Music Assistant's socket: not on the main thread. */
    fun setVolume(volume: Int)
}

// `started` means "started since boot" and is never cleared, so the setting has to be checked first.
internal fun pairingStatus(enabled: Boolean, started: Boolean, secretsReadable: Boolean = true): Int = when {
    !enabled -> SendspinApi.STATUS_DISABLED
    // The key store is down: a token now would pair Music Assistant with a stand-in identity.
    !started || !secretsReadable -> SendspinApi.STATUS_NOT_READY
    else -> SendspinApi.STATUS_OK
}
