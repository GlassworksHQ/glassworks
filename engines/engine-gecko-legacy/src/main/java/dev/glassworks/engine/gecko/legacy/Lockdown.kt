/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

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
internal class Lockdown(private val logTag: String) : PageGuard {
    private val policy = LockdownPolicy()
    private var port: WebExtension.Port? = null
    /** Opens when the extension's background script connects, which it does after its webRequest listener is up. */
    private val ready = ReadyGate()

    /**
     * One content process, none started ahead, and no process per site (cross-origin frames are refused anyway):
     * the Mirror has about 930 MB of RAM. The extensions process stays, as GeckoView runs it.
     */
    override val prefs: Map<String, Any> = linkedMapOf(
        "dom.ipc.processCount" to 1,
        "dom.ipc.processPrelaunch.enabled" to false,
        "fission.autostart" to false,
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
        }, { e -> Log.e(logTag, "Lockdown extension failed: nothing will load (the lockdown fails closed)", e) })
    }

    /** The app's loads wait for the extension: a page that loads first would fetch from anywhere (seen on API 25). */
    override fun whenReady(load: () -> Unit) = ready.run(load)

    override fun appLoads(url: String) {
        if (policy.appLoads(url)) {
            Log.i(logTag, "Lockdown: pages may load ${policy.origin}")
            sendOrigin()
        }
    }

    override fun allows(kind: PageGuard.Load, url: String): Boolean {
        if (policy.allows(kind, url)) return true
        val host = Origin.host(url)
        Log.w(logTag, "Lockdown: refused ${kind.name.lowercase()} from $host")
        send(JSONObject().put("type", "blocked").put("kind", kind.name.lowercase()).put("host", host))
        return false
    }

    private fun sendOrigin() = policy.origin?.let { send(JSONObject().put("type", "origin").put("origin", it)) }

    private fun send(message: JSONObject) {
        runCatching { port?.postMessage(message) }.onFailure { Log.w(logTag, "Lockdown: the extension didn't take a message", it) }
    }

    companion object {
        const val LOCATION = "resource://android/assets/lockdown/"
        const val ID = "lockdown@glassworks.dev"
        /** The native app the extension's background script connects to (manifest.json, background.js). */
        const val NATIVE_APP = "glassworks_lockdown"
    }
}
