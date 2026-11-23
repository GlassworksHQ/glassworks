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
    /**
     * The screen takes a tap. Without one (the frame's Input.NONE) the card can't show its buttons, so the step never
     * holds setup there; the setup channel can still answer it (CodeRabbit on #513).
     */
    private val touch: () -> Boolean = { true },
) : SetupStep {
    override val id = ID
    override val title = "Silent updates"
    override val reason = "updates ask for a tap, and the person can change that"

    /**
     * The step's card was drawn (not in a preview): it stays until answered, so the person sees "On" after allowing
     * it. Set in bind, not when the screen is made: the setup screen makes every step's screen at its first tick.
     */
    @Volatile private var shown = false

    /**
     * Not done while the answer isn't known yet (the update manager reads the frame's root service at its start): setup
     * waits on this step rather than end before its first offer can show (CodeRabbit on #513).
     */
    override fun done(): Boolean = answered() || !touch() || (!shown && answer().let { it != null && offer(it) == null })

    /** For the setup channel: whether updates are silent, what the step offers ([Offer.wire], or null) and the reason. */
    override fun channelState(): JSONObject? = answer()?.let { s ->
        JSONObject().put("silent", s.silent).put("reason", s.reason).put("offer", offer(s)?.wire ?: JSONObject.NULL).put("answered", answered())
    }

    /** For the setup channel and the LAN page: answers the step, as "Not now" does. */
    override val ops = mapOf("answer" to SetupOp { if (answer() == null) NOT_READ else { markAnswered(); null } })

    override fun screen(context: Context, host: SetupHost): SetupScreen = Screen(context, host)

    private inner class Screen(private val context: Context, private val host: SetupHost) : SetupScreen {
        private var lastSilent = answer()?.silent
        /** Android's settings page couldn't be opened. */
        private var noSettings = false

        override fun bind(card: SetupCard, entered: Boolean) {
            val s = answer()
            // Only a live card with something to offer latches: one opened from the rail once done stays done.
            if (!host.preview && offer(s) != null) shown = true
            lastSilent = s?.silent
            when {
                s == null -> {
                    card.title(CHECKING_TITLE)
                    card.body(CHECKING_BODY)
                }
                s.silent -> {
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
        const val ALLOW_TITLE = "Allow automatic updates?"
        const val ALLOW_BODY = "Allow “Install unknown apps” for Glassworks so updates from Home Assistant install automatically. " +
            "Glassworks only installs its own signed updates."
        const val OFF = "Off: each update asks for a tap on this screen"
        const val ON = "On: updates install automatically"
        const val NO_SETTINGS = "Android didn't open that page. Allow it under Settings → Apps → Glassworks → Install unknown apps."
        const val OPEN_SETTINGS = "Open Android's settings"
        const val OWNER_TITLE = "Updates need a tap"
        const val OPTIONAL = "Optional"
        const val CHECKING_TITLE = "Checking updates"
        const val CHECKING_BODY = "Checking whether updates can install automatically on this device…"
        /** The setup channel's answer while [answer] isn't known yet. */
        const val NOT_READ = "Silent updates isn't read yet. Try again in a moment."
        const val KILN = "https://kiln.glassworks.dev"

        fun ownerBody(android: String) =
            "On Android $android, only a device owner can install updates without a tap. Kiln can set that up on a factory-reset device with no accounts, and undo it anytime."

        /** The S1 with a boot image from before the root update service. */
        const val BOOT_IMAGE_BODY = "This panel's system software doesn't include automatic updates yet. Update it once with Kiln on a computer — your settings stay safe."

        fun targetBody(s: SilentUpdates.TargetTooLow) =
            "Android ${s.android} only allows automatic updates for apps targeting API ${s.needed} or higher (this build targets ${s.has}). " +
                (if (s.unknownApps) "" else "That release will also need “Install unknown apps” allowed for Glassworks. ") +
                "Until then, only a device owner can skip the tap — Kiln can set that up."

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
