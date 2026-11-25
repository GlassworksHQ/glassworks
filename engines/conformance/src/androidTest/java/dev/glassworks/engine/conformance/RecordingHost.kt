/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** The activity a page opens in: the page's view fills it, as MainActivity's does. */
class HostActivity : Activity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }

    fun show(view: View) = root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
}

/**
 * A [PageHost] that writes down every call, in order, as MainActivity would hear them. It lets every load through
 * ([redirect] null) and grants Android permissions. A call off the main thread is a broken contract: it is noted in
 * [offMain] and the test fails on it.
 */
class RecordingHost : PageHost {
    /** One call: what ([kind]), with what ([value]), and the port for [CONNECT] and [MESSAGE]. */
    data class Event(val kind: String, val value: String?, val port: PagePort? = null, val message: JSONObject? = null) {
        override fun toString() = if (message != null) "$kind(${message.optString("type")}${message.optString("why").let { if (it.isEmpty()) "" else ", $it" }})" else "$kind($value)"
    }

    val events = CopyOnWriteArrayList<Event>()
    val offMain = CopyOnWriteArrayList<String>()

    private fun note(e: Event) {
        if (Looper.myLooper() != Looper.getMainLooper()) offMain += "$e on ${Thread.currentThread().name}"
        events += e
    }

    override fun onPageStart(url: String) = note(Event(START, url))
    override fun onPageStop(success: Boolean) = note(Event(STOP, success.toString()))
    override fun onFirstPaint() = note(Event(PAINT, null))
    override fun onLocationChange(url: String?) = note(Event(LOCATION, url))
    override fun redirect(url: String): String? = null.also { note(Event(REDIRECT, url)) }
    override fun onContentGone(killed: Boolean) = note(Event(GONE, killed.toString()))
    /** Which pages may have the microphone and camera without a prompt (#847). None, unless a test says so. */
    @Volatile var trust: (String) -> Boolean = { false }
    override fun trusts(url: String): Boolean = trust(url)
    override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) {
        note(Event(PERMISSIONS, permissions.joinToString()))
        answer(true)
    }
    override fun onConnect(port: PagePort) = note(Event(CONNECT, port.url, port))
    override fun onPortMessage(port: PagePort, message: JSONObject) = note(Event(MESSAGE, port.url, port, message))

    /** The `ready` messages so far. */
    fun readies(): List<Event> = events.filter { it.kind == MESSAGE && it.message?.optString("type") == "ready" }

    fun count(kind: String, value: String? = null) = events.count { it.kind == kind && (value == null || it.value == value) }

    /** The locations reported so far, as paths (`/lovelace/home`), null for none. */
    fun locationPaths(): List<String?> = events.filter { it.kind == LOCATION }.map { it.value?.let(::pathOf) }

    override fun toString() = events.joinToString(", ")

    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val PAINT = "paint"
        const val LOCATION = "location"
        const val REDIRECT = "redirect"
        const val GONE = "gone"
        const val PERMISSIONS = "permissions"
        const val CONNECT = "connect"
        const val MESSAGE = "message"

        fun pathOf(url: String): String? = runCatching { java.net.URI(url).path }.getOrNull()
    }
}
