/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glassworks.engine.vitro.VitroEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine contract for Vitro over this run's Gecko engine, as the apps build it with Vitro on. On API 23 this is
 * Android 6's runtime and verifier with the legacy build's desugaring: Vitro's java.time, its Platform checks and its
 * own websocket client run there for real, against [FakeHomeAssistant].
 */
@RunWith(AndroidJUnit4::class)
class VitroEngineContractTest : EngineContract() {
    override fun subject(ha: FakeHomeAssistant) = Subjects.vitro(ha)

    /** A dashboard is Vitro's own: no fallback page, and Home Assistant heard from Vitro's websocket client. */
    @Test fun aDashboardIsDrawnWithoutTheFallback() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        assertEquals("no fallback page for a dashboard", 0, subject.fallback!!.pages.size)
        assertTrue("Vitro asked for the dashboard: ${ha.commands}", "lovelace/config" in ha.commands)
        assertTrue("and its states: ${ha.commands}", "subscribe_entities" in ha.commands)
        assertEquals("ready from Vitro itself: $host", "vitro", host.readies().first().message!!.optString("why"))
        onMain { assertTrue("its view draws the dashboard", (page.view as ViewGroup).getChildAt(0).isShown) }
    }
}

/**
 * Vitro's off switch on a real runtime: a VitroEngine that is off at start is its fallback. Its page is the fallback's
 * own, it says what the fallback says, and nothing of Vitro runs (no websocket). The apps don't even make a VitroEngine
 * with Vitro off (EngineChoiceTest, on Robolectric): they run on the Gecko engine, which GeckoEngineContractTest checks.
 */
@RunWith(AndroidJUnit4::class)
class VitroOffTest : EngineHarness() {
    override fun subject(ha: FakeHomeAssistant) = Subjects.vitro(ha, enabled = false)

    @Test fun itIsItsFallback() {
        val vitro = engine as VitroEngine
        val page = open()
        assertSame("the page is the fallback's own", subject.fallback!!.pages.single(), page)
        assertEquals("named as the fallback", vitro.fallback.name, vitro.name)
        assertSame("started as the fallback", vitro.fallback.started, vitro.started)
        assertEquals("the plain engine's settings", Subjects.dashboard(ha), vitro.started?.dashboard)
        load(page, Subjects.dashboard(ha))
        assertTrue("no websocket from Vitro: ${ha.commands}", ha.commands.isEmpty())
    }
}
