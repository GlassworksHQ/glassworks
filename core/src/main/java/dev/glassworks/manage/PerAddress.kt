/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

/**
 * At most [count] tries per address in any [windowMs], on a monotonic clock. Remembers [maxAddresses] addresses,
 * the least recently seen going first, so a flood from many addresses can't grow it. That cap bounds memory, not
 * the rate: with more than [maxAddresses] addresses trying at once, an address that was forgotten starts a fresh
 * window when it comes back. Thread-safe.
 */
class PerAddress(private val count: Int, private val windowMs: Long, private val maxAddresses: Int = 64) {
    private val tries = LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true)

    /** Whether [address] may try now; counts the try when it may. */
    @Synchronized
    fun allow(address: String, nowMs: Long): Boolean {
        val q = tries.getOrPut(address) { ArrayDeque() }
        while (q.isNotEmpty() && nowMs - q.first() >= windowMs) q.removeFirst()
        while (tries.size > maxAddresses) tries.remove(tries.keys.first())
        if (q.size >= count) return false
        q.addLast(nowMs)
        return true
    }
}
