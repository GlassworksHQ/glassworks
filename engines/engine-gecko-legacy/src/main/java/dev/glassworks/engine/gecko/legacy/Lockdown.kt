/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.glassworks.engine.gecko.PageGuard
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * The legacy engine's [PageGuard]: [LockdownPolicy] for pages, frames, new windows and downloads, and the
 * lockdown extension (assets/lockdown) for everything else a page fetches. The extension shows the in-page
 * note for every refusal, its own and the ones this class sends it.
 *
 * Logs name the origin only: a URL can carry a signed path or a token.
 */
internal class Lockdown(
    private val logTag: String,
    /** Runs a task after a delay on the main thread. Tests pass their own, to fire [READY_TIMEOUT_MS] at once. */
    private val later: (Long, () -> Unit) -> Unit = { ms, task -> Handler(Looper.getMainLooper()).postDelayed(task, ms) },
) : PageGuard {
    private val policy = LockdownPolicy()
    private var port: WebExtension.Port? = null
    /** Opens when the extension's background script connects, which it does after its webRequest listener is up. */
    private val ready = ReadyGate()

    override val prefs: Map<String, Any> = linkedMapOf(
        // One content process, none started ahead, and no process per site (cross-origin frames are refused anyway):
        // the Mirror has about 930 MB of RAM. The extensions process stays, as GeckoView runs it.
        "dom.ipc.processCount" to 1,
        "dom.ipc.processPrelaunch.enabled" to false,
        "fission.autostart" to false,
        // No GPU process: Gecko draws in the app's process. When the app leaves the front (the screen goes off, a
        // system dialog), GeckoView 144 binds the GPU process at IDLE priority, which makes it a cached process to
        // Android 6, and the Mirror's lowmemorykiller kills those at once: the "GPU crashes" seen on the Mirror
        // (frames/lululemon-mirror/research/mirror-memory.md). The process itself also goes: about 35 MB on the API 23 emulator.
        "layers.gpu-process.enabled" to false,
        // WebRTC talks to any address over UDP, where neither the delegates nor webRequest can see it. Off:
        // RTCPeerConnection is gone, and HA's frontend falls back to HLS for cameras.
        "media.peerconnection.enabled" to false,
        "media.peerconnection.ice.default_address_only" to true,
        "media.peerconnection.ice.no_host" to true,
        // Connections Gecko opens on its own, ahead of a request (DNS prefetch, the predictor, speculative
        // connects for <link rel=preconnect> and hovered links): none, so a page can't reach out without a request.
        "network.dns.disablePrefetch" to true,
        "network.dns.disablePrefetchFromHTTPS" to true,
        "network.predictor.enabled" to false,
        "network.predictor.enable-prefetch" to false,
        "network.prefetch-next" to false,
        "network.http.speculative-parallel-limit" to 0,
        // A 103 Early Hints answer could otherwise preconnect or preload around the limit above.
        "network.early-hints.enabled" to false,
        "network.early-hints.preconnect.enabled" to false,
        // GeckoView 144 upgrades every http image, audio and video request from a secure context to https, and the
        // app makes Home Assistant's http host one (dom.securecontext.allowlist, for the microphone). So HA's own
        // images went to https://<HA>, which HA doesn't serve and the lockdown refuses (#455). Gecko 158 stopped
        // upgrading for allowlisted hosts; here it's off. A page's http images stay http, and the lockdown still
        // allows only HA's origin.
        "security.mixed_content.upgrade_display_content" to false,
    )

    override fun attach(runtime: GeckoRuntime) {
        runtime.webExtensionController.ensureBuiltIn(LOCATION, ID).accept({ ext ->
            ext?.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(p: WebExtension.Port) {
                    port = p
                    p.setDelegate(object : WebExtension.PortDelegate {
                        override fun onDisconnect(p2: WebExtension.Port) { if (port === p2) port = null }
                    })
                    sendOrigin()
                    if (ready.open()) Log.i(logTag, "Lockdown on: pages load only the dashboard's origin")
                }
            }, NATIVE_APP)
        }, { e ->
            Log.e(logTag, "Lockdown extension failed: nothing will load (the lockdown fails closed)", e)
            ready.fail(FAILED)
        })
    }

    private var timing = false

    /**
     * The app's loads wait for the extension: a page that loads first would fetch from anywhere (seen on API 25).
     * If it isn't up in [READY_TIMEOUT_MS], the screen says so; the load still runs if it comes up later.
     */
    override fun whenReady(load: () -> Unit, stuck: (String) -> Unit) {
        ready.whenOpen(stuck, load)
        if (!timing) {
            timing = true
            later(READY_TIMEOUT_MS) { if (ready.fail(SLOW)) Log.e(logTag, "Lockdown: not up after $READY_TIMEOUT_MS ms; nothing loads") }
        }
    }

    override fun start(dashboard: String?, local: String?) {
        if (!policy.start(dashboard, local)) return
        val origin = policy.origin
        if (origin != null) Log.i(logTag, "Lockdown: pages may load $origin, for this process")
        else Log.w(logTag, "Lockdown: no dashboard address (or one the lockdown can't read), so nothing loads")
        // The app's own loopback server: its port only. Nothing names an address on loopback but this one.
        if (policy.local != null) Log.i(logTag, "Lockdown: and the app's own loopback server, each page held to its own origin")
        else if (local != null) Log.w(logTag, "Lockdown: the app's loopback server isn't 127.0.0.1 with a port, so it doesn't load")
        sendOrigin()
    }

    override fun refusedAppLoad(url: String): String =
        if (policy.origin == null) NO_DASHBOARD else ELSEWHERE.replace("{host}", Origin.host(url, policy.origin).ifEmpty { "This address" })

    override fun allows(kind: PageGuard.Load, url: String): Boolean {
        if (policy.allows(kind, url)) return true
        val host = Origin.host(url, policy.origin)
        Log.w(logTag, "Lockdown: refused ${kind.name.lowercase()} from ${host.ifEmpty { "a URL without a network origin" }}")
        send(JSONObject().put("type", "blocked").put("kind", kind.name.lowercase()).put("host", host))
        return false
    }

    private fun sendOrigin() = policy.origin?.let {
        send(JSONObject().put("type", "origin").put("origin", it).apply { policy.local?.let { l -> put("local", l) } })
    }

    private fun send(message: JSONObject) {
        runCatching { port?.postMessage(message) }.onFailure { Log.w(logTag, "Lockdown: the extension didn't take a message", it) }
    }

    companion object {
        const val LOCATION = "resource://android/assets/lockdown/"
        const val ID = "lockdown@glassworks.dev"
        /** The native app the extension's background script connects to (manifest.json, background.js). */
        const val NATIVE_APP = "glassworks_lockdown"
        const val READY_TIMEOUT_MS = 20_000L

        private const val WHY = "This panel's browser engine is frozen, so it only loads your Home Assistant, and only " +
            "with its lockdown on."
        const val FAILED = "Home Assistant can't load: the lockdown didn't start.\n\n$WHY\n\nRestart the app. If this stays, report it."
        const val NO_DASHBOARD = "Home Assistant can't load: the panel started without a dashboard address.\n\n$WHY\n\n" +
            "Restart the app after setting it."
        const val ELSEWHERE = "{host} isn't the Home Assistant address the panel started with, so it didn't open.\n\n$WHY\n\n" +
            "If the dashboard's address changed, restart the app."
        const val SLOW = "Home Assistant can't load yet: the lockdown hasn't started.\n\n$WHY\n\nWaiting for it…"
    }
}
