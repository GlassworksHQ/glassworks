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
}
