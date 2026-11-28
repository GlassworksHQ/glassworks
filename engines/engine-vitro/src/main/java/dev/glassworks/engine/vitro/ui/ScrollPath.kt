/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

/**
 * The scroll path's two switches, on in every build: what made the S1's heavy view scroll at about 46 fps with a p95
 * frame of 21 to 26 ms, against 43 fps and 36 to 39 ms before. Tests turn them off to compare with the drawing before
 * them; a build that needs one gone (a device where either misbehaves) changes it here.
 */
internal object ScrollPath {
    /**
     * Masonry columns draw only the cards in a band around the viewport ([ViewportCull]). False: every card is drawn,
     * as before. Read when a view is built.
     */
    @Volatile
    @JvmField
    var cull: Boolean = true

    /**
     * A card clips its content to its rounded outline only while some of it reaches the corners ([OutlineClip]).
     * False: every rounded card clips, as before.
     */
    @Volatile
    @JvmField
    var contentClip: Boolean = true
}
