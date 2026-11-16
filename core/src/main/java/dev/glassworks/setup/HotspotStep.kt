/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.HotspotFailure
import dev.glassworks.setup.SetupHotspotPolicy.Stop

/**
 * What the setup screen's Wi-Fi step shows about the setup hotspot (#197), decided here in plain Kotlin so a test
 * reads it: the card's title and text, its QR code, a status line and the one action. The screen draws it in the
 * approved setup card (#34, #63): the same QR tile as the address and sign-in QR codes.
 */
object HotspotStep {
    enum class Tone { MUTED, AMBER, ROSE }

    /** The one action. [OPEN] asks for the hotspot, [ALLOW] asks Android for the permission first. */
    enum class Action(val label: String) { OPEN("Set up from a phone"), ALLOW("Allow"), STOP("Close it"), RETRY("Try again") }

    /**
     * The step's card while the hotspot is on ([title], [body], [qr]: the text of a "join this Wi-Fi" QR code), or
     * only a [status] line and an [action] under the Wi-Fi step's own card while it isn't.
     */
    data class Card(
        /** The card's small label while on: how long it stays open. */
        val label: String? = null,
        val title: String? = null,
        val body: String? = null,
        val qr: String? = null,
        val status: String? = null,
        val tone: Tone = Tone.MUTED,
        val action: Action? = null,
    )

    /**
     * The card for [view]. [permission]: Android's permission step, which wins over an offer to open the hotspot.
     * [setupUrl]: the setup page on the hotspot's address, once the panel has one.
     */
    fun card(view: SetupHotspot.View, permission: HotspotPermission.Step, setupUrl: String?): Card = when (view) {
        is SetupHotspot.View.On -> Card(
            label = "Wi-Fi · closes in ${minutes(view.leftMs)}",
            title = "Join the panel's Wi-Fi.",
            // Short lines that fit beside the QR code; the address alone on one, as Android would break it at a dot.
            body = "Scan it with a phone, then open\n" + (setupUrl ?: "the address that shows here") +
                "\nWi-Fi “${view.network.ssid}”\nPassword ${view.network.passphrase}",
            qr = view.network.qrText(),
            action = Action.STOP,
        )
        SetupHotspot.View.Starting -> Card(status = "Opening the panel's own Wi-Fi…", tone = Tone.AMBER)
        is SetupHotspot.View.Off -> when {
            permission is HotspotPermission.Step.Blocked -> Card(status = permission.why, tone = Tone.ROSE)
            permission is HotspotPermission.Step.Explain -> Card(status = permission.why, tone = Tone.AMBER, action = Action.ALLOW)
            view.error != null && view.error != HotspotFailure.NO_PERMISSION -> Card(status = view.error.text, tone = Tone.ROSE, action = Action.RETRY)
            view.stopped == Stop.TIMED_OUT -> Card(
                status = "The panel's own Wi-Fi closed after 15 minutes, and the panel tries your Wi-Fi again.", action = Action.OPEN,
            )
            else -> Card(action = Action.OPEN)
        }
    }

    /** Whole minutes, rounded up: the screen refreshes every 2 s, so seconds would flicker. */
    fun minutes(ms: Long): String {
        val m = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return if (m == 1L) "1 minute" else "$m minutes"
    }
}
