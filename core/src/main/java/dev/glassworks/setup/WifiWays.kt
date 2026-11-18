package dev.glassworks.setup


/**
 * Which way the Wi-Fi step leads with, and which it offers as alternatives (#468). In order of preference:
 *
 * 1. [Way.IMPROV]: Home Assistant (its app on a phone, or its settings) finds the panel over Bluetooth and sends
 *    the Wi-Fi. Nothing to type on the panel.
 * 2. [Way.HOTSPOT]: "Set up from a phone". A phone joins the panel's own Wi-Fi and sends yours from a page (#197).
 * 3. [Way.MANUAL]: "Choose a network", typed on the panel.
 *
 * The first one the panel can do right now leads; the rest that it can do follow, in the same order. Pure, so the
 * order and the fallback have tests (WifiWaysTest).
 */
object WifiWays {
    enum class Way { IMPROV, HOTSPOT, MANUAL }

    /** [lead] fills the card; [others] wait behind "Other ways to set up ›", in this order. */
    data class Plan(val lead: Way, val others: List<Way>)

    /**
     * [improv]: Bluetooth setup as the step shows it. It can lead while it runs, in any state but one that couldn't
     * start; switched off (Not now, or its window ended) it leads only when the hotspot can't open, and is otherwise an
     * alternative ("Use Bluetooth"). One that couldn't start never leads, but stays an alternative ("Try Bluetooth
     * again"): some failures pass (Bluetooth off, a permission), and the step keeps a way to retry.
     *
     * [hotspot]: the setup hotspot's line, null where the app has none. It counts while one tap opens it
     * ([HotspotStep.Action.OPEN], [HotspotStep.Action.ALLOW] for Android's permission, or [HotspotStep.Action.RETRY]);
     * off with Wi-Fi off, or with the permission refused for good, it doesn't. Android's permission
     * ([HotspotStep.Action.ALLOW]) shows before anyone picks the hotspot (nobody has asked for it yet), so it doesn't
     * lead by itself: the hotspot keeps its place in the order, and its Allow is its button.
     */
    fun plan(improv: WifiStep.ImprovLine, hotspot: HotspotStep.Card?): Plan {
        val improvRuns = improv.phase != WifiStep.Phase.OFF && improv.phase != WifiStep.Phase.UNAVAILABLE
        val improvStartable = improv.phase == WifiStep.Phase.OFF
        val improvRetry = improv.phase == WifiStep.Phase.UNAVAILABLE
        val hotspotOpens = hotspot?.title == null && hotspot?.action in OPENS
        val lead = when {
            improvRuns -> Way.IMPROV
            hotspotOpens -> Way.HOTSPOT
            // Switched off (Not now) it gives way to the hotspot, but one tap still beats typing it.
            improvStartable -> Way.IMPROV
            else -> Way.MANUAL
        }
        val can = buildList {
            if (improvRuns || improvStartable || improvRetry) add(Way.IMPROV)
            if (hotspotOpens) add(Way.HOTSPOT)
            add(Way.MANUAL)
        }
        return Plan(lead, can - lead)
    }

    /** The hotspot's actions that open it in one tap: its card's button while it is a way to join. */
    val OPENS = setOf(HotspotStep.Action.OPEN, HotspotStep.Action.ALLOW, HotspotStep.Action.RETRY)
}
