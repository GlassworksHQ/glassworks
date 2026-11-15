/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.brand.Tokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A status chip looks the same wherever it sits (2026-11-15): on the Overview's Panes card, `.row span` beat
 * `.chip`, so the chip lost its inline-flex and gap, and its dot touched its word ("●Running"). A small
 * cascade over [ManagePages.MANAGE_CSS] (specificity, then order) for each place a chip is drawn, at desktop
 * and phone width.
 */
class ManageCssTest {

    @Test
    fun chipKeepsItsOwnStyleEverywhere() {
        val want = mapOf(
            "display" to "inline-flex",
            "gap" to "${Tokens.Chip.GAP}px",
            "font-size" to "${Tokens.Chip.TEXT}px",
            "color" to "var(--muted)",
        )
        val chip = El("span", "chip")
        // Ancestors from the outside in, as ManagePages draws them.
        val places = mapOf(
            "Overview pane row" to listOf(El("section", "card", "list"), El("a", "row"), chip),
            "blueprint row" to listOf(El("div", "row", "bp"), El("div", "act"), chip),
            "nav item" to listOf(El("nav"), El("a", "ni"), chip),
            "pane heading" to listOf(El("div", "phr"), El("span", "pchip"), chip),
        )
        val all = rules(ManagePages.MANAGE_CSS)
        for (phone in listOf(false, true)) {
            val rules = all.filter { phone || it.media?.contains("max-width") != true }
            for ((place, path) in places) for ((prop, value) in want) {
                assertEquals("$place, ${if (phone) "phone" else "desktop"}: $prop", value, computed(rules, path, prop))
            }
        }
    }

    /** The cascade keeps up with the stylesheet: a row's text still gets its own look. */
    @Test
    fun rowTextStillStyled() {
        val rules = rules(ManagePages.MANAGE_CSS).filter { it.media == null }
        val detail = listOf(El("a", "row"), El("div"), El("span"))
        assertEquals("block", computed(rules, detail, "display"))
        assertEquals("15px", computed(rules, detail, "font-size"))
    }

    private class El(val tag: String, vararg classes: String) {
        val classes = classes.toSet()
    }

    private class Rule(val selector: String, val decls: Map<String, String>, val media: String?, val order: Int)

    /** The winning value of [prop] on the last element of [path]: !important, then specificity, then order. */
    private fun computed(rules: List<Rule>, path: List<El>, prop: String): String? = rules
        .filter { prop in it.decls && matches(it.selector, path) }
        .maxWithOrNull(compareBy<Rule>({ it.decls[prop]!!.endsWith("!important") }, { specificity(it.selector) }, { it.order }))
        ?.decls?.get(prop)?.removeSuffix("!important")

    private fun rules(css: String, media: String? = null, out: MutableList<Rule> = mutableListOf()): List<Rule> {
        var i = 0
        while (true) {
            val open = css.indexOf('{', i).takeIf { it >= 0 } ?: break
            val head = css.substring(i, open).trim()
            if (head.startsWith("@media")) {
                var depth = 1
                var j = open + 1
                while (depth > 0) { if (css[j] == '{') depth++ else if (css[j] == '}') depth--; j++ }
                rules(css.substring(open + 1, j - 1), head, out)
                i = j
            } else {
                val close = css.indexOf('}', open)
                val decls = css.substring(open + 1, close).split(';').filter { ':' in it }
                    .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
                for (sel in head.split(',')) out += Rule(sel.trim(), decls, media, out.size)
                i = close + 1
            }
        }
        return out
    }

    private fun parts(selector: String) = selector.replace(">", " > ").trim().split(Regex("\\s+"))

    private fun matches(selector: String, path: List<El>): Boolean {
        val p = parts(selector)
        return compound(p.last(), path.last()) && ancestors(p.dropLast(1), path.dropLast(1))
    }

    /** The rest of the selector, right to left, against the element's ancestors (innermost last). */
    private fun ancestors(sel: List<String>, up: List<El>): Boolean {
        if (sel.isEmpty()) return true
        if (sel.last() == ">") {
            val parent = up.lastOrNull() ?: return false
            return compound(sel[sel.size - 2], parent) && ancestors(sel.dropLast(2), up.dropLast(1))
        }
        return up.indices.reversed().any { k -> compound(sel.last(), up[k]) && ancestors(sel.dropLast(1), up.subList(0, k)) }
    }

    private val pseudo = Regex(""":([\w-]+)(?:\(([^)]*)\))?""")

    private fun compound(c: String, el: El): Boolean {
        if ("::" in c || '[' in c) return false
        val base = c.replace(pseudo, "")
        val tag = base.substringBefore('.')
        if (tag.isNotEmpty() && tag != "*" && tag != el.tag) return false
        if (!base.split('.').drop(1).all { it in el.classes }) return false
        return pseudo.findAll(c).all { m ->
            when (m.groupValues[1]) {
                "not" -> !compound(m.groupValues[2], el)
                "first-child", "last-child" -> true // the chip's siblings don't matter here: may match
                else -> false // :hover, :focus-visible, :disabled: not the resting look
            }
        }
    }

    /** (classes and pseudo-classes, tags), ids unused. */
    private fun specificity(selector: String): Int = parts(selector).filter { it != ">" }.sumOf { c ->
        val not = pseudo.findAll(c).filter { it.groupValues[1] == "not" }.sumOf { specificity(it.groupValues[2]) }
        val other = pseudo.findAll(c).count { it.groupValues[1] != "not" }
        val base = c.replace(pseudo, "")
        val tag = if (base.substringBefore('.').let { it.isNotEmpty() && it != "*" }) 1 else 0
        not + (base.count { it == '.' } + other) * 100 + tag
    }
}
