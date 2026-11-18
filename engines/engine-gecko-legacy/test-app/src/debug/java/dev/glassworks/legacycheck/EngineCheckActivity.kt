/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.legacycheck

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.gecko.GeckoEngine
import org.json.JSONObject

/**
 * The legacy test APK's engine check (#198): the legacy engine and its lockdown alone, through the core's
 * [Engine] API, without the S1 app's MainActivity (whose API 24+ calls are a later part of #198). It opens one
 * page with the S1 app's helper extension and shows, on top, what ran:
 *
 *   adb -s <serial> shell am start -n sh.edm.s1kiosk.legacy/dev.glassworks.legacycheck.EngineCheckActivity --es url http://127.0.0.1:18125/
 *
 * The URL is the dashboard the engine starts with, so its origin is the one the lockdown allows, for the life of
 * the process: to check another origin, `am force-stop` the app first. Without `url` it opens the last one.
 * Debug builds only; never in the S1 app. One GeckoView runtime per process: don't open MainActivity in the same run.
 */
class EngineCheckActivity : Activity() {
    private var page: Page? = null
    private lateinit var status: TextView
    private var helper = "not connected"
    private var lastUrl = ""
    /** The URL this check was opened with: loaded again after the content process goes. */
    private var target: String? = null
    private var loads = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("legacy_check", MODE_PRIVATE)
        val url = intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.also { prefs.edit().putString("url", it).apply() }
            ?: prefs.getString("url", null)
        val root = FrameLayout(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        setContentView(root)
        val p = engine.open(this, host, Color.BLACK)
        page = p
        root.addView(p.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        target = url
        if (url == null) {
            show("no URL: am start … --es url http://<home-assistant>/")
        } else {
            p.load(url)
            show("opening")
        }
    }

    private fun show(state: String) {
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val text = "${engine.name} · Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull()} · " +
            "RAM ${mem.totalMem / (1 shl 20)} MB (${mem.availMem / (1 shl 20)} free)\n" +
            "helper extension: $helper · loads: $loads · $state\n$lastUrl"
        status.text = text
        Log.i(TAG, text.replace('\n', ' '))
    }

    override fun onBackPressed() { page?.goBack() }

    private val host = object : PageHost {
        // Not the URL: GeckoView reports a start for a load the lockdown then refuses. onLocationChange is where the page is.
        override fun onPageStart(url: String) { loads++; show("loading") }
        override fun onPageStop(success: Boolean) = show(if (success) "loaded" else "load failed")
        override fun onFirstPaint() = show("painted")
        override fun onLocationChange(url: String?) { lastUrl = url.orEmpty(); show("at") }
        override fun redirect(url: String): String? = null
        override fun onContentGone(killed: Boolean) {
            show(if (killed) "content process killed" else "content process crashed")
            page?.restart()
            target?.let { page?.load(it) }
        }
        override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) = answer(false)
        override fun onConnect(port: PagePort) {
            helper = "connected (${if (port.topLevel) "top" else "frame"})"
            // content.js publishes this as window.s1kiosk.
            port.post(JSONObject().put("type", "state").put("state", JSONObject().put("legacyCheck", true).put("engine", engine.name)))
            show("helper up")
        }
        override fun onPortMessage(port: PagePort, message: JSONObject) {
            Log.i(TAG, "page says ${message.optString("type")}")
        }
    }

    companion object {
        private const val TAG = "LegacyCheck"

        /** The same engine, extension and settings shape as KioskApp's. */
        private val engine: Engine by lazy {
            GeckoEngine(
                // The dashboard is the URL this check was opened with (saved before the first open): the lockdown takes
                // its origin once, so a check of another origin needs `am force-stop` first.
                settings = { ctx ->
                    val url = ctx.getSharedPreferences("legacy_check", android.content.Context.MODE_PRIVATE).getString("url", null)
                    // The dashboard's host is a secure context, as KioskPrefs.secureHosts makes it: GeckoView 144 then
                    // upgrades its http images to https (#455), which a check without it never sees.
                    val host = url?.let { android.net.Uri.parse(it).host }
                    EngineSettings(secureHosts = listOfNotNull(host), zoom = 0f, remoteDebugging = true, dashboard = url)
                },
                extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
                logTag = TAG,
                generatedBy = "Legacy engine check",
            )
        }
    }
}
