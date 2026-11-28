/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.auth.OAuth
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.lang.ref.WeakReference

/**
 * [Engine] on GeckoView. This package is the only code that imports GeckoView
 * (`EngineConfinementTest`).
 *
 * Gecko reads its prefs from a YAML file once per process, so [settings] is read at the first
 * [open]. GeckoView allows one GeckoRuntime per process; keep one engine per process.
 */
class GeckoEngine(
    /** Read once, at the first [open], with the application context. */
    private val settings: (Context) -> EngineSettings,
    private val extension: Extension,
    private val logTag: String,
    /** Named in the prefs file's first line. */
    private val generatedBy: String,
) : Engine {

    /** A built-in web extension the page gets, and the native app name its scripts connect to. */
    class Extension(val location: String, val id: String, val nativeApp: String)

    @Volatile override var started: EngineSettings? = null
        private set

    private var runtime: GeckoRuntime? = null
    /** Null in the current engine: pages load anything. The legacy engine's locks them to Home Assistant. */
    private val guard: PageGuard? = GeckoVariant.guard(logTag)
    private var helper: GeckoResult<WebExtension>? = null

    private fun runtime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        val s = settings(app)
        started = s
        guard?.start(s.dashboard, s.localOrigin)
        val config = writeConfig(app, s)
        val rs = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .consoleOutput(true)
            .aboutConfigEnabled(true)
            .remoteDebuggingEnabled(s.remoteDebugging)
            .build()
        return GeckoRuntime.create(app, rs).also {
            runtime = it
            guard?.attach(it)
            GeckoVariant.runtimeStarted(logTag)
        }
    }

    @Volatile private var grantsChecked = false

    /**
     * Once per process, at the first page: content permissions Gecko saved for a page the app doesn't trust go back to
     * asking (#847). Gecko keeps a page's answer and doesn't call the delegate again, and builds before #847 allowed
     * every page. A later request from such a page then reaches the delegate, which refuses it. A read that fails, or
     * that answers once the page is gone, is tried again with the next page.
     */
    private fun forgetUntrustedGrants(rt: GeckoRuntime, host: PageHost) {
        if (grantsChecked) return
        grantsChecked = true
        val storage = try { rt.storageController } catch (e: Exception) { Log.w(logTag, "No saved content permissions to check", e); return }
        // Weak: the read waits for Gecko's profile, and Gecko's dispatcher holds it until then (forever, where Gecko
        // never starts: the JVM tests). It mustn't keep the activity, or this engine, alive meanwhile.
        val hostRef = WeakReference(host)
        val engineRef = WeakReference(this)
        val tag = logTag
        storage.allPermissions.accept({ saved ->
            val h = hostRef.get()
            if (h == null) engineRef.get()?.grantsChecked = false else forget(saved.orEmpty(), storage, h, tag)
        }, { e ->
            Log.w(tag, "Couldn't read saved content permissions; trying again with the next page", e)
            engineRef.get()?.grantsChecked = false
        })
    }

    private fun helper(context: Context): GeckoResult<WebExtension> {
        helper?.let { return it }
        return runtime(context).webExtensionController
            .ensureBuiltIn(extension.location, extension.id)
            .also { helper = it }
    }

    private fun writeConfig(context: Context, s: EngineSettings): File {
        val p = linkedMapOf<String, Any>(
            // HA is usually plain http on the LAN. Treat its host as a secure context so
            // navigator.mediaDevices / getUserMedia / AudioWorklet are exposed.
            "dom.securecontext.allowlist" to s.secureHosts.joinToString(","),
            // Belt and braces for the same thing via the legacy insecure-media prefs.
            "media.devices.insecure.enabled" to true,
            "media.getusermedia.insecure.enabled" to true,
            // No media.navigator.permission.disabled: it hands getUserMedia the devices without asking the
            // PermissionDelegate, which grants them only to Home Assistant and the secure hosts (#847).
            // Kiosk: no prompts (the PermissionDelegate answers), autoplay allowed.
            "media.autoplay.default" to 0,
            "media.autoplay.blocking_policy" to 0,
            "media.autoplay.block-webaudio" to false,
            // Kiosk niceties.
            "browser.tabs.warnOnClose" to false,
            "dom.disable_beforeunload" to true,
        )
        if (s.zoom > 0f) p["layout.css.devPixelsPerPx"] = s.zoom.toString()
        guard?.prefs?.let(p::putAll)

        val yaml = buildString {
            appendLine("# Generated by $generatedBy at startup; edits are overwritten.")
            appendLine("prefs:")
            for ((k, v) in p) {
                val value = when (v) {
                    is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    else -> v.toString()
                }
                appendLine("  $k: $value")
            }
        }
        val f = File(context.filesDir, "geckoview-config.yaml")
        f.writeText(yaml)
        Log.i(logTag, "Gecko config:\n$yaml")
        return f
    }

    override fun open(activity: Activity, host: PageHost, background: Int): Page =
        GeckoPage(activity, host, background).also { it.start() }

    override val name: String get() = "GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}${GeckoVariant.LABEL}"

    override val lockedDown: Boolean get() = GeckoVariant.LOCKED_DOWN

    /**
     * GeckoView's manifest runs `GeckoChildProcessServices$tab0..39` in `:sandboxed_tab_<flags><n>` (the S1's
     * Android 10), or in `:tab<n>` (GeckoView 144 on Android 6 and 7, #198).
     */
    override fun isContentProcess(processName: String) =
        processName.substringAfter(':', "").let { it.startsWith("sandboxed_tab") || tabProcess.matches(it) }

    private val tabProcess = Regex("tab\\d+")

    private inner class GeckoPage(
        private val activity: Activity,
        private val host: PageHost,
        private val background: Int,
    ) : Page {
        override val view = GeckoView(activity)
        private val main = Handler(Looper.getMainLooper())
        private var session: GeckoSession? = null
        override var canGoBack = false
            private set
        private val ports = mutableSetOf<Port>()

        fun start() {
            val s = GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .useTrackingProtection(false)
                    .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                    .allowJavascript(true)
                    .build()
            )
            s.permissionDelegate = permissionDelegate
            s.contentDelegate = contentDelegate
            s.navigationDelegate = navigationDelegate
            s.progressDelegate = progressDelegate
            s.open(runtime(activity))
            forgetUntrustedGrants(runtime(activity), host)
            helper(activity).accept({ ext ->
                if (ext != null) s.webExtensionController.setMessageDelegate(ext, messageDelegate, extension.nativeApp)
            }, { e -> Log.e(logTag, "helper extension failed", e) })
            // A new session starts out active (shown). GeckoView makes it active when the view has a surface
            // (setSession, and each new surface) and inactive when the surface goes, but setSession without a
            // surface leaves it as it is. So a session opened while the app is in the background (the content
            // process was killed there and restart() runs) would stay "shown" until the app comes back: the page
            // keeps drawing for nobody, and Home Assistant never sees it hidden. Start it hidden; a surface shows it.
            s.setActive(false)
            view.setSession(s)
            view.coverUntilFirstPaint(background)
            session = s
        }

        override fun restart() {
            if (closed) return
            session?.let {
                view.releaseSession()
                it.close()
            }
            session = null
            shown = null // the new session is empty until it reports a location
            canGoBack = false // and has no history
            start()
        }

        override fun load(url: String) {
            if (closed) return
            val g = guard ?: return run { session?.loadUri(url) }
            // The app's own load is a page like any other: off the dashboard's origin, it's refused (and noted). With
            // nothing on screen yet, the note has no page to show in, so the guard's words go over the page instead.
            if (!g.allows(PageGuard.Load.PAGE, url)) {
                if (blank(shown)) {
                    showStuck(g.refusedAppLoad(url))
                    refusalShown = true
                }
                return
            }
            g.whenReady({ if (!closed) { showStuck(null); session?.loadUri(url) } }, { if (!closed) showStuck(it) })
        }

        /** Where the page is (onLocationChange), for a guard's refusal of the app's own load. */
        private var shown: String? = null

        /** The words over the page are a refusal, which a page that then commits takes away (not a wait). */
        private var refusalShown = false

        private fun blank(url: String?) = url.isNullOrEmpty() || url == "about:blank"

        /** The guard's words for why nothing loads, over the page (GeckoView is a FrameLayout); null takes them away. */
        private var stuck: TextView? = null

        private fun showStuck(message: String?) {
            if (message == null) {
                stuck?.let { view.removeView(it) }
                stuck = null
                refusalShown = false
                return
            }
            val t = stuck ?: TextView(activity).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                gravity = Gravity.CENTER
                textSize = 20f
                setPadding(48, 48, 48, 48)
            }.also {
                view.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                stuck = it
            }
            t.text = message
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (!closed) host.onBlocked(message)
            } else {
                main.post { if (!closed) host.onBlocked(message) }
            }
        }
        override fun reload() { session?.reload() }
        override fun goBack() { if (canGoBack) session?.goBack() }

        override fun post(message: JSONObject) {
            ports.toList().forEach { runCatching { it.post(message) } }
        }

        /** True once [close] ran: the page has no session, and nothing makes one again. */
        private var closed = false

        override fun setActive(active: Boolean) { session?.setActive(active) }

        override fun close() {
            if (closed) return
            closed = true
            showStuck(null)
            ports.clear()
            session?.let {
                view.releaseSession()
                it.close()
            }
            session = null
            shown = null
        }

        override fun capture(done: (android.graphics.Bitmap?) -> Unit) {
            // Engine.Page promises the main thread: post rather than trust which thread GeckoResult completes on.
            view.capturePixels().accept({ b -> main.post { done(b) } }, { e -> Log.w(logTag, "page capture failed", e); main.post { done(null) } })
        }

        private inner class Port(private val port: WebExtension.Port) : PagePort {
            override val url: String? get() = port.sender.url
            override val topLevel: Boolean get() = port.sender.isTopLevel
            override fun post(message: JSONObject) = port.postMessage(message)
        }

        private val permissionDelegate = object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: PermissionDelegate.Callback
            ) {
                host.onAndroidPermissions(permissions.orEmpty().toList()) { ok -> if (ok) callback.grant() else callback.reject() }
            }

            // Gecko sends the top-level page as uri (the frame that asks, for storage access) and the frame that asks as
            // thirdPartyOrigin, and saves the answer for the top-level page. A refusal is PROMPT, not DENY: nothing is
            // granted, and a frame of another site doesn't leave a saved no on Home Assistant's own page (#847).
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: ContentPermission
            ): GeckoResult<Int> {
                val frame = frameOf(perm)
                if (!trusted(perm.uri) || (frame != null && !host.trusts(frame))) {
                    Log.w(logTag, "Refusing content permission ${perm.permission} for ${forLog(frame ?: perm.uri)} on ${forLog(shown)}: not Home Assistant or a secure host")
                    return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
                }
                Log.i(logTag, "Granting content permission ${perm.permission} for ${forLog(perm.uri)}")
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            }

            // uri is the document that asks (a frame's own, in a frame).
            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out MediaSource>?,
                audio: Array<out MediaSource>?,
                callback: PermissionDelegate.MediaCallback
            ) {
                if (!trusted(uri)) {
                    Log.w(logTag, "Refusing media for ${forLog(uri)} on ${forLog(shown)}: not Home Assistant or a secure host")
                    callback.reject()
                    return
                }
                val mic = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE } ?: audio?.firstOrNull()
                val cam = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA } ?: video?.firstOrNull()
                Log.i(logTag, "Granting media for ${forLog(uri)}: audio=${mic?.name} video=${cam?.name}")
                callback.grant(cam, mic)
            }

            /**
             * Both the top-level page ([shown]) and the document that asks ([uri]) are ones the app trusts (#847): a
             * page off Home Assistant gets nothing, and neither does a frame of another site inside a dashboard.
             */
            private fun trusted(uri: String?): Boolean {
                val top = shown
                return top != null && uri != null && host.trusts(top) && host.trusts(uri)
            }
        }

        private val contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: GeckoSession) = host.onFirstPaint()
            override fun onCrash(session: GeckoSession) = host.onContentGone(killed = false)
            override fun onKill(session: GeckoSession) = host.onContentGone(killed = true)

            // Gecko can't show it (a download). Without a guard nothing happens, as before.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                guard?.allows(PageGuard.Load.DOWNLOAD, response.uri)
            }
        }

        private val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoPage.canGoBack = canGoBack
            }

            // target=_blank / window.open: keep it in the single kiosk session.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (guard?.allows(PageGuard.Load.WINDOW, uri) == false) return null
                if (!host.allowsLoad(uri)) return null
                session.loadUri(uri)
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                shown = url
                if (refusalShown && !blank(url)) {
                    showStuck(null)
                    refusalShown = false
                }
                host.onLocationChange(url)
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val current = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT
                if (guard?.allows(if (current) PageGuard.Load.PAGE else PageGuard.Load.WINDOW, request.uri) == false) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // The host's own policy, for this window and a new one (a new one opens in this session).
                if (!host.allowsLoad(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                if (!current) return null
                val target = host.redirect(request.uri) ?: return null
                // Like the activity's handler before: a redirect still queued when the activity is destroyed is dropped.
                main.post { if (!activity.isDestroyed) session.loadUri(target) }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
                if (guard?.allows(PageGuard.Load.FRAME, request.uri) == false) GeckoResult.fromValue(AllowOrDeny.DENY) else null
        }

        private val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) = host.onPageStart(url)
            override fun onPageStop(session: GeckoSession, success: Boolean) = host.onPageStop(success)
        }

        private val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val p = Port(port)
                ports += p
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onDisconnect(port: WebExtension.Port) { ports -= p }
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        (message as? JSONObject)?.let { host.onPortMessage(p, it) }
                    }
                })
                host.onConnect(p)
            }
        }
    }

    private companion object {
        /**
         * Sets [saved] grants that [host] doesn't trust back to asking: the page's, or the frame's of another site a
         * storage-access grant names. Gecko answers such a grant itself, without asking the delegate. Holds nothing of
         * the engine's.
         */
        fun forget(saved: List<ContentPermission>, storage: StorageController, host: PageHost, tag: String) {
            saved.filter { it.value == ContentPermission.VALUE_ALLOW }.forEach {
                val frame = frameOf(it)
                val untrusted = when {
                    !host.trusts(it.uri) -> it.uri
                    frame != null && !host.trusts(frame) -> frame
                    else -> return@forEach
                }
                Log.i(tag, "Forgetting content permission ${it.permission} for ${forLog(untrusted)}: not Home Assistant or a secure host")
                storage.setPermission(it, ContentPermission.VALUE_PROMPT)
            }
        }

        /**
         * The frame a content permission is for, when it isn't the page's own: Gecko's thirdPartyOrigin (a storage-access
         * request or grant), without the ^attributes an origin can carry. Null when there is none.
         */
        fun frameOf(perm: ContentPermission): String? =
            perm.thirdPartyOrigin?.substringBefore('^')?.takeIf { it.isNotBlank() }

        /** A page in the log: its origin only, so a query or a path with a secret in it stays out (#847). */
        fun forLog(url: String?): String =
            url?.let { OAuth.origin(it) ?: (it.substringBefore(':', "").ifEmpty { "a page" } + ":") } ?: "no page"
    }
}
