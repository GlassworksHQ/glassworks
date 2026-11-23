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
    enum class Action(val label: String) {
        OPEN("Set up from a phone"), ALLOW("Allow"), STOP("Close it"), RETRY("Try again"),
        /** The radio is off and the panel can't turn it on: Android's Wi-Fi panel or settings. */
        WIFI_SETTINGS("Turn on Wi-Fi"),
    }

    /**
     * What the Wi-Fi step shows. With a [title], the hotspot fills the step's card: starting, on, or a start the person
     * asked for that failed (#460). Without one, only a [status] line and an [action] under the Wi-Fi step's own card.
     */
    data class Card(
        /** The card's small label while on: how long it stays open. */
        val label: String? = null,
        val title: String? = null,
        val body: String? = null,
        /** The QR code's text: how to join the hotspot, or the setup page's address once a phone is on it. */
        val qr: String? = null,
        /** A line beside the spinner while it starts. */
        val waiting: String? = null,
        val status: String? = null,
        val tone: Tone = Tone.MUTED,
        val action: Action? = null,
    )

    /**
     * The card for [view]. [permission]: Android's permission step, which wins over an offer to open the hotspot.
     * [setupUrl]: the setup page on the hotspot's address, once the panel has one. [asked]: the person asked for the
     * hotspot on the setup screen, so a failed start answers them in the card.
     */
    fun card(view: SetupHotspot.View, permission: HotspotPermission.Step, setupUrl: String?, asked: Boolean = false): Card = when (view) {
        is SetupHotspot.View.On -> if (view.phone && setupUrl != null) Card(
            label = label(view),
            title = "Open the setup page",
            // The address on its own line, as on the join card; the network stays written for a second phone.
            body = "Scan it on the phone, or open\n$setupUrl\nWi-Fi “${view.network.ssid}”\nPassword ${view.network.passphrase}",
            qr = setupUrl,
            action = Action.STOP,
        ) else Card(
            label = label(view),
            title = "Join the panel's Wi-Fi",
            // Short lines that fit beside the QR code; the address alone on one, as Android would break it at a dot.
            body = "Scan it with a phone, then open\n" + (setupUrl ?: "the address that shows here") +
                "\nWi-Fi “${view.network.ssid}”\nPassword ${view.network.passphrase}",
            qr = view.network.qrText(),
            action = Action.STOP,
        )
        SetupHotspot.View.Starting -> Card(
            title = "Starting the setup Wi-Fi…",
            body = "Join it from your phone to finish setup. The QR code will appear here in a few seconds.",
            waiting = "Looking for Wi-Fi networks, then opening it…",
            action = Action.STOP,
        )
        is SetupHotspot.View.Off -> when {
            permission is HotspotPermission.Step.Blocked -> Card(status = permission.why, tone = Tone.ROSE)
            permission is HotspotPermission.Step.Explain -> Card(status = permission.why, tone = Tone.AMBER, action = Action.ALLOW)
            asked && view.error != null && view.error != HotspotFailure.NO_PERMISSION -> Card(
                title = "Couldn't start setup Wi-Fi",
                status = if (view.error == HotspotFailure.FAILED) "Android didn't say why." else view.error.text,
                tone = Tone.ROSE,
                action = if (view.error == HotspotFailure.WIFI_OFF) Action.WIFI_SETTINGS else Action.RETRY,
            )
            view.error == HotspotFailure.WIFI_OFF -> Card(status = view.error.text, tone = Tone.ROSE, action = Action.WIFI_SETTINGS)
            view.error != null && view.error != HotspotFailure.NO_PERMISSION -> Card(status = view.error.text, tone = Tone.ROSE, action = Action.RETRY)
            view.stopped == Stop.ANDROID -> Card(
                status = "Android closed the setup Wi-Fi because no device joined for a while. It will open again if Wi-Fi stays disconnected.",
                action = Action.OPEN,
            )
            view.stopped == Stop.TIMED_OUT -> Card(
                status = "The setup Wi-Fi closed after 15 minutes, and the panel is trying your Wi-Fi again.", action = Action.OPEN,
            )
            else -> Card(action = Action.OPEN)
        }
    }

    // "within": Android may close it sooner when nobody joins (10 minutes on the S1's Android 10).
    private fun label(on: SetupHotspot.View.On) = "Wi-Fi · closes within ${minutes(on.leftMs)}"

    /** Whole minutes, rounded up: the screen refreshes every 2 s, so seconds would flicker. */
    fun minutes(ms: Long): String {
        val m = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return if (m == 1L) "1 minute" else "$m minutes"
    }
}
