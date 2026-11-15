/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/** Work a frame runs while the app's service runs ([Frame.serviceWork]). The service starts it once and stops it at its end. */
interface ServiceWork {
    fun start()
    fun stop()
}
