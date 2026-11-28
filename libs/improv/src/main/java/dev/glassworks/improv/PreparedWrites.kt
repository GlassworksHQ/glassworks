package dev.glassworks.improv

import java.io.ByteArrayOutputStream

/**
 * A BLE long write's pieces, per device, until it is executed ([ImprovBleServer]). At most [max] bytes per device: a
 * client that keeps sending prepared writes without executing them would otherwise grow the buffer until the app runs
 * out of memory. An Improv command is at most [Improv.MAX_PACKET] bytes (a Wi-Fi network's name and password fit in
 * well under that), so the cap never cuts a real one. Thread-safe.
 */
internal class PreparedWrites(private val max: Int = MAX_BYTES) {
    /** A device's write so far; [rejected] once it passed [max], so the rest of that write is refused too. */
    private class Write(val buf: ByteArrayOutputStream = ByteArrayOutputStream(), var rejected: Boolean = false)

    // One lock for the map and the buffers: a take between finding a buffer and writing to it would lose a piece.
    private val pending = HashMap<String, Write>()

    /**
     * Adds [piece] to [device]'s write. False when it would pass [max]: the write so far is dropped, and every later
     * piece of the same write is refused until it is executed or canceled ([take]) or the device goes ([drop]), so no
     * tail of it runs as a command of its own.
     */
    @Synchronized
    fun append(device: String, piece: ByteArray): Boolean {
        val w = pending.getOrPut(device) { Write() }
        if (w.rejected) return false
        if (w.buf.size() + piece.size > max) {
            w.rejected = true
            w.buf.reset()
            return false
        }
        w.buf.write(piece)
        return true
    }

    /** [device]'s write, whole, and forgets it; null when none is pending or it was refused. */
    @Synchronized
    fun take(device: String): ByteArray? = pending.remove(device)?.takeUnless { it.rejected }?.buf?.toByteArray()

    @Synchronized
    fun drop(device: String) { pending.remove(device) }

    @Synchronized
    fun clear() = pending.clear()

    companion object {
        /** Four times the largest Improv command. */
        const val MAX_BYTES: Int = 1024
    }
}
