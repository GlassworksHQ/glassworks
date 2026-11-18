/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import dev.glassworks.update.UpdateInstallReceiver

/**
 * The classes the app's manifest declares for the core's Android components. Android knows a component by its
 * class name: device admin, the HOME choice, PackageInstaller's status receiver and `am start -n` all name it. An
 * app whose installed apps know other names (the S1's `sh.edm.s1kiosk.*`, #22) declares a
 * subclass under each, and the core starts and names the app's classes, never its own.
 */
class AppComponents(
    /** The activity that shows the dashboard, and that Home Assistant's commands go to. */
    val mainActivity: Class<out MainActivity>,
    val service: Class<out KioskService>,
    val admin: Class<out AdminReceiver>,
    val updateReceiver: Class<out UpdateInstallReceiver>,
)
