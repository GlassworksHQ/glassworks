/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import android.app.Activity
import android.view.View
import org.json.JSONObject

/**
 * The web engine that shows the dashboard. One per process: it reads its [EngineSettings] once, at
 * the first [open], so the process must restart to change them.
 *
 * It is a kiosk engine, with no prompts. A page the app trusts ([PageHost.trusts]: Home Assistant and the
 * secure-context hosts) gets every permission it asks for and the microphone and the camera for
 * `getUserMedia`. Any other page gets none of them, and the engine logs the refusal (#847). Only Android's
 * own runtime permissions go to the app ([PageHost.onAndroidPermissions]), because only an activity can ask
 * for them.
 */
interface Engine {
    /** What the engine started with, or null before the first [open]. */
    val started: EngineSettings?

    /**
     * Opens an empty page in [activity]. The caller adds [Page.view] to its layout and loads a URL.
     * The view shows [background] until the page first paints. [host] hears from the page.
     */
    fun open(activity: Activity, host: PageHost, background: Int): Page

    /** Whether [processName], one of this app's processes, is one the engine renders web content in. */
    fun isContentProcess(processName: String): Boolean

    /** The engine and its version in words, for the Manage page ("GeckoView 159.0"). */
    val name: String get() = ""

    /**
     * True for the legacy engine (#198): a frozen GeckoView that loads only the dashboard's origin. The Manage page
     * shows a Legacy chip that says so, and the app never turns on remote debugging for it (#29).
     */
    val lockedDown: Boolean get() = false
}

/** What an engine starts with. */
data class EngineSettings(
    /** Hosts treated as secure contexts, so a plain-http page there can use the microphone. */
    val secureHosts: List<String>,
    /** CSS pixels per screen pixel. 0 keeps the engine's own. */
    val zoom: Float,
    /** Lets a desktop browser debug the page. */
    val remoteDebugging: Boolean,
    /**
     * The dashboard URL the app is set to, or null while there is none (Home Assistant not found yet). An engine
     * that holds pages to one origin (the legacy engine, #198) takes this URL's origin, once: like the rest of these
     * settings it can't change in this process, so a new dashboard address needs a process restart.
     */
    val dashboard: String? = null,
)

/** A page in one activity. Call it on the main thread. */
interface Page {
    val view: View

    fun load(url: String)
    fun reload()

    /** Goes back one page, if the page has history. */
    fun goBack()

    /** Replaces the page with a new, empty one in the same view, after [PageHost.onContentGone]. */
    fun restart()

    /** Sends [message] to every connected [PagePort]. A port that fails is skipped. */
    fun post(message: JSONObject)

    /**
     * A still picture of what the page shows now, at the view's size, or null when the engine can't
     * take one. [done] runs on the main thread. A notification's half-screen view (#230) shows the
     * dashboard behind it this way, with one page and no second engine.
     */
    fun capture(done: (android.graphics.Bitmap?) -> Unit) = done(null)

    /**
     * Ends the page for good: its view lets go of the engine's session and the session closes, so an activity that is
     * destroyed (a recreate for a new locale or font scale) leaves no live session holding it. Idempotent; the page
     * does nothing after it.
     */
    fun close() {}
}

/** A page script's connection to the app (the web extension's native port). */
interface PagePort {
    /** The URL of the page that opened the port. */
    val url: String?

    /** True for the top-level document, false for a frame. */
    val topLevel: Boolean

    fun post(message: JSONObject)
}

/** What a [Page] reports. Every call comes on the main thread. */
interface PageHost {
    fun onPageStart(url: String)
    fun onPageStop(success: Boolean)
    fun onFirstPaint()
    fun onLocationChange(url: String?)

    /** A top-level load in the page's own window. Return a URL to load instead, or null to let it load. */
    fun redirect(url: String): String?

    /** The content process crashed, or the system [killed] it. The page shows nothing until [Page.restart]. */
    fun onContentGone(killed: Boolean)

    /**
     * The engine blocked a load or couldn't start its guard, and is showing its own explanation over the page
     * (the legacy engine's lockdown). Hosts covering the page with a loading screen should hide it so the
     * message is visible.
     */
    fun onBlocked(reason: String) {}

    /**
     * Whether a page at [url] may have the microphone, the camera and the permissions it asks for, with no prompt
     * (#847). The engine asks for the top-level page and for the document that asks, and grants only when both are
     * trusted.
     */
    fun trusts(url: String): Boolean

    /** The page needs these Android runtime permissions. Call [answer] once: true if all are granted. */
    fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit)

    fun onConnect(port: PagePort)

    /** A message from a port. Only JSON objects reach the host. */
    fun onPortMessage(port: PagePort, message: JSONObject)
}
