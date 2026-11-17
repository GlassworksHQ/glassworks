/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewQueueTest {
    private val door = 1L
    private val garage = 2L
    private val washer = 3L

    @Test
    fun theOpenViewKeepsTheScreenAndNewOnesWait() {
        val q = ViewQueue().arrive(door).arrive(garage).arrive(washer)
        assertEquals(door, q.open)
        assertEquals(2, q.waiting)
        assertEquals("then in the order they came", listOf(garage, washer), listOf(q.next().open, q.next().next().open))
    }

    @Test
    fun nextOnTheLastLeavesNothingOpen() {
        val q = ViewQueue().arrive(door).next()
        assertNull(q.open)
        assertEquals(0, q.waiting)
        assertEquals(ViewQueue(), q.next())
    }

    @Test
    fun arrivingTwiceKeepsOnePlace() {
        assertEquals(ViewQueue(listOf(door, garage)), ViewQueue().arrive(door).arrive(garage).arrive(door))
    }

    @Test
    fun aViewThePersonAskedForOpensInFront() {
        val q = ViewQueue().arrive(door).arrive(garage).jump(washer)
        assertEquals(listOf(washer, door, garage), q.ids)
        assertEquals("tapped in the list while waiting", listOf(garage, washer, door), q.jump(garage).ids)
    }

    @Test
    fun aSecondRingKeepsItsPlace() {
        val ring2 = 9L
        val q = ViewQueue().arrive(garage).arrive(door).arrive(washer).replace(door, ring2)
        assertEquals(listOf(garage, ring2, washer), q.ids)
        assertEquals("the open one replaced stays open", listOf(ring2, washer), ViewQueue().arrive(door).arrive(washer).replace(door, ring2).ids)
        assertEquals("nothing to replace: it waits", listOf(garage, ring2), ViewQueue().arrive(garage).replace(door, ring2).ids)
    }

    @Test
    fun aClearedOrExpiredViewLeavesTheLineWhereverItIs() {
        val q = ViewQueue().arrive(door).arrive(garage).arrive(washer)
        assertEquals(listOf(door, washer), q.remove(garage).ids)
        assertEquals("the open one: the next opens", garage, q.remove(door).open)
        assertEquals(q, q.remove(42L))
    }

    @Test
    fun theButtonSaysWhereItLeads() {
        assertEquals(ViewButton.NEXT, ViewButton.of(1, ViewSize.FULL, Input.TOUCH))
        assertEquals(ViewButton.NEXT, ViewButton.of(2, ViewSize.HALF, Input.TOUCH))
        assertEquals(ViewButton.BACK_NOW, ViewButton.of(0, ViewSize.FULL, Input.TOUCH))
        assertEquals(ViewButton.CLOSE, ViewButton.of(0, ViewSize.HALF, Input.TOUCH))
        assertEquals(listOf("Next", "Back now", "Close"), ViewButton.entries.map { it.label })
    }

    @Test
    fun noButtonWithoutTouch() {
        assertNull(ViewButton.of(1, ViewSize.FULL, Input.NONE))
        assertNull(ViewButton.of(0, ViewSize.HALF, Input.NONE))
    }

    @Test
    fun aListTheCallerChangesLaterDoesntChangeTheQueue() {
        val ids = mutableListOf(door)
        val q = ViewQueue(ids)
        ids.clear()
        assertEquals(door, q.open)
    }
}
