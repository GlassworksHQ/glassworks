package dev.glassworks.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.improv.ImprovState
import dev.glassworks.setup.WifiStep.Action
import dev.glassworks.setup.WifiStep.Phase

class WifiStepTest {

    private val on = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)

    @Test
    fun offOffersToStart() {
        val l = WifiStep.improv(ImprovFacts())
        assertEquals(Phase.OFF, l.phase)
        assertEquals("Bluetooth setup is off", l.title)
        assertEquals("Then Home Assistant can add the panel.", l.detail)
        assertEquals(listOf(Action.START), l.actions)
    }

    @Test
    fun startFailureShowsTheReason() {
        val l = WifiStep.improv(ImprovFacts(startError = "bluetoothLeAdvertiser is null (no LE peripheral mode)"))
        assertEquals(Phase.UNAVAILABLE, l.phase)
        assertEquals(WifiStep.Tone.ROSE, l.tone)
        assertTrue(l.detail, l.detail.startsWith("BluetoothLeAdvertiser is null (no LE peripheral mode)."))
        assertEquals(listOf(Action.RETRY), l.actions)
    }

    @Test
    fun startingWhileBluetoothComesUp() {
        // The pending start (Bluetooth turning on): a session, no advertisement yet.
        val l = WifiStep.improv(on.copy(advertising = false))
        assertEquals(Phase.STARTING, l.phase)
        assertEquals(listOf(Action.NOT_NOW), l.actions)
    }

    @Test
    fun readySaysToAddItInHomeAssistantThenAllow() {
        // How Home Assistant sends it (its app or its settings) is the card's line above the tile (#468).
        val l = WifiStep.improv(on)
        assertEquals(Phase.READY, l.phase)
        assertEquals("Bluetooth setup is on", l.title)
        assertEquals("Add the panel in Home Assistant, then tap Allow here.", l.detail)
        assertEquals(listOf(Action.ALLOW, Action.NOT_NOW), l.actions)
    }

    @Test
    fun connectedClientAsksForPermission() {
        val l = WifiStep.improv(on.copy(clients = 1))
        assertEquals(Phase.ASKING, l.phase)
        assertEquals("A device wants to send Wi-Fi", l.title) // any BLE central: HA, a proxy, improv-wifi.com
        assertEquals(listOf(Action.ALLOW, Action.NOT_NOW), l.actions)
    }

    @Test
    fun authorizedCountsDown() {
        val l = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, authLeftMs = 119_001))
        assertEquals(Phase.AUTHORIZED, l.phase)
        assertTrue(l.label, l.label.endsWith("allowed for 2:00"))
        assertEquals(listOf(Action.STOP), l.actions)
        val approve = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, needsApproval = true))
        assertTrue(approve.detail.contains("notification"))
    }

    @Test
    fun connectingAndConnected() {
        val c = WifiStep.improv(on.copy(state = ImprovState.PROVISIONING, ssid = "Home"))
        assertEquals(Phase.CONNECTING, c.phase)
        assertEquals("Connecting to Home…", c.title)
        assertTrue(c.actions.isEmpty())
        assertEquals("Turning Wi-Fi on…", WifiStep.improv(on.copy(state = ImprovState.PROVISIONING, progress = "Turning Wi-Fi on…")).detail)
        val ok = WifiStep.improv(on.copy(state = ImprovState.PROVISIONED, ssid = "Home"))
        assertEquals(Phase.CONNECTED, ok.phase)
        assertEquals("Connected to Home", ok.title)
        assertEquals(WifiStep.Tone.GREEN, ok.tone)
    }

    @Test
    fun failedConnectShowsTheReasonAndStaysAllowed() {
        val l = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, ssid = "Home", failure = "wrong password (authentication failed)"))
        assertEquals(Phase.FAILED, l.phase)
        assertEquals("Couldn't connect to Home", l.title)
        assertTrue(l.detail.startsWith("Wrong password (authentication failed)."))
        assertEquals(WifiStep.Tone.ROSE, l.tone)
    }

    @Test
    fun clock() {
        assertEquals("0:00", WifiStep.clock(-5))
        assertEquals("0:01", WifiStep.clock(1))
        assertEquals("1:05", WifiStep.clock(65_000))
    }

    @Test
    fun aRadioThatDidntComeBackIsNotThePasswordsFault() {
        val radio = WifiStep.improv(ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZED,
            ssid = "Example-WiFi", failure = dev.glassworks.improv.WifiProvisioner.RADIO_DIDNT_COME_BACK))
        assertTrue(radio.detail, "didn't come back" in radio.detail && "password" !in radio.detail)
        val wrong = WifiStep.improv(ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZED,
            ssid = "Example-WiFi", failure = "Wrong password"))
        assertTrue(wrong.detail, "Check the password" in wrong.detail)
    }

    @Test
    fun anUnusablePasswordSaysWhatToDoOnce() {
        val line = WifiStep.improv(ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZED,
            ssid = "Example-WiFi", failure = dev.glassworks.improv.WifiProvisioner.UNUSABLE_PASSWORD))
        assertEquals(line.detail, 1, Regex("send it again").findAll(line.detail).count())
        assertTrue(line.detail, "Check the password" !in line.detail)
    }

    @Test
    fun theFailureTextAddsPasswordAdviceOnlyWhenTheReasonHasNone() {
        val unusable = WifiStep.failureText(dev.glassworks.improv.WifiProvisioner.UNUSABLE_PASSWORD)
        assertEquals(unusable, 1, Regex("send it again").findAll(unusable).count())
        assertTrue(WifiStep.failureText(dev.glassworks.improv.WifiProvisioner.RADIO_DIDNT_COME_BACK).let { "password" !in it })
        assertEquals("Wrong password. Check the password and send it again.", WifiStep.failureText("wrong password"))
    }

    /**
     * The Improv overlay (ImprovController) and the setup step say a failed join through [WifiStep.failureText], so the
     * doubled "send it again" (#418 verifier) can't come back in one of them: no other source writes the advice itself.
     */
    @Test
    fun onlyTheFailureTextWritesThePasswordAdvice() {
        val code = dev.glassworks.testing.RepoFiles.code(atLeast = 200)
        val writers = code.filter { "Check the password and send it again" in it.readText() }.map { it.name }
        assertEquals(listOf("WifiStep.kt"), writers)
        val overlay = code.single { it.name == "ImprovController.kt" }.readText()
        assertTrue("the Improv overlay uses it", "WifiStep.failureText(fail)" in overlay)
    }
}
