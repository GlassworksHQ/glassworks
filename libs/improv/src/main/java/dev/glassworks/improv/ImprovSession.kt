package dev.glassworks.improv

/**
 * The Improv service state machine (pure Kotlin; the BLE and Wi-Fi sides are behind [Effects]).
 * Time is passed in explicitly, so tests drive it with a fake clock. Single-threaded: call every
 * method from the same thread.
 *
 * Behaviour follows https://www.improv-wifi.com/ble/ and ESPHome's improv_ble component:
 *  - Starts in AUTHORIZATION_REQUIRED (a physical action on the panel authorizes) or AUTHORIZED.
 *  - Authorization lapses after [authTimeoutMs] (spec suggests ~1 min; it's the gadget's choice).
 *  - Wi-Fi settings are only accepted while AUTHORIZED -> PROVISIONING -> PROVISIONED (+ RPC result
 *    with the redirect URL) or back to AUTHORIZED with error UNABLE_TO_CONNECT, restarting the
 *    authorization timeout.
 *  - Identify and device info work in both pre-provisioning states and don't need authorization.
 *  - Every complete packet first resets the error to NONE, so a repeated error notifies again.
 *
 * @param effects The device side: BLE publishing, Wi-Fi and device info.
 * @property capabilities The [Improv] `CAP_*` flags to advertise.
 * @param authTimeoutMs How long an authorization lasts: 2 minutes by default.
 */
public class ImprovSession(
    private val effects: Effects,
    public val capabilities: Int = Improv.CAP_IDENTIFY or Improv.CAP_DEVICE_INFO or Improv.CAP_SCAN_WIFI,
    private val authTimeoutMs: Long = DEFAULT_AUTH_TIMEOUT_MS,
) {
    /** What the session needs from the device. Called on the session's thread. */
    public interface Effects {
        /** Update the state characteristic (+notify) and the advertisement's service data. */
        public fun publishState(state: ImprovState)
        /** Update the error characteristic (+notify). */
        public fun publishError(error: ImprovError)
        /** Write the RPC result characteristic (+notify). */
        public fun sendResult(packet: ByteArray)
        /** Show the user which device this is: blink, beep or show something. */
        public fun identify()
        /** Start connecting; report back with [onWifiResult]. */
        public fun startWifi(ssid: String, password: String)
        /** The Wi-Fi networks the device sees now. */
        public fun scanNetworks(): List<ScannedNetwork>
        /** Firmware name, firmware version, hardware chip/variant, device name. */
        public fun deviceInfo(): List<String>
        /** Largest RPC result that fits in one notification for the current connection(s). */
        public fun maxResultSize(): Int = Improv.MAX_PACKET
    }

    /** The current state. */
    public var state: ImprovState = ImprovState.AUTHORIZATION_REQUIRED
        private set
    /** The current error. */
    public var error: ImprovError = ImprovError.NONE
        private set
    /** True between [start] and [stop]. */
    public var started: Boolean = false
        private set
    /** Wall time (same clock as `now`) when authorization lapses; 0 when not authorized. */
    public var authorizedUntil: Long = 0L
        private set

    private val assembler = RpcAssembler()
    private var lastWriteAt = 0L

    /**
     * Starts the session at [now]: in AUTHORIZATION_REQUIRED when [requireAuthorization], else
     * authorized right away. Publishes the state and clears the error.
     */
    public fun start(requireAuthorization: Boolean, now: Long) {
        started = true
        assembler.clear()
        if (requireAuthorization) setState(ImprovState.AUTHORIZATION_REQUIRED, force = true)
        else authorize(now, force = true)
        setError(ImprovError.NONE, force = true)
    }

    /** Stops the session and drops a half-received command. */
    public fun stop() {
        started = false
        assembler.clear()
        authorizedUntil = 0L
    }

    /**
     * Physical authorization on the device (button tap, proximity...): authorized until
     * [now] + the timeout. With [force], also from PROVISIONING or PROVISIONED.
     */
    public fun authorize(now: Long, force: Boolean = false) {
        if (!force && state != ImprovState.AUTHORIZATION_REQUIRED && state != ImprovState.AUTHORIZED) return
        authorizedUntil = now + authTimeoutMs
        setState(ImprovState.AUTHORIZED, force)
    }

    /** Call it every second or so; lapses the authorization once it times out. */
    public fun tick(now: Long) {
        if (state == ImprovState.AUTHORIZED && now >= authorizedUntil) {
            authorizedUntil = 0L
            setState(ImprovState.AUTHORIZATION_REQUIRED)
        }
    }

    /** A client disconnected: drop half-received packets, clear the error (like ESPHome). */
    public fun onDisconnect() {
        assembler.clear()
        setError(ImprovError.NONE)
    }

    /** Bytes written to the RPC command characteristic at [now] (possibly a fragment). */
    public fun onRpcWrite(chunk: ByteArray, now: Long) {
        // Fragments of one packet arrive back to back; a stale half packet would swallow the next one.
        if (assembler.pending > 0 && now - lastWriteAt > FRAGMENT_TIMEOUT_MS) assembler.clear()
        lastWriteAt = now
        val packet = assembler.add(chunk) ?: return
        setError(ImprovError.NONE)
        handle(Improv.parse(packet), now)
    }

    private fun handle(cmd: RpcCommand, now: Long) {
        when (cmd) {
            is RpcCommand.Invalid -> setError(ImprovError.INVALID_RPC)
            is RpcCommand.Unknown -> setError(ImprovError.UNKNOWN_RPC)
            is RpcCommand.Identify -> if (preProvisioning()) effects.identify()
            is RpcCommand.DeviceInfo -> effects.sendResult(Improv.encode(Improv.CMD_DEVICE_INFO, effects.deviceInfo().map { clip(it) }))
            is RpcCommand.ScanWifi -> effects.sendResult(Improv.encodeScan(effects.scanNetworks(), effects.maxResultSize()))
            is RpcCommand.WifiSettings -> {
                when {
                    state != ImprovState.AUTHORIZED -> setError(ImprovError.NOT_AUTHORIZED)
                    cmd.ssid.isEmpty() || cmd.ssid.toByteArray().size > 32 -> setError(ImprovError.INVALID_RPC)
                    else -> {
                        setState(ImprovState.PROVISIONING)
                        effects.startWifi(cmd.ssid, cmd.password)
                    }
                }
            }
        }
    }

    /**
     * The result of [Effects.startWifi]: on [success], PROVISIONED with [redirectUrl] (null or empty
     * for none) as the result; else the UNABLE_TO_CONNECT error and authorized again.
     */
    public fun onWifiResult(success: Boolean, redirectUrl: String?, now: Long) {
        if (state != ImprovState.PROVISIONING) return
        if (success) {
            // ESPHome order: RPC result first, then the PROVISIONED state.
            val urls = listOfNotNull(redirectUrl?.takeIf { it.isNotEmpty() })
            effects.sendResult(Improv.encode(Improv.CMD_WIFI_SETTINGS, urls))
            setState(ImprovState.PROVISIONED)
        } else {
            setError(ImprovError.UNABLE_TO_CONNECT)
            authorize(now, force = true)
        }
    }

    /** The device got a network some other way while Improv was running: PROVISIONED. */
    public fun onProvisionedExternally() {
        if (state == ImprovState.AUTHORIZATION_REQUIRED || state == ImprovState.AUTHORIZED) {
            setState(ImprovState.PROVISIONED)
        }
    }

    private fun preProvisioning() =
        state == ImprovState.AUTHORIZATION_REQUIRED || state == ImprovState.AUTHORIZED

    private fun setState(s: ImprovState, force: Boolean = false) {
        if (s == state && !force) return
        state = s
        if (s == ImprovState.AUTHORIZATION_REQUIRED || s == ImprovState.PROVISIONED) authorizedUntil = 0L
        effects.publishState(s)
    }

    private fun setError(e: ImprovError, force: Boolean = false) {
        if (e == error && !force) return
        error = e
        effects.publishError(e)
    }

    private fun clip(s: String): String {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > 60) out = out.dropLast(1)
        return out
    }

    internal companion object {
        /** 2 minutes: long enough to type a Wi-Fi password on a phone after tapping Allow. */
        const val DEFAULT_AUTH_TIMEOUT_MS: Long = 120_000L
        const val FRAGMENT_TIMEOUT_MS: Long = 2_000L
    }
}
