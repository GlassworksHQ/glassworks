/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.dock.DockRow
import dev.glassworks.settings.VitroDrawingRows
import dev.glassworks.pane.BLUEPRINTS
import dev.glassworks.pane.BlueprintAction
import dev.glassworks.pane.BlueprintSurface
import dev.glassworks.pane.NOT_RUNNING
import dev.glassworks.pane.ChipTone
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.chipTone
import dev.glassworks.pane.chipWord
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.StatesShown
import dev.glassworks.pane.shown
import dev.glassworks.brand.Tokens
import dev.glassworks.theme.Themes
import dev.glassworks.web.PageShell
import dev.glassworks.web.PageShell.Look
import dev.glassworks.web.PageShell.esc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Manage page's HTML (#33's approved design), on the shell the QR pages use ([PageShell]). No
 * scripts: the nav folds into a `<details>` menu on a phone, filters are links, actions are forms. All panels
 * ([panels]) is the one exception: its list and copy come from other panels, in the browser (#466).
 * Everything from outside (names, log lines, settings values) is escaped here.
 */
class ManagePages(private val zone: TimeZone = TimeZone.getDefault(), private val clock: () -> Long = System::currentTimeMillis) {

    /** Where a page sits in the nav: a fixed page or a settings section / pane by id. */
    data class Place(val path: String, val title: String)

    /**
     * The signed-in browser, for the nav's foot and the forms' CSRF field. [homeAssistant]: it signed in with
     * Home Assistant (a blueprints card says what the panel's code can't do).
     */
    class Viewer(val who: String, val csrf: String, val homeAssistant: Boolean = true)

    // ------------------------------------------------------------------ sign in

    /**
     * "Manage this panel." One form: HA's button posts to `/manage/ha`, the code link to `/manage/code`, so
     * the remember switch counts for both. [haOk]: HA's address is known and local, so HA's button shows.
     */
    fun signIn(name: String, next: String, haOk: Boolean, problem: String? = null): String {
        val remember = """<div class="rem"><label class="sw"><input type="checkbox" name="remember" value="1" checked> Remember this browser for 30 days</label></div>"""
        val form = """<form method="post" action="/manage/ha"><input type="hidden" name="next" value="${esc(next)}">""" +
            (if (haOk) """<button class="btn" type="submit">Continue with Home Assistant <span aria-hidden="true">↗</span></button>$remember
<div class="rule"></div><p class="alt">Home Assistant down? <button class="lk" type="submit" formaction="/manage/code">Show a code on the panel</button></p>"""
            else """<button class="btn" type="submit" formaction="/manage/code">Show a code on the panel</button>$remember""") + "</form>"
        val text = when {
            problem != null -> esc(problem)
            haOk -> "Sign in with a Home Assistant administrator."
            else -> "Use the code the panel shows. The panel has no Home Assistant on your network to sign in with."
        }
        return PageShell.qrPage(name, "Manage this panel", text, if (problem != null) Look.PROBLEM else Look.START, button = form, extraCss = SIGN_IN_CSS)
    }

    /** The code dialog over [behind] (#33's drawing 07): six boxes, Cancel and the action. */
    fun codeDialog(name: String, title: String, text: String, action: String, actionLabel: String, cancel: String,
                   hidden: Map<String, String>, problem: String?, danger: Boolean, behindHtml: String = ""): String {
        val fields = hidden.entries.joinToString("") { (k, v) -> """<input type="hidden" name="${esc(k)}" value="${esc(v)}">""" }
        val body = """<div class="dim">$behindHtml</div><div class="scrim"><form class="dlg" method="post" action="${esc(action)}">$fields
<h2>${esc(title)}</h2><p>${esc(text)}</p>
<div class="code"><span></span><span></span><span></span><span></span><span></span><span></span>
<input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required autofocus aria-label="The 6-digit code"></div>
${if (problem != null) """<p class="err">${esc(problem)}</p>""" else ""}
<div class="dbtns"><a class="pill" href="${esc(cancel)}">Cancel</a><button class="pill${if (danger) " danger" else " primary"}" type="submit">${esc(actionLabel)}</button></div></form></div>"""
        return PageShell.document(name, MANAGE_CSS + DIALOG_CSS, body)
    }

    /** A page that only loads [to] from here, so a link from another site (HA's "Visit") arrives with the SameSite=Strict cookie. */
    fun bounce(name: String, to: String): String =
        PageShell.document(name, "", """<meta http-equiv="refresh" content="0;url=${esc(to)}"><noscript></noscript>""")

    // ------------------------------------------------------------------ the signed-in frame

    private fun nav(s: PanelStatus, current: String, viewer: Viewer): String {
        fun item(path: String, title: String, chip: String = "", grey: Boolean = false) =
            """<a class="ni${if (path == current) " on" else ""}${if (grey) " grey" else ""}" href="${esc(path)}"${if (path == current) " aria-current=\"page\"" else ""}><span class="nt">${esc(title)}</span>$chip</a>"""
        fun pane(p: SettingsNav.Item) = item("/" + p.id, p.title, p.state?.let(::navDot).orEmpty(), grey = p.state?.kind == PaneState.Kind.CANT_RUN)
        // The panel's sidebar (settingsNav), with the Security page this surface adds; each pane's status dot at
        // the row's end, as on the panel (maintainer 2026-11-15: one look).
        val n = settingsNav(s.sections, s.panes, OWN_PAGES)
        val panes = n.panes.joinToString("") { pane(it) } + if (n.off.isEmpty()) "" else
            """<details class="off"${if (n.off.any { "/" + it.id == current }) " open" else ""}><summary>Off · ${n.off.size}</summary>${n.off.joinToString("") { pane(it) }}</details>"""
        return """<div class="brand">${PageShell.mark(Look.DONE, "bm")}<div><b>${esc(s.name)}</b><small>${esc(if (s.name == "Glassworks Panel") s.frame else "Glassworks Panel · ${s.frame}")}</small></div></div>
<nav>${item("/", "Overview")}${item(PANELS, "All panels")}""" +
            (if (n.panel.isNotEmpty()) """<h3>Panel</h3>${n.panel.joinToString("") { item("/" + it.id, it.title) }}""" else "") +
            (if (n.panes.isNotEmpty() || n.off.isNotEmpty()) "<h3>Panes</h3>$panes" else "") +
            """<h3>System</h3>${n.system.joinToString("") { item("/" + it.id, it.title) }}</nav>
${foot(viewer)}"""
    }

    /** The sidebar's foot, where the panel has Done: who is signed in, and Sign out. */
    private fun foot(viewer: Viewer): String {
        val who = if (viewer.homeAssistant) viewer.who else SIGNED_IN_WITH_CODE
        val line = if (viewer.homeAssistant) "Signed in · $who" else who
        return """<form class="who" method="post" action="/manage/signout">${csrf(viewer)}<span><i class="dot ok"></i><span class="nm" title="${esc(who)}">${esc(line)}</span></span><button type="submit">Sign out</button></form>"""
    }

    /** The page: the nav, the phone's header, [main], and [overlay] over it all (a dialog). */
    private fun frame(s: PanelStatus, current: String, title: String, viewer: Viewer, main: String, notice: String? = null, overlay: String = "", head: String = "", css: String = ""): String {
        val n = nav(s, current, viewer)
        val note = notice?.let { """<div class="notice" role="status"><i class="dot ok"></i>${esc(it)}</div>""" }.orEmpty()
        // Under a dialog the page is inert: Tab stays in the dialog, as in the panel's sheet.
        val inert = if (overlay.isEmpty()) "" else " inert"
        val body = """<aside class="side"$inert>$n</aside>
<header class="top"$inert>${PageShell.mark(Look.DONE, "m")}<span>${esc(if (current == "/") s.name else title)}</span><details class="menu"><summary aria-label="Menu"><span></span><span></span></summary><div class="drop">$n</div></details></header>
<main class="main"$inert>$note$main</main>$overlay"""
        return PageShell.document("$title · ${s.name}", MANAGE_CSS + DIALOG_CSS + css, body, head = head)
    }

    private fun csrf(v: Viewer) = """<input type="hidden" name="csrf" value="${esc(v.csrf)}">"""

    private fun action(v: Viewer, path: String, label: String, cls: String = "pill") =
        """<form method="post" action="${esc(path)}">${csrf(v)}<button class="$cls" type="submit">${esc(label)}</button></form>"""

    /** A pane's dot in the nav, in its status colour, as the panel's sidebar draws it; its word is for screen readers. */
    private fun navDot(state: PaneState): String {
        val label = state.chipWord ?: return ""
        return """<i class="dot ${tone(state)}" role="img" aria-label="status ${esc(label)}"></i>"""
    }

    private fun tone(state: PaneState) = when (state.chipTone) { ChipTone.OK -> "ok"; ChipTone.PROBLEM -> "bad"; ChipTone.QUIET -> "off" }

    /** The pane's chip: its word ([chipWord]) and dot ([chipTone]), the same as the panel's rail (#243). */
    private fun chip(state: PaneState): String {
        val label = state.chipWord ?: return ""
        val tone = tone(state)
        return """<span class="chip" aria-label="status ${esc(label)}"><i class="dot $tone"></i>${esc(label)}</span>"""
    }

    // ------------------------------------------------------------------ pages

    fun overview(s: PanelStatus, v: Viewer, actions: PanelActions, notice: String?): String {
        val tiles = s.tiles.joinToString("") { t ->
            val dot = t.tone?.let { """<i class="dot ${toneClass(it)}"></i>""" }.orEmpty()
            val inner = """<small>${esc(t.label)}</small><b>$dot${esc(t.value)}</b><span>${esc(t.detail)}</span>"""
            if (t.href != null) """<a class="tile" href="${esc(t.href)}">$inner</a>""" else """<div class="tile">$inner</div>"""
        }
        val panes = s.panes.joinToString("") { p ->
            """<a class="row" href="/${esc(p.id)}"><div><b>${esc(p.title)}</b><span>${esc(p.state.detail)}</span></div>${chip(p.state)}<i class="chev" aria-hidden="true">›</i></a>"""
        }
        val buttons = OverviewAction.entries.joinToString("") { a ->
            action(v, "/action/${a.key}", a.label) + if (a == OverviewAction.RESTART && actions.canReboot) action(v, "/action/reboot", "Reboot") else ""
        }
        val main = """<section class="card hero">${PageShell.mark(Look.DONE, "hm")}<div class="ht"><h1><i class="dot ok"></i>Running</h1>
<p class="meta${if (s.legacy) " lgm" else ""}">Glassworks ${esc(s.version)} · up ${esc(duration(s.uptimeMs))}${if (s.legacy) LEGACY_CHIP else ""}</p></div><div class="acts">$buttons</div></section>
<section class="tiles">$tiles</section>""" +
            (if (s.panes.isNotEmpty()) """<section class="card list"><div class="lh"><b>Panes</b><span>${esc(panesSummary(s.panes))}</span></div>$panes</section>""" else "")
        return frame(s, "/", "Overview", v, main, notice)
    }

    /**
     * A settings page, drawn as the panel's menu draws it (#242): the heading with "Panes ›" for a pane,
     * its on/off at top right, why it can't run, then its items, each changeable here as on the panel.
     * A pane's [state] puts its chip after the heading on a phone, where the nav is folded away.
     * [dialog]: an item's edit or a question over the page. [problem]: what went wrong with the last change, a
     * link to the item at [problemSee] when it says where to fix it ([SettingsItem.Switch.see]).
     * [restart]: a change waits for the app to start again.
     */
    fun section(s: PanelStatus, v: Viewer, path: String, title: String, state: PaneState?, sec: SettingsSection?,
                links: List<Pair<String, String>> = emptyList(), dialog: Dialog? = null, problem: String? = null, restart: Boolean = false,
                notice: String? = null, problemSee: String? = null, paused: Boolean = false): String {
        val heading = sec?.heading ?: title
        val why = sec?.let { runCatching { it.unavailable() }.getOrNull() }
        val master = sec?.master?.takeIf { why == null }?.let { m -> toggle(v, sec.id, SettingsChange.MASTER, m, heading) }.orEmpty()
        val head = """<div class="phr">${if (state != null) """<span class="crumb">Panes ›</span>""" else ""}<h1 class="ph">${esc(heading)}</h1>""" +
            """${state?.let { """<span class="pchip">${chip(it)}</span>""" }.orEmpty()}$master</div>"""
        // A page whose rows change by themselves (the dock row) reloads itself: the page has no scripts. Never under a
        // dialog, and the person can pause it (WCAG 2.2.1: a reload restarts a screen reader).
        // Labs follows the view on screen while Vitro draws it (VitroDrawingRows).
        val follows = when {
            sec == null -> null
            DockRow.shownIn(sec.items) -> LIVE_DOCK
            VitroDrawingRows.shownIn(sec.items) -> LIVE_DASHBOARD
            else -> null
        }
        val live = follows != null
        val lines = listOfNotNull(
            why?.let { """<p class="sub">${esc("This pane can't run on this panel: $it.")}</p>""" },
            if (follows == null) null else if (paused) """<p class="sub">${esc(livePaused(follows))} <a href="${esc(path)}">Resume</a></p>"""
                else """<p class="sub">${esc(liveOn(follows))} <a href="${esc(path)}?$LIVE_PARAM=off">Pause</a></p>""",
            problem?.let { """<p class="err" role="alert">${see(it, problemSee)}</p>""" },
            if (restart) """<div class="banner"><i class="dot work"></i><span>${esc(SettingsChange.RESTART_TEXT)}</span>${action(v, "/action/restart", "Restart now", "pill primary")}</div>""" else null,
        ).joinToString("")
        val card = if (sec == null) """<section class="card list pg"><div class="row static note"><span>${esc(NOT_RUNNING)}</span></div></section>"""
            else """<section class="card list pg">${items(v, path, sec.id, sec.items)}</section>"""
        val off = state?.kind == PaneState.Kind.OFF
        val more = if (off || links.isEmpty()) "" else """<section class="card list">${links.joinToString("") { (label, href) ->
            """<a class="row" href="${esc(href)}"><div><b>${esc(label)}</b></div><i class="chev" aria-hidden="true">›</i></a>""" }}</section>"""
        val overlay = if (dialog != null && sec != null) dialog(v, path, sec, dialog) else ""
        val reload = if (live && !paused && overlay.isEmpty()) """<meta http-equiv="refresh" content="$LIVE_RELOAD_S;url=${esc(path)}">""" else ""
        return frame(s, path, title, v, head + lines + card + more, notice, overlay, reload)
    }

    /** A question or an item's edit over a settings page, as the panel's sheet ([Dialog]). */
    sealed interface Dialog {
        val key: String

        /**
         * Change the item at [key]; [problem]: why the last try didn't save, and [typed]: what was sent then,
         * shown again.
         */
        class Edit(override val key: String, val problem: String? = null, val typed: String? = null) : Dialog

        /**
         * Ask [ask] before setting the switch at [key] to [value] (`on`/`off`), picking the choice's [value], or
         * running the action there ([SettingsItem.Action.RUN]). [answer]: the one-shot token the form sends back.
         */
        class Confirm(override val key: String, val value: String, val ask: SettingsItem.Confirm, val answer: String) : Dialog
    }

    private fun fields(v: Viewer, section: String, key: String) =
        csrf(v) + """<input type="hidden" name="section" value="${esc(section)}"><input type="hidden" name="key" value="${esc(key)}">"""

    /** The on/off: a switch that posts the other value. */
    private fun toggle(v: Viewer, section: String, key: String, item: SettingsItem.Switch, label: String): String {
        val on = runCatching { item.value() }.getOrDefault(false)
        return """<form class="tgf" method="post" action="/settings">${fields(v, section, key)}<button class="tg${if (on) " on" else ""}" type="submit" name="value" value="${if (on) "off" else "on"}" role="switch" aria-checked="$on" aria-label="${esc(label)}"><i></i></button></form>"""
    }

    private fun items(v: Viewer, path: String, section: String, items: List<SettingsItem>, prefix: String = ""): String = items.joinToString("") { item ->
        val key = prefix + SettingsChange.titleOf(item)
        fun edit(title: String, value: String, dot: Int? = null) =
            """<a class="row" id="${anchor(key)}" href="${esc(path)}?edit=${esc(enc(key))}"><div><b>${esc(title)}</b><span>""" +
                dot?.let { """<i class="dot" style="background:${css(it)}" aria-hidden="true"></i>""" }.orEmpty() +
                """${esc(value)}</span></div><i class="chev" aria-hidden="true">›</i></a>"""
        when (item) {
            is SettingsItem.Text -> if (SettingsChange.canChange(item)) {
                val shown = safe { SettingsChange.shown(item) }
                // A color shows itself next to its value.
                edit(item.title, shown, if (item.kind == SettingsItem.TextKind.COLOR) Themes.colour(shown) else null)
            }
                else row(item.title, safe { SettingsChange.shown(item) }, dim = true)
            is SettingsItem.Switch -> """<div class="row" id="${anchor(key)}"><div><b>${esc(item.title)}</b>${if (item.detail.isNotEmpty()) "<span>${see(item.detail, item.see)}</span>" else ""}</div>${toggle(v, section, key, item, item.title)}</div>"""
            is SettingsItem.Slider -> {
                val now = runCatching { item.value() }.getOrDefault(item.min)
                """<form class="row sl" method="post" action="/settings">${fields(v, section, key)}<div class="slh"><b>${esc(item.title)}</b><em>${esc(safe { item.shown(now) })}</em></div>""" +
                    """<div class="slr"><input type="range" name="value" min="${num(item.min)}" max="${num(item.max)}" step="${num(item.step)}" value="${num(now)}" aria-label="${esc(item.title)}"><button class="pill sm" type="submit">Save</button></div></form>"""
            }
            is SettingsItem.Duration -> edit(item.title, safe { item.shown(item.value()) })
            is SettingsItem.TimeRange -> edit(item.title, safe { item.shown(item.value()) })
            is SettingsItem.Choice -> item.swatch?.let { sw -> themes(v, section, key, item, sw) }
                ?: item.cards?.let { lines -> cards(v, section, key, item, lines) } ?: edit(item.title, safe { SettingsChange.shown(item) })
            is SettingsItem.SignIn -> edit(item.title, safe { item.shown(item.isSet()) })
            // As the panel: a line that says what's missing and can be set is amber (fixable).
            is SettingsItem.Info -> row(safe { item.title() }, safe { item.detail() }, fix = runCatching { item.fixable() }.getOrDefault(false))
            is SettingsItem.Note -> """<div class="row static note"><span>${esc(safe { item.text() })}</span></div>"""
            // Another section's page opens here as on the panel; what the link opens, its twin under its title (#518).
            // Only another app (Android's settings, the licences) is on the panel alone: say so, rather than drop the row.
            is SettingsItem.Link -> item.section?.let { id ->
                """<a class="row" href="/${esc(id)}"><div><b>${esc(item.title)}</b>${if (item.detail.isNotEmpty()) "<span>${esc(item.detail)}</span>" else ""}</div><i class="chev" aria-hidden="true">›</i></a>"""
            } ?: SettingsChange.twinOf(item).takeIf { it.isNotEmpty() }?.let { group(v, path, section, item.title, it, prefix) }
                ?: row(item.title, item.detail, right = ON_THE_PANEL, dim = item.detail.isEmpty())
            is SettingsItem.Group -> group(v, path, section, item.title, item.items, prefix)
            // A view only the panel draws: its typed twin's rows here (#269).
            is SettingsItem.Native -> items(v, path, section, SettingsChange.twinOf(item), prefix)
            // A button on its row; one that asks first on the panel asks here too (ManageServer).
            is SettingsItem.Action -> {
                val detail = safe { item.detail() }
                """<form class="row" method="post" action="/settings">${fields(v, section, key)}<div><b>${esc(item.title)}</b>${if (detail.isNotEmpty()) "<span>${esc(detail)}</span>" else ""}</div>""" +
                    """<button class="pill sm" type="submit" name="value" value="${SettingsItem.Action.RUN}">${esc(item.label)}</button></form>"""
            }
            // As the panel: green while it works, rose for a problem the person can fix, else quiet.
            is SettingsItem.Status -> {
                val tone = when {
                    runCatching { item.problem() }.getOrDefault(false) -> "bad"
                    runCatching { item.ok() }.getOrDefault(false) -> "ok"
                    else -> "off"
                }
                """<div class="st${if (tone == "bad") " bad" else ""}"><i class="dot $tone"></i><span>${esc(safe { item.text() })}</span></div>"""
            }
            // As the panel: without Home Assistant, a line says so; not connected, every row is dimmed (#261).
            is SettingsItem.States -> {
                val now = runCatching { item.shown() }.getOrDefault(StatesShown(null, emptyList(), dimmed = false))
                val list = now.rows
                """<div class="grp"><div class="gl">${esc(item.title)}</div>""" +
                    (now.note?.let { """<div class="row static note"><span>${esc(it)}</span></div>""" } ?: "") +
                    list.joinToString("") { r ->
                        """<div class="sr${if (r.offByDefault || now.dimmed) " quiet" else ""}"><span>${esc(r.name)}</span><em>${esc(r.value)}</em></div>""" } +
                    (if (!now.dimmed && list.any { it.offByDefault }) """<p class="foot">${esc(SettingsChange.OFF_BY_DEFAULT)}</p>""" else "") + "</div>"
            }
            is SettingsItem.Tiles -> tiles(item)
            is SettingsItem.Blueprints -> blueprints(v, item)
        }
    }

    /** [list] under [title], their keys under it too ([SettingsChange.changeable]). */
    private fun group(v: Viewer, path: String, section: String, title: String, list: List<SettingsItem>, prefix: String) =
        """<div class="grp"><div class="gl">${esc(title)}</div>${items(v, path, section, list, "$prefix$title/")}</div>"""

    /** A choice drawn as cards (#117's approved look): each card a button that posts its option, the picked one lit. */
    private fun cards(v: Viewer, section: String, key: String, item: SettingsItem.Choice, lines: (String) -> String): String {
        val now = safe { item.value() }
        val buttons = runCatching { item.options() }.getOrDefault(emptyList()).joinToString("") { (id, name) ->
            val on = id == now
            """<button class="uc${if (on) " on" else ""}" type="submit" name="value" value="${esc(id)}" aria-pressed="$on">""" +
                """<b>${esc(name)}</b><span>${esc(safe { lines(id) })}</span></button>"""
        }
        return """<form class="ucs" id="${anchor(key)}" method="post" action="/settings">${fields(v, section, key)}<div class="gl">${esc(item.title)}</div>""" +
            """<div class="ucg">$buttons</div></form>"""
    }

    /**
     * A choice with swatches as the theme picker (#691), as the panel draws it: one card per theme with its light and
     * its dark orb, each orb a button that posts its option; the picked orb ringed with a sun or moon badge.
     */
    private fun themes(v: Viewer, section: String, key: String, item: SettingsItem.Choice, swatch: (String) -> SettingsItem.Swatch): String {
        val now = safe { item.value() }
        val groups = LinkedHashMap<String, MutableList<Pair<String, SettingsItem.Swatch>>>()
        for ((id, _) in runCatching { item.options() }.getOrDefault(emptyList())) runCatching { swatch(id) }.getOrNull()?.let { groups.getOrPut(it.group) { ArrayList() } += id to it }
        val cards = groups.entries.joinToString("") { (name, orbs) ->
            val on = orbs.any { it.first == now }
            val buttons = orbs.joinToString("") { (id, s) ->
                val picked = id == now
                // Home Assistant's theme is light or dark as HA is: its badge is half sun, half moon.
                val words = if (s.auto) "$name, light or dark as Home Assistant is" else "$name, ${if (s.dark) "dark" else "light"}"
                """<button class="sw${if (s.dark) " dk" else ""}${if (picked) " on" else ""}" type="submit" name="value" value="${esc(id)}" aria-pressed="$picked" """ +
                    """aria-label="${esc(words)}" title="${esc(words)}" style="--a:${css(s.a)};--b:${css(s.b)};--g:${css(s.ground)}">""" +
                    (if (picked) """<i class="bd ${when { s.auto -> "auto"; s.dark -> "moon"; else -> "sun" }}" aria-hidden="true"></i>""" else "") + "</button>"
            }
            val note = orbs.first().second.note
            """<div class="tc${if (on) " on" else ""}"><div class="pair">$buttons</div><b>${esc(name)}</b>${if (note.isNotEmpty()) "<span>${esc(note)}</span>" else ""}</div>"""
        }
        return """<form class="ths" id="${anchor(key)}" method="post" action="/settings">${fields(v, section, key)}<div class="thg">$cards</div></form>"""
    }

    /** A colour as CSS (#rrggbb). */
    private fun css(argb: Int): String = String.format(Locale.ROOT, "#%06x", argb and 0xffffff)

    /** [text], as a link to the item at [key] on the same page when there is one. */
    private fun see(text: String, key: String?): String =
        key?.let { """<a class="see" href="#${anchor(it)}">${esc(text)}</a>""" } ?: esc(text)

    /** An item's place on its page, from its key ([SettingsChange.titleOf], a group's title first). */
    private fun anchor(key: String) = "i-" + key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * A pane's blueprints, as the panel's card: each button is a link to Home Assistant's own page in a new
     * tab, for both sign-ins, so the Manage page keeps no Home Assistant token and Home Assistant checks who
     * opens it. With the panel's code, a line says so. "In Home Assistant" comes from the panel's own check.
     */
    private fun blueprints(v: Viewer, item: SettingsItem.Blueprints): String {
        val card = runCatching { item.card(BlueprintSurface.MANAGE) }.getOrNull() ?: return ""
        val note = card.note ?: if (!v.homeAssistant) BLUEPRINTS_CODE else null
        fun link(label: String, url: String) = """<a class="pill sm" href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(label)}</a>"""
        val rows = card.rows.joinToString("") { (bp, a) ->
            val act = when (a) {
                is BlueprintAction.Add -> link("Add in Home Assistant ↗", a.url)
                // blueprintCard gives a QR code on the panel only; the Manage page always links.
                is BlueprintAction.Phone -> error("no Phone action on the Manage page")
                is BlueprintAction.CreateAutomation -> """<span class="chip"><i class="dot ok"></i>In Home Assistant</span>""" + link("Create automation ↗", a.url)
                BlueprintAction.Off -> """<button class="pill sm" type="button" disabled>Add in Home Assistant</button>"""
            }
            """<div class="row bp"><div><b>${esc(bp.name)}</b><span>${esc(bp.summary)}</span></div><div class="act">$act</div></div>"""
        }
        return """<div class="grp"><div class="gl">${esc(BLUEPRINTS)}</div>""" +
            note?.let { """<div class="st"><i class="dot off"></i><span>${esc(it)}</span></div>""" }.orEmpty() + rows + "</div>"
    }

    /** Readings as tiles, [SettingsItem.Tiles.columns] to a row, each with its last hour or how full it is. */
    private fun tiles(item: SettingsItem.Tiles): String {
        val list = runCatching { item.tiles() }.getOrDefault(emptyList())
        return """<div class="ptl" style="--cols:${item.columns.coerceIn(1, 6)}">""" + list.joinToString("") { t ->
            val unit = if (t.unit.isEmpty()) "" else """<u>${esc(t.unit)}</u>"""
            val meter = t.meter
            val under = when {
                meter != null -> """<i class="mt"><i style="width:${(meter.coerceIn(0f, 1f) * 100).toInt()}%"></i></i>"""
                (t.history?.size ?: 0) >= 2 -> spark(t.history!!)
                else -> ""
            }
            """<div class="pt"><small>${esc(t.name)}</small><b>${esc(t.value)}$unit</b>${if (t.detail.isNotEmpty()) "<span>${esc(t.detail)}</span>" else ""}$under</div>"""
        } + "</div>"
    }

    /** The last hour as a line, oldest first. */
    private fun spark(h: List<Float>): String {
        val lo = h.min(); val hi = h.max(); val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pts = h.mapIndexed { i, y -> "${num(i * 100f / (h.size - 1))},${num(22f - (y - lo) / span * 20f)}" }.joinToString(" ")
        return """<svg class="sp" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true"><polyline points="$pts"/></svg>"""
    }

    /** The edit for [d.key] in [sec], or the question before a switch: the panel's sheet as a dialog. */
    private fun dialog(v: Viewer, path: String, sec: SettingsSection, d: Dialog): String {
        val item = SettingsChange.find(sec, d.key) ?: return ""
        fun sheet(title: String, hint: String, body: String, action: String?, problem: String?, more: String = "") =
            """<div class="scrim"><form class="dlg" method="post" action="/settings" role="dialog" aria-modal="true" aria-labelledby="dlg-t">${fields(v, sec.id, d.key)}""" +
                // Enter submits the form's first button: make that the action, not Show or Clear.
                (if (action != null) """<button class="dflt" type="submit" tabindex="-1" aria-hidden="true">${esc(action)}</button>""" else "") +
                """<h2 id="dlg-t">${esc(title)}</h2>""" +
                (if (hint.isNotEmpty()) "<p>${esc(hint)}</p>" else "") + body + (problem?.let { """<p class="err" role="alert">${esc(it)}</p>""" }.orEmpty()) +
                """<div class="dbtns"><a class="pill" href="${esc(path)}">Cancel</a>$more${action?.let { """<button class="pill primary" type="submit">${esc(it)}</button>""" }.orEmpty()}</div></form></div>"""
        fun chips(now: String, options: List<Pair<String, String>>) = """<div class="pcs">""" + options.joinToString("") { (value, name) ->
            """<button class="pc${if (value == now) " on" else ""}" type="submit" name="value" value="${esc(value)}"${if (value == now) " aria-current=\"true\" autofocus" else ""}>${esc(name)}</button>"""
        } + "</div>"
        return when (d) {
            is Dialog.Confirm -> sheet(d.ask.title, d.ask.detail,
                """<input type="hidden" name="value" value="${esc(d.value)}"><input type="hidden" name="answer" value="${esc(d.answer)}">""", d.ask.action, null)
            is Dialog.Edit -> when (item) {
                // A color: the browser's color picker, offering the same swatches as the panel's sheet.
                is SettingsItem.Text -> if (item.kind == SettingsItem.TextKind.COLOR) {
                    val now = Themes.colour(d.typed ?: safe { item.value() })?.let(Themes::hex) ?: Themes.hex(Themes.GLASSWORKS.dark.a)
                    val list = runCatching { item.suggestions() }.getOrDefault(emptyList()).mapNotNull { Themes.colour(it)?.let(Themes::hex) }
                    sheet(item.title, "", """<div class="cpk"><input type="color" name="value" value="$now" list="cpk-s" autofocus aria-label="${esc(item.title)}">""" +
                        """<datalist id="cpk-s">${list.joinToString("") { """<option value="$it"></option>""" }}</datalist></div>""", "Save", d.problem)
                } else {
                    val mode = when (item.kind) { SettingsItem.TextKind.NUMBER -> "numeric"; SettingsItem.TextKind.DECIMAL -> "decimal"; SettingsItem.TextKind.ADDRESS -> "url"; else -> "text" }
                    sheet(item.title, item.hint, """<input class="fld" name="value" value="${esc(d.typed ?: safe { item.value() })}" placeholder="${esc(item.hint)}" inputmode="$mode" autocomplete="off" spellcheck="false" autofocus aria-label="${esc(item.title)}">""",
                        "Save", d.problem)
                }
                is SettingsItem.Duration -> {
                    val now = runCatching { item.value() }.getOrDefault(0)
                    val all = (item.choices + now).distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
                    sheet(item.title, "", chips(now.toString(), all.map { it.toString() to safe { item.shown(it) }.removePrefix("After ") }), null, d.problem)
                }
                // The stored password never comes back: the field starts empty and takes a new one.
                is SettingsItem.SignIn -> {
                    val set = runCatching { item.isSet() }.getOrDefault(false)
                    sheet(item.title, "",
                        """<div class="flds"><input class="fld" name="user" value="${esc(safe { item.user() })}" placeholder="Username" autocomplete="username" autocapitalize="none" spellcheck="false" autofocus aria-label="Username">""" +
                            """<input class="fld" type="password" name="password" value="" placeholder="${if (set) SettingsChange.NEW_PASSWORD else "Password"}" autocomplete="new-password" aria-label="Password"></div>""",
                        "Save", d.problem, more = if (set) """<button class="pill" type="submit" name="clear" value="1">Clear</button>""" else "")
                }
                is SettingsItem.Choice -> item.group?.let { group -> sheet(item.title, "", select(item, group), "Save", d.problem) }
                    ?: sheet(item.title, "", chips(safe { item.value() }, runCatching { item.options() }.getOrDefault(emptyList())), null, d.problem)
                is SettingsItem.TimeRange -> {
                    val now = safe { item.value() }
                    val (from, to) = SettingsChange.parseRange(now) ?: (22 * 60 to 7 * 60)
                    fun time(name: String, label: String, min: Int) =
                        """<label class="tm"><span>$label</span><input type="time" name="$name" value="${SettingsChange.hhmm(min)}" step="${item.stepMin * 60}" required></label>"""
                    sheet(item.title, "", """<label class="sw"><input type="checkbox" name="on" value="1"${if (now.isNotBlank()) " checked" else ""} autofocus> On</label>""" +
                        """<div class="tms">${time("from", "From", from)}${time("to", "To", to)}</div>""", "Save", d.problem)
                }
                else -> ""
            }
        }
    }

    /**
     * A long choice as one `<select>`, its options under their groups (the time zone's regions), the picked
     * one selected. With none picked (a zone the list doesn't have), a first line says to pick, so Save
     * never sends the list's first option by accident.
     */
    private fun select(item: SettingsItem.Choice, group: (String) -> String): String {
        val now = safe { item.value() }
        val options = runCatching { item.options() }.getOrDefault(emptyList())
        val groups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (o in options) groups.getOrPut(safe { group(o.first) }) { ArrayList() } += o
        val none = if (options.none { it.first == now }) """<option value="" disabled selected>${esc(PICK_ONE)}</option>""" else ""
        return """<select class="fld" name="value" required autofocus aria-label="${esc(item.title)}">$none""" + groups.entries.joinToString("") { (g, list) ->
            """<optgroup label="${esc(g)}">""" + list.joinToString("") { (id, name) ->
                """<option value="${esc(id)}"${if (id == now) " selected" else ""}>${esc(name)}</option>"""
            } + "</optgroup>"
        } + "</select>"
    }

    private fun safe(f: () -> Any?): String = try { f()?.toString().orEmpty() } catch (_: Throwable) { "" }

    private fun num(f: Float): String = SettingsChange.plain(f)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** A name with its value under it, as the panel's rows; [right]: a word at the row's end. */
    private fun row(title: String, value: String, right: String? = null, dim: Boolean = false, fix: Boolean = false) =
        """<div class="row static${if (dim) " quiet" else ""}"><div><b>${esc(title)}</b>${if (value.isNotEmpty()) "<span${if (fix) " class=\"fix\"" else ""}>${esc(value)}</span>" else ""}</div>${right?.let { "<em>${esc(it)}</em>" }.orEmpty()}</div>"""

    /** The core's own section with [id] (Log's, About's) as a card, or nothing. */
    private fun ownCard(v: Viewer, s: PanelStatus, id: String): String =
        s.sections.firstOrNull { it.id == id }?.let { """<section class="card list">${items(v, "/$id", it.id, it.items)}</section>""" }.orEmpty()

    fun log(s: PanelStatus, v: Viewer, entries: List<LogEntry>, level: LogEntry.Level, search: String): String {
        fun chipLink(l: LogEntry.Level, label: String): String {
            val q = buildList { if (l != LogEntry.Level.INFO) add("level=" + l.name.lowercase()); if (search.isNotEmpty()) add("q=" + java.net.URLEncoder.encode(search, "UTF-8")) }
            return """<a class="fc${if (l == level) " on" else ""}" href="/log${if (q.isEmpty()) "" else "?" + q.joinToString("&")}">$label</a>"""
        }
        val rows = if (entries.isEmpty()) """<p class="empty">Nothing here${if (search.isNotEmpty() || level != LogEntry.Level.INFO) " that matches" else " yet"}.</p>"""
        else entries.joinToString("") { e ->
            val cls = when (e.level) { LogEntry.Level.WARNING -> "w"; LogEntry.Level.ERROR -> "e"; else -> "" }
            """<div class="lr"><time>${esc(time(e.timeMs))}</time><b class="$cls">${esc(e.source)}</b><span>${esc(e.text)}</span></div>"""
        }
        val main = """<div class="lt"><div><h1 class="ph">Log</h1><p class="sub">Kept across reboots, the last 7 days.</p></div>
<div class="dl"><a class="pill" href="/log/diagnostics.txt" download>Download diagnostics</a><small>Tokens and keys are removed</small></div></div>
<div class="filters">${chipLink(LogEntry.Level.INFO, "All")}${chipLink(LogEntry.Level.WARNING, "Warnings")}${chipLink(LogEntry.Level.ERROR, "Errors")}
<form class="search" method="get" action="/log">${if (level != LogEntry.Level.INFO) """<input type="hidden" name="level" value="${level.name.lowercase()}">""" else ""}<input type="search" name="q" value="${esc(search)}" placeholder="Search" aria-label="Search the log"></form></div>
${ownCard(v, s, "log")}<section class="card logs">$rows</section>"""
        return frame(s, "/log", "Log", v, main)
    }

    /**
     * The signed-in browsers, then the core's Security section (#33's drawing 07: setup over the network, the factory reset),
     * then [more]: a frame's own cards, such as "Allow a new computer". [dialog] and [problem] as on a settings page ([section]).
     */
    fun security(s: PanelStatus, v: Viewer, sessions: List<Session>, mine: String, notice: String?, more: String = "",
                 dialog: Dialog? = null, problem: String? = null): String {
        val rows = sessions.joinToString("") { x ->
            val me = x.id == mine
            val detail = if (x.remember) "Remembered until ${day(x.expiresMs)}" else "Last used ${ago(x.lastUsedMs)}"
            val who = when (x.with) { SignedInWith.HOME_ASSISTANT -> x.user?.let { " · $it" }.orEmpty(); SignedInWith.PANEL_CODE -> " · with the panel's code" }
            """<div class="row static"><div><b>${esc(x.browser)}${if (me) """ · <span class="me">this browser</span>""" else ""}</b><span>${esc(detail + who)}</span></div>
<form method="post" action="/security/signout">${csrf(v)}<input type="hidden" name="id" value="${esc(x.id)}"><button class="pill" type="submit">Sign out</button></form></div>"""
        }
        val err = problem?.let { """<p class="err" role="alert">${esc(it)}</p>""" }.orEmpty()
        val main = """<h1 class="ph">Security</h1><p class="sub">Who can manage this panel, and the actions that need you there.</p>$err
<section class="card list"><div class="lh"><b>Signed-in browsers</b></div>$rows</section>${ownCard(v, s, SECURITY)}$more"""
        val sec = s.sections.firstOrNull { it.id == SECURITY }
        val overlay = if (dialog != null && sec != null) dialog(v, "/$SECURITY", sec, dialog) else ""
        return frame(s, "/$SECURITY", "Security", v, main, notice, overlay)
    }

    fun about(s: PanelStatus, v: Viewer): String {
        val rows = s.about.joinToString("") { (k, value) -> row(k, "", right = value) }
        val main = """<h1 class="ph">About</h1><p class="sub">This panel and the software it runs.</p>${ownCard(v, s, "about")}<section class="card list">$rows</section>"""
        return frame(s, "/about", "About", v, main)
    }

    /**
     * All panels (#466): every panel in Home Assistant, each with Manage, and Copy settings. The browser fills
     * both in ([PANELS_SCRIPT]) with its own Home Assistant sign-in at [ha], this panel's local Home Assistant
     * address; null when it has none, and the page says so. [session]: this browser's Manage session's public id: the
     * tab keeps its Home Assistant sign-in for that session only, so a later sign-in here can't use it.
     */
    fun panels(s: PanelStatus, v: Viewer, ha: String?, session: String, noHa: String? = null, haSignIn: Boolean = false): String {
        val first = when {
            ha != null -> FINDING
            noHa != null -> noHa
            else -> "This panel has no Home Assistant on your network to find the others with."
        }
        val main = """<h1 class="ph">All panels</h1><p class="sub">Every panel in Home Assistant. Open one, or copy settings from one to another.</p>
<div id="gw-panels" data-ha="${esc(ha.orEmpty())}" data-session="${esc(session)}" data-ha-signin="${if (haSignIn) "1" else ""}" data-csrf="${esc(v.csrf)}"><section class="card list" id="gw-list"><div class="lh"><b>Panels in Home Assistant</b></div>
<div class="row static note"><span>${esc(first)}</span></div></section><section class="card list" id="gw-copy" hidden></section></div>
${if (ha == null) "" else """<noscript><style>#gw-list .note{display:none}</style><section class="card list"><div class="row static note"><span>${esc(NEEDS_SCRIPT)}</span></div></section></noscript>"""}
${if (ha == null) "" else """<script src="$PANELS_BOOT" async></script><script type="module" src="$PANELS_SCRIPT"></script>"""}"""
        return frame(s, PANELS, "All panels", v, main, css = PANELS_CSS)
    }

    fun notFound(s: PanelStatus, v: Viewer): String =
        frame(s, "", "Not found", v, """<h1 class="ph">Not found</h1><p class="sub">This panel has no such page. <a href="/">Back to the overview</a>.</p>""")

    // ------------------------------------------------------------------ words

    private fun toneClass(t: Tile.Tone) = when (t) { Tile.Tone.OK -> "ok"; Tile.Tone.WORKING -> "work"; Tile.Tone.PROBLEM -> "bad"; Tile.Tone.OFF -> "off" }

    private fun fmt(p: String) = SimpleDateFormat(p, Locale.ENGLISH).apply { timeZone = zone }

    /** Today: `09:41:07`. Earlier: `11 Nov 09:41`. */
    fun time(ms: Long): String {
        val today = fmt("yyyyMMdd").format(Date(clock()))
        return if (fmt("yyyyMMdd").format(Date(ms)) == today) fmt("HH:mm:ss").format(Date(ms)) else fmt("d MMM HH:mm").format(Date(ms))
    }

    private fun day(ms: Long) = fmt("d MMM").format(Date(ms))

    private fun ago(ms: Long): String {
        val m = (clock() - ms).coerceAtLeast(0) / 60_000
        return when {
            m < 2 -> "just now"
            m < 60 -> "$m minutes ago"
            m < 120 -> "an hour ago"
            m < 24 * 60 -> "${m / 60} hours ago"
            m < 48 * 60 -> "yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    companion object {
        /** Sections drawn on a page of their own under "System" in the nav, not under "Panel". */
        val OWN_PAGES = mapOf("log" to "Log", "security" to "Security", "about" to "About")

        /** The Security page's id; the core's section of that id draws on it ([security]). */
        const val SECURITY = "security"

        /** All panels (#466), after the Overview in the nav. */
        const val PANELS = "/panels"
        const val PANELS_SCRIPT = "/manage/js/panels.js"
        /** Says so if [PANELS_SCRIPT] can't start, rather than "Finding your panels…" for good. */
        const val PANELS_BOOT = "/manage/js/panels-boot.js"

        /** All panels while its script starts (a slow panel or phone can take seconds), and without scripts (#466 run). */
        const val FINDING = "Finding your panels…"
        const val NEEDS_SCRIPT = "This page needs JavaScript to ask Home Assistant and the other panels."

        /** All panels' own: the copy card's pickers and rows. */
        val PANELS_CSS = """
.lh .lk{background:none;border:0;padding:0;font:inherit;color:var(--muted);text-decoration:underline;text-underline-offset:3px;cursor:pointer}.lh .lk:hover{color:var(--fg)}.row .pill{white-space:nowrap}.pill.sm:disabled{opacity:.45;cursor:not-allowed}
.cph{display:flex;align-items:flex-end;gap:16px;padding:16px 0;border-bottom:1px solid var(--edge)}.cph label{flex:1;display:flex;flex-direction:column;gap:6px;color:var(--muted);font-size:14px}
.cph .arrow{color:var(--faint);font-size:22px;padding-bottom:12px}.cph select.fld{font-size:16px;padding:11px 14px}
label.row.cp{cursor:pointer}.row.cp input{width:20px;height:20px;accent-color:var(--sky);flex:none}.row.cp.bad span{color:var(--rose)}
.cpa{margin:14px 0;padding:14px 18px;border-radius:16px;border:1px solid rgba(251,113,133,.45)}.cpa b{font-weight:500}.cpa p{color:var(--muted);margin:6px 0;font-size:15px}
.cpf{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 0 12px;color:var(--muted);font-size:15px}
@media (max-width:900px){.cph{flex-direction:column;align-items:stretch}.cph .arrow{display:none}.cpf{flex-direction:column;align-items:stretch}}"""

        /** The sidebar's foot for a browser signed in with the code the panel showed. */
        /** How often a page with a live row ([DockRow], [VitroDrawingRows]) reloads, in seconds: about as often as the panel's menu reads it (2 s). */
        const val LIVE_RELOAD_S = 3
        /** The query parameter that pauses it (`?live=off`), and the line that says which it does, for what it follows. */
        const val LIVE_PARAM = "live"
        const val LIVE_DOCK = "the dock"
        const val LIVE_DASHBOARD = "the dashboard"
        fun liveOn(follows: String) = "This page reloads every $LIVE_RELOAD_S seconds to follow $follows."
        fun livePaused(follows: String) = "Paused: this page doesn't follow $follows until you resume it."
        const val SIGNED_IN_WITH_CODE = "Signed in with the panel's code"

        /** A sign-in's Show on the page of a browser signed in with the panel's code. */

        /** A row that opens another app on the panel (its settings, the licences). */
        const val ON_THE_PANEL = "On the panel"
        /** A long choice's first line while nothing in it is picked. */
        const val PICK_ONE = "Pick one"
        /** The Blueprints card's line with the panel's code: Manage doesn't know who you are in Home Assistant. */
        const val BLUEPRINTS_CODE = "You signed in with the panel's code. Home Assistant asks who you are, and only an administrator can add blueprints."

        /** `up 3 days, 4 h`, `up 5 h, 12 min`, `up 3 min`. */
        fun duration(ms: Long): String {
            val min = ms / 60_000
            val h = min / 60
            val d = h / 24
            return when {
                d >= 1 -> "$d day${if (d == 1L) "" else "s"}, ${h % 24} h"
                h >= 1 -> "$h h, ${min % 60} min"
                else -> "$min min"
            }
        }

        private const val SIGN_IN_CSS = """h1{font-size:30px}@media (min-width:720px){h1{font-size:52px;letter-spacing:-.03em}.copy p{font-size:18px}}
.rem{margin-top:18px}.alt{font-size:15px}.act form{margin:0}"""

        /**
         * The legacy build's chip (#29). Its limits show on hover, on focus (keyboard) and on a tap, which focuses the
         * button. No script: the page's CSP allows none.
         */
        private const val LEGACY_CHIP = """<span class="lg"><button class="chip" type="button" aria-describedby="lgtip">Legacy</button>""" +
            """<span class="tip" id="lgtip" role="tooltip">The legacy build for Android 6 and 7. Its frozen web engine gets no """ +
            """security fixes, so it loads only your Home Assistant (no map tiles or web cards from other sites), and remote """ +
            """debugging is off.</span></span>"""

        val MANAGE_CSS = """body{display:block}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;flex:none;background:var(--faint)}.dot.ok{background:var(--green)}.dot.work{background:var(--amber)}.dot.bad{background:var(--rose)}.dot.off{background:var(--faint)}
.side{position:fixed;inset:0 auto 0 0;width:280px;padding:28px 18px;border-right:1px solid var(--edge);display:flex;flex-direction:column;overflow:auto}
.brand{display:flex;gap:14px;align-items:center;padding:0 12px 26px}.brand .bm{width:34px;height:34px;flex:none}.brand b{display:block;font-size:18px;font-weight:500}.brand small{color:var(--muted);font-size:14px}
nav{display:flex;flex-direction:column;gap:2px;flex:1}nav h3{margin:20px 12px 6px;font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--faint);font-weight:600}
.ni{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:12px;text-decoration:none;font-size:16px;border:1px solid transparent}
.ni:hover{background:var(--hover)}.ni.on{background:var(--glass);border-color:var(--edge)}.ni.grey{color:var(--faint)}
.who{display:flex;align-items:center;justify-content:space-between;gap:8px;border-top:1px solid var(--edge);margin-top:18px;padding:16px 12px 0;font-size:14px;color:var(--muted)}
.who>span{display:flex;align-items:center;gap:8px;min-width:0}.who .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.who button{background:none;border:0;color:var(--muted);font:inherit;cursor:pointer;padding:0;white-space:nowrap;flex:none}.who button:hover{color:var(--fg)}
.top{display:none}
.main{margin-left:280px;padding:40px 56px 64px;max-width:1336px}
.card{background:var(--glass);border:1px solid var(--edge);border-radius:22px;margin-bottom:18px}
.hero{display:grid;grid-template-columns:120px 1fr;grid-template-rows:auto auto;column-gap:40px;align-items:center;padding:32px 40px}
.hm{width:120px;height:112px;grid-row:1/3}.ht{align-self:end}.acts{grid-column:2;align-self:start}
.hero h1{display:flex;align-items:center;gap:14px;font-size:32px;font-weight:300;margin:0 0 4px}.meta{color:var(--muted);margin:0 0 20px;font-size:17px}
.acts{display:flex;flex-wrap:wrap;gap:10px}.acts form{margin:0}
.pill{display:inline-block;padding:11px 22px;border-radius:999px;background:var(--glass);border:1px solid var(--edge);color:var(--fg);font:inherit;font-size:16px;cursor:pointer;text-decoration:none}
.pill:hover{background:var(--hover)}.pill.danger{color:var(--rose);border-color:rgba(251,113,133,.5)}.pill.primary{background:var(--btn);color:var(--btx);border-color:transparent}
.tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:18px;margin-bottom:18px}
.tile{background:var(--glass);border:1px solid var(--edge);border-radius:22px;padding:22px 24px;display:flex;flex-direction:column;gap:4px;min-width:0}
a.tile{text-decoration:none}a.tile:hover{background:var(--hover)}.tile small{color:var(--muted);font-size:14px}.tile b{display:flex;align-items:center;gap:10px;font-weight:400;font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.tile span{color:var(--muted);font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.list{padding:8px 28px}.lh{display:flex;justify-content:space-between;align-items:baseline;padding:16px 0 12px;border-bottom:1px solid var(--edge)}.lh b{font-weight:500;font-size:17px}.lh span{color:var(--muted);font-size:14px}
.row{display:flex;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid var(--edge);text-decoration:none;min-height:64px}.row:last-child{border-bottom:0}
.row>div{flex:1;min-width:0}.row b{display:block;font-weight:400;font-size:17px}.row span{display:block;color:var(--muted);font-size:15px}.row em{font-style:normal;color:var(--muted);text-align:right;overflow-wrap:anywhere;max-width:55%}
.row form{margin:0}.me{display:inline!important;color:var(--ok-ink)!important;font-size:inherit!important}
.grp .lh{border-bottom:1px solid var(--edge)}.grp:not(:first-child) .lh{padding-top:22px}
.chip{display:inline-flex;align-items:center;gap:${Tokens.Chip.GAP}px;padding:${Tokens.Chip.PAD_V}px ${Tokens.Chip.PAD_END}px ${Tokens.Chip.PAD_V}px ${Tokens.Chip.PAD_START}px;border-radius:999px;border:1px solid var(--edge);background:var(--glass);font-size:${Tokens.Chip.TEXT}px;line-height:1.35;color:var(--muted);white-space:nowrap;flex:none}
.chip .dot{width:${Tokens.Chip.DOT}px;height:${Tokens.Chip.DOT}px}
.lgm{position:relative}.lg{display:inline-flex;vertical-align:middle;margin-left:10px}.lg .chip{font-family:inherit;cursor:help}
.lg .chip:focus-visible{outline:2px solid var(--sky);outline-offset:3px}.lg:hover .tip,.lg:focus-within .tip{display:block}
.lg .tip{display:none;position:absolute;left:0;top:calc(100% + 8px);z-index:4;max-width:420px;padding:12px 16px;border-radius:14px;border:1px solid var(--edge);background:var(--bg-2);color:var(--fg);font-size:14px;line-height:1.45;white-space:normal;box-shadow:0 20px 40px rgba(0,0,0,.35)}
.ni .nt{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ni .dot{margin-left:10px}
details.off summary{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:8px 12px;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--faint);font-weight:600}
details.off summary::-webkit-details-marker{display:none}details.off summary::after{content:"›";font-size:18px;letter-spacing:0;transition:transform .15s}details.off[open] summary::after{transform:rotate(90deg)}
.phr{display:flex;align-items:center;gap:14px;margin:0 0 6px}.phr .ph{flex:1;min-width:0;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.crumb{color:var(--faint);font-size:28px;font-weight:300;white-space:nowrap}.pchip{display:none}.pg{margin-top:18px}
.tgf{margin:0;flex:none}.tg{position:relative;display:block;width:${Tokens.Toggle.W}px;height:${Tokens.Toggle.H}px;border-radius:999px;border:1px solid var(--edge);background:var(--field);cursor:pointer;padding:0}
.tg i{position:absolute;top:${Tokens.Toggle.KNOB_INSET - 1}px;left:${Tokens.Toggle.KNOB_INSET - 1}px;width:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;height:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;border-radius:50%;background:var(--muted)}
.tg.on{background:linear-gradient(90deg,var(--sky),var(--violet));border-color:transparent}.tg.on i{left:auto;right:${Tokens.Toggle.KNOB_INSET - 1}px;background:#fff}
.tg:disabled{opacity:.45;cursor:not-allowed}.tg:focus-visible,.pc:focus-visible,.pill:focus-visible{outline:2px solid var(--sky);outline-offset:3px}
.row.note span{color:var(--muted);font-size:15px;line-height:1.35}.row.quiet b{color:var(--muted)}
.row.sl{flex-direction:column;align-items:stretch;gap:8px}.slh{display:flex;align-items:baseline;gap:16px}.slh b{flex:1;min-width:0}.row.sl .slh em{flex:none;max-width:60%;text-align:right;font-style:normal;color:var(--muted);font-size:15px}
.row.bp .act{display:flex;align-items:center;gap:10px;flex:none}.row .chip{display:inline-flex;font-size:${Tokens.Chip.TEXT}px}.pill:disabled{opacity:.45;cursor:not-allowed}
.slr{display:flex;align-items:center;gap:14px}.slr input{flex:1;min-width:0;accent-color:var(--sky)}.pill.sm{padding:7px 16px;font-size:14px}
.gl{margin:24px 0 4px;font-size:12px;letter-spacing:.2em;text-transform:uppercase;color:var(--faint);font-weight:600}
.st{display:flex;align-items:center;gap:10px;padding:16px 0 8px;color:var(--muted);font-size:15px}.st.bad span{color:var(--rose)}.row span.fix{color:var(--work-ink)}
.sr{display:flex;align-items:center;gap:16px;min-height:46px;border-top:1px solid var(--edge);font-size:15.5px}.sr span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sr em{font-style:normal;color:var(--muted);text-align:right}.sr.quiet span{color:var(--muted)}.sr.quiet em{color:var(--faint)}
.ptl{display:grid;grid-template-columns:repeat(var(--cols),minmax(0,1fr));gap:12px;padding:10px 0 14px}
.pt{background:var(--glass);border:1px solid var(--edge);border-radius:var(--r-tile);padding:14px 16px;display:flex;flex-direction:column;gap:2px;min-width:0}
.pt small{color:var(--faint);font-size:11px;font-weight:600;letter-spacing:.16em;text-transform:uppercase}.pt b{font-weight:300;font-size:26px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.pt b u{text-decoration:none;font-size:14px;color:var(--muted);margin-left:4px}
.pt span{color:var(--faint);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mt{display:block;height:4px;border-radius:2px;background:var(--edge);margin-top:10px;overflow:hidden}.mt i{display:block;height:100%;background:linear-gradient(90deg,var(--sky),var(--violet))}
.sp{display:block;width:100%;height:24px;margin-top:8px}.sp polyline{fill:none;stroke:var(--sky);stroke-width:1.5;vector-effect:non-scaling-stroke}
.banner{display:flex;align-items:center;gap:13px;padding:10px 10px 10px 18px;border-radius:20px;background:rgba(251,191,36,.08);border:1px solid rgba(251,191,36,.35);margin:14px 0}.banner span{flex:1}.banner form{margin:0}
.main>.err{color:var(--rose);margin:10px 0}
a.see{color:inherit;text-decoration:underline;text-underline-offset:3px}[id^="i-"]{scroll-margin-top:24px}
.ucs{margin:0;padding:6px 0 18px;border-bottom:1px solid var(--edge)}.ucs .gl{margin-top:16px}
.ucg{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:10px}
.ths{margin:0;padding:10px 0 18px;min-width:0;max-width:100%}.thg{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(104px,100%),1fr));gap:12px;min-width:0}
.tc{display:flex;flex-direction:column;align-items:center;gap:6px;min-width:0;padding:14px 8px 11px;border-radius:16px;border:1px solid var(--edge);background:var(--glass)}
.tc.on{border-color:color-mix(in srgb,var(--fg) 30%,transparent)}.tc b{font-weight:400;font-size:14px}.tc span{font-size:11.5px;color:var(--faint);margin-top:-5px}
.tc .pair{display:flex;gap:12px;padding:4px 0}
.ths .sw{position:relative;width:38px;height:38px;border-radius:50%;border:1px solid rgba(16,20,32,.1);padding:0;cursor:pointer;
background:radial-gradient(circle at 30% 26%,rgba(255,255,255,.95),transparent 30%),linear-gradient(135deg,color-mix(in srgb,var(--a) 65%,#fff),color-mix(in srgb,var(--b) 75%,#fff))}
.ths .sw.dk{border-color:rgba(255,255,255,.14);background:radial-gradient(circle at 30% 28%,rgba(255,255,255,.35),transparent 22%),radial-gradient(circle at 32% 34%,var(--a),transparent 62%),radial-gradient(circle at 70% 72%,var(--b),transparent 64%),color-mix(in srgb,var(--g) 95%,#fff)}
.ths .sw.on{outline:2px solid var(--fg);outline-offset:4px}.ths .sw:focus-visible{outline:2px solid var(--sky);outline-offset:4px}
.ths .sw .bd{position:absolute;right:-10px;top:-10px;width:20px;height:20px;border-radius:50%;background:var(--bg-2);border:1px solid var(--edge)}
.ths .sw .bd.sun::after{content:"";position:absolute;inset:6px;border-radius:50%;background:var(--fg);box-shadow:0 0 0 1.5px var(--bg-2),0 0 0 3px var(--fg)}
.ths .sw .bd.auto::after{content:"";position:absolute;inset:4px;border-radius:50%;border:1.5px solid var(--fg);background:linear-gradient(90deg,var(--fg) 50%,transparent 50%)}
.ths .sw .bd.moon::after{content:"";position:absolute;inset:4px;border-radius:50%;box-shadow:inset -4px -3px 0 0 var(--fg);transform:rotate(-20deg)}
.uc{display:flex;flex-direction:column;gap:4px;text-align:left;padding:16px 18px;border-radius:22px;border:1px solid var(--edge);background:var(--glass);color:var(--muted);font:inherit;cursor:pointer}
.uc b{font-weight:400;font-size:19px}.uc span{font-size:14px;line-height:1.35;color:var(--muted)}.uc:hover{background:var(--hover)}
.uc.on{color:var(--fg);border:1.6px solid transparent;background:linear-gradient(var(--bg-2),var(--bg-2)) padding-box,linear-gradient(135deg,var(--sky),var(--violet)) border-box}
.uc:focus-visible{outline:2px solid var(--sky);outline-offset:3px}
.chev{font-style:normal;color:var(--faint);font-size:22px;line-height:1}
.ph{font-size:48px;font-weight:300;letter-spacing:-.02em;margin:0 0 6px}.sub{color:var(--muted);font-size:18px;margin:0 0 26px}
.foot{color:var(--faint);font-size:14px;margin-top:6px}
.notice{display:flex;align-items:center;gap:12px;padding:14px 20px;border-radius:16px;background:var(--glass);border:1px solid var(--edge);margin-bottom:18px;font-size:15px}
.lt{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.dl{text-align:right;margin-top:10px}.dl small{display:block;color:var(--muted);font-size:14px;margin-top:8px}
.filters{display:flex;gap:10px;align-items:center;margin:4px 0 18px;flex-wrap:wrap}.fc{padding:8px 18px;border-radius:999px;border:1px solid var(--edge);text-decoration:none;font-size:15px}
.fc.on{background:var(--lit);border-color:transparent;color:#fff}.search{margin-left:auto;flex:0 1 380px}
.search input{width:100%;padding:11px 18px;border-radius:14px;border:1px solid var(--edge);background:var(--glass);color:var(--fg);font:inherit;font-size:15px}
.logs{padding:18px 24px;font:14px/1.4 ui-monospace,"SF Mono",Menlo,Consolas,monospace}.lr{display:grid;grid-template-columns:auto 130px 1fr;gap:20px;padding:5px 4px}
.lr time{color:var(--faint);white-space:nowrap}.lr b{font-weight:400;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.lr b.w{color:var(--amber)}.lr b.e{color:var(--rose)}.lr span{color:var(--muted);overflow-wrap:anywhere}
.empty{color:var(--muted);font-family:system-ui,-apple-system,sans-serif;margin:8px 4px}
@media (prefers-color-scheme:light){.lr b.w{color:var(--work-ink)}.lr b.e{color:#be123c}}
@media (max-width:900px){.side{display:none}.main{margin:0;padding:18px 16px 40px}
.top{display:flex;align-items:center;gap:12px;padding:18px 20px;border-bottom:1px solid var(--edge);font-size:17px;position:relative}.top .m{width:26px;height:26px}.top>span{flex:1}
.menu>summary{list-style:none;cursor:pointer;width:28px;height:22px;display:flex;flex-direction:column;justify-content:center;gap:6px}.menu>summary::-webkit-details-marker{display:none}
.drop .brand{display:none}.menu>summary>span{display:block;height:2px;border-radius:2px;background:var(--fg)}
.menu .drop{position:absolute;left:0;right:0;top:100%;z-index:5;background:var(--bg);border-bottom:1px solid var(--edge);padding:16px;max-height:80vh;overflow:auto;box-shadow:0 20px 40px rgba(0,0,0,.35)}
.hero{grid-template-columns:64px 1fr;column-gap:18px;padding:18px 20px;margin-bottom:12px}.hm{width:64px;height:60px;grid-row:1}.ht{align-self:center}
.hero h1{font-size:24px}.meta{font-size:14px;margin-bottom:0}.lg{display:flex;width:max-content;margin:6px 0 0}
.acts{grid-column:1/-1;margin-top:16px;gap:8px}.acts .pill{padding:9px 16px;font-size:15px}
.tiles{grid-template-columns:1fr;gap:0;background:var(--glass);border:1px solid var(--edge);border-radius:22px;padding:4px 20px}
.tile{flex-direction:row;align-items:center;justify-content:space-between;border:0;border-bottom:1px solid var(--edge);border-radius:0;background:none;padding:14px 0}
.tile:last-child{border-bottom:0}.tile small{color:var(--fg);font-size:16px}.tile b{font-size:14px;color:var(--muted);gap:8px}.tile span{display:none}
.crumb{font-size:20px}.pchip{display:inline-flex}.ptl{grid-template-columns:repeat(2,minmax(0,1fr))}.banner{flex-wrap:wrap}
.row.bp{flex-wrap:wrap;row-gap:10px}.row.bp>div:first-child{flex-basis:100%}
.ucg{grid-template-columns:1fr}.list{padding:4px 20px}.row{min-height:56px}.row.static{flex-wrap:wrap;row-gap:4px}.row em{flex-basis:100%;max-width:none;text-align:left}.row b{font-size:16px}.row span{font-size:14px}.chev{display:none}
.ph{font-size:32px}.sub{font-size:15px;margin-bottom:18px}.lt{flex-direction:column;gap:0}.dl{text-align:left;margin:0 0 14px}.search{flex-basis:100%}
.logs{padding:12px 14px;font-size:12.5px}.lr{grid-template-columns:auto 1fr;gap:4px 12px}.lr span{grid-column:1/-1;padding-bottom:6px}}"""

        val DIALOG_CSS = """.dim{position:fixed;inset:0;filter:brightness(.45);pointer-events:none;overflow:hidden}
.scrim{position:fixed;inset:0;display:grid;place-items:center;padding:16px;background:rgba(0,0,0,.25)}
.dlg{width:100%;max-width:${Tokens.DIALOG_WIDTH}px;background:var(--bg-2);border:1px solid var(--edge);border-radius:${Tokens.R_DIALOG}px;padding:36px 36px 32px;box-shadow:0 30px 80px rgba(0,0,0,.5)}
.scrim .dlg{max-height:calc(100vh - 32px);overflow:auto}
.pcs{display:flex;flex-wrap:wrap;gap:10px;margin:4px 0}.pc{padding:10px 17px;border-radius:999px;border:1px solid var(--edge);background:var(--glass);color:var(--muted);font:inherit;font-size:15.5px;font-weight:500;cursor:pointer}
.pc:hover{background:var(--hover)}.pc.on{background:linear-gradient(90deg,var(--sky),var(--violet));color:#0a0b0f;border-color:transparent}
.dflt{position:absolute;width:1px;height:1px;padding:0;border:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}.flds{display:flex;flex-direction:column;gap:12px}.fld{width:100%;padding:14px 18px;border-radius:16px;border:1px solid var(--edge);background:var(--field);color:var(--fg);font:inherit;font-size:17px}.fld:focus{outline:none;border-color:rgba(56,189,248,.55)}.cpk input{display:block;width:100%;height:64px;padding:6px;border-radius:16px;border:1px solid var(--edge);background:var(--field);cursor:pointer}.cpk input:focus-visible{outline:2px solid var(--sky);outline-offset:3px}.row .dot{display:inline-block;width:13px;height:13px;border-radius:50%;margin-right:7px;vertical-align:-1px;border:1px solid var(--edge)}select.fld{cursor:pointer}select.fld option,select.fld optgroup{background:var(--bg);color:var(--fg)}
.sw{display:flex;justify-content:space-between;align-items:center;gap:12px;color:var(--fg);font-size:16.5px;cursor:pointer}
.sw input{appearance:none;-webkit-appearance:none;margin:0;width:${Tokens.Toggle.W}px;height:${Tokens.Toggle.H}px;border-radius:999px;background:var(--field);border:1px solid var(--edge);position:relative;cursor:pointer;flex:none;order:2}
.sw input::after{content:"";position:absolute;top:${Tokens.Toggle.KNOB_INSET - 1}px;left:${Tokens.Toggle.KNOB_INSET - 1}px;width:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;height:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;border-radius:50%;background:var(--muted)}
.sw input:checked{background:linear-gradient(90deg,var(--sky),var(--violet));border-color:transparent}.sw input:checked::after{left:auto;right:${Tokens.Toggle.KNOB_INSET - 1}px;background:#fff}
.tms{display:flex;flex-direction:column;gap:10px;margin-top:16px}.tm{display:flex;align-items:center;gap:16px;color:var(--muted);font-size:16.5px}.tm span{flex:1}
.tm input{padding:9px 14px;border-radius:12px;border:1px solid var(--edge);background:var(--field);color:var(--fg);font:inherit;font-size:20px}
.dlg h2{font-size:32px;font-weight:300;letter-spacing:-.02em;margin:0 0 10px}.dlg>p{color:var(--muted);margin:0 0 26px;font-size:17px}
.code{position:relative;display:flex;gap:14px;width:max-content;max-width:100%}.code span{width:62px;height:72px;border-radius:14px;background:var(--glass);border:1px solid var(--edge)}
.code input{position:absolute;inset:0;width:100%;border:0;background:transparent;color:var(--fg);font:30px/72px ui-monospace,"SF Mono",Menlo,monospace;
padding:0 0 0 calc(31px - .5ch);letter-spacing:calc(76px - 1ch);caret-color:var(--sky);outline:none}
.code:focus-within span{border-color:rgba(56,189,248,.45)}
.err{color:var(--rose)!important;margin:14px 0 0!important;font-size:15px!important}
.dbtns{display:flex;justify-content:flex-end;gap:12px;margin-top:30px}
@media (max-width:600px){.code{gap:8px}.code span{width:42px;height:56px}.code input{font-size:22px;line-height:56px;padding-left:calc(21px - .5ch);letter-spacing:calc(50px - 1ch)}.dlg{padding:26px 20px}.dlg h2{font-size:26px}}"""
    }
}
