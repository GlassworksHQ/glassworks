/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.MoreInfoStateHeader
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The state text of More Info: the dialog row's (`state-card-display`) and the newer header's
 * (`ha-more-info-state-header`), for timestamp sensors as HA draws each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoStateTextTest {
    private val now = 1_762_963_680_000L // 2025-11-12T16:08:00Z
    private val inThreeHours = "2025-11-12T19:08:00+00:00"
    private val threeDaysAgo = "2025-11-09T16:08:00+00:00"

    private fun sensor(state: String, dc: String?) = EntityState("sensor.x", state, listOfNotNull(dc?.let { "device_class" to it }).toMap())

    @Test
    fun theRowShowsATimestampAsItsDateAndTimeAndAnUptimeAsATotal() {
        val ms = 1_762_974_480_000L
        assertEquals(HaFormat.formatDateTime(ms), MoreInfoStateHeader.stateCardDisplay(sensor(inThreeHours, "timestamp"), now))
        assertEquals("3 days", MoreInfoStateHeader.stateCardDisplay(sensor(threeDaysAgo, "uptime"), now))
        assertEquals("Invalid timestamp", MoreInfoStateHeader.stateCardDisplay(sensor("soon", "timestamp"), now))
        // Unknown, and anything that isn't a timestamp sensor, is HA's formatted state.
        assertEquals("Unknown", MoreInfoStateHeader.stateCardDisplay(sensor("unknown", "timestamp"), now))
        assertEquals(sensor("on", null).formattedState(), MoreInfoStateHeader.stateCardDisplay(sensor("on", null), now))
    }

    @Test
    fun theNewerHeaderShowsATimestampRelative() {
        assertEquals("In 3 hours", MoreInfoStateHeader.stateOf(sensor(inThreeHours, "timestamp"), now))
        assertEquals("Status", MoreInfoStateHeader.stateOf(sensor(inThreeHours, "timestamp"), now, override = "Status"))
    }
}
