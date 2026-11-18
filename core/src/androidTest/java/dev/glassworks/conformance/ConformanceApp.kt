/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.conformance

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.TextSensorEntity
import dev.glassworks.frame.Capability
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Hardware
import dev.glassworks.frame.NamedBy
import dev.glassworks.manage.ManagePage
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import java.util.concurrent.ScheduledExecutorService
import dev.glassworks.AppParts
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.OverlayHost
import dev.glassworks.PaneOverlay
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.DeviceHost
import dev.glassworks.update.ReleaseLine

/**
 * An app on the core with nothing real but Android (#23): a fake frame, a fake engine and one fake pane of
 * its own. The conformance test boots it on emulators (core-emulators.yml) to show the core runs on any
 * Android 8+ device, not only on the S1.
 */
class ConformanceApp : Application(), GlassworksApp {
    override val glassworks: GlassworksRuntime by lazy {
        GlassworksRuntime(FakeFrame, AppBuild("1.0.0", 10000, debug = true, product = "Conformance"), BlankEngine, FakeParts(),
            releases = ReleaseLine("conformance", "conformance-", plainTags = false) { "conformance-${it.id}" })
    }
}

/** A device with a speaker and fake sensors the test drives. No radio, so a radio pane is hidden. */
object FakeFrame : Frame {
    override val board = "conformance"
    override val namedBy = NamedBy.MODEL
    override val hardware = Hardware("Glassworks", "Conformance Tablet", "fake")
    override val provides = setOf(Capability.SPEAKER)
    val presenceSource = FakePresence()
    val lightSource = FakeLight()
    override val presence: PresenceSource = presenceSource
    override val light: LightSource = lightSource
    override fun systemSettingsIntents() = listOf(Intent(android.provider.Settings.ACTION_SETTINGS))
    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()
}

class FakePresence : PresenceSource {
    var onReading: ((Boolean, Float) -> Unit)? = null
    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) { this.onReading = onReading }
    override fun stop(context: Context) { onReading = null }
}

class FakeLight : LightSource {
    var onLux: ((Float) -> Unit)? = null
    override fun start(context: Context, onLux: (Float) -> Unit) { this.onLux = onLux }
    override fun stop(context: Context) { onLux = null }
}

/** An engine whose page shows nothing and loads nothing, so MainActivity runs here without GeckoView (#198). */
object BlankEngine : Engine {
    override val started: EngineSettings? = null
    override fun open(activity: Activity, host: PageHost, background: Int): Page = object : Page {
        override val view = android.view.View(activity).apply { setBackgroundColor(background) }
        override fun load(url: String) {}
        override fun reload() {}
        override fun goBack() {}
        override fun restart() {}
        override fun post(message: org.json.JSONObject) {}
    }
    override fun isContentProcess(processName: String) = false
}

/** A pane that needs only a speaker, with one entity. */
class SpeakerDefinition : PaneDefinition {
    override val id = "speaker-pane"
    override val requires = setOf(Capability.SPEAKER)
}

/** A pane that needs a radio the frame doesn't have: hidden everywhere. */
class RadioDefinition : PaneDefinition {
    override val id = "radio-pane"
    override val requires = setOf(Capability.SILABS_RADIO)
}

class SpeakerPane : Pane {
    override val id = "speaker-pane"
    var started = false
    override fun entities(): List<ApiEntity> = listOf(TextSensorEntity("speaker_state", "Speaker state") { if (started) "running" else "stopped" })
    override fun start() { started = true }
    override fun stop() { started = false }
}

class FakeParts : AppParts {
    override fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>) =
        listOf(SpeakerDefinition(), RadioDefinition()) + frameDefinitions

    override fun devicePanes(runtime: GlassworksRuntime, host: DeviceHost): List<Pane> =
        listOf(SpeakerPane()).filter { it.id !in host.cantRun }

    override fun overlays(runtime: GlassworksRuntime, host: OverlayHost) = emptyList<PaneOverlay>()
    override val components = dev.glassworks.AppComponents(
        dev.glassworks.MainActivity::class.java, dev.glassworks.KioskService::class.java,
        dev.glassworks.AdminReceiver::class.java, dev.glassworks.update.UpdateInstallReceiver::class.java,
    )
    override fun paneFile(context: Context, namespace: String): SharedPreferences = context.getSharedPreferences("conformance", Context.MODE_PRIVATE)
    override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        error("no Manage page in the conformance test")
}
