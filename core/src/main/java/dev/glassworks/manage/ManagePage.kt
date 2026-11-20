/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.web.HttpServer

/** The app's Manage page (#33) behind the QR-code sign-in pages, on the panel's web port. */
interface ManagePage {
    /** The web server for [port]: the Manage page with the sign-in pages behind it. */
    fun http(port: Int): HttpServer

    /** Starts keeping the page's log. */
    fun startLog()

    /** Stops keeping the page's log. */
    fun stopLog()

    /** The page stops with the web server: its log, and any work it does in the background. */
    fun stop() = stopLog()

    /** What the page's Overview shows now; the panel's settings menu opens on the same Overview. */
    fun status(): PanelStatus

    /** The Overview's actions, the same on the page and in the panel's menu. */
    val actions: PanelActions

    /** Signs every browser out of the page (a factory reset, #33); false when that couldn't be saved. */
    fun signOutAll(): Boolean
}

/**
 * [make]'s result, or null when it can't load or start: for a part the panel runs without, such as the Manage page or
 * one of its cards. A class Android refuses to load throws a LinkageError (VerifyError, NoClassDefFoundError); not
 * caught, it ended the app at every start on the Mirror (#435, 2026-11-18). [warn] gets what failed and why.
 */
inline fun <T> orWithout(what: String, warn: (String) -> Unit, make: () -> T): T? =
    try {
        make()
    } catch (e: LinkageError) {
        warn("$what can't load, so the panel runs without it: $e")
        null
    } catch (e: RuntimeException) {
        warn("$what can't start, so the panel runs without it: $e")
        null
    }
