/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.MdSheet
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** What a view parses once and reuses: the same answers as parsing again, from a cache or a faster check. */
class ParseOnceTest {
    private val entityPattern = Regex("""^[a-z][a-z0-9_]*\.[a-z0-9_]+$""")

    @Test
    fun entityIdCheckIsThePattern() {
        val samples = listOf(
            "light.kitchen", "sensor.a_1", "a.b", "light.", ".light", "light", "Light.kitchen", "light.Kitchen",
            "light.kit.chen", "1light.x", "_light.x", "light.kitchen ", " light.kitchen", "light.kitchen\n",
            "{{ states('light.kitchen') }}", "http://x.y", "light-kitchen.x", "", "a.", "z9_.0",
        )
        for (s in samples) assertEquals("'$s'", entityPattern.matches(s), CardRouter.isEntityId(s))
    }

    @Test
    fun cardModParsesOncePerTextAndTheme() {
        val vars = mapOf("primary-color" to "#03a9f4")
        val css = "ha-card { background: var(--primary-color); padding: 12px; border: 1px solid red; }"
        val a = ThemeResolver.parseCardMod(css, vars)
        assertSame(a, ThemeResolver.parseCardMod(css, vars))
        // Another theme map is parsed again, even with equal contents (keys are by identity), with the same answer.
        val b = ThemeResolver.parseCardMod(css, HashMap(vars))
        assertNotSame(a, b)
        assertEquals(a, b)
    }

    @Test
    fun sheetsParseOncePerText() {
        val css = "ha-markdown-element h1 { font-size: 56px }"
        assertSame(MdSheet.parse(css), MdSheet.parse(css))
    }
}
