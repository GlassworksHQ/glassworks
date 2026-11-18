/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.app.Application
import android.provider.Settings
import android.view.View
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Setup's Silent updates step (#422): when it shows, what each card offers, and what answers it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SilentUpdatesStepTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Card : SetupCard {
        var title = ""; var body: String? = null; var qr: String? = null
        var status: Pair<String?, SetupCard.Tone>? = null
        var primaryText = ""; var primary: (() -> Unit)? = null
        var secondaryText: String? = null; var secondary: (() -> Unit)? = null
        override fun title(text: String) { title = text }
        override fun body(text: String?) { body = text }
        override fun status(text: String?, tone: SetupCard.Tone) { status = text to tone }
        override fun qr(url: String?) { qr = url }
        override fun content(view: View?) {}
        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) { primaryText = text; primary = onClick }
        override fun secondary(text: String?, onClick: () -> Unit) { secondaryText = text; secondary = if (text == null) null else onClick }
        override fun tightButtons() {}
    }

    private class Rig(var answer: SilentUpdates?, val preview: Boolean = false) {
        var answered = false
        var next = 0
        var redraws = 0
        val step = SilentUpdatesStep({ answer }, { answered }, { answered = true })
        val host = object : SetupHost {
            override val preview = this@Rig.preview
            override fun redraw() { redraws++ }
            override fun next() { this@Rig.next++ }
        }
    }

    private fun bound(r: Rig): Pair<dev.glassworks.pane.SetupScreen, Card> {
        val screen = r.step.screen(app, r.host)
        return screen to Card().also { screen.bind(it, entered = true) }
    }

    /** One rule (the maintainer, 2026-11-18): the step shows whenever updates ask for a tap and there's a fix. */
    @Test fun itShowsWheneverTheAnswerHasAFix() {
        val nothingToDo = listOf(SilentUpdates.RootService, SilentUpdates.SystemApp, SilentUpdates.DeviceOwner, SilentUpdates.SelfUpdate, null)
        for (s in nothingToDo) assertTrue("done for $s", Rig(s).step.done())
        val fixable = listOf(
            SilentUpdates.UnknownAppsOff, SilentUpdates.OldAndroid("9"), SilentUpdates.NoRootService, SilentUpdates.TargetTooLow("17", 35, 34),
        )
        for (s in fixable) {
            assertFalse("shown for $s", Rig(s).step.done())
            assertTrue("has a fix: $s", s.fix != null)
        }
    }

    @Test fun anOldBootImagePointsAtKiln() {
        val (_, card) = bound(Rig(SilentUpdates.NoRootService))
        assertEquals(SilentUpdatesStep.OWNER_TITLE, card.title)
        assertEquals(SilentUpdatesStep.BOOT_IMAGE_BODY, card.body)
        assertEquals(SilentUpdatesStep.KILN, card.qr)
        assertEquals("boot_image", SilentUpdatesStep.offer(SilentUpdates.NoRootService)?.wire)
        assertEquals("device_owner", SilentUpdatesStep.offer(SilentUpdates.TargetTooLow("17", 35, 34))?.wire)
    }

    @Test fun makingItsScreenDoesntShowIt() {
        // The setup screen makes every step's screen at its first tick, shown or not (CodeRabbit on #431).
        val r = Rig(SilentUpdates.SelfUpdate)
        r.step.screen(app, r.host).tick(current = false)
        assertTrue(r.step.done())
    }

    @Test fun aPreviewDoesntHoldItOpen() {
        val r = Rig(SilentUpdates.UnknownAppsOff, preview = true)
        bound(r)
        r.answer = SilentUpdates.SelfUpdate
        assertTrue("a preview changes nothing, this included", r.step.done())
    }

    @Test fun notNowAnswersItForGood() {
        val r = Rig(SilentUpdates.UnknownAppsOff)
        val (_, card) = bound(r)
        assertEquals(SilentUpdatesStep.ALLOW_TITLE, card.title)
        assertEquals(SilentUpdatesStep.OFF to SetupCard.Tone.MUTED, card.status)
        assertEquals("Not now", card.secondaryText)
        card.secondary!!()
        assertTrue(r.answered)
        assertEquals(1, r.next)
        assertTrue("setup doesn't come back for it", r.step.done())
    }

    @Test fun openSettingsOpensTheAppsOwnInstallUnknownAppsPage() {
        val r = Rig(SilentUpdates.UnknownAppsOff)
        val (_, card) = bound(r)
        assertEquals(SilentUpdatesStep.OPEN_SETTINGS, card.primaryText)
        card.primary!!()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)
        assertFalse("opening the page doesn't answer it", r.answered)
        assertEquals(0, r.next)
    }

    @Test fun afterAllowingItTheCardSaysOnAndContinueAnswersIt() {
        val r = Rig(SilentUpdates.UnknownAppsOff)
        val (screen, _) = bound(r)
        r.answer = SilentUpdates.SelfUpdate
        assertFalse("still shown until Continue, so the person sees it's on", r.step.done())
        screen.tick(current = true)
        assertEquals("back from Android's settings, the card is drawn again", 1, r.redraws)
        val card = Card() // the setup screen clears the card before each bind
        screen.bind(card, entered = false)
        assertEquals(SilentUpdatesStep.ON to SetupCard.Tone.GREEN, card.status)
        assertEquals("Continue", card.primaryText)
        assertNull(card.secondary)
        card.primary!!()
        assertTrue(r.answered)
        assertEquals(1, r.next)
    }

    @Test fun onOldAndroidItPointsAtKilnForDeviceOwner() {
        val r = Rig(SilentUpdates.OldAndroid("9"))
        val (_, card) = bound(r)
        assertEquals(SilentUpdatesStep.OWNER_TITLE, card.title)
        assertEquals(SilentUpdatesStep.ownerBody("9"), card.body)
        assertEquals(SilentUpdatesStep.KILN, card.qr)
        assertEquals("Continue", card.primaryText)
        card.primary!!()
        assertTrue(r.answered)
    }

    @Test fun aPreviewChangesNothing() {
        val r = Rig(SilentUpdates.UnknownAppsOff, preview = true)
        val (_, card) = bound(r)
        card.primary!!()
        assertNull("no settings page opened", shadowOf(app).nextStartedActivity)
        card.secondary!!()
        assertFalse(r.answered)
        assertEquals(1, r.next)
    }

    @Test fun theSetupChannelSeesItAndCanAnswerIt() {
        val r = Rig(SilentUpdates.UnknownAppsOff)
        val st = r.step.channelState()!!
        assertFalse(st.getBoolean("silent"))
        assertEquals("allow", st.getString("offer"))
        assertEquals(SilentUpdates.UnknownAppsOff.reason, st.getString("reason"))
        assertNull(r.step.ops.getValue("answer").run(JSONObject()))
        assertTrue(r.answered)
        assertEquals(JSONObject.NULL, Rig(SilentUpdates.SelfUpdate).step.channelState()!!.get("offer"))
    }
}
