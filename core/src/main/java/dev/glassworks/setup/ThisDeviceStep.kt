/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import dev.glassworks.ui.Glass
import dev.glassworks.ui.StatusTile
import org.json.JSONObject

/**
 * Setup's This device step (#403, the maintainer's reading A, 2026-11-18): after a download from the tablet's own
 * browser, the two things a Kiln install grants that the app asks for here instead. The home screen (Android's home
 * role or its home settings) and the device admin that lets it turn the screen off (Android's "Activate device admin
 * app?" dialog). The card's one button does the next missing one, then Continue. After a Kiln install both are on and
 * the step never shows. Never required: "Not now" and "Continue" both answer it, and [answered] keeps that across
 * restarts. The same facts stay on the Device page.
 *
 * [grants] is asked on every 2 s tick and several times per draw: keep it cheap. [askHome] and [askAdmin] open
 * Android's page or dialog from the setup screen's activity, and return false when Android had nothing to open.
 */
class ThisDeviceStep(
    private val grants: () -> Grants?,
    private val answered: () -> Boolean,
    private val markAnswered: () -> Unit,
    private val askHome: (Context) -> Boolean,
    private val askAdmin: (Context) -> Boolean,
) : SetupStep {
    override val id = ID
    override val title = TITLE
    override val reason = "the home screen or turning the screen off isn't allowed yet"

    /** What this device lets the app do, read from the device, not from where the APK came from. */
    data class Grants(
        /** The app is the home screen: it holds the home role (Android 10+), or answers the HOME intent (older). */
        val home: Boolean,
        /** The app is an active device admin, so it can turn the screen off (`lockNow`). */
        val admin: Boolean,
    ) {
        val missing: Boolean get() = !home || !admin
    }

    /** The card was drawn (not in a preview): it stays until answered, so the person sees each row turn on. */
    @Volatile private var shown = false

    override fun done(): Boolean = answered() || (!shown && grants()?.missing != true)

    override fun channelState(): JSONObject? = grants()?.let { g ->
        JSONObject().put("home", g.home).put("admin", g.admin).put("answered", answered())
    }

    /** For the setup channel and the LAN page: answers the step, as "Not now" does. */
    override val ops = mapOf("answer" to SetupOp { markAnswered(); null })

    override fun screen(context: Context, host: SetupHost): SetupScreen = Screen(context, host)

    private inner class Screen(private val context: Context, private val host: SetupHost) : SetupScreen {
        private val homeTile = StatusTile(context).apply { title.text = HOME }
        private val adminTile = StatusTile(context).apply { title.text = SCREEN }
        /**
         * The rows scroll in the room above the card's buttons, so a large font never cuts their lines (CodeRabbit on
         * #524). The buttons are the card's foot and stay put.
         */
        private val rows = ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(Glass(context).px(FADE_DP))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(homeTile.view, LinearLayout.LayoutParams(-1, -2))
                addView(adminTile.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Glass(context).px(TILE_GAP_DP) })
            }, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        private var last: Grants? = null
        /** Android had no page or dialog to open for that row. */
        private var noHome = false
        private var noAdmin = false

        override fun bind(card: SetupCard, entered: Boolean) {
            val g = grants()
            // Only a live card with something missing latches: one opened from the rail once done stays done (#524).
            if (!host.preview && g?.missing == true) shown = true
            last = g
            val home = g?.home == true
            val admin = g?.admin == true
            card.title(TITLE_TEXT)
            card.body(null)
            homeTile.tone(when { home -> Glass.GREEN; noHome -> Glass.ROSE; else -> Glass.AMBER })
            homeTile.detail.text = when { home -> HOME_ON; noHome -> NO_HOME; else -> HOME_OFF }
            adminTile.tone(when { admin -> Glass.GREEN; noAdmin -> Glass.ROSE; else -> Glass.AMBER })
            adminTile.detail.text = when { admin -> SCREEN_ON; noAdmin -> NO_ADMIN; else -> SCREEN_OFF }
            card.content(rows)
            when {
                !home -> {
                    card.primary(MAKE_HOME) { ask { noHome = !askHome(context) } }
                    card.secondary("Not now") { done() }
                }
                !admin -> {
                    card.primary(ALLOW_SCREEN_OFF) { ask { noAdmin = !askAdmin(context) } }
                    card.secondary("Not now") { done() }
                }
                // Read again on Continue: a grant Android took back since this draw asks again (#524). Not now still skips.
                else -> card.primary("Continue") { if (grants()?.missing == true) host.redraw() else done() }
            }
        }

        /** Back from Android's page or dialog: draw the card again when the answer changed. */
        override fun tick(current: Boolean) {
            if (current && grants() != last) host.redraw()
        }

        private fun ask(open: () -> Unit) {
            if (!host.preview) open()
            host.redraw()
        }

        private fun done() {
            if (!host.preview) markAnswered()
            host.next()
        }
    }

    companion object {
        const val ID = "this_device"
        const val TITLE = "This device"
        const val TITLE_TEXT = "Make it a panel."
        const val HOME = "Home screen"
        const val HOME_ON = "On: Glassworks is the default Home app"
        const val HOME_OFF = "Off: Glassworks isn't the default Home app"
        const val NO_HOME = "Android didn't open it: Settings, Apps, Default apps, Home app"
        const val SCREEN = "Turn the screen off"
        const val SCREEN_ON = "On: Glassworks can turn the screen off when idle. Turn this off before you uninstall it."
        const val SCREEN_OFF = "Android asks to make Glassworks a device admin, so it can turn the screen off when idle. " +
            "To uninstall it later, turn that off first."
        const val NO_ADMIN = "Android didn't open it: Settings, Security, Device admin apps"
        const val MAKE_HOME = "Make it the home screen"
        const val ALLOW_SCREEN_OFF = "Let it turn the screen off"
        /** What Android's "Activate device admin app?" dialog says Glassworks wants it for (`EXTRA_ADD_EXPLANATION`). */
        const val ADMIN_EXPLANATION = "Glassworks turns the screen off when the panel is idle, or when Home Assistant " +
            "turns it off. With a PIN or password, Android then locks the device. Turn this off before you uninstall Glassworks."
        const val TILE_GAP_DP = 11f
        const val FADE_DP = 28f
    }
}
