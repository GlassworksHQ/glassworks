/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.view.View
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.EntityRowView

/**
 * The words [v] itself shows, for tests that read a card's texts: a TextView's text, or the name and state an
 * entities row draws without TextViews ([EntityRowView]). Empty for any other view.
 */
internal fun drawnWords(v: View): List<String> = when (v) {
    is TextView -> listOf(v.text.toString())
    is EntityRowView -> listOfNotNull(v.nameText.toString(), v.stateText?.toString())
    else -> emptyList()
}
