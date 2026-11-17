package sh.edm.s1kiosk.setup

/**
 * The scan before each setup hotspot start (#197): on most devices the radio can't scan once it is the access point,
 * so the setup page lists what this scan found. Main thread.
 *
 * Each run clears the list first, so the page never gets one from an earlier hotspot: when [scan] can't run (the
 * Wi-Fi provisioner isn't up), the hotspot starts at once with an empty list. A scan that a newer run superseded
 * doesn't overwrite that run's list (#418 verifier).
 */
class HotspotScanStep(
    /** Starts a fresh scan; false when it can't. Its callback gets the networks. */
    private val scan: (done: (List<WifiNetwork>) -> Unit) -> Boolean,
    /** Where the setup page reads the list (the runtime's `hotspotScan`). */
    private val store: (List<WifiNetwork>) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var generation = 0

    /** The hotspot driver's `beforeStart`: scan, keep the list, then [start]. */
    operator fun invoke(start: () -> Unit) {
        val mine = ++generation
        store(emptyList())
        val asked = scan { nets ->
            if (mine == generation) {
                store(nets)
                log("scanned before the hotspot: ${nets.size} networks")
            }
            start() // a superseded run's start is the driver's to ignore
        }
        if (!asked) {
            log("no scan before the hotspot: the Wi-Fi provisioner isn't up")
            start()
        }
    }
}
