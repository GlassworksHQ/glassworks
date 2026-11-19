/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** #466: the handoff's per-address limit. */
class PerAddressTest {
    @Test fun countPerWindowThenAFreshWindow() {
        val p = PerAddress(count = 3, windowMs = 10_000)
        assertEquals(listOf(true, true, true, false), (1..4).map { p.allow("192.0.2.77", 0) })
        assertFalse("still within the window", p.allow("192.0.2.77", 9_999))
        assertTrue("the first try is 10 s old", p.allow("192.0.2.77", 10_000))
    }

    @Test fun eachAddressHasItsOwnCount() {
        val p = PerAddress(count = 1, windowMs = 10_000)
        assertTrue(p.allow("192.0.2.77", 0))
        assertFalse(p.allow("192.0.2.77", 0))
        assertTrue(p.allow("192.0.2.78", 0))
    }

    @Test fun itRemembersAtMost64AddressesAndAForgottenOneStartsAfresh() {
        val p = PerAddress(count = 1, windowMs = 10_000)
        assertTrue(p.allow("first", 0))
        assertFalse(p.allow("first", 0))
        // 64 others push it out: memory stays bounded, and the cap isn't a strict rate.
        for (n in 1..64) assertTrue(p.allow("other-$n", 0))
        assertTrue("forgotten, so a fresh window", p.allow("first", 0))
        assertFalse("the one just seen is remembered", p.allow("other-64", 0))
    }

    @Test fun aRecentlySeenAddressIsKeptOverAnOlderOne() {
        val p = PerAddress(count = 1, windowMs = 10_000, maxAddresses = 2)
        assertTrue(p.allow("a", 0))
        assertTrue(p.allow("b", 0))
        assertFalse(p.allow("a", 0))
        assertTrue(p.allow("c", 0))
        assertFalse("a was seen after b, so b went", p.allow("a", 0))
        assertTrue(p.allow("b", 0))
    }

    @Test fun concurrentTriesGetExactlyTheCount() {
        val p = PerAddress(count = 5, windowMs = 10_000)
        val start = CountDownLatch(1)
        val allowed = AtomicInteger()
        val threads = (1..8).map { Thread { start.await(); repeat(100) { if (p.allow("192.0.2.77", 0)) allowed.incrementAndGet() } }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(5, allowed.get())
    }
}
