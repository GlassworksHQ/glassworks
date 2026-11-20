package dev.glassworks.setup

import dev.glassworks.s1.frame.S1Agent
import android.app.Application
import dev.glassworks.pane.SetupStep
import dev.glassworks.platform.AppBuild
import dev.glassworks.testing.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.GlassworksRuntime
import dev.glassworks.s1.KioskApp
import dev.glassworks.KioskPrefs
import dev.glassworks.s1.PanePrefs
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.radio.RadioController
import dev.glassworks.radio.TestInit
import java.io.File
import dev.glassworks.radio.runningRadio

/**
 * Setup without the screen: every pane step the S1 has (its real definitions, with a radio controller
 * on a fake init) is finished with setup channel requests alone, and the requests Kiln sends
 * (kiln/apps/kiln/src/wizard/session.ts through kiln/packages/setup-channel) still get the replies
 * channel.ts expects. The replies are pinned in kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json,
 * which setup-channel's own tests replay through channel.ts. Regenerate it with -Ps1RegenGolden=true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupChannelStepsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var runtime: GlassworksRuntime
    private lateinit var steps: List<SetupStep>

    /**
     * How each pane step is finished over the setup channel, as the installer would. A pane that adds a
     * step adds its requests here; [everyPaneStepHasAChannelPath] fails until it does.
     */
    private val viaChannel = mapOf(
        "radio" to listOf("""{"id":1,"op":"radio.set_mode","mode":"off"}"""),
    )

    /** The core's part, as the app's backend does it (KioskPrefs), without the main-thread hop. */
    private inner class Core : SetupChannel.Backend {
        val prefs = KioskPrefs(app)
        override fun state() = ChannelState(
            ip = IP,
            ha = ChannelState.Ha(
                discovered = null, url = prefs.haBase.orEmpty(), signedIn = false,
                signinUrl = ChannelState.signinUrl(IP, prefs.haBase),
            ),
        )
        override fun setHa(url: String): SetupChannel.Change {
            prefs.urlSetting = url
            return SetupChannel.Change()
        }
    }

    @Before fun s1() {
        runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
        val panePrefs = PanePrefs(app)
        runtime.runningRadio(app).controller = RadioController(
            S1Agent(TestInit()), load = { panePrefs.radio.mode }, store = { panePrefs.radio.mode = it }, inline = true, log = {}, filesDir = tmp.root,
        ).also { it.start() }
        steps = runtime.paneDefinitions(app).flatMap { it.setupSteps }
    }

    /** The channel as the app makes it: its steps, and the Panes step the panel's screen answers too. */
    private fun channel() = SetupChannel(Core(), steps, panes = runtime.panesStep(app))

    private fun reply(c: SetupChannel, line: String) = JSONObject(c.handle(line).first)

    @Test
    fun everyPaneStepHasAChannelPath() {
        assertEquals(listOf("radio"), steps.map { it.id })
        assertEquals(steps.map { it.id }.toSet(), viaChannel.keys)
        assertTrue(steps.all { it.ops.isNotEmpty() })
    }

    @Test
    fun everyPaneStepIsFinishedOverTheChannelAlone() {
        val c = channel()
        for (step in steps) {
            assertFalse("${step.id} starts unanswered on a fresh panel", step.done())
            for (line in viaChannel.getValue(step.id)) {
                val r = reply(c, line)
                assertTrue("$line: $r", r.getBoolean("ok"))
            }
            assertTrue("${step.id} is done after its requests", step.done())
        }
        // ... and the setup screen moves past them to Sign in, the step that is done in a browser.
        val d = SetupState.decide(
            SetupInputs(
                online = true, offlineForMs = 0, haKnown = true, haSeen = true, apiKeySet = true, haSkipped = false,
                haHelloThisProcess = true, uptimeMs = 5_000, signedIn = false, onAuthPage = true, signedOut = false,
                usePanelLogin = false, paneSteps = steps.map { PaneStepFacts(it.id, it.done(), it.reason) },
            ),
        )
        assertEquals(SetupFlow.SIGNIN, d.step)
    }

    @Test
    fun theCoreStepsGiveWhatTheInstallerNeeds() {
        val c = channel()
        val before = reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").getJSONObject("ha")
        assertFalse(before.has("signinUrl")) // no Home Assistant yet: nothing to sign in to
        val after = reply(c, """{"id":2,"op":"ha.set_url","url":"http://homeassistant.local:8123"}""").getJSONObject("state").getJSONObject("ha")
        assertEquals("http://homeassistant.local:8123", after.getString("url"))
        // Sign in is done in a browser at this link (Kiln opens it); Wi-Fi goes over Improv Serial.
        assertEquals("http://$IP:8099/signin", after.getString("signinUrl"))
    }

    /** What Kiln sends, in the standard flow's order: the state, the HA address, the state again, the panes, the radio role. */
    private val kiln = listOf(
        """{"id":1,"op":"state"}""",
        """{"id":2,"op":"set_ha","url":"http://homeassistant.local:8123"}""",
        """{"id":3,"op":"state"}""",
        """{"id":4,"op":"panes.set","on":"assist,radio"}""",
        """{"id":5,"op":"set_radio","mode":"zigbee_tcp"}""",
        """{"id":6,"op":"set_radio","mode":"off"}""",
    )

    @Test
    fun kilnsPanesAreThePanelsAndHomeAssistantsToo() {
        val defs = runtime.paneDefinitions(app)
        val music = defs.single { it.id == "sendspin" }.switch!!
        val voice = defs.single { it.id == "assist" }.switch!!
        music.on = true
        voice.on = false
        val c = channel()
        val cards = reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").getJSONArray("panes")
        val ids = (0 until cards.length()).map { cards.getJSONObject(it).getString("id") }
        // Every card the panel's own Panes step has, by its pane's id (Kiln finds the icon by it); none the S1 can't run.
        assertEquals(runtime.panesStep(app).panes.map { it.id }, ids)
        assertTrue(ids.containsAll(listOf("assist", "sendspin", "radio")))
        assertFalse("no camera on the S1", "camera" in ids)

        assertTrue(reply(c, """{"id":2,"op":"panes.set","on":"assist"}""").getBoolean("ok"))
        // The switches the pane's settings page, the Manage page and Home Assistant's switch read
        assertTrue(voice.on)
        assertFalse(music.on)
        assertEquals("On", voice.entity().shownState())
        assertEquals("Off", music.entity().shownState())
        // and the panel's own setup screen moves past Panes: the same answer
        assertTrue(runtime.panesStep(app).chosen)
        assertTrue(SetupOverlay(app, dev.glassworks.s1.frame.TestFrames.s1(), runtime.panesStep(app).panes, panesStep = runtime.panesStep(app)).panesChosen)
    }

    @Test
    fun kilnsRequestsStillGetTheRepliesItExpects() {
        val c = channel()
        val transcript = JSONArray()
        for (line in kiln) transcript.put(JSONObject().put("request", JSONObject(line)).put("reply", reply(c, line)))
        val radio = steps.single { it.id == "radio" }
        assertTrue("Kiln's set_radio answers the panel's radio step", radio.done())

        val file = transcriptFile()
        if (System.getProperty("s1.regenGolden") == "true") {
            file.writeText(transcript.toString(2).replace("\\/", "/") + "\n")
            return
        }
        val golden = JSONArray(file.readText())
        assertEquals(golden.length(), transcript.length())
        for (i in 0 until golden.length()) {
            assertTrue(
                "reply ${i + 1} changed: ${transcript.getJSONObject(i)} vs pinned ${golden.getJSONObject(i)}",
                same(golden.get(i), transcript.get(i)),
            )
        }
    }

    /** JSON equality that ignores key order. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
            a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun transcriptFile(): File = File(RepoFiles.root, "kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json")

    private companion object {
        const val IP = "192.0.2.10"
    }
}
