/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import dev.glassworks.platform.AppBuild
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

/**
 * One of a pane's Home Assistant blueprints: an entry in its pane.json's `blueprints`, with the name from
 * the YAML's `blueprint:` block. [path] is the file in the repository
 * (`panes/notifications/blueprints/doorbell-panels.yaml`). Home Assistant imports it from GitHub itself;
 * the panel only points it there, through Home Assistant's own import dialog.
 */
data class Blueprint(val pane: String, val path: String, val name: String, val summary: String) {
    /** The file's name. Home Assistant saves an import from GitHub as `GlassworksHQ/<file>`, so it is unique in the repo. */
    val file: String get() = path.substringAfterLast('/')

    companion object {
        /** The app's asset with every pane's blueprints, in pane.json order (BlueprintCatalogTest writes and checks it). */
        const val ASSET = "blueprints.json"

        /** [ASSET]'s list: `[{"pane", "path", "name", "summary"}]`. */
        fun parse(json: String): List<Blueprint> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Blueprint(o.getString("pane"), o.getString("path"), o.getString("name"), o.getString("summary"))
            }
        }
    }
}

/**
 * Where a blueprint's file is, and the Home Assistant pages that import or list it. Every link points at
 * one file per app version: raw GitHub at [ref], which Home Assistant's import dialog trusts without a
 * warning. Its import saves the file as `GlassworksHQ/<file>`, with that address as its `source_url`.
 */
object BlueprintLinks {
    const val REPO = "GlassworksHQ/glassworks"

    /**
     * The git ref the app's blueprints come from: a test build's commit, a release's tag `v<versionName>`,
     * else `main`. A development build has no release version (versionCode 1, the build's default), so it
     * follows main, as its code does.
     */
    fun ref(build: AppBuild): String = when {
        build.testBuild.isNotEmpty() -> build.testBuild
        build.versionCode > 1 -> "v${build.versionName}"
        else -> "main"
    }

    fun raw(ref: String, path: String) = "https://raw.githubusercontent.com/$REPO/$ref/$path"

    /** Home Assistant's import dialog with [raw] filled in, on the instance at [haBase] (no My Home Assistant needed). Null for an address that isn't http(s). */
    fun importOn(haBase: String, raw: String): String? = origin(haBase)?.let { "$it/_my_redirect/blueprint_import?blueprint_url=${enc(raw)}" }

    /** The same through My Home Assistant: the phone app opens it signed in, and a browser asks for the address once. */
    fun importMy(raw: String) = "https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=${enc(raw)}"

    /**
     * Home Assistant's blueprint list filtered to [name], on the instance at [haBase]. Home Assistant has no
     * link that opens a new automation on a blueprint; one tap on this row starts one.
     */
    fun blueprintsOn(haBase: String, name: String): String? = origin(haBase)?.let { "$it/_my_redirect/blueprints?search=${enc(name)}" }

    /** `http://h:8123/lovelace/0` → `http://h:8123`; null for an address that isn't http(s). */
    fun origin(base: String): String? = runCatching {
        val u = URI(base.trim())
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.rawAuthority.isNullOrEmpty()) null else "${u.scheme.lowercase()}://${u.rawAuthority}"
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** A blueprint Home Assistant has, as `blueprint/list` gives it: its path under `blueprints/automation/` and where it came from. */
data class HaBlueprint(val path: String, val sourceUrl: String?)

/** [this] is one of [list]: imported from this repo at any ref, or saved under the name an import from it gets. */
fun Blueprint.isIn(list: List<HaBlueprint>): Boolean = list.any { h ->
    h.sourceUrl?.let { "/${BlueprintLinks.REPO}/" in it && it.endsWith("/$path") } == true || h.path == "GlassworksHQ/$file"
}

/**
 * What the panel's own Home Assistant sign-in may do with blueprints, as last checked. It only reads:
 * `auth/current_user`, then `blueprint/list` for an administrator. Home Assistant's own dialog does the
 * import, after the person taps (pane rule 4).
 */
sealed interface BlueprintAccess {
    /** Not signed in, Home Assistant not reachable, or signed in to another one than the dashboard's: [why], in words. */
    data class Off(val why: String) : BlueprintAccess

    /** Not known yet: the check runs. */
    data object Checking : BlueprintAccess

    /** Signed in as [user], who isn't an administrator: Home Assistant's blueprint pages are closed to them. */
    data class NotAdmin(val user: String) : BlueprintAccess

    /** An administrator; Home Assistant has [inHa]. */
    data class Admin(val inHa: List<HaBlueprint>) : BlueprintAccess
}

/** Where the card is drawn: the panel's settings, or the Manage page in someone's browser. */
enum class BlueprintSurface { PANEL, MANAGE }

/** What a blueprint's button does now. */
sealed interface BlueprintAction {
    /** Opens Home Assistant's import dialog at [url], filled in: in the dashboard on the panel, in a new tab on the Manage page. */
    data class Add(val url: String) : BlueprintAction

    /** Home Assistant has it: its blueprint list filtered to it, at [url], where one tap starts an automation. */
    data class CreateAutomation(val url: String) : BlueprintAction

    /** The panel's sign-in isn't an administrator: a QR code of [url] for a phone signed in as one. */
    data class Phone(val url: String) : BlueprintAction

    /** Nothing to do from here now; the card's [BlueprintCard.note] says why. */
    data object Off : BlueprintAction
}

/** A pane's Blueprints card as a surface draws it: [note], one line on top or null, then each blueprint with its button. */
class BlueprintCard(val note: String?, val rows: List<Pair<Blueprint, BlueprintAction>>)

/**
 * The card for [list] on [surface], with the panel's sign-in at [access] and its Home Assistant at
 * [haBase] (the dashboard's), for the app's [ref] ([BlueprintLinks.ref]).
 *
 * - The panel, an administrator: Add opens the import dialog in the dashboard; one Home Assistant has
 *   offers Create automation.
 * - The panel, not an administrator: a QR code for a phone, with the reason on top.
 * - The panel, not signed in, not reachable or still checking: off, with the reason.
 * - The Manage page: a link in a new tab for both sign-ins (Home Assistant checks who opens it); off only
 *   without an address. Create automation when the panel's own check found it in Home Assistant.
 */
fun blueprintCard(list: List<Blueprint>, surface: BlueprintSurface, access: BlueprintAccess, haBase: String?, ref: String): BlueprintCard {
    val base = haBase?.let(BlueprintLinks::origin)
        ?: return BlueprintCard(NO_ADDRESS, list.map { it to BlueprintAction.Off })
    val rows = list.map { bp ->
        val raw = BlueprintLinks.raw(ref, bp.path)
        val action = when {
            access is BlueprintAccess.Admin && bp.isIn(access.inHa) -> BlueprintAction.CreateAutomation(BlueprintLinks.blueprintsOn(base, bp.name)!!)
            surface == BlueprintSurface.MANAGE || access is BlueprintAccess.Admin -> BlueprintAction.Add(BlueprintLinks.importOn(base, raw)!!)
            access is BlueprintAccess.NotAdmin -> BlueprintAction.Phone(BlueprintLinks.importMy(raw))
            else -> BlueprintAction.Off
        }
        bp to action
    }
    val note = if (surface == BlueprintSurface.MANAGE) null else when (access) {
        is BlueprintAccess.Off -> access.why
        BlueprintAccess.Checking -> CHECKING
        is BlueprintAccess.NotAdmin -> "Signed in as ${access.user}, not an administrator. Add them from a phone signed in as one."
        is BlueprintAccess.Admin -> null
    }
    return BlueprintCard(note, rows)
}

/** The card's heading on every surface. */
const val BLUEPRINTS = "Blueprints for Home Assistant"
const val NO_ADDRESS = "The panel has no Home Assistant address yet."
const val CHECKING = "Checking the panel's Home Assistant sign-in…"
