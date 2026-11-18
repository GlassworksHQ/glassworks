/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.view.View
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThisDeviceStepTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Card : SetupCard {
        var title = ""; var qr: String? = null; var content: View? = null
        var primaryText = ""; var primary: (() -> Unit)? = null
        var secondaryText: String? = null; var secondary: (() -> Unit)? = null
        override fun title(text: String) { title = text }
        override fun body(text: String?) {}
        override fun status(text: String?, tone: SetupCard.Tone) {}
        override fun qr(url: String?) { qr = url }
        override fun content(view: View?) { content = view }
        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) { primaryText = text; primary = onClick }
        override fun secondary(text: String?, onClick: () -> Unit) { secondaryText = text; secondary = onClick }
        override fun tightButtons() {}
    }

    private class Rig(var grants: ThisDeviceStep.Grants?, val opens: Boolean = true) {
        var answered = false
        var next = 0
        var home = 0
        var admin = 0
        val step = ThisDeviceStep({ grants }, { answered }, { answered = true }, { home++; opens }, { admin++; opens })
        val host = object : SetupHost {
            override val preview = false
            override fun redraw() {}
            override fun next() { this@Rig.next++ }
        }
        val screen by lazy { step.screen(RuntimeEnvironment.getApplication(), host) }
    }

    private fun bound(r: Rig) = Card().also { r.screen.bind(it, entered = true) }

    private fun details(card: Card): List<String> = ((card.content as android.widget.ScrollView).getChildAt(0) as android.view.ViewGroup).let { rows ->
        (0 until rows.childCount).map { i ->
            fun texts(v: View): List<String> = if (v is android.widget.TextView) listOf(v.text.toString())
                else if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
            texts(rows.getChildAt(i)).joinToString(" | ")
        }
    }

    @Test fun itShowsOnlyWhenSomethingAKilnInstallGrantsIsMissing() {
        assertTrue("after a Kiln install", Rig(ThisDeviceStep.Grants(home = true, admin = true)).step.done())
        assertTrue("not read yet", Rig(null).step.done())
        assertFalse(Rig(ThisDeviceStep.Grants(home = false, admin = true)).step.done())
        assertFalse(Rig(ThisDeviceStep.Grants(home = true, admin = false)).step.done())
    }

    @Test fun theButtonDoesTheNextMissingOneThenContinue() {
        val r = Rig(ThisDeviceStep.Grants(home = false, admin = false))
        var card = bound(r)
        assertNull("no QR code: everything here is a tap on the tablet", card.qr)
        assertEquals(ThisDeviceStep.MAKE_HOME, card.primaryText)
        card.primary!!()
        assertEquals(1, r.home)
        r.grants = ThisDeviceStep.Grants(home = true, admin = false)
        card = bound(r)
        assertEquals(ThisDeviceStep.ALLOW_SCREEN_OFF, card.primaryText)
        assertEquals("Not now", card.secondaryText)
        card.primary!!()
        assertEquals(1, r.admin)
        r.grants = ThisDeviceStep.Grants(home = true, admin = true)
        assertFalse("drawn, so it stays until answered", r.step.done())
        card = bound(r)
        assertEquals("Continue", card.primaryText)
        assertNull(card.secondaryText)
        card.primary!!()
        assertTrue(r.step.done())
        assertEquals(1, r.next)
    }

    @Test fun theScreenRowSaysWhatTheDialogIsForAndHowToUndoIt() {
        val card = bound(Rig(ThisDeviceStep.Grants(home = true, admin = false)))
        val row = details(card)[1]
        assertTrue(row, "device admin" in row && "turn the screen off when idle" in row && "uninstall" in row)
        assertTrue(ThisDeviceStep.ADMIN_EXPLANATION.contains("turns the screen off") && ThisDeviceStep.ADMIN_EXPLANATION.contains("uninstall"))
    }

    @Test fun aDoneStepOpenedFromTheRailStaysDone() {
        val r = Rig(ThisDeviceStep.Grants(home = true, admin = true))
        bound(r)
        assertTrue(r.step.done())
    }

    @Test fun continueReadsTheGrantsAgain() {
        val r = Rig(ThisDeviceStep.Grants(home = false, admin = false))
        bound(r)
        r.grants = ThisDeviceStep.Grants(home = true, admin = true)
        val card = bound(r)
        r.grants = ThisDeviceStep.Grants(home = true, admin = false) // Android took the admin back before the tap
        card.primary!!()
        assertFalse("not answered: it asks again", r.answered)
        assertEquals(0, r.next)
    }

    @Test fun notNowAnswersIt() {
        val r = Rig(ThisDeviceStep.Grants(home = false, admin = false))
        bound(r).secondary!!()
        assertTrue(r.answered)
        assertEquals(1, r.next)
    }

    @Test fun whenAndroidHasNothingToOpenTheRowSaysWhereToGoByHand() {
        val r = Rig(ThisDeviceStep.Grants(home = false, admin = false), opens = false)
        bound(r).primary!!()
        assertEquals(ThisDeviceStep.noHome(), details(bound(r))[0].substringAfter(" | "))
    }

    @Test fun aButtonReadsTheGrantsAgainOnItsTap() {
        val r = Rig(ThisDeviceStep.Grants(home = true, admin = false))
        val card = bound(r)
        r.grants = ThisDeviceStep.Grants(home = false, admin = false) // the Home app changed since this draw
        card.primary!!()
        assertEquals("redrawn, no device admin dialog", 0, r.admin)
    }

    @Test fun android6sHomeSettingsPath() {
        assertEquals("Settings, Home", dev.glassworks.frame.GenericAndroid.homeSettingsPath(dev.glassworks.platform.PlatformApi(23)))
        assertEquals("Settings, Apps, Default apps, Home app", dev.glassworks.frame.GenericAndroid.homeSettingsPath(dev.glassworks.platform.PlatformApi(24)))
    }
}
