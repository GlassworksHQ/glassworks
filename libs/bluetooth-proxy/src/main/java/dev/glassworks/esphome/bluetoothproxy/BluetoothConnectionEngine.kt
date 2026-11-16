package dev.glassworks.esphome.bluetoothproxy

import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.BluetoothConnectionHandler
import dev.glassworks.esphome.EspHomeLog
import dev.glassworks.esphome.GattEvent
import dev.glassworks.esphome.GattRequest

/**
 * The proxy's active connections: Home Assistant's GATT requests on Android's links, as ESPHome's
 * bluetooth_proxy does on an ESP32. Set it as [ApiServer.bluetoothConnections] next to a
 * [BluetoothProxyEngine]; [close] takes it out again.
 *
 * A slot table of [limit] links (ESPHome's default is 3). Each link connects, asks for an MTU of 517,
 * finds its services, and only then tells Home Assistant it is connected. Requests on a link wait in
 * its queue, one at a time, because Android runs one GATT operation per link. Every step has a
 * timeout: a link that stops answering is dropped and its slot freed. Everything runs on one thread
 * ([loop]). Addresses are never logged.
 */
class BluetoothConnectionEngine internal constructor(
    private val radio: GattRadio,
    private val send: (GattEvent) -> Boolean,
    private val loop: BluetoothProxyEngine.Loop,
    val limit: Int,
    private val timeouts: Timeouts,
    /** Takes this engine out of its server, if it is still in. */
    private val detach: (BluetoothConnectionEngine) -> Unit,
) : BluetoothConnectionHandler {
    constructor(server: ApiServer, radio: GattRadio, limit: Int = DEFAULT_LIMIT) : this(
        radio, server::sendBluetoothEvent, BluetoothProxyEngine.ExecutorLoop("bt-gatt"), limit, Timeouts(),
        { server.removeBluetoothConnections(it) },
    )

    /** How long each step may take, in ms. */
    internal class Timeouts(val connectMs: Long = 20_000, val operationMs: Long = 10_000, val disconnectMs: Long = 5_000)

    companion object {
        /** ESPHome's `connection_slots` default. The RTL8821CS's own limit isn't known yet (#246). */
        const val DEFAULT_LIMIT = 3
        /** The largest ATT MTU; the device may settle on less. */
        const val MTU = 517
        /** BLE's MTU before an exchange. */
        const val DEFAULT_MTU = 23
    }

    private enum class State { CONNECTING, MTU, DISCOVERING, READY, CLOSING }

    /** One slot. Owned by the loop thread. */
    private inner class Link(val address: Long) {
        var gatt: GattLink? = null
        var state = State.CONNECTING
        var mtu = DEFAULT_MTU
        val queue = ArrayDeque<GattRequest.Handle>()
        var inFlight: GattRequest.Handle? = null
        var timer: (() -> Unit)? = null

        fun arm(ms: Long, onTimeout: () -> Unit) {
            timer?.invoke()
            timer = loop.schedule(loop.now() + ms) { if (links[address] === this) onTimeout() }
        }

        fun disarm() { timer?.invoke(); timer = null }

        /** The listener for this link. Once dropped, the link is CLOSING with nothing in flight, so late callbacks do nothing. */
        val listener = object : GattLink.Listener {
            private fun on(task: Link.() -> Unit) = loop.execute { task() }
            override fun onConnectionState(connected: Boolean, status: Int) = on { connectionState(this, connected, status) }
            override fun onMtu(mtu: Int, status: Int) = on { mtuChanged(this, mtu, status) }
            override fun onServicesDiscovered(status: Int) = on { discovered(this, status) }
            override fun onRead(handle: Int, data: ByteArray, status: Int) = on { done(this, handle, status) { GattEvent.Read(address, handle, data) } }
            override fun onWritten(handle: Int, status: Int) = on { done(this, handle, status) { GattEvent.Written(address, handle) } }
            override fun onDescriptorRead(handle: Int, data: ByteArray, status: Int) = on { done(this, handle, status) { GattEvent.Read(address, handle, data) } }
            override fun onDescriptorWritten(handle: Int, status: Int) = on { done(this, handle, status) { GattEvent.Written(address, handle) } }
            override fun onNotification(handle: Int, data: ByteArray) = on { if (state == State.READY) send(GattEvent.Notification(address, handle, data)) }
        }
    }

    // Owned by the loop thread.
    private val links = LinkedHashMap<Long, Link>()
    private var closed = false

    @Volatile private var free = GattEvent.ConnectionsFree(limit, limit, emptyList())

    /** Links in use now, for a settings page. */
    val inUse: Int get() = free.limit - free.free

    override fun connectionsFree(): GattEvent.ConnectionsFree = free

    override fun onRequest(request: GattRequest) = loop.execute {
        if (closed) return@execute
        // A throw (Android's or a send's) must not end the loop's task with the request unanswered.
        try { handle(request) } catch (e: RuntimeException) { EspHomeLog.w("Bluetooth proxy: a GATT request failed", e) }
    }

    override fun onSubscriberChanged() = loop.execute { if (!closed) closeAll() }

    /** Closes every link for good and takes the engine out of its server. */
    fun close() {
        detach(this)
        loop.execute {
            closeAll()
            closed = true
        }
        loop.shutdown()
    }

    private fun handle(r: GattRequest) {
        val link = links[r.address]
        when (r) {
            is GattRequest.Connect -> connect(r.address, link)
            is GattRequest.Disconnect -> disconnect(r.address, link)
            is GattRequest.GetServices -> {
                val gatt = link?.gatt
                if (link?.state != State.READY || gatt == null) {
                    send(GattEvent.Error(r.address, 0, GattEvent.NOT_CONNECTED))
                    return
                }
                for (service in gatt.services()) send(GattEvent.Services(r.address, listOf(service)))
                send(GattEvent.ServicesDone(r.address))
            }
            is GattRequest.Handle -> {
                if (link?.state != State.READY) {
                    send(GattEvent.Error(r.address, r.handle, GattEvent.NOT_CONNECTED))
                    return
                }
                link.queue.addLast(r)
                pump(link)
            }
        }
    }

    private fun connect(address: Long, existing: Link?) {
        if (existing != null) {
            // As ESPHome: connected already, say so again; still on its way, wait for it. A link that is
            // closing can't be reused: say not connected, and Home Assistant tries again once it's gone.
            when (existing.state) {
                State.READY -> send(GattEvent.Connection(address, true, existing.mtu, 0))
                State.CLOSING -> send(GattEvent.Connection(address, false, 0, 0))
                else -> Unit
            }
            return
        }
        if (links.size >= limit) {
            EspHomeLog.w("Bluetooth proxy: no free connection slot (${links.size} of $limit)")
            send(GattEvent.Connection(address, false, 0, 0))
            return
        }
        val link = Link(address)
        links[address] = link
        publishFree()
        val gatt = try { radio.connect(address, link.listener) } catch (e: RuntimeException) {
            EspHomeLog.w("Bluetooth proxy: connecting failed: ${e.message}")
            null
        }
        if (gatt == null) {
            drop(link, GattEvent.CONN_FAIL_ESTABLISH)
            return
        }
        link.gatt = gatt
        link.arm(timeouts.connectMs) {
            EspHomeLog.w("Bluetooth proxy: a connection didn't come up in ${timeouts.connectMs / 1000} s")
            drop(link, GattEvent.CONN_TIMEOUT)
        }
    }

    private fun disconnect(address: Long, link: Link?) {
        if (link == null) {
            send(GattEvent.Connection(address, false, 0, 0))
            publishFree()
            return
        }
        if (link.state == State.CLOSING) return
        link.state = State.CLOSING
        link.gatt?.disconnect()
        link.arm(timeouts.disconnectMs) { drop(link, 0) }
    }

    private fun connectionState(link: Link, connected: Boolean, status: Int) {
        if (!connected) {
            drop(link, if (link.state == State.CLOSING) 0 else connError(status))
            return
        }
        if (link.state != State.CONNECTING) return
        link.state = State.MTU
        if (link.gatt?.requestMtu(MTU) != true) discover(link)
    }

    private fun mtuChanged(link: Link, mtu: Int, status: Int) {
        if (link.state != State.MTU) return
        if (status == 0) link.mtu = mtu
        discover(link)
    }

    private fun discover(link: Link) {
        link.state = State.DISCOVERING
        if (link.gatt?.discoverServices() != true) drop(link, GattEvent.GATT_ERROR)
    }

    private fun discovered(link: Link, status: Int) {
        if (link.state != State.DISCOVERING) return
        if (status != 0) {
            drop(link, gattError(status))
            return
        }
        link.state = State.READY
        link.disarm()
        send(GattEvent.Connection(link.address, true, link.mtu, 0))
    }

    /** Starts the next queued operation, if none is running. */
    private fun pump(link: Link) {
        val gatt = link.gatt ?: return
        while (link.inFlight == null && link.state == State.READY) {
            val op = link.queue.removeFirstOrNull() ?: return
            if (op is GattRequest.Notify) {
                // Local only: Home Assistant writes the CCCD itself (REMOTE_CACHING).
                send(if (gatt.setNotify(op.handle, op.enable)) GattEvent.NotifySet(link.address, op.handle) else GattEvent.Error(link.address, op.handle, GattEvent.GATT_ERROR))
                continue
            }
            val started = when (op) {
                is GattRequest.Read -> gatt.read(op.handle)
                is GattRequest.Write -> gatt.write(op.handle, op.data, op.withResponse)
                is GattRequest.ReadDescriptor -> gatt.readDescriptor(op.handle)
                is GattRequest.WriteDescriptor -> gatt.writeDescriptor(op.handle, op.data)
                is GattRequest.Notify -> error("handled above")
            }
            if (!started) {
                send(GattEvent.Error(link.address, op.handle, GattEvent.GATT_ERROR))
                continue
            }
            link.inFlight = op
            link.arm(timeouts.operationMs) {
                // Android holds a link's later operations until this one's callback, so the link is stuck.
                EspHomeLog.w("Bluetooth proxy: a GATT operation got no answer in ${timeouts.operationMs / 1000} s; dropping the link")
                send(GattEvent.Error(link.address, op.handle, GattEvent.GATT_ERROR))
                drop(link, GattEvent.CONN_TIMEOUT)
            }
        }
    }

    private inline fun done(link: Link, handle: Int, status: Int, ok: () -> GattEvent) {
        val op = link.inFlight ?: return
        if (op.handle != handle) return
        link.inFlight = null
        link.disarm()
        send(if (status == 0) ok() else GattEvent.Error(link.address, handle, gattError(status)))
        pump(link)
    }

    /** Frees [link]'s slot now and tells Home Assistant it is gone, with [error]. */
    private fun drop(link: Link, error: Int) {
        if (links[link.address] !== link) return
        links.remove(link.address)
        release(link)
        send(GattEvent.Connection(link.address, false, 0, error))
        publishFree()
    }

    private fun closeAll() {
        if (links.isEmpty()) return
        val all = links.values.toList()
        links.clear()
        for (link in all) {
            link.gatt?.disconnect()
            release(link)
            send(GattEvent.Connection(link.address, false, 0, GattEvent.CONN_TERMINATE_LOCAL_HOST))
        }
        publishFree()
    }

    private fun release(link: Link) {
        link.disarm()
        link.queue.clear()
        link.inFlight = null
        link.state = State.CLOSING
        link.gatt?.close()
    }

    private fun publishFree() {
        free = GattEvent.ConnectionsFree(limit - links.size, limit, links.keys.toList())
        send(free)
    }

    /** An ATT error (1–255) passes through; Android's own codes above that become ESPHome's generic one. */
    private fun gattError(status: Int) = if (status in 1..255) status else GattEvent.GATT_ERROR

    /** Android's disconnect reasons are HCI codes, as ESPHome's (timeout 8, peer 0x13, local 0x16, failed 0x3E). */
    private fun connError(status: Int) = if (status in 0..255) status else GattEvent.GATT_ERROR
}
