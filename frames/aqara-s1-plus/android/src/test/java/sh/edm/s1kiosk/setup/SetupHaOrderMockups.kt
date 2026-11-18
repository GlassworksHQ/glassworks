package sh.edm.s1kiosk.setup

import android.app.Application
import android.content.Context
import android.view.ViewGroup
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Input
import dev.glassworks.pane.PaneChoice
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.frame.TestFrames
import sh.edm.s1kiosk.ui.Mockup

/**
 * The Home Assistant step's two sub-steps in every state the panel can be in, as SetupState decides them: the
 * screen the S1 (touch, landscape) and the Mirror (no touch, portrait) show. Illustrative values only.
 */
object HaOrderCases {
    const val HA_URL = "http://homeassistant.local:8123"
    const val IP = "192.0.2.10"
    val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")

    /** Home Assistant found, on its login page, nothing done yet; a pane step waits after it. */
    private val base = SetupInputs(
        online = true, offlineForMs = 0, haKnown = true, haSeen = false, apiKeySet = false, haSkipped = false,
        haHelloThisProcess = false, uptimeMs = 10 * 60_000L, signedIn = false, onAuthPage = true, signedOut = false,
        usePanelLogin = false, showing = true, paneSteps = listOf(PaneStepFacts("radio", false, "radio mode not chosen")),
    )

    /** Name to inputs. */
    val all: List<Pair<String, SetupInputs>> = listOf(
        "neither" to base,
        "neither-page-loading" to base.copy(onAuthPage = false),
        "signed-in-not-added" to base.copy(signedIn = true, onAuthPage = false),
        "added-not-signed-in" to base.copy(haSeen = true, apiKeySet = true),
        "both" to base.copy(signedIn = true, onAuthPage = false, haSeen = true, apiKeySet = true),
        "moved" to base.copy(haSeen = true, apiKeySet = true, onAuthPage = false, signinMoved = true),
        "not-found" to base.copy(haKnown = false, onAuthPage = false),
    )

    fun inputs(i: SetupInputs, input: Input) = i.copy(input = input)

    /** The model MainActivity draws for [i]. */
    fun model(i: SetupInputs): SetupOverlay.Model {
        val d = SetupState.decide(i)
        return SetupOverlay.Model(
            step = d.step, done = d.done, wifi = online, haUrl = HA_URL.takeIf { i.haKnown }, haSource = "mdns",
            panelIp = IP, signedIn = i.signedIn, signinMoved = i.signinMoved,
        )
    }

    /** The setup screen for [i] on [frame], with a pane on Panes (so the Panes group is there). */
    fun screen(a: Context, frame: Frame, i: SetupInputs): SetupOverlay {
        val s = SetupOverlay(a, frame, listOf(SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList())))
        (a as android.app.Activity).setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(model(inputs(i, frame.input)))
        return s
    }
}

/**
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SetupHaOrderMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupHaOrderMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    @Test
    fun s1Landscape() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((name, i) in HaOrderCases.all) {
            val a = Mockup.activity()
            val s = HaOrderCases.screen(a, TestFrames.s1(), i)
            Mockup.idle()
            Mockup.save("ha-order-s1-$name", s)
        }
    }

    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirrorPortrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((name, i) in HaOrderCases.all) {
            val a = Mockup.activity()
            val s = HaOrderCases.screen(a, TestFrames.mirror(a), i)
            Mockup.idle()
            Mockup.save("ha-order-mirror-$name", s, 1080, 1920)
        }
    }
}
