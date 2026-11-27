package dev.glassworks.improv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A BLE long write is held until it is executed, at most [PreparedWrites.MAX_BYTES] per device: a client that keeps
 * preparing writes and never executes them can't grow it until the app runs out of memory.
 */
class PreparedWritesTest {
    private val phone = "02:00:00:00:00:01"

    @Test fun theLargestWiFiSettingsCommandFits() {
        // Improv's longest: a 32-byte network name and a 63-character password, in 20-byte pieces (the default MTU).
        val cmd = Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("n".repeat(32), "p".repeat(63)))
        val w = PreparedWrites()
        for (piece in cmd.toList().chunked(20)) assertTrue(w.append(phone, piece.toByteArray()))
        assertArrayEquals(cmd, w.take(phone))
        assertNull("taken once", w.take(phone))
        assertTrue(Improv.MAX_PACKET <= PreparedWrites.MAX_BYTES)
    }

    @Test fun aWriteThatNeverEndsIsDroppedAtTheCap() {
        val w = PreparedWrites(max = 64)
        repeat(3) { assertTrue(w.append(phone, ByteArray(20))) }
        assertFalse(w.append(phone, ByteArray(20)))
        assertNull("the write so far is gone", w.take(phone))
        assertTrue("a new write starts empty", w.append(phone, ByteArray(64)))
        assertFalse(w.append(phone, ByteArray(1)))
    }

    @Test fun eachDeviceHasItsOwn() {
        val w = PreparedWrites(max = 10)
        val other = "02:00:00:00:00:02"
        assertTrue(w.append(phone, ByteArray(10)))
        assertTrue(w.append(other, byteArrayOf(1)))
        w.drop(phone)
        assertNull(w.take(phone))
        assertArrayEquals(byteArrayOf(1), w.take(other))
    }
}
