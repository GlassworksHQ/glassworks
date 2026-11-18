/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

/**
 * The current engine: no [PageGuard], so pages load anything, as before #198. The legacy engine
 * (engines/engine-gecko-legacy) compiles this module's GeckoEngine.kt with its own GeckoVariant.kt instead.
 */
internal object GeckoVariant {
    /** Shown after the engine's version on the Manage page. */
    const val LABEL = ""

    /** Engine.lockedDown: only the legacy engine says it is frozen and held to Home Assistant (#29). */
    const val LOCKED_DOWN = false

    fun guard(logTag: String): PageGuard? = null
}
