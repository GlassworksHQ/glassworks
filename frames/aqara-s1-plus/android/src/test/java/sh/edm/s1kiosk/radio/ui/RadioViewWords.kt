package sh.edm.s1kiosk.radio.ui

import sh.edm.s1kiosk.radio.RadioMode

/**
 * The radio view's own words, which its twin on the Manage page says its own way (#269): one list for
 * RadioTwinTest and PanelManageParityTest. Everything else the view draws must be in the twin.
 */
object RadioViewWords {
    /** The role picker's tiles (RadioTechPicker): the twin's Role row and its list of roles. */
    val PICKER = setOf("Zigbee", "For ZHA or Zigbee2MQTT", "Coordinator", "Repeater β", "Thread", "For Matter over Thread", "Border router")

    /** Off's legend under "The radio is off". */
    const val OFF_LEGEND = "β  experimental"

    /** The disclosures the twin leaves out on purpose (RadioTwin): pasting a dataset, and the longer explanation. */
    fun disclosure(text: String) = text.startsWith(RadioFollowUp.DATASET_DISCLOSURE) || text.startsWith("How does this work?")

    /** How [f]'s role is doing, in the card's header and pill: the Manage page's chip says it. */
    fun status(f: RadioFollowUp.Facts): Set<String> = setOf(RoleStyle.header(f.mode), RadioFollowUp.health(f).label)

    /** The view's own words for [f]. */
    fun own(f: RadioFollowUp.Facts, text: String) = text in PICKER || text == OFF_LEGEND || disclosure(text) || text in status(f)

    /** The twin's own words for [f]: its Role row's title and the role's short name. */
    fun twinOwn(f: RadioFollowUp.Facts, word: String) =
        word == RadioTwin.ROLE || (f.mode != RadioMode.OFF && word == RadioStep.shortName(f.mode))
}
