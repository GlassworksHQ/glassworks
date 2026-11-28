/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.vitro.test

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.auth.OAuth
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.vitro.VitroEngine
import dev.glassworks.engine.vitro.webrtc.VitroWebRtc
import dev.glassworks.platform.Platform
import org.json.JSONObject

/**
 * Vitro's test activity: the native Lovelace engine alone, with no GeckoView linked, and a metrics HUD on top.
 *
 *   adb shell am start -n dev.glassworks.vitro.test/.VitroTestActivity \
 *       --es url http://127.0.0.1:8089/clarity-ref/typical-nocam [--es token <access token>] [--ez hud false] [--ez dark true|false]
 *
 * `url` is the dashboard to open and `token` an optional Home Assistant access token (empty works with Clarity's
 * fakeha.py); both are kept in the `vitro_test` preferences, so a launch without them opens the last ones. `hud`
 * (default true) shows or hides the HUD. `dark` forces Vitro's dark or light mode for visual comparisons (absent: it follows
 * the device's night mode, kept until a launch passes `--ez dark` again; `--ez dark` is sticky, so use `pm clear` to reset). Tap the HUD pill to collapse or expand it; expanded, it has a Reload button.
 * Debug builds only.
 */
class VitroTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: VitroEngine
    private var page: Page? = null
    private lateinit var root: FrameLayout
    private lateinit var hud: LinearLayout
    private lateinit var hudText: TextView
    private lateinit var reload: Button
    private var expanded = false
    private var resumed = false
    private var url: String? = null
    private var status = "starting"
    private var loads = 0
    private val dp by lazy { resources.displayMetrics.density }

    private val tick = object : Runnable {
        override fun run() {
            renderHud()
            main.postDelayed(this, HUD_REFRESH_MS)
        }
    }

    /** Wraps camera players registered after start (the WebRTC one) for the camera end-to-end test: [CameraProbe]. */
    private val probeTick = object : Runnable {
        override fun run() {
            CameraProbe.install()
            main.postDelayed(this, PROBE_CHECK_MS)
        }
    }

    private val host = object : PageHost {
        override fun onPageStart(url: String) { status = "loading" }
        override fun onPageStop(success: Boolean) { status = if (success) "ready" else "failed" }
        override fun onFirstPaint() { status = "painted" }
        override fun onLocationChange(url: String?) {}
        override fun redirect(url: String): String? = null
        override fun onContentGone(killed: Boolean) {
            status = "content gone"
            page?.restart()
        }
        override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) = answer(false)
        // The test app grants nothing, Android permissions included, so no page is trusted (#847).
        override fun trusts(url: String): Boolean = false
        override fun onConnect(port: PagePort) {}
        override fun onPortMessage(port: PagePort, message: JSONObject) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        saveExtras(intent)
        url = prefs.getString(KEY_URL, null)
        if (prefs.contains(KEY_DARK)) forceNightMode(prefs.getBoolean(KEY_DARK, true))

        // Cameras play over WebRTC first, then HLS and MJPEG, as in the generic app with Vitro on (GenericApp).
        VitroWebRtc.register()
        engine = VitroEngine(
            fallback = StubFallbackEngine,
            settings = { EngineSettings(secureHosts = emptyList(), zoom = 0f, remoteDebugging = false, dashboard = url) },
            enabled = { true },
            tokenProvider = { _, done -> done(prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }) },
        )
        CameraProbe.install()

        root = FrameLayout(this)
        setContentView(root)
        val p = engine.open(this, host, Color.BLACK)
        page = p
        root.addView(p.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildHud()
        applyHudVisibility(prefs.getBoolean(KEY_HUD, true))
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val before = url
        saveExtras(intent)
        if (intent.hasExtra(EXTRA_DARK)) {
            recreate() // Vitro reads the night mode when its view is built
            return
        }
        url = prefs.getString(KEY_URL, null)
        applyHudVisibility(prefs.getBoolean(KEY_HUD, true))
        if (intent.hasExtra(EXTRA_TOKEN)) {
            // A new token: sign in again (restart drops the client), rather than reuse the one already connected.
            loads++
            status = "restarting"
            page?.restart()
            if (url != before) load()
        } else if (url != before) {
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleHud()
        main.post(probeTick)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        main.removeCallbacks(probeTick)
        super.onPause()
    }

    @Deprecated("Back closes Vitro's more-info sheet or goes back one dashboard; Home leaves the app.")
    override fun onDestroy() {
        // This activity owns its engine: a recreate (`--ez dark`) makes a new one, so the old island server (its
        // listener holds the old engine and this activity) stops here. A page reload keeps it running.
        engine.islandServer.stop()
        super.onDestroy()
    }

    override fun onBackPressed() {
        page?.goBack()
    }

    /** Keeps the launch extras in the preferences; an absent extra leaves the last value. */
    private fun saveExtras(i: Intent?) {
        i ?: return
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        i.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { next ->
            // The saved token is for the origin it came with: a URL on another origin without its own token drops it.
            val before = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null)
            if (!i.hasExtra(EXTRA_TOKEN) && OAuth.origin(next) != before?.let(OAuth::origin)) edit.remove(KEY_TOKEN)
            edit.putString(KEY_URL, next)
        }
        if (i.hasExtra(EXTRA_TOKEN)) edit.putString(KEY_TOKEN, i.getStringExtra(EXTRA_TOKEN).orEmpty())
        if (i.hasExtra(EXTRA_HUD)) edit.putBoolean(KEY_HUD, i.getBooleanExtra(EXTRA_HUD, true))
        if (i.hasExtra(EXTRA_DARK)) edit.putBoolean(KEY_DARK, i.getBooleanExtra(EXTRA_DARK, true))
        edit.apply()
    }

    private fun load() {
        val u = url
        if (u.isNullOrBlank()) {
            status = "no url: launch with --es url <dashboard>"
            return
        }
        loads++
        status = "loading"
        CameraProbe.install()
        page?.load(u)
    }

    private fun buildHud() {
        hudText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(px(8), px(5), px(8), px(5))
            setOnClickListener {
                expanded = !expanded
                renderHud()
            }
        }
        reload = Button(this).apply {
            text = "Reload"
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(px(8), px(2), px(8), px(2))
            setOnClickListener { reloadPage() }
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = 12f * dp
            }
            addView(hudText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(reload, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(px(6), 0, px(6), px(6))
            })
        }
        root.addView(hud, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, px(8), px(8), 0)
        })
    }

    private fun applyHudVisibility(show: Boolean) {
        hud.visibility = if (show) View.VISIBLE else View.GONE
        scheduleHud()
    }

    /**
     * The HUD refreshes only while it shows. Its tick reads this process's PSS (Debug.getMemoryInfo walks
     * /proc/self/smaps) on the main thread: on the S1 that stalled a frame by about 100 ms every second, so with
     * `--ez hud false` the test app measured its own HUD (live-update p95, scroll frames, idle CPU).
     */
    private fun scheduleHud() {
        main.removeCallbacks(tick)
        if (resumed && hud.visibility == View.VISIBLE) main.post(tick)
    }

    private fun reloadPage() {
        status = "reloading"
        page?.reload()
    }

    private fun renderHud() {
        val m = engine.lastMetrics
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val pssMb = pssMb()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) shr 20
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val head = "${VitroEngine.VERSION_NAME.substringBefore(" (")} · $status"
        hudText.text = if (!expanded) {
            "$head · ${m.buildTimeMs}ms · ${pssMb}MB"
        } else {
            buildString {
                appendLine(head)
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Platform.apiLevel}) $abi")
                appendLine("RAM ${mi.totalMem shr 20}MB total, ${mi.availMem shr 20}MB free")
                appendLine("PSS ${pssMb}MB  heap ${heapMb}MB")
                appendLine("view ${m.viewTitle.ifEmpty { "-" }} ${m.viewType}".trimEnd())
                appendLine("build ${m.buildTimeMs}ms  cache ${if (m.cacheHit) "hit" else "miss"}")
                appendLine("cards core ${m.nativeCoreCards}  hacs ${m.nativeHacsCards}  island ${m.webIslandCards}")
                // Why each island falls back (the compatibility checker); the full list is in logcat (VitroView).
                m.islands.take(MAX_HUD_ISLANDS).forEach { appendLine("· ${it.takeLast(120)}") }
                if (m.islands.size > MAX_HUD_ISLANDS) appendLine("· +${m.islands.size - MAX_HUD_ISLANDS} more in logcat")
                append("entities ${m.subscribedEntities}  loads $loads")
            }
        }
        reload.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /** This process's proportional set size, in MB. */
    private fun pssMb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() / 1024
    }

    /** Overrides this activity's night mode, which is what Vitro reads to pick its light or dark theme. */
    @Suppress("DEPRECATION")
    private fun forceNightMode(dark: Boolean) {
        val cfg = android.content.res.Configuration(resources.configuration)
        val night = if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        resources.updateConfiguration(cfg, resources.displayMetrics)
    }

    private fun px(dips: Int): Int = (dips * dp).toInt()

    /**
     * Vitro's fallback for pages it doesn't draw (sign-in, blueprint import). This test APK links no browser, so
     * the fallback is an empty page: a non-dashboard URL shows nothing.
     */
    private object StubFallbackEngine : Engine {
        override val started: EngineSettings? = null
        override val name: String = "none"
        override fun isContentProcess(processName: String): Boolean = false

        override fun open(activity: Activity, host: PageHost, background: Int): Page = object : Page {
            override val view: View = FrameLayout(activity).apply { setBackgroundColor(background) }
            override fun load(url: String) {}
            override fun reload() {}
            override fun goBack() {}
            override fun restart() {}
            override fun post(message: JSONObject) {}
        }
    }

    private companion object {
        const val PREFS = "vitro_test"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_HUD = "hud"
        const val MAX_HUD_ISLANDS = 4
        const val KEY_DARK = "dark"
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_HUD = "hud"
        const val EXTRA_DARK = "dark"
        const val HUD_REFRESH_MS = 1000L
        const val PROBE_CHECK_MS = 1000L
    }
}
