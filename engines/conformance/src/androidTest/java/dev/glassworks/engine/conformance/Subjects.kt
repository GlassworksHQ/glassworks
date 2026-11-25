/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import android.app.Activity
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.vitro.VitroEngine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an engine declares about itself, where engines legitimately differ. The contract checks each against what the
 * engine does: a declared difference is tested, never skipped.
 */
data class Capabilities(
    /** [Engine.lockedDown]: pages load only the dashboard's origin (the legacy engine, #198). */
    val lockedDown: Boolean,
    /** Renders web content in processes of its own, which [Engine.isContentProcess] names. */
    val contentProcesses: Boolean,
    /** Opens a fallback engine's page for what it can't draw itself (Vitro: every non-dashboard page). */
    val fallback: Boolean,
)

/** An engine under test, built as an app builds it at start, against [ha]. */
class Subject(val engine: Engine, val capabilities: Capabilities, val fallback: CountingEngine?) {
    /** Stops what outlives a page (Vitro's island server). The process ends after each test anyway. */
    fun close() {
        (engine as? VitroEngine)?.islandServer?.stop()
    }
}

/** Wraps a fallback engine to count the pages opened through it, so a test sees where a load went. */
class CountingEngine(private val inner: Engine) : Engine by inner {
    val pages = CopyOnWriteArrayList<Page>()

    override fun open(activity: Activity, host: PageHost, background: Int): Page =
        inner.open(activity, host, background).also { pages += it }
}

object Subjects {
    /** True when this run tests the legacy GeckoView (API 23 and up), false for the current one (API 26 and up). */
    val legacyGecko: Boolean get() = BuildConfig.GECKO == "legacy"

    /** The dashboard every test opens: the default dashboard's first view. */
    fun dashboard(ha: FakeHomeAssistant) = "${ha.origin}/lovelace/home"
    fun secondView(ha: FakeHomeAssistant) = "${ha.origin}/lovelace/second"

    /** A page no dashboard engine draws: Home Assistant's sign-in, where a panel goes first. */
    fun signIn(ha: FakeHomeAssistant) = "${ha.origin}/auth/authorize?response_type=code&client_id=http%3A%2F%2F127.0.0.1%2F"

    private fun settings(dashboard: String, localOrigin: String? = null) = EngineSettings(
        secureHosts = listOf("127.0.0.1"), zoom = 0f, remoteDebugging = false, dashboard = dashboard, localOrigin = localOrigin,
    )

    /** The Gecko engine of this run, with the kiosk's helper extension, as the apps build it. */
    private fun gecko(settings: () -> EngineSettings) = GeckoEngine(
        settings = { settings() },
        extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "glassworks"),
        logTag = "EngineContract",
        generatedBy = "the engine contract test",
    )

    private val geckoCapabilities get() = Capabilities(lockedDown = legacyGecko, contentProcesses = true, fallback = false)

    /** GeckoView alone: what the apps run with Vitro off. */
    fun gecko(ha: FakeHomeAssistant) = Subject(gecko { settings(dashboard(ha)) }, geckoCapabilities, fallback = null)

    /**
     * Vitro over this run's Gecko engine, as the apps build it with Vitro on (GenericApp.geckoSettings): both keep the
     * real Home Assistant, and a locked-down Gecko also gets the island server's loopback origin as its one more
     * ([EngineSettings.localOrigin]). [enabled] false is Vitro off at start: the plain Gecko engine's settings.
     */
    fun vitro(ha: FakeHomeAssistant, enabled: Boolean = true): Subject {
        var vitro: VitroEngine? = null
        val gecko = CountingEngine(gecko {
            settings(dashboard(ha), localOrigin = if (legacyGecko && enabled) vitro!!.islandOrigin() else null)
        })
        vitro = VitroEngine(
            fallback = gecko,
            settings = { settings(dashboard(ha)) },
            enabled = { enabled },
            tokenProvider = { _, done -> done(FakeHomeAssistant.TOKEN) },
        )
        // Vitro draws dashboards in the app's own process; its fallback's pages render in the fallback's processes.
        return Subject(vitro, geckoCapabilities.copy(fallback = true), gecko)
    }
}
