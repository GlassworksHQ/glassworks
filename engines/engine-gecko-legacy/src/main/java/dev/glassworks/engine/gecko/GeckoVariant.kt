/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import dev.glassworks.engine.gecko.legacy.Lockdown

/**
 * The legacy engine (#198): engine-gecko's GeckoEngine.kt, compiled against the frozen GeckoView 144, takes this
 * file in place of engine-gecko's GeckoVariant.kt. Pages load only the panel's Home Assistant ([Lockdown]).
 */
internal object GeckoVariant {
    const val LABEL = " (legacy, Home Assistant only)"

    /** Engine.lockedDown (#29), declared here rather than read from [guard]: a guard alone doesn't mean frozen. */
    const val LOCKED_DOWN = true

    fun guard(logTag: String): PageGuard? = Lockdown(logTag)
}
