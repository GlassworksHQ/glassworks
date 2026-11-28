/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.ViewConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A view's `visible`, read as HA's `hui-root` reads it outside edit mode: `_isTabHiddenForUser` (no tab) and
 * `_isVisible` (which view a dashboard with no view path opens on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ViewVisibilityTest {
    private fun view(visible: String?): ViewConfig =
        LovelaceParser.parseView(0, JSONObject(if (visible == null) "{}" else """{"visible": $visible}"""))

    @Test
    fun aTabIsHiddenForVisibleFalseOrAListWithoutTheUser() {
        assertFalse(view(null).tabHiddenFor("u1"))
        assertFalse(view("true").tabHiddenFor("u1"))
        assertTrue(view("false").tabHiddenFor("u1"))
        assertTrue(view("[]").tabHiddenFor("u1"))
        assertTrue(view("""[{"user": "u2"}]""").tabHiddenFor("u1"))
        assertFalse(view("""[{"user": "u2"}, {"user": "u1"}]""").tabHiddenFor("u1"))
        // Any other value hides no tab (HA checks for a list or false only).
        assertFalse(view("\"u1\"").tabHiddenFor("u1"))
        assertFalse(view("null").tabHiddenFor("u1"))
    }

    @Test
    fun aViewIsVisibleWithNoVisibleTrueOrAListWithTheUser() {
        assertTrue(view(null).visibleTo("u1"))
        assertTrue(view("true").visibleTo("u1"))
        assertFalse(view("false").visibleTo("u1"))
        assertFalse(view("[]").visibleTo("u1"))
        assertFalse(view("""[{"user": "u2"}]""").visibleTo("u1"))
        assertTrue(view("""[{"user": "u2"}, {"user": "u1"}]""").visibleTo("u1"))
        // `visible: null` is defined, and neither true nor a list.
        assertFalse(view("null").visibleTo("u1"))
        // `e.user === hass.user?.id` is strict: an entry with no user matches only while no user is known.
        assertFalse(view("""[{}]""").visibleTo("u1"))
        assertTrue(view("""[{}]""").visibleTo(null))
        assertFalse(view("""[{"user": null}]""").visibleTo(null))
    }

    @Test
    fun noViewPathOpensTheFirstViewVisibleToTheUser() {
        val dash = LovelaceParser.parseDashboard(
            JSONObject(
                """{"views": [
                  {"path": "secret", "visible": false},
                  {"path": "theirs", "visible": [{"user": "u2"}]},
                  {"path": "home"},
                  {"path": "mine", "visible": [{"user": "u1"}]}
                ]}""",
            ),
        )
        assertEquals(2, dash.viewIndex(null, "u1"))
        assertEquals(1, dash.viewIndex("", "u2"))
        // A path opens its view whether or not it's visible.
        assertEquals(0, dash.viewIndex("secret", "u1"))
        assertEquals(1, dash.viewIndex("1", "u1"))
        // None visible: the first view (HA's findIndex finds none).
        val hidden = LovelaceParser.parseDashboard(JSONObject("""{"views": [{"visible": false}, {"visible": []}]}"""))
        assertEquals(0, hidden.viewIndex(null, "u1"))
    }
}
