/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import android.view.View
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.ButtonEntity
import dev.glassworks.esphome.EventEntity

/**
 * One section of the panel's settings. The panel's settings menu draws it, and the Manage page (#33)
 * will draw the same sections in a browser, so a section is data: typed items with their values and how
 * to save them. Only [SettingsItem.Native]'s view is panel-only; its typed twin goes everywhere.
 */
class SettingsSection(
    /** Stable, for links and tests (`dashboard`, `screen`, a pane's id). */
    val id: String,
    /** Its name in the menu's list (`Radio`). */
    val title: String,
    val items: List<SettingsItem>,
    /** The heading of its page, when it says more than [title] (`Zigbee / Thread radio`). */
    val heading: String = title,
    /**
     * A pane's on/off, in the page's header at top right (#63 round 2). The core makes it from the
     * pane's setup card ([PaneChoice]) unless the pane gives its own (the radio asks before turning off).
     */
    val master: SettingsItem.Switch? = null,
    /**
     * How the pane is doing now, in its own words (the radio's role, Voice's wake word), or null when it
     * has none: [paneState] then says On or Off from its [master]. The chip after its name in the panel's
     * rail and on the Manage page (#243).
     */
    val status: () -> PaneState? = { null },
    /** Why the pane can't run now (the radio without its root helper), or null: the nav greys it and the page says why. */
    val unavailable: () -> String? = { null },
    /**
     * What its items read, when that can be replaced while the menu is open: a pane's running [Pane],
     * which the shell builds again when the device restarts. Another one draws the page again.
     */
    val source: Any? = null,
    /**
     * The object ids of the pane's entities this page already covers: it shows them, or leaves them out
     * on purpose. Set, and the core ends the page with "In Home Assistant" for the pane's other
     * entities, or adds nothing when none is left, so no entity is listed twice (#212). Null (the
     * default): the core adds nothing to this page.
     */
    val covers: Set<String>? = null,
)


/**
 * The panes' sections as every settings surface shows them (#63, #33, #67, #212): each pane's own, with
 * its on/off as the section's [SettingsSection.master] when it has none: its setup card, else its
 * [PaneDefinition.switch]. [running] gives the pane the shell built, or null while the panel's device
 * isn't running. A pane with no section of its own gets one with its entities ([inHomeAssistant]). A
 * page of its own that says what it covers ([SettingsSection.covers]) ends with the pane's other
 * entities. A pane whose [PaneDefinition.switch] is off shows what that means ([PaneSwitch.offNote])
 * and its entities, unavailable. Definition order.
 */
fun paneSections(
    definitions: List<PaneDefinition>,
    /** A pane's Blueprints card by its id, or null when it has none. Shown only while the pane is on. */
    blueprints: (String) -> SettingsItem.Blueprints? = { null },
    running: (String) -> Pane? = { null },
): List<SettingsSection> = definitions.flatMap { d ->
    val choice = d.setupChoice ?: d.switch?.choice()
    val master = choice?.let { c ->
        // The card's apply can wait on the device (the radio): off the main thread.
        SettingsItem.Switch(c.title, c.detail, c.on) { on, done ->
            Thread({ done(runCatching { c.apply(on) }.getOrElse { it.message ?: "It didn't work." }) }, "gw-pane-switch").apply { isDaemon = true; start() }
        }
    }
    val pane = running(d.id)
    val off = d.switch?.takeIf { !it.on }
    val own = if (off != null) emptyList() else d.settings(pane)
    when {
        own.isNotEmpty() -> {
            // The pane's entities no page covers, once, at the end of the first page that takes part.
            val covered = own.mapNotNull { it.covers }.flatten().toSet()
            val rest = inHomeAssistant(pane) { it.objectId !in covered && it.objectId !in d.shownElsewhere }
            val first = own.indexOfFirst { it.covers != null }
            val card = blueprints(d.id)
            own.mapIndexed { i, s ->
                val items = if (i == 0) withBlueprints(s.items, card) else s.items
                SettingsSection(
                    s.id, s.title, if (i == first && rest != null) items + rest else items, s.heading,
                    if (i > 0 || s.master != null) s.master else master, s.status, s.unavailable, source = pane, covers = s.covers,
                )
            }
        }
        choice != null -> listOf(SettingsSection(
            d.id, choice.title,
            listOfNotNull(off?.let { s -> SettingsItem.Note { s.offNote } }, if (off == null) blueprints(d.id) else null, inHomeAssistant(pane, on = off == null) { it.objectId !in d.shownElsewhere })
                .ifEmpty { listOf(SettingsItem.Note { NOT_RUNNING }) },
            master = master, source = pane,
        ))
        else -> emptyList()
    }
}

/**
 * [items] with the pane's Blueprints [card] just above its own "In Home Assistant" list, or at the end
 * when it has none: the pane's settings, then what to add to Home Assistant, then what is in it.
 */
private fun withBlueprints(items: List<SettingsItem>, card: SettingsItem.Blueprints?): List<SettingsItem> {
    if (card == null) return items
    val at = items.indexOfFirst { (it is SettingsItem.Group && it.title == IN_HOME_ASSISTANT) || (it is SettingsItem.States && it.title == IN_HOME_ASSISTANT) }
    return if (at < 0) items + card else items.subList(0, at) + card + items.subList(at, items.size)
}

/**
 * The "In Home Assistant" list of [pane]'s entities (#212): each one's name and its state as Home
 * Assistant shows it, `Unavailable` while the pane is off ([on] false). Buttons and events have no
 * state and are left out. Null when the pane isn't built or has no such entity.
 */
fun inHomeAssistant(pane: Pane?, on: Boolean = true, only: (ApiEntity) -> Boolean = { true }): SettingsItem.States? {
    if (pane == null) return null
    // Read again with the rows: a pane's list can change while the page is open (a wall base mounted).
    fun shown() = pane.entities().filter { it !is ButtonEntity && it !is EventEntity && only(it) }
    if (shown().isEmpty()) return null
    return SettingsItem.States(IN_HOME_ASSISTANT, rows = {
        shown().map { e -> SettingsItem.StateRow(e.name, if (on) e.shownState() ?: UNKNOWN else UNAVAILABLE, e.disabledByDefault) }
    })
}

const val IN_HOME_ASSISTANT = "In Home Assistant"

/**
 * Whether Home Assistant has the panel (#261). Every "In Home Assistant" list says so, where it would
 * otherwise read "Unknown" on each row.
 */
enum class HaLink {
    /** No Home Assistant has added the panel yet. */
    NOT_ADDED,

    /** Added before, but not connected now: the rows are the last known states. */
    NOT_CONNECTED,
    CONNECTED;

    companion object {
        /**
         * From whether Home Assistant ever [added] the panel, and whether a client listens to its states now.
         * Any client can subscribe without a key, so a listener alone doesn't make a panel added.
         */
        fun of(connected: Boolean, added: Boolean): HaLink = when {
            !added -> NOT_ADDED
            connected -> CONNECTED
            else -> NOT_CONNECTED
        }
    }
}

const val HA_NOT_ADDED = "Not in Home Assistant yet. Add it from Settings → Devices & services."
const val HA_NOT_CONNECTED = "Home Assistant isn't connected. These are the last known states."

/** What a [SettingsItem.States] list shows now: a line above its rows ([note]), and whether the rows are [dimmed]. */
class StatesShown(val note: String?, val rows: List<SettingsItem.StateRow>, val dimmed: Boolean)

/** [this] list as [SettingsItem.States.link] has Home Assistant now, read once, for every surface (#261). */
fun SettingsItem.States.shown(): StatesShown = when (link()) {
    HaLink.CONNECTED -> StatesShown(null, rows(), dimmed = false)
    HaLink.NOT_CONNECTED -> StatesShown(HA_NOT_CONNECTED, rows(), dimmed = true)
    HaLink.NOT_ADDED -> StatesShown(HA_NOT_ADDED, emptyList(), dimmed = false)
}

/** [sections] with each "In Home Assistant" list, in a group too, following [link], which is read again with the rows. */
fun withHaLink(sections: List<SettingsSection>, link: () -> HaLink): List<SettingsSection> = sections.map { s ->
    if (!hasHa(s.items)) s
    else SettingsSection(s.id, s.title, linked(s.items, link), s.heading, s.master, s.status, s.unavailable, s.source, s.covers)
}

private fun linked(items: List<SettingsItem>, link: () -> HaLink): List<SettingsItem> = items.map { i ->
    when {
        i is SettingsItem.States && i.title == IN_HOME_ASSISTANT -> SettingsItem.States(i.title, link, i.rows)
        i is SettingsItem.Group && hasHa(i.items) -> SettingsItem.Group(i.title, linked(i.items, link))
        else -> i
    }
}

private fun hasHa(items: List<SettingsItem>): Boolean =
    items.any { (it is SettingsItem.States && it.title == IN_HOME_ASSISTANT) || (it is SettingsItem.Group && hasHa(it.items)) }
/** A pane's page while the panel's Home Assistant device isn't running, so its entities can't be read. */
const val NOT_RUNNING = "The panel's Home Assistant device isn't running yet. Its state shows here once it is."
/** Home Assistant's words for an entity with no state now, and for one whose device can't give it. */
const val UNKNOWN = "Unknown"
const val UNAVAILABLE = "Unavailable"

/** Where a settings section sits. The core's own come first and last; panes go in between. */
enum class SettingsPlace { DASHBOARD, SCREEN, SOUND, APPEARANCE, DEVICE, NETWORK, PANES, LOG, SECURITY, LABS, ABOUT }

/**
 * The sections in the order every settings surface uses: the core's Dashboard, Screen, Sound and Appearance, the frame's
 * Device page ([dev.glassworks.frame.Frame.deviceSection]) and Network,
 * then [panes] in definition order, then Log, Security, Labs and About. Sections without items are left out.
 */
fun settingsOrder(core: Map<SettingsPlace, SettingsSection>, panes: List<SettingsSection>): List<SettingsSection> {
    val sections = SettingsPlace.entries.flatMap { p -> if (p == SettingsPlace.PANES) panes else listOfNotNull(core[p]) }
    // A link to a section that isn't shown (its pane can't run on this frame, or it has no items) would
    // open nothing: left out. Leaving one out can empty its section, and links to that go too, until
    // nothing changes; each round only drops sections, so it ends.
    var ids = sections.filter { it.items.isNotEmpty() }.map { it.id }.toSet()
    while (true) {
        val kept = sections.filter { it.id in ids }.map { pruned(it, ids) }.filter { it.items.isNotEmpty() }
        val next = kept.map { it.id }.toSet()
        if (next == ids) return kept
        ids = next
    }
}

/** [s] without links to sections outside [ids], nor the groups that leaves empty; [s] itself when it has none. */
private fun pruned(s: SettingsSection, ids: Set<String>): SettingsSection {
    fun dead(i: SettingsItem) = i is SettingsItem.Link && i.section != null && i.section !in ids
    fun any(items: List<SettingsItem>): Boolean = items.any { dead(it) || (it is SettingsItem.Group && any(it.items)) }
    fun live(items: List<SettingsItem>): List<SettingsItem> = items.mapNotNull { i ->
        when {
            dead(i) -> null
            i is SettingsItem.Group -> live(i.items).takeIf { it.isNotEmpty() }?.let { SettingsItem.Group(i.title, it) }
            else -> i
        }
    }
    return if (!any(s.items)) s else SettingsSection(s.id, s.title, live(s.items), s.heading, s.master, s.status, s.unavailable, s.source, s.covers)
}

/**
 * A row in a [SettingsSection]. Values are read when the menu opens. Each change applies on its own,
 * as the person makes it (#63): `save` gets the new value and calls `done` with null, or with what went
 * wrong in words for the person at the panel. An item whose change only takes effect when the app
 * starts again says so with `restartsApp`; the menu then says so and offers to restart.
 */
sealed interface SettingsItem {

    /** What a [Text] takes. [COLOR] is `#rrggbb`: the panel offers its [Text.suggestions] as swatches, Manage a color picker. */
    enum class TextKind { PLAIN, ADDRESS, NUMBER, DECIMAL, COLOR }

    /**
     * A value typed in: an address, a number, a list. The menu shows [shown] of the value under the
     * title ([hint] when it is empty) and edits it on its own.
     */
    class Text(
        val title: String,
        val hint: String,
        val kind: TextKind,
        val value: () -> String,
        val enabled: () -> Boolean = { true },
        /** The value as the row shows it ("After 2 minutes" for `120`). */
        val shown: (String) -> String = { it },
        val restartsApp: Boolean = false,
        /**
         * This panel's own, never another's (its Home Assistant address): All panels doesn't copy it from one panel to
         * another, and a page on another panel can't change it (#466). The panel's own surfaces change it as before.
         */
        val perPanel: Boolean = false,
        /** Values to offer as well as typing one: a [TextKind.COLOR]'s swatches, `#rrggbb` each. */
        val suggestions: () -> List<String> = { emptyList() },
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    class Switch(
        val title: String,
        val detail: String,
        val value: () -> Boolean,
        val restartsApp: Boolean = false,
        /** Asked before the change to this value, or null to apply it at once. */
        val confirm: (Boolean) -> Confirm? = { null },
        /**
         * The key of the item on this page that [detail] points at, or null: the camera stream's "Needs Presence +
         * camera." points at the camera's use. The Manage page links the line, and a refusal, to that item.
         */
        val see: String? = null,
        val save: (Boolean, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** A question before a change: "Turn the radio off?", what it costs, the button's word. */
    class Confirm(val title: String, val detail: String, val action: String)

    /**
     * A number on a scale (#63 round 2: the presence range). [min] can mean "the default"; [shown] says
     * so ("The sensor's own"). The value is saved when the person lets go.
     */
    class Slider(
        val title: String,
        val value: () -> Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val shown: (Float) -> String,
        val save: (Float, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** A length of time picked from [choices] in seconds, never typed (#63 round 2). 0 can mean never. */
    class Duration(
        val title: String,
        val value: () -> Int,
        val choices: List<Int>,
        val shown: (Int) -> String,
        val save: (Int, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /**
     * A daily window as `HH:MM-HH:MM`, or empty for none (the night window). Picked in [stepMin]
     * steps, never typed.
     */
    class TimeRange(
        val title: String,
        val value: () -> String,
        val shown: (String) -> String,
        val stepMin: Int = 30,
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /**
     * A sign-in: a user name and a password, saved together (the camera stream's, #252). The password is
     * write-only: no surface ever shows it again, so the row says [shown] of [isSet] and the person types a
     * new one to change it. Never in a log, an entity, the diagnostics or a response. `save` gets the user
     * name and the new password, or null for the password to keep the stored one; or null for Clear.
     * [SettingsChange.signIn] checks it first.
     */
    class SignIn(
        val title: String,
        val isSet: () -> Boolean,
        val user: () -> String? = { null },
        val shown: (Boolean) -> String = { if (it) "Set" else "Not set" },
        val save: (Pair<String, String?>?, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** Opens something else: another app's settings, the licences, or another section ([Link.section]). */
    class Link(
        val title: String, val detail: String,
        /** The id of another section this row opens instead (the Device page's radio line opens the Radio pane). */
        val section: String? = null,
        /**
         * What [open] shows, as typed items read again with the page (#518: the notifications on the panel). A
         * surface that can't open it (the Manage page) draws them under [title] instead; their keys start with
         * [title], as a [Group]'s. Never a secret: it is a page in a browser.
         */
        val twin: () -> List<SettingsItem> = { emptyList() },
        val open: (Context) -> Unit = {},
    ) : SettingsItem

    /** A line of status text. */
    class Note(val text: () -> String) : SettingsItem

    /**
     * A name and a status line under it (the app's version, what plays now). Read again while the page is
     * open. [fixable]: the line says what's missing that the person can set (amber). A secret is never an
     * Info: a password is a [SignIn], which never shows it.
     */
    class Info(
        val title: () -> String,
        val detail: () -> String,
        val fixable: () -> Boolean = { false },
    ) : SettingsItem

    /** Items under their own heading, in a card of their own. */
    class Group(val title: String, val items: List<SettingsItem>) : SettingsItem

    /**
     * One of a few [options] (id to its name), picked from a list, never typed (the wake word). The row
     * shows the picked one's name.
     */
    class Choice(
        val title: String,
        val value: () -> String,
        val options: () -> List<Pair<String, String>>,
        /**
         * A long list's group for each option id (the time zone's regions), or null for a short one. A surface
         * lists a grouped choice by group (the Manage page's `<select>` with its `<optgroup>`s), not as chips.
         */
        val group: ((String) -> String)? = null,
        /** The row's line for the picked id, when it says more than its option's name (`Europe/Madrid · UTC+01:00 · 14:32 now`). */
        val line: ((String) -> String)? = null,
        /** Asked before picking the id given, or null to apply it at once (the radio's role, #269), as [Switch.confirm]. */
        val confirm: (String) -> Confirm? = { null },
        /**
         * A line under each option id, or null. With lines, a surface draws the options as cards side by side, the
         * picked one lit, and a tap picks at once (the camera's use, #117's approved cards).
         */
        val cards: ((String) -> String)? = null,
        /**
         * Each option id's [Swatch], or null. With swatches, a surface draws the options as one card per
         * [Swatch.group] (a theme), each option an orb in it, the picked one ringed and badged with a sun or moon
         * (#691's theme picker). A tap on an orb picks it at once.
         */
        val swatch: ((String) -> Swatch)? = null,
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /**
     * How a [Choice] option looks as an orb (#691): the card it sits in ([group], with a [note] under the name, "Default"),
     * whether it is the [dark] one, and its two lights and ground ([dev.glassworks.theme.Themes.Palette], 0xAARRGGBB).
     */
    class Swatch(
        val group: String, val note: String, val dark: Boolean, val a: Int, val b: Int, val ground: Int,
        /** Light or dark is decided elsewhere (Home Assistant's theme): picked, it shows a half sun, half moon. */
        val auto: Boolean = false,
    )

    /**
     * A button on a row (#269): its [title], a [detail] line read again while the page is open, and the
     * button's [label] ("Use it"). [confirm]: asked first, as a [Switch] does, for anything that costs
     * something (a reset, the radio's roles). [needsCode]: a surface away from the panel (the Manage page)
     * runs it only after someone types the code the panel shows then, which proves they stand at it; the
     * panel's own menu doesn't ask. `run` does it and calls `done` with null, or what went wrong.
     * A surface sends the word [RUN] for it.
     */
    class Action(
        val title: String,
        val detail: () -> String,
        val label: String,
        val confirm: Confirm? = null,
        val needsCode: Boolean = false,
        val run: (done: (String?) -> Unit) -> Unit,
    ) : SettingsItem {
        companion object {
            /** What a surface sends to run an action ([SettingsChange.change]). */
            const val RUN = "run"
        }
    }

    /**
     * Read-only states under [title], one line each: a name on the left, its value on the right
     * ([inHomeAssistant]). [rows] is read again every couple of seconds while the page is open. A row
     * [StateRow.offByDefault] is dimmed, with a line under the list that says why. [link]: whether Home
     * Assistant has the panel now; surfaces read the list through [shown], which says so instead of the rows.
     */
    class States(val title: String, val link: () -> HaLink = { HaLink.CONNECTED }, val rows: () -> List<StateRow>) : SettingsItem

    class StateRow(val name: String, val value: String, val offByDefault: Boolean = false)

    /**
     * A line under the page's heading that says how the pane is doing now. [ok]: it works (green), else
     * quiet grey; [problem]: something is wrong that the person can fix (rose, over [ok]). Read again while open.
     */
    class Status(val text: () -> String, val ok: () -> Boolean, val problem: () -> Boolean = { false }) : SettingsItem

    /**
     * A pane's Home Assistant blueprints ([Blueprint]) under [BLUEPRINTS], each with a button that opens
     * Home Assistant's own import dialog: the panel never writes them itself (pane rule 4). [card] says what
     * each button does now ([blueprintCard]); surfaces read it again every couple of seconds while the page
     * is open. [open]: the panel opens a link in its dashboard (the Manage page links in a new tab instead).
     */
    class Blueprints(val list: List<Blueprint>, val card: (BlueprintSurface) -> BlueprintCard, val open: (String) -> Unit) : SettingsItem

    /** Readings as tiles, [columns] to a row (the diagnostics' six, the wall base's strip). Read again while the page is open. */
    class Tiles(val columns: Int, val tiles: () -> List<Tile>) : SettingsItem

    /**
     * One reading: its [name], the [value] large with its [unit], a [detail] line, and under it either its
     * last hour ([history], oldest first: a line) or how full it is ([meter], 0 to 1).
     */
    class Tile(
        val name: String,
        val value: String,
        val unit: String = "",
        val detail: String = "",
        val history: List<Float>? = null,
        val meter: Float? = null,
    )

    /**
     * A view only the panel draws, where its own layout is nicer than rows (the time zone's picker). [key]
     * names it: the menu makes it once with [create] and keeps it while the activity lives. A section with
     * a native item is drawn without its own card: native views bring their surfaces, and its other items
     * go in [Group]s.
     *
     * [twin] (#269): the same rows and actions as typed items, read again with the page, which every other
     * surface draws instead of the view (the Manage page). The view and its twin show the same words and
     * change the same things; `PanelManageParityTest` checks the words. Their keys are their own titles,
     * as if the twin's items sat in the section itself. Never put a secret in a twin: it is a page in a
     * browser and a line in the diagnostics.
     */
    class Native(
        val key: String,
        /** What the view is, in words. */
        val title: String = "",
        val twin: () -> List<SettingsItem> = { emptyList() },
        val create: (NativeHost) -> NativeView,
    ) : SettingsItem

    /** What the menu offers a [Native] view. Main thread. */
    interface NativeHost {
        val context: Context

        /** Adds [layer] over the whole menu (a dialog). */
        fun addLayer(layer: View)

        /** Scrolls [field] into sight above the keyboard. */
        fun reveal(field: View)

        fun hideKeyboard()
    }

    /** A native view and its hooks. Main thread. */
    interface NativeView {
        val view: View

        /** Read the state again: when the menu opens, and every couple of seconds while it is open. */
        fun refresh()

        /** The menu closes or is drawn again: close dialogs. */
        fun reset() {}

        /** Back was pressed: close this view's topmost dialog and say so, or false when none is open. */
        fun back(): Boolean = false
    }
}
