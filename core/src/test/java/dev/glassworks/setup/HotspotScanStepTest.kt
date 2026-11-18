package dev.glassworks.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scan before each setup hotspot start: a fresh list per run, never an old one, never a superseded one. */
class HotspotScanStepTest {
    private val home = listOf(WifiNetwork("Example-Home", -50, true))
    private val upstairs = listOf(WifiNetwork("Example-Upstairs", -60, true))

    private var stored: List<WifiNetwork>? = null
    private val scans = mutableListOf<(List<WifiNetwork>) -> Unit>()
    private var canScan = true
    private var starts = 0
    private val step = HotspotScanStep(scan = { done -> if (canScan) { scans += done; true } else false }, store = { stored = it })

    @Test
    fun `it scans, keeps the list, then starts`() {
        step { starts++ }
        assertEquals(0, starts)
        scans.single()(home)
        assertEquals(home, stored)
        assertEquals(1, starts)
    }

    @Test
    fun `when it can't scan, the list from an earlier run is gone and it starts at once`() {
        step { starts++ }
        scans.single()(home)
        canScan = false
        step { starts++ }
        assertEquals("never a list minutes old", emptyList<WifiNetwork>(), stored)
        assertEquals(2, starts)
    }

    @Test
    fun `a scan a newer run superseded doesn't overwrite that run's list`() {
        step { starts++ }
        val old = scans.single()
        step { starts++ }
        scans.last()(upstairs)
        old(home)
        assertEquals(upstairs, stored)
    }
}
