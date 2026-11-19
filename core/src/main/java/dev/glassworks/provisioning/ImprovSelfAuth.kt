/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.provisioning

import dev.glassworks.frame.Input
import dev.glassworks.improv.ImprovSession

/**
 * When Improv over Bluetooth authorizes itself, with nobody to press Allow (#611). On a screen that takes no tap
 * ([Input.NONE], the lululemon Mirror) it starts authorized for [WINDOW_MS], but only while the panel has no network:
 * a panel that is online is never sent new Wi-Fi without a person. That is the exposure of an ESPHome device, which
 * asks for no authorization at all. A screen that takes a tap keeps Allow, and these rules change nothing there.
 *
 * Pure, so the rules have tests (ImprovSelfAuthTest). [dev.glassworks.provisioning.ImprovController] applies them.
 */
object ImprovSelfAuth {
    /** How long it stays authorized by itself, from the start or from an approach. */
    const val WINDOW_MS = 5 * 60_000L

    /** The session for [input]: without touch, an authorization lasts [WINDOW_MS] instead of Allow's 2 minutes. */
    fun session(effects: ImprovSession.Effects, input: Input): ImprovSession =
        if (input == Input.NONE) ImprovSession(effects, authTimeoutMs = WINDOW_MS) else ImprovSession(effects)

    /** Improv starts (or is asked to start again) authorized: without touch, while offline. */
    fun atStart(input: Input, online: Boolean): Boolean = input == Input.NONE && !online

    /**
     * An approach authorizes, where approach authorization is on ([proximityAuth]). Without touch only while offline:
     * a camera's presence is anyone who walks past, not a person setting the panel up.
     */
    fun onApproach(input: Input, online: Boolean, proximityAuth: Boolean): Boolean =
        proximityAuth && (input == Input.TOUCH || !online)

    /** Whether an approach authorizes: as set ([chosen], `--ez improv_proximity_auth`), else on without touch. */
    fun proximityAuth(input: Input, chosen: Boolean?): Boolean = chosen ?: (input == Input.NONE)

    /**
     * How long the setup hotspot waits before it opens by itself ([dev.glassworks.setup.SetupHotspotPolicy]'s grace,
     * [graceMs]). Without touch, while Improv starts by itself ([improvAuto], [bootGraceMs] after the start), it waits
     * until this window has closed: Bluetooth setup comes first, and the radio isn't an access point while a phone
     * scans for networks through Improv.
     */
    fun hotspotGraceMs(input: Input, improvAuto: Boolean, bootGraceMs: Long, graceMs: Long = HOTSPOT_GRACE_MS): Long =
        if (input == Input.NONE && improvAuto) maxOf(graceMs, bootGraceMs + WINDOW_MS) else graceMs

    /** SetupHotspotPolicy's own grace: 3 minutes. */
    const val HOTSPOT_GRACE_MS = 3 * 60_000L
}
