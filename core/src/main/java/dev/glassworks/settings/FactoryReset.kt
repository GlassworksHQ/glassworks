/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

/**
 * Factory reset (#33, decided by the maintainer 2026-11-17): the app forgets its settings and its Home Assistant
 * sign-in, then starts again in setup. Not an Android wipe. The same steps from the panel's menu and from the Manage
 * page, which asks for the panel's code first. What it keeps is [dev.glassworks.KioskPrefs.RESET_KEEPS].
 *
 * The sign-in goes first: a reset that couldn't remove it stops there, with the settings as they were, and says so.
 * Then every browser on the Manage page: one that couldn't be signed out for good would come back after the restart,
 * so the reset stops there too. Then the settings. The app restarts only when all three are done.
 */
class FactoryReset(
    /** Revokes the dashboard's Home Assistant sign-in and deletes it ([dev.glassworks.auth.HaAuth.signOut]); false when it stayed. */
    private val signOut: (done: (signedOut: Boolean) -> Unit) -> Unit,
    /** Clears `kiosk.xml` but what it keeps, and setup's answers; false when a file couldn't be written. */
    private val clearSettings: () -> Boolean,
    /** Signs every browser out of the Manage page; false when that couldn't be saved. Null when the page isn't running. */
    private val signOutBrowsers: () -> Boolean?,
    /** Restarts the app, after the answer has gone out. */
    private val restart: () -> Unit,
    /** Runs a step on the main thread (the sign-out answers on the auth thread). */
    private val onMain: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    /**
     * Runs [clearSettings] off the main thread: it writes files and deletes a cache, and waits for page-storage writes
     * already queued. The answer and the restart wait for it, back on [onMain].
     */
    private val inBackground: (() -> Unit) -> Unit = { it() },
) {
    fun run(done: (String?) -> Unit) {
        signOut { signedOut ->
            onMain {
                if (!signedOut) {
                    log("Factory reset stopped: the Home Assistant sign-in couldn't be removed")
                    done(SIGN_IN_STAYED)
                    return@onMain
                }
                if (signOutBrowsers() == false) {
                    log("Factory reset stopped: the Manage page's browsers couldn't be signed out for good")
                    done(BROWSERS_STAYED)
                    return@onMain
                }
                inBackground {
                    val cleared = runCatching { clearSettings() }.getOrDefault(false)
                    onMain {
                        if (!cleared) {
                            log("Factory reset stopped: the settings couldn't be cleared")
                            done(NOT_CLEARED)
                            return@onMain
                        }
                        log("Factory reset: settings, Home Assistant sign-in and browsers cleared; restarting into setup")
                        done(null)
                        restart()
                    }
                }
            }
        }
    }

    companion object {
        const val TITLE = "Reset the panel"
        const val DETAIL = "Clears this app's settings and its Home Assistant sign-in, then starts setup again."
        const val LABEL = "Reset"

        /** The question both surfaces ask: exactly what goes and what stays. */
        const val CONFIRM_DETAIL = "This clears every setting of the app and its panes, setup's answers, the dashboard's " +
            "Home Assistant sign-in, and every browser signed in to the Manage page. It keeps Wi-Fi, the installed app, the Log, " +
            "the radio's settings (its Zigbee or Thread network stays up), and the panel in Home Assistant with its entities. " +
            "The app then restarts into setup."
        const val CONFIRM_ACTION = "Reset the panel"

        const val SIGN_IN_STAYED = "The panel couldn't remove its Home Assistant sign-in, so nothing was reset. Try again in a minute."
        const val BROWSERS_STAYED = "The panel couldn't sign the Manage page's browsers out for good, so its settings weren't reset. " +
            "Its Home Assistant sign-in is already removed. Try again."
        const val NOT_CLEARED = "The panel couldn't clear its settings. Its Home Assistant sign-in is already removed, and every browser " +
            "is signed out. Try again."
    }
}
