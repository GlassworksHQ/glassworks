package dev.glassworks.esphome.android

import dev.glassworks.esphome.*
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises `<name>._esphomelib._tcp` with Android [NsdManager], with the TXT records ESPHome
 * publishes (esphome `mdns/mdns_component.cpp`): `friendly_name`, `version`, `mac` (12 lowercase
 * hex digits), `board`, `network`, and the encryption records of esphome 2026.10.2
 * `mdns_component.cpp`: `api_encryption` = `Noise_NNpsk0_25519_ChaChaPoly_SHA256` when a key is in
 * use; otherwise `api_encryption_supported` = the same value plus `api_provisioning` = `zero-psk`.
 * Home Assistant's ESPHome integration listens for `_esphomelib._tcp.local.`, requires the `mac`
 * record and only treats `api_encryption` as "key required" (config_flow.py `async_step_zeroconf`).
 *
 * The SRV target is the Android hostname, not `<name>.local`; HA replaces the discovered name with
 * `DeviceInfoResponse.name` once it connects.
 *
 * Re-advertise ([stop] then [start]) when the encryption state changes, e.g. from
 * [ApiServer.onKeyActivated], so HA sees that a key is now required.
 *
 * Call it from one thread, or synchronize: it keeps one registration and isn't thread-safe.
 *
 * @param context Any context; the application context is kept.
 * @param board The `board` TXT record (ESPHome puts its board id there), or null to leave it out.
 */
public class EspHomeMdns(context: Context, private val board: String? = null) {
    private val app = context.applicationContext ?: context

    /**
     * Made on the first [start], on its thread: NsdManager's constructor waits for its channel to Android's NsdService
     * to connect (a quarter of a second on a slow device), so making this object costs the thread that makes it nothing.
     */
    private val nsd: NsdManager? by lazy { app.getSystemService(Context.NSD_SERVICE) as? NsdManager }
    private var listener: NsdManager.RegistrationListener? = null

    /**
     * Advertises [identity] on [port]. [encrypted]: a key is in use, so Home Assistant asks for it.
     * [provisionable]: no key yet, and Home Assistant may set one. Does nothing while already advertising.
     */
    public fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = identity.name
            serviceType = "_esphomelib._tcp"
            setPort(port)
            setAttribute("friendly_name", identity.friendlyName)
            setAttribute("version", identity.esphomeVersion)
            setAttribute("mac", identity.macHex)
            board?.let { setAttribute("board", it) }
            setAttribute("network", "wifi")
            if (encrypted) {
                setAttribute("api_encryption", ApiServer.NOISE_PROTOCOL)
            } else if (provisionable) {
                setAttribute("api_encryption_supported", ApiServer.NOISE_PROTOCOL)
                setAttribute("api_provisioning", "zero-psk")
            }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                EspHomeLog.i("mDNS: registered ${info.serviceName}._esphomelib._tcp port $port")

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                EspHomeLog.w("mDNS: registration failed ($errorCode)")

            override fun onServiceUnregistered(info: NsdServiceInfo) =
                EspHomeLog.i("mDNS: unregistered ${info.serviceName}")

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                EspHomeLog.w("mDNS: unregistration failed ($errorCode)")
        }
        val nsd = nsd ?: return EspHomeLog.w("mDNS: this device has no NsdManager")
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            EspHomeLog.w("mDNS: registerService threw", e)
        }
    }

    /** Advertises [server], which must be started, with its port and current encryption state. */
    public fun start(identity: DeviceIdentity, server: ApiServer): Unit =
        start(identity, server.localPort, encrypted = server.encrypted, provisionable = server.provisionable)

    /** Stops advertising. Does nothing when not advertising. */
    public fun stop() {
        val l = listener ?: return
        listener = null
        try { nsd?.unregisterService(l) } catch (e: Exception) { EspHomeLog.w("mDNS: unregister threw", e) }
    }
}
