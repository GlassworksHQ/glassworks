/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import dev.glassworks.engine.CardInBrowser
import dev.glassworks.engine.Drawing
import dev.glassworks.engine.ViewDrawing

/** A made-up dashboard as Vitro draws it, for the Labs mock-ups on the panel and on the Manage page. Illustrative values only. */
object VitroDrawingMockup {
    val home = ViewDrawing(
        "Home",
        islands = listOf(
            CardInBrowser("home › section 2 › card 3 (custom:mushroom-media-player-card)", "the `layout: horizontal` option isn't drawn natively yet"),
            CardInBrowser("home › section 4 › card 1 (custom:clock-weather-card)", "`custom:clock-weather-card` isn't a card Vitro draws"),
        ),
    )
    val drawing = Drawing(home) {
        listOf(home, ViewDrawing("Rooms"), ViewDrawing("Energy", frontend = "the built-in `energy-date-selection` card"), ViewDrawing("Cameras"))
    }
}
