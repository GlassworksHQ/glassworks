package dev.glassworks.s1.frame

import android.content.SharedPreferences
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import java.time.Instant
import java.util.TimeZone

/** The time zone ([TimeZonePane]) for the whole process: its on/off and its page. */
class TimeZoneDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "time-zone"

    // `time_zone` is the zone's own text entity, so the switch says what it does.
    override val switch = PaneSwitch(
        file, TimeZonePrefs.KEY_ENABLED, default = true, title = "Time zone", detail = "Keeps the zone Aqara resets at boot",
        objectId = "keep_time_zone", entityName = "Keep time zone", icon = "mdi:map-clock-outline",
        offNote = "The panel no longer keeps the time zone. Aqara's settings set their own at the next start.",
    )

    /** The zones the panel knows, and their words: read once. */
    private val choices by lazy { TimeZoneChoices(TimeZone.getAvailableIDs().toList()) { id -> android.icu.util.TimeZone.getCanonicalID(id) ?: id } }

    /** The running pane's match of Home Assistant's zone, shared by its page and the page's twin (#269). */
    private var match: Pair<TimeZonePane, HomeAssistantZoneMatch>? = null

    @Synchronized private fun matchOf(p: TimeZonePane): HomeAssistantZoneMatch =
        match?.takeIf { it.first === p }?.second ?: HomeAssistantZoneMatch(choices, p::inBackground).also { match = p to it }

    /**
     * The zone, picked from a list or Home Assistant's, and whether the panel keeps it (#212). The panel draws
     * its own page ([TimeZoneView]); every other surface draws the same rows from its twin ([twin]).
     */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? TimeZonePane ?: return emptyList()
        val m = matchOf(p)
        return listOf(SettingsSection(id, "Time zone", covers = setOf("time_zone", "time_zone_status"), items = listOf(
            SettingsItem.Native("time-zone-${System.identityHashCode(p)}", "The zone, from a list", twin = { twin(p, choices, m) }) { host ->
                TimeZoneView(host, p, choices, m)
            },
        )))
    }

    internal companion object {
        const val ZONE = "Time zone"
        const val HOME_ASSISTANT = "Home Assistant's time zone"

        /**
         * The time zone page as typed items (#269), in the page's order and words: the zone, picked from the
         * regions' lists; whether the panel keeps it; and Home Assistant's zone once matched, with "Use it"
         * when another zone keeps its clock, as the picker offers.
         */
        fun twin(p: TimeZonePane, choices: TimeZoneChoices, match: HomeAssistantZoneMatch): List<SettingsItem> {
            fun current() = choices.name(p.kept.ifEmpty { TimeZone.getDefault().id })
            fun options(): List<Pair<String, String>> {
                val now = Instant.now()
                return choices.regions.flatMap { r ->
                    choices.zones(r).map { (id, name) -> id to if (r == TimeZoneChoices.UTC) name else "$name · ${TimeZoneChoices.offset(id, now)}" }
                }
            }
            // The option for the zone kept: its own id, else the one with the same current name (UTC's is
            // Etc/UTC; CodeRabbit on #331).
            fun picked(): String {
                val c = current()
                val all = options()
                return all.firstOrNull { it.first == c }?.first ?: all.firstOrNull { choices.name(it.first) == c }?.first ?: c
            }
            val zone = SettingsItem.Choice(
                ZONE, ::picked, ::options,
                group = choices::regionOf,
                // As the panel's page says it: the zone's current name.
                line = { TimeZoneChoices.line(choices.name(it), Instant.now()) },
            ) { id, done -> done(p.keep(id)) }
            val kept = SettingsItem.Info({ TimeZoneChoices.kept(p.status).first }, { TimeZoneChoices.kept(p.status).second })
            val ha = p.homeAssistantRule?.let { match.get(it, current()) }
            val home = when (ha) {
                null -> null
                is TimeZoneChoices.HomeAssistantZone.Like ->
                    SettingsItem.Action(HOME_ASSISTANT, { ha.line(Instant.now()) }, "Use it") { done -> done(p.keep(ha.zone)) }
                else -> SettingsItem.Info({ HOME_ASSISTANT }, { ha.line(Instant.now()) })
            }
            return listOfNotNull(zone, kept, home)
        }
    }
}
