/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.ViewTab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A view's tab, as HA's `hui-root` (frontend 20261028.5) draws it: `show_icon_and_title && icon && title` shows both,
 * else an icon shows alone (the title as its label), else the title, or "Unnamed view".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ViewTabTest {
    private fun tab(json: String): ViewTab = LovelaceParser.parseView(3, JSONObject(json)).tab { "Unnamed view" }

    @Test
    fun aViewWithAnIconShowsTheIconAlone() {
        assertEquals(ViewTab("mdi:home", null, "Home"), tab("""{"title": "Home", "icon": "mdi:home"}"""))
        assertEquals(ViewTab("mdi:home", null, "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": false}"""))
        // An icon and no title: still the icon alone, with no label.
        assertEquals(ViewTab("mdi:home", null, null), tab("""{"icon": "mdi:home", "show_icon_and_title": true}"""))
    }

    @Test
    fun showIconAndTitleShowsBothWhenTheViewHasBoth() {
        assertEquals(ViewTab("mdi:home", "Home", "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": true}"""))
        // HA tests it with `&&`: any truthy value counts, a falsy one doesn't.
        assertEquals(ViewTab("mdi:home", "Home", "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": "yes"}"""))
        assertEquals(ViewTab("mdi:home", null, "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": 0}"""))
        assertEquals(ViewTab("mdi:home", null, "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": ""}"""))
        assertEquals(ViewTab("mdi:home", null, "Home"), tab("""{"title": "Home", "icon": "mdi:home", "show_icon_and_title": null}"""))
    }

    @Test
    fun aViewWithNoIconShowsItsTitleOrUnnamedView() {
        assertEquals(ViewTab(null, "Living", "Living"), tab("""{"title": "Living"}"""))
        assertEquals(ViewTab(null, "Living", "Living"), tab("""{"title": "Living", "show_icon_and_title": true}"""))
        // Not Vitro's own "View 4" fallback title: HA's localized "Unnamed view", with no aria-label.
        assertEquals(ViewTab(null, "Unnamed view", null), tab("""{}"""))
        assertEquals(ViewTab(null, "Unnamed view", null), tab("""{"title": "", "icon": ""}"""))
    }
}
