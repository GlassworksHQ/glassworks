/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import org.mozilla.geckoview.GeckoRuntime

/**
 * What a page may load, for an engine that can't be trusted with the open web: the frozen GeckoView of the
 * legacy engine (engines/engine-gecko-legacy, #198) loads only the panel's Home Assistant. [GeckoVariant] picks
 * it. The current engine has none, and its pages load as they always did.
 */
internal interface PageGuard {
    /** Gecko prefs the guard adds to the generated config. */
    val prefs: Map<String, Any>

    /** Called once, when the runtime exists. */
    fun attach(runtime: GeckoRuntime)

    /**
     * The engine starts with the configured [dashboard] URL ([dev.glassworks.engine.EngineSettings.dashboard]): its
     * origin is the one pages may use, for the life of the process. [local] is the app's own loopback server
     * ([dev.glassworks.engine.EngineSettings.localOrigin]), whose pages may load too, held to their own origin.
     * Called once, before any load.
     */
    fun start(dashboard: String?, local: String? = null)

    /**
     * Runs [load] once the guard is in force: at once if it is, else when it is (never, if it can't be). If it
     * can't be, or is slow to, [stuck] says why in words for the screen.
     */
    fun whenReady(load: () -> Unit, stuck: (String) -> Unit)

    /** Words for the screen when the app's own load of [url] is refused and the page shows nothing else. */
    fun refusedAppLoad(url: String): String

    /** Whether [url] may load as [kind]. The guard notes a refusal on the page and in the log. */
    fun allows(kind: Load, url: String): Boolean

    enum class Load {
        /** A top-level load in the page's own window. */
        PAGE,

        /** A document in an iframe. */
        FRAME,

        /** `window.open` or `target=_blank`. */
        WINDOW,

        /** A response Gecko can't show: a download. */
        DOWNLOAD,
    }
}
