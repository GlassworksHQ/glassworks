package dev.glassworks.improv

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A BLE long write's pieces, per device, until it is executed ([ImprovBleServer]). At most [max] bytes per device: a
 * client that keeps sending prepared writes without executing them would otherwise grow the buffer until the app runs
 * out of memory. An Improv command is at most [Improv.MAX_PACKET] bytes (a Wi-Fi network's name and password fit in
 * well under that), so the cap never cuts a real one. Thread-safe.
 */
internal class PreparedWrites(private val max: Int = MAX_BYTES) {
    private val pending = ConcurrentHashMap<String, ByteArrayOutputStream>()

    /** Adds [piece] to [device]'s write. False when it would pass [max]: the write so far is dropped. */
    fun append(device: String, piece: ByteArray): Boolean {
        val buf = pending.getOrPut(device) { ByteArrayOutputStream() }
        synchronized(buf) {
            if (buf.size() + piece.size > max) {
                pending.remove(device, buf)
                return false
            }
            buf.write(piece)
        }
        return true
    }

    /** [device]'s write, whole, and forgets it; null when none is pending. */
    fun take(device: String): ByteArray? = pending.remove(device)?.let { synchronized(it) { it.toByteArray() } }

    fun drop(device: String) { pending.remove(device) }

    fun clear() = pending.clear()

    companion object {
        /** Four times the largest Improv command. */
        const val MAX_BYTES: Int = 1024
    }
}
