/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import org.json.JSONObject

/**
 * Setup's Silent updates step (#422's approved design): shown whenever updates ask for a tap and there is a way to
 * change that (the maintainer, 2026-11-18). With "Install unknown apps" off (Android 12+), it opens Android's page for
 * it. On Android 6 to 11, or where the app targets too low an API, it says a device owner skips the tap; on an S1
 * whose boot image has no update service, that Kiln adds one. Those two point at Kiln. Never required: "Not now" and
 * "Continue" both answer it, and [answered] keeps that across restarts so setup doesn't come back for it.
 *
 * [answer] is asked on every 2 s tick and several times per draw: give it a [SilentUpdates.Fresh]. [answered] and
 * [markAnswered] are the app's storage.
 */
class SilentUpdatesStep(
    private val answer: () -> SilentUpdates?,
    private val answered: () -> Boolean,
    private val markAnswered: () -> Unit,
) : SetupStep {
    override val id = ID
    override val title = "Silent updates"
    override val reason = "updates ask for a tap, and the person can change that"

    /**
     * The step's card was drawn (not in a preview): it stays until answered, so the person sees "On" after allowing
     * it. Set in bind, not when the screen is made: the setup screen makes every step's screen at its first tick.
     */
    @Volatile private var shown = false

    override fun done(): Boolean = answered() || (!shown && offer(answer()) == null)

    /** For the setup channel: whether updates are silent, what the step offers (`allow`, `device_owner`) and the reason. */
    override fun channelState(): JSONObject? = answer()?.let { s ->
        JSONObject().put("silent", s.silent).put("reason", s.reason).put("offer", offer(s)?.wire ?: JSONObject.NULL).put("answered", answered())
    }

    /** For the setup channel and the LAN page: answers the step, as "Not now" does. */
    override val ops = mapOf("answer" to SetupOp { markAnswered(); null })

    override fun screen(context: Context, host: SetupHost): SetupScreen = Screen(context, host)

    private inner class Screen(private val context: Context, private val host: SetupHost) : SetupScreen {
        private var lastSilent = answer()?.silent
        /** Android's settings page couldn't be opened. */
        private var noSettings = false

        override fun bind(card: SetupCard, entered: Boolean) {
            if (!host.preview) shown = true
            val s = answer()
            lastSilent = s?.silent
            when {
                s?.silent == true -> {
                    card.title(ALLOW_TITLE)
                    card.body(ALLOW_BODY)
                    card.status(ON, SetupCard.Tone.GREEN)
                    card.primary("Continue") { done() }
                }
                s is SilentUpdates.OldAndroid || s is SilentUpdates.TargetTooLow || s == SilentUpdates.NoRootService -> {
                    card.title(OWNER_TITLE)
                    card.body(when (s) {
                        is SilentUpdates.OldAndroid -> ownerBody(s.android)
                        is SilentUpdates.TargetTooLow -> targetBody(s)
                        else -> BOOT_IMAGE_BODY
                    })
                    card.qr(KILN)
                    card.status(OPTIONAL)
                    card.primary("Continue") { done() }
                }
                else -> {
                    card.title(ALLOW_TITLE)
                    card.body(ALLOW_BODY)
                    if (noSettings) card.status(NO_SETTINGS, SetupCard.Tone.ROSE) else card.status(OFF)
                    card.primary(OPEN_SETTINGS) {
                        if (!host.preview) noSettings = !openSettings()
                        host.redraw()
                    }
                    card.secondary("Not now") { done() }
                }
            }
        }

        /** Coming back from Android's settings: draw the card again when the answer changed. */
        override fun tick(current: Boolean) {
            if (current && answer()?.silent != lastSilent) host.redraw()
        }

        private fun done() {
            if (!host.preview) markAnswered()
            host.next()
        }

        /** The app's own "Install unknown apps" page. False when Android has none to open. */
        private fun openSettings(): Boolean = runCatching {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    companion object {
        const val ID = "silent_updates"
        const val ALLOW_TITLE = "Install updates by themselves?"
        const val ALLOW_BODY = "Allow “Install unknown apps” for Glassworks, and updates from Home Assistant install with " +
            "nobody at the screen. Glassworks uses it only for its own updates, signed with its key."
        const val OFF = "Off: each update asks for a tap on this screen"
        const val ON = "On: updates install by themselves"
        const val NO_SETTINGS = "Android didn't open the page. Allow it under Settings, Apps, Glassworks, Install unknown apps."
        const val OPEN_SETTINGS = "Open Android's settings"
        const val OWNER_TITLE = "A tap per update"
        const val OPTIONAL = "Optional"
        const val KILN = "https://kiln.glassworks.dev"

        fun ownerBody(android: String) =
            "On Android $android, only a device owner skips it. Kiln can set that up on a reset device with no accounts, and undo it."

        /** The S1 with a boot image from before the root update service. */
        const val BOOT_IMAGE_BODY = "This panel's boot image has no update service. Kiln adds it from a computer, and keeps your settings."

        fun targetBody(s: SilentUpdates.TargetTooLow) =
            "Android ${s.android} skips the tap only for apps that target API ${s.needed}, and this one targets ${s.has}. " +
                "Until a release does, only a device owner skips it. Kiln can set that up."

        /** What the step offers for [s], or null when there's nothing to do on this device. */
        fun offer(s: SilentUpdates?): Offer? = when (s) {
            SilentUpdates.UnknownAppsOff -> Offer.ALLOW_UNKNOWN_APPS
            is SilentUpdates.OldAndroid, is SilentUpdates.TargetTooLow -> Offer.DEVICE_OWNER
            SilentUpdates.NoRootService -> Offer.BOOT_IMAGE
            else -> null
        }
    }

    /** What the person can do here. [wire]: its name on the setup channel. */
    enum class Offer(val wire: String) {
        /** Android 12+: allow "Install unknown apps" in Android's settings. */
        ALLOW_UNKNOWN_APPS("allow"),
        /** Android 6 to 11, or an app that targets too low an API: device owner, which Kiln sets up on a reset device. */
        DEVICE_OWNER("device_owner"),
        /** The S1 with an old boot image: Kiln adds the root update service. */
        BOOT_IMAGE("boot_image"),
    }
}
