/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.manage.ManageFixture
import dev.glassworks.net.WifiChange
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.ui.Mockup
import dev.glassworks.web.HttpResponse
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Network page's Wi-Fi row (#660) on the panel at 1440x720, and as the Manage page's HTML (render it at desktop and
 * phone width): the network now, the sheet, a change under way, back on the old network after a wrong password, and a
 * device that can't change Wi-Fi from the app. Illustrative network names only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*WifiChangeMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiChangeMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val app = RuntimeEnvironment.getApplication()
    private val home = WifiChange.Network("Brightside Home", 3)

    private class Radio(val home: WifiChange.Network) : WifiChange.Radio {
        var block: WifiChange.Block? = null
        var joined: ((WifiChange.Joined) -> Unit)? = null
        override fun block() = block
        override fun current() = home
        override fun isSaved(name: String) = false
        override fun nearby() = listOf("Garden Shed", "Brightside Guest", "Studio 5G", "Neighbor's Wi-Fi")
        override fun join(name: String, password: String, done: (WifiChange.Joined) -> Unit) { joined = done }
        override fun cancel() {}
        override fun goBack(to: WifiChange.Network, leave: String, forget: Boolean, done: (Boolean) -> Unit) = done(true)
        override fun reaches(done: (Boolean) -> Unit) = done(false)
        override fun armHotspot() {}
    }

    private val radio = Radio(home)
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val change = WifiChange(radio, object : WifiChange.Store {
        override fun load(): WifiChange.Pending? = null
        override fun save(p: WifiChange.Pending?) = true
    }, clock = { 0L }, schedule = { ms, run -> val t = ms to run; timers += t; { timers.remove(t) } })

    private fun model() = MenuFixture.model(app, radioDefinition(app, FixedReadout(null, running = false)), wifi = WifiSettings.item(change) { true })

    private fun panel(name: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.reload = ::model
        o.show(model())
        o.previewSection(CoreSettings.NETWORK)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    private val manage = ManageFixture().also { f -> f.statusFn = { f.status.copy(sections = model().sections) } }
    private val browser = manage.Browser().also { it.signInWithHa() }

    private fun html(name: String, r: HttpResponse) {
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "$name.html").writeBytes(r.body)
    }

    private fun both(name: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        panel(name, then)
        html(name, browser.get("/network"))
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        KioskPrefs(app).urlSetting = "http://homeassistant.local:8123/lovelace/panel"
        both("wifi-01-network")
        panel("wifi-02-sheet") { it.previewEdit(WifiSettings.TITLE) }
        val csrf = browser.csrf()
        html("wifi-02-sheet", browser.post("/settings", mapOf("csrf" to csrf, "section" to CoreSettings.NETWORK, "key" to WifiSettings.TITLE)))

        change.start("Garden Shed", "illustrative")
        timers.filter { it.first < 60_000 }.forEach { timers.remove(it); it.second() }
        panel("wifi-03-joining")
        html("wifi-03-joining", browser.get("/network?done=wifi"))

        radio.joined!!(WifiChange.Joined.Failed(WifiChange.Why.PASSWORD))
        both("wifi-04-back-on-old-network")

        radio.block = WifiChange.Block.SUGGESTIONS_ONLY
        both("wifi-05-cant-change-here")
    }
}
