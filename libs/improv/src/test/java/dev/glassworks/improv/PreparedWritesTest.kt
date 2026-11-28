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

    @Test fun aRefusedWritesTailNeverRunsAsACommand() {
        // CodeRabbit on #974: after the cap, the next pieces of the same write would have started a new one, and
        // Execute Write would have run that tail. They're refused until the write is executed or canceled.
        val w = PreparedWrites(max = 64)
        repeat(3) { assertTrue(w.append(phone, ByteArray(20))) }
        assertFalse(w.append(phone, ByteArray(20)))
        assertFalse("the rest of the write is refused", w.append(phone, byteArrayOf(1, 2)))
        assertNull("execute runs nothing", w.take(phone))
        assertTrue("after it, a new write works", w.append(phone, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(3), w.take(phone))
    }

    @Test fun appendAndTakeAtOnceLoseNoPiece() {
        // One lock for the map and the buffers: every piece an append accepted is in exactly one take.
        val w = PreparedWrites(max = Int.MAX_VALUE)
        var taken = 0
        val writer = Thread { repeat(20_000) { assertTrue(w.append(phone, byteArrayOf(1))) } }
        writer.start()
        while (writer.isAlive) taken += w.take(phone)?.size ?: 0
        writer.join()
        taken += w.take(phone)?.size ?: 0
        org.junit.Assert.assertEquals(20_000, taken)
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
