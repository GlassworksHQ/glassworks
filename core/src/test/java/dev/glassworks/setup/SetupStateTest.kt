package dev.glassworks.setup

import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {

    private val min = 60_000L

    /** The S1's one pane step, the radio. */
    private fun radio(done: Boolean) = listOf(PaneStepFacts("radio", done, "radio mode not chosen"))

    /**
     * The already-configured desk panel: Wi-Fi, HA connects over ESPHome, a saved dashboard URL
     * (so HA is known), a cookie session (no refresh token, never on the login page). Its radio step
     * was marked done by the upgrade migration (the saved URL alone is enough, see radioUpgrade*).
     */
    private fun deskPanel(uptimeMs: Long, haSeen: Boolean, apiKey: Boolean, hello: Boolean) = SetupInputs(
        online = true, offlineForMs = 0, haKnown = true, haSeen = haSeen, apiKeySet = apiKey, haSkipped = false,
        haHelloThisProcess = hello, uptimeMs = uptimeMs, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false,
        paneSteps = radio(SetupState.setUpBefore(haKnown = true, haSeen = haSeen, apiKeySet = apiKey, haSkipped = false)),
    )

    private val fresh = SetupInputs(
        online = true, offlineForMs = 0, haKnown = false, haSeen = false, apiKeySet = false, haSkipped = false,
        haHelloThisProcess = false, uptimeMs = 5_000, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false, paneSteps = radio(false),
    )

    /**
     * The Mirror's run on 2026-11-20: the app joined Wi-Fi, found Home Assistant and restarted itself to apply it. The
     * new process had no setup screen up, so it showed Home Assistant's page, then asked to sign in. Carried across the
     * restart (showing), the screen stays up and says it connects until the page shows whether a sign-in is needed.
     */
    private val afterRestart = fresh.copy(haKnown = true, apiKeySet = true, uptimeMs = 3_000, input = Input.NONE, paneSteps = emptyList())

    @Test
    fun theSetupScreenCarriedAcrossTheRestartConnectsUntilTheSignInIsKnown() {
        val d = SetupState.decide(afterRestart.copy(showing = true, afterOwnRestart = true))
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertTrue(d.signinChecking)
        // without the carry nothing put the screen up: the page showed Home Assistant first
        assertFalse(SetupState.decide(afterRestart).show)
    }

    @Test
    fun theSignInCheckEndsWhenThePageAnswersOrAfterAWhile() {
        val up = afterRestart.copy(showing = true, afterOwnRestart = true)
        // Home Assistant's login page: a sign-in is needed, asked at once
        SetupState.decide(up.copy(onAuthPage = true)).let { assertEquals(SetupFlow.SIGNIN, it.step); assertFalse(it.signinChecking) }
        // the dashboard with a session it has: no sign-in, and nothing else left
        SetupState.decide(up.copy(dashboardLoaded = true)).let { assertFalse(it.signinChecking); assertTrue(it.signinDone); assertEquals(SetupFlow.DONE, it.step) }
        // signed in already (a sign-in handed back): nothing to check
        assertFalse(SetupState.decide(up.copy(signedIn = true)).signinChecking)
        // the page never answered: after the check's time, the screen goes on as before the check
        assertFalse(SetupState.decide(up.copy(uptimeMs = SetupRules().signinCheckMs)).signinChecking)
        // Home Assistant not found yet: the step finds it (its address field), no check over it
        assertFalse(SetupState.decide(up.copy(haKnown = false)).signinChecking)
        // a panel that just started without the app's own restart (a reboot) isn't held, also with the screen up
        // for another reason (CodeRabbit on #681): as before
        assertFalse(SetupState.decide(afterRestart.copy(dashboardLoaded = false)).signinChecking)
        assertFalse(SetupState.decide(afterRestart.copy(showing = true, paneSteps = radio(false))).signinChecking)
        assertFalse(SetupState.decide(afterRestart.copy(showing = true)).signinChecking)
    }

    @Test
    fun aHeldPanesStepGivesWayToALostWifi() {
        // CodeRabbit on #160: after a refusal Panes held the screen, also with Wi-Fi gone, and Panes can't fix Wi-Fi.
        val online = SetupState.decide(fresh.copy(haKnown = true, haSeen = true, signedIn = true))
        assertEquals(SetupFlow.PANES, online.holdingPanes(true).step)
        val offline = SetupState.decide(fresh.copy(online = false, offlineForMs = 60_000, neverSetUp = true))
        assertEquals(SetupFlow.WIFI, offline.holdingPanes(true).step)
        assertEquals(online, online.holdingPanes(false))
    }

    @Test
    fun aSignInForAnAddressHomeAssistantLeftAsksForTheSignInAgain() {
        // #467: Home Assistant moved where the panel can't confirm it. MainActivity passes signedIn = false then. The
        // Sign in step comes before Home Assistant's own login page loads, so a frame without touch can still sign in.
        val desk = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(signedIn = true)
        assertFalse(SetupState.decide(desk).show)
        val moved = SetupState.decide(desk.copy(signedIn = false, signinMoved = true))
        assertTrue(moved.show)
        assertEquals(SetupFlow.SIGNIN, moved.step)
        assertEquals(listOf("sign-in needed"), moved.reasons)
        // "Sign in on this panel instead" still wins, as for a first sign-in.
        assertFalse(SetupState.decide(desk.copy(signedIn = false, signinMoved = true, usePanelLogin = true)).show)
    }

    @Test
    fun afterSigningInOnThePanelSetupComesBackOnAddItAtOnce() {
        // #665: HA's login page hid the screen. Before, the dashboard showed until the 2 min grace ran out.
        val login = fresh.copy(haKnown = true, uptimeMs = 40_000, usePanelLogin = true, hidForPanelLogin = true)
        assertFalse("not over Home Assistant's login page", SetupState.decide(login).show)
        assertFalse("nor while it loads over the dashboard", SetupState.decide(login.copy(dashboardLoaded = true)).show)
        val signedIn = SetupState.decide(login.copy(usePanelLogin = false, signedIn = true))
        assertTrue(signedIn.show)
        assertEquals(SetupFlow.HA, signedIn.step)
        assertTrue("Add it checks while the grace runs", signedIn.haChecking)
        // A failed sign-in brings it back on Sign in, over a new login page.
        val failed = SetupState.decide(login.copy(usePanelLogin = false, onAuthPage = true))
        assertTrue(failed.show)
        assertEquals(SetupFlow.SIGNIN, failed.step)
    }

    @Test
    fun withoutTheLoginPagesPauseTheDashboardStillWaitsOutTheGrace() {
        // A panel whose setup screen wasn't up (a Kiln install that carried the sign-in over): HA may know it already.
        val kiln = fresh.copy(haKnown = true, signedIn = true, uptimeMs = 40_000, panesChosen = true, paneSteps = radio(true))
        assertFalse(SetupState.decide(kiln).show)
        assertTrue(SetupState.decide(kiln.copy(uptimeMs = 3 * min)).show)
    }

    @Test
    fun addItChecksOnlyWhileTheGraceRuns() {
        val up = fresh.copy(haKnown = true, signedIn = true, showing = true)
        assertTrue(SetupState.decide(up).haChecking)
        assertFalse("grace over", SetupState.decide(up.copy(uptimeMs = 3 * min)).haChecking)
        assertFalse("Home Assistant said Hello", SetupState.decide(up.copy(haHelloThisProcess = true)).haChecking)
        assertFalse("added", SetupState.decide(up.copy(haSeen = true)).haChecking)
        assertFalse("skipped", SetupState.decide(up.copy(haSkipped = true)).haChecking)
    }

    @Test
    fun afterTheSignInTheRestOfSetupFollowsAndThenEnds() {
        val after = fresh.copy(haKnown = true, signedIn = true, hidForPanelLogin = true, haSeen = true)
        assertEquals(SetupFlow.PANES, SetupState.decide(after).step)
        assertTrue(SetupState.decide(after).show)
        val done = SetupState.decide(after.copy(panesChosen = true, paneSteps = radio(true)))
        assertEquals(SetupFlow.DONE, done.step)
        assertFalse(done.show)
    }

    @Test
    fun freshOfflinePanelShowsSetupAtOnce() {
        // First boot after the installer: nothing set up, no Wi-Fi yet. No 30 s "waiting" screen.
        val first = fresh.copy(online = false, offlineForMs = 0, uptimeMs = 1_000, neverSetUp = true)
        val d = SetupState.decide(first)
        assertTrue(d.reasons.toString(), d.show)
        assertEquals(SetupFlow.WIFI, d.step)
    }

    @Test
    fun neverSetUpIsFalseForAnyTraceOfSetup() {
        assertTrue(SetupState.neverSetUp(false, false, false, false, false, false))
        val traces = listOf(
            SetupState.neverSetUp(true, false, false, false, false, false),
            SetupState.neverSetUp(false, true, false, false, false, false),
            SetupState.neverSetUp(false, false, true, false, false, false),
            SetupState.neverSetUp(false, false, false, true, false, false),
            SetupState.neverSetUp(false, false, false, false, true, false),
            SetupState.neverSetUp(false, false, false, false, false, true),
        )
        assertTrue(traces.none { it })
    }

    @Test
    fun deskPanelNeverShowsSetup() {
        // Every combination the desk panel can be in after an update to this build: just booted,
        // before HA reconnects, before ha_seen was ever written, with or without the old API key.
        for (uptime in listOf(0L, 10_000L, 2 * min - 1, 2 * min, 30 * min))
            for (seen in listOf(false, true))
                for (key in listOf(false, true))
                    for (hello in listOf(false, true)) {
                        // haSeen=false, key=false, hello=false after 2 min is "HA never connected": not the desk panel.
                        if (!seen && !key && !hello && uptime >= 2 * min) continue
                        val d = SetupState.decide(deskPanel(uptime, seen, key, hello))
                        assertFalse("uptime=$uptime seen=$seen key=$key hello=$hello: ${d.reasons}", d.show)
                    }
    }

    @Test
    fun deskPanelOnceHaSeenStaysHiddenEvenWhenHaIsDown() {
        val d = SetupState.decide(deskPanel(60 * min, haSeen = true, apiKey = false, hello = false))
        assertFalse(d.show)
        assertEquals(SetupFlow.DONE, d.step)
    }

    @Test
    fun deskPanelShortWifiDropoutIsIgnored() {
        val i = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true).copy(online = false, offlineForMs = 20_000)
        assertFalse(SetupState.decide(i).show)
        val long = i.copy(offlineForMs = 31_000)
        val d = SetupState.decide(long)
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        // Back online: the sticky screen is done and goes away.
        val back = SetupState.decide(i.copy(online = true, offlineForMs = 0, showing = true))
        assertFalse(back.show)
        assertEquals(SetupFlow.DONE, back.step)
    }

    @Test
    fun cookieSessionWithoutRefreshTokenIsNotASigninTrigger() {
        val d = SetupState.decide(deskPanel(5 * min, haSeen = true, apiKey = true, hello = true))
        assertTrue(d.signinDone)
        assertFalse(d.show)
    }

    @Test
    fun freshOfflinePanelDoesNotShowSigninAsDone() {
        // Factory-reset panel, no Wi-Fi yet: nothing is known about sign-in, so it must not be ticked.
        val d = SetupState.decide(fresh.copy(online = false, offlineForMs = 60_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertFalse(d.wifiDone)
        assertFalse(d.haDone)
        assertFalse(d.signinDone)
    }

    @Test
    fun loginPageShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(onAuthPage = true)
        val d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertEquals(listOf("sign-in needed"), d.reasons)
        // "Sign in on this panel instead": leave HA's login page alone.
        assertFalse(SetupState.decide(i.copy(usePanelLogin = true)).show)
        // A refresh token means it isn't needed.
        assertFalse(SetupState.decide(i.copy(signedIn = true)).show)
    }

    @Test
    fun signOutOnThePanelShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(signedOut = true)
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(i).step)
        assertTrue(SetupState.decide(i).show)
    }

    @Test
    fun freshPanelOffline() {
        val boot = fresh.copy(online = false, offlineForMs = 10_000)
        assertFalse("boot grace", SetupState.decide(boot).show)
        val d = SetupState.decide(boot.copy(offlineForMs = 45_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertFalse(d.wifiDone)
    }

    @Test
    fun freshPanelWalksThroughTheSteps() {
        // Online, HA not found yet: Home Assistant's first step, Sign in, with the manual URL field.
        var i = fresh
        var d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertTrue(d.wifiDone)

        // HA found by mDNS; its login page not loaded yet, within the 2 min grace: the trigger is gone but the
        // screen is already up, so it stays (no flicker) on Sign in.
        i = i.copy(haKnown = true, showing = true)
        d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertTrue(d.reasons.isEmpty())

        // HA's login page loaded: still Sign in.
        i = i.copy(onAuthPage = true)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertFalse(d.signinDone)

        // Signed in with the QR code: on to Add it, the other half of Home Assistant.
        i = i.copy(signedIn = true, onAuthPage = false)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.HA, d.step)
        assertTrue(d.signinDone)
        assertTrue(d.show)

        // HA's discovery probe says Hello: not "added" yet.
        i = i.copy(haHelloThisProcess = true)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.HA, d.step)
        assertFalse(d.haDone)

        // HA added the panel (subscribed): on to Panes, which panes are on.
        i = i.copy(haSeen = true)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.PANES, d.step)
        assertFalse(SetupFlow.PANES in d.done)

        // Continue with the radio on: its own step under Panes.
        i = i.copy(panesChosen = true)
        d = SetupState.decide(i)
        assertEquals("radio", d.step)
        assertTrue(SetupFlow.PANES in d.done)

        // The radio's mode applied and its follow-up seen: done, the screen goes.
        i = i.copy(paneSteps = radio(true))
        d = SetupState.decide(i)
        assertEquals(SetupFlow.DONE, d.step)
        assertFalse(d.show)
    }

    @Test
    fun notAddedAfterGraceShowsAddToHa() {
        val i = fresh.copy(haKnown = true)
        assertFalse(SetupState.decide(i.copy(uptimeMs = 119_000)).show)
        val d = SetupState.decide(i.copy(uptimeMs = 121_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step) // Sign in comes first
        assertEquals(SetupFlow.HA, SetupState.decide(i.copy(uptimeMs = 121_000, signedIn = true)).step)
        assertEquals(listOf("not added to home assistant"), d.reasons)
        // Any recorded evidence suppresses it (a panel with its radio step done).
        val r = i.copy(paneSteps = radio(true))
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, apiKeySet = true)).show)
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, haSeen = true)).show)
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, haHelloThisProcess = true)).show)
        // Without it, the HA step being done moves on to signing in (a pane's step still waits).
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(i.copy(uptimeMs = 121_000, haSeen = true)).step)
    }

    @Test
    fun skipCompletesTheHaStep() {
        val i = fresh.copy(haKnown = true, uptimeMs = 5 * min, showing = true, haSkipped = true)
        val d = SetupState.decide(i)
        assertTrue(d.haDone)
        assertEquals(SetupFlow.SIGNIN, d.step) // a pane's step waits: sign-in must be known first
        assertEquals(SetupFlow.PANES, SetupState.decide(i.copy(dashboardLoaded = true)).step)
        assertTrue(d.show)
        val r = i.copy(paneSteps = radio(true))
        assertFalse(SetupState.decide(r).show)
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(r.copy(onAuthPage = true)).step)
    }

    @Test
    fun stepParsing() {
        val steps = listOf("radio")
        assertEquals(SetupFlow.WIFI, SetupFlow.parse("wifi", true, steps))
        assertEquals(SetupFlow.HA, SetupFlow.parse(" HA ", true, steps))
        assertEquals(SetupFlow.SIGNIN, SetupFlow.parse("signin", true, steps))
        assertEquals(SetupFlow.PANES, SetupFlow.parse("panes", true, steps))
        assertEquals("radio", SetupFlow.parse("radio", true, steps)) // a pane's own step
        // Step order: the rail and the preview rely on it. Home Assistant (sign in, add it), then Panes
        // (choose, then the panes' steps).
        assertEquals(listOf("wifi", "signin", "ha", "panes", "radio", "done"), SetupFlow.ids(true, steps))
        assertEquals(listOf("wifi", "signin", "ha", "panes", "done"), SetupFlow.ids(true))
        assertEquals(listOf("wifi", "signin", "ha", "done"), SetupFlow.ids(false))
        assertEquals(SetupFlow.DONE, SetupFlow.parse("done", true, steps))
        assertEquals(null, SetupFlow.parse("radio", false, steps))
        assertEquals(null, SetupFlow.parse("panes", false))
        assertEquals(null, SetupFlow.parse("nope", true, steps))
        assertEquals(null, SetupFlow.parse(null, true, steps))
        // The rail's groups.
        assertEquals(listOf(null, "ha", "ha", "panes", "panes", null), SetupFlow.ids(true, steps).map(SetupFlow::group))
    }

    /** The device's own steps (#422) follow Panes, each a rail row of its own, not under Panes. */
    @Test fun theDevicesOwnStepsFollowPanesOnRowsOfTheirOwn() {
        val own = listOf("silent_updates")
        val ids = SetupFlow.ids(true, listOf("radio"), own)
        assertEquals(listOf("wifi", "signin", "ha", "panes", "radio", "silent_updates", "done"), ids)
        assertEquals(listOf(null, "ha", "ha", "panes", "panes", null, null), ids.map { SetupFlow.group(it, own) })
        assertEquals(listOf("wifi", "signin", "ha", "silent_updates", "done"), SetupFlow.ids(false, own = own))
        assertEquals("silent_updates", SetupFlow.parse("silent_updates", true, listOf("radio"), own))
    }

    @Test fun theDevicesOwnStepComesAfterEveryPaneStepAndKeepsTheScreenUp() {
        val silent = PaneStepFacts("silent_updates", false, "updates ask for a tap, and the person can change that")
        val i = fresh.copy(haKnown = true, haSeen = true, signedIn = true, ownSteps = listOf(silent))
        // Panes and the radio first: the device's step never jumps ahead of them, nor holds Panes.
        assertEquals(SetupFlow.PANES, SetupState.decide(i).step)
        val chosen = i.copy(panesChosen = true)
        assertEquals("radio", SetupState.decide(chosen).step)
        var d = SetupState.decide(chosen.copy(paneSteps = radio(true)))
        assertEquals("silent_updates", d.step)
        assertEquals(listOf(silent.reason), d.reasons)
        assertTrue(d.show)
        // A pane turned off on Panes doesn't skip it either.
        assertEquals("silent_updates", SetupState.decide(chosen.copy(paneSteps = radio(false).map { it.copy(wanted = false) })).step)
        // Answered: done, and Done is next.
        d = SetupState.decide(chosen.copy(paneSteps = radio(true), ownSteps = listOf(silent.copy(done = true)), showing = true))
        assertEquals(SetupFlow.DONE, d.step)
        assertTrue("silent_updates" in d.done)
        // Not over Home Assistant's login page while the person signs in there ("Sign in on this panel instead").
        val panelLogin = chosen.copy(signedIn = false, usePanelLogin = true, dashboardLoaded = false, paneSteps = radio(true))
        assertFalse(silent.reason in SetupState.decide(panelLogin).reasons)
        assertTrue(silent.reason in SetupState.decide(panelLogin.copy(dashboardLoaded = true)).reasons)
        // A frame with no panes at all: straight after Sign in.
        assertEquals("silent_updates", SetupState.decide(i.copy(paneSteps = emptyList())).step)
    }

    @Test
    fun paneStepsComeUnderPanesAfterSignin() {
        val two = listOf(PaneStepFacts("radio", false, "radio mode not chosen"), PaneStepFacts("camera", false, "camera not set up"))
        val i = fresh.copy(haKnown = true, haSeen = true, paneSteps = two, onAuthPage = true)
        var d = SetupState.decide(i)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertEquals(listOf("sign-in needed", "radio mode not chosen"), d.reasons)
        val signed = i.copy(signedIn = true, onAuthPage = false)
        assertEquals(SetupFlow.PANES, SetupState.decide(signed).step) // which panes are on, first
        val chosen = signed.copy(panesChosen = true)
        assertEquals("radio", SetupState.decide(chosen).step)
        d = SetupState.decide(chosen.copy(paneSteps = listOf(two[0].copy(done = true), two[1])))
        assertEquals("camera", d.step)
        assertEquals(setOf("wifi", "ha", "signin", "panes", "radio"), d.done)
        assertEquals(listOf("camera not set up"), d.reasons)
        // A pane turned off on Panes: its steps don't ask any more.
        d = SetupState.decide(chosen.copy(paneSteps = listOf(two[0].copy(done = true), two[1].copy(wanted = false)), showing = true))
        assertEquals(SetupFlow.DONE, d.step)
        assertFalse(d.show)
        // A frame with no pane steps: Wi-Fi, Home Assistant, done.
        assertEquals(SetupFlow.DONE, SetupState.decide(signed.copy(paneSteps = emptyList())).step)
    }

    // ---- radio step

    @Test
    fun anyTraceOfAnEarlierSetupCounts() {
        fun up(known: Boolean = false, seen: Boolean = false, key: Boolean = false, skipped: Boolean = false) =
            SetupState.setUpBefore(known, seen, key, skipped)
        assertFalse("fresh install", up())
        assertTrue(up(known = true))
        assertTrue(up(seen = true))
        assertTrue(up(key = true))
        assertTrue(up(skipped = true))
    }

    @Test
    fun setUpPanelNeverSeesTheRadioStep() {
        // Desk panel after the upgrade, every trigger it can hit: a long Wi-Fi dropout, a sign-out.
        val desk = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true)
        assertTrue(desk.paneSteps.single().done)
        assertFalse(SetupState.decide(desk).show)
        val dropout = SetupState.decide(desk.copy(online = false, offlineForMs = 60_000))
        assertEquals(SetupFlow.WIFI, dropout.step)
        val back = SetupState.decide(desk.copy(showing = true))
        assertFalse(back.show)
        assertEquals(SetupFlow.DONE, back.step)
        val signout = SetupState.decide(desk.copy(signedOut = true, showing = true))
        assertEquals(SetupFlow.SIGNIN, signout.step)
        assertTrue(SetupFlow.PANES in signout.done)
    }

    @Test
    fun unansweredRadioStepComesBackAfterAReboot() {
        // Fresh panel rebooted on the radio step: HA added it, nothing chosen yet, screen not up.
        val i = fresh.copy(haKnown = true, haSeen = true, uptimeMs = 3_000, dashboardLoaded = true)
        val d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.PANES, d.step)
        assertEquals(listOf("radio mode not chosen"), d.reasons)
        // Answered (persisted, or a mode chosen with HA's select: RadioDefinitionTest): gone for good.
        assertFalse(SetupState.decide(i.copy(paneSteps = radio(true))).show)
        // A mode chosen with HA's select also answers it.
    }

    @Test
    fun radioStepNeedsWifiAndHaFirst() {
        val i = fresh.copy(online = false, offlineForMs = 60_000, haKnown = true, haSeen = true)
        assertEquals(SetupFlow.WIFI, SetupState.decide(i).step)
        assertFalse("not a reason while offline", "radio mode not chosen" in SetupState.decide(i).reasons)
        assertEquals(SetupFlow.HA, SetupState.decide(fresh.copy(haKnown = true, showing = true, signedIn = true)).step)
    }

    @Test
    fun homeAssistantSignsInFirstThenWaitsForTheAdd() {
        // The Mirror on 2026-11-18: Add it stayed current after the sign-in. Sign in comes first, whichever half
        // happened first, and each half is done on its own evidence.
        val i = fresh.copy(haKnown = true, onAuthPage = true, uptimeMs = 10 * min, showing = true)
        fun at(x: SetupInputs) = SetupState.decide(x).let { Triple(it.step, it.signinDone, it.haDone) }
        assertEquals("neither", Triple(SetupFlow.SIGNIN, false, false), at(i))
        assertEquals("neither, login page still loading", Triple(SetupFlow.SIGNIN, false, false), at(i.copy(onAuthPage = false)))
        assertEquals("signed in, not added", Triple(SetupFlow.HA, true, false), at(i.copy(signedIn = true, onAuthPage = false)))
        assertEquals("added before the sign-in", Triple(SetupFlow.SIGNIN, false, true), at(i.copy(haSeen = true)))
        assertEquals("both", Triple(SetupFlow.PANES, true, true), at(i.copy(signedIn = true, onAuthPage = false, haSeen = true)))
        assertEquals("not found", Triple(SetupFlow.SIGNIN, false, false), at(i.copy(haKnown = false, onAuthPage = false)))
        // "Sign in on this panel instead" before Home Assistant added it: HA's login page stays uncovered, also after
        // the grace; once the page is left (signed in there), Add it asks.
        val panel = i.copy(usePanelLogin = true, showing = false)
        assertFalse("on HA's login page", SetupState.decide(panel).show)
        assertFalse("while that page loads", SetupState.decide(panel.copy(onAuthPage = false)).show)
        val left = SetupState.decide(panel.copy(onAuthPage = false, dashboardLoaded = true))
        assertTrue(left.show)
        assertEquals(SetupFlow.HA, left.step)
        // A session the page already has counts as signed in: on to Add it.
        assertEquals("cookie session", Triple(SetupFlow.HA, true, false), at(i.copy(onAuthPage = false, dashboardLoaded = true)))
    }

    // ---- Improv / installer Wi-Fi

    @Test
    fun improvRunningShowsTheWifiStepWithoutTheGrace() {
        val boot = fresh.copy(online = false, offlineForMs = 5_000)
        assertFalse(SetupState.decide(boot).show)
        val improv = ImprovFacts(running = true, state = dev.glassworks.improv.ImprovState.AUTHORIZATION_REQUIRED)
        val d = SetupState.decide(boot.copy(improv = improv))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertEquals(WifiStep.Phase.STARTING, d.improv.phase)
        assertEquals(WifiStep.Phase.READY, SetupState.decide(boot.copy(improv = improv.copy(advertising = true))).improv.phase)
        // Installer Wi-Fi in flight: same.
        val inst = InstallerWifi("Home", InstallerWifi.Phase.CONNECTING)
        assertTrue(SetupState.decide(boot.copy(installer = inst)).show)
        // Online: Improv running on demand doesn't bring the screen up on a set-up panel.
        val desk = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true)
        assertFalse(SetupState.decide(desk.copy(improv = improv.copy(advertising = true))).show)
    }

    @Test
    fun withoutTouchAnOfflinePanelWithNothingToJoinShowsWifiAtOnce() {
        // #448: the Mirror, its Home Assistant set by Kiln, no network saved. The loading screen's offline text
        // pointed at a button nobody can press; the Wi-Fi step says how to set it up from a computer.
        val mirror = deskPanel(5_000, haSeen = false, apiKey = false, hello = false)
            .copy(online = false, offlineForMs = 5_000, input = Input.NONE)
        val d = SetupState.decide(mirror)
        assertTrue(d.reasons.toString(), d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        // A join underway or expected (a reboot with a saved network): "Connecting to Wi-Fi…" for the grace, as before.
        assertFalse(SetupState.decide(mirror.copy(joinExpected = true)).show)
        assertTrue(SetupState.decide(mirror.copy(joinExpected = true, offlineForMs = 30_000)).show)
        // With touch, nothing changes: the grace, then the screen.
        assertFalse(SetupState.decide(mirror.copy(input = Input.TOUCH)).show)
        // Online, the rule says nothing.
        assertFalse(SetupState.decide(deskPanel(10 * min, haSeen = true, apiKey = true, hello = true).copy(input = Input.NONE)).show)
    }

    @Test
    fun withoutTouchTheWifiStepShowsBluetoothOpenByItself() {
        // #611: the Mirror, Improv authorized by itself with no network. The step's line has no Allow nor Stop.
        val open = ImprovFacts(running = true, advertising = true, state = dev.glassworks.improv.ImprovState.AUTHORIZED, authLeftMs = 299_001)
        val mirror = fresh.copy(online = false, offlineForMs = 60_000, neverSetUp = true, input = Input.NONE, improv = open)
        val d = SetupState.decide(mirror)
        assertEquals("Send Wi-Fi from a phone", d.improv.title)
        assertTrue(d.improv.actions.isEmpty())
        assertEquals("a touchscreen keeps Stop", listOf(WifiStep.Action.STOP), SetupState.decide(mirror.copy(input = Input.TOUCH)).improv.actions)
    }
}
