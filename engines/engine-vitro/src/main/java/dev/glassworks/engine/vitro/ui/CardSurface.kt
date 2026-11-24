/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Shared context passed to every Vitro card renderer on the active view. */
class VitroCardContext(
    val context: Context,
    val haBase: String,
    val store: EntityStore,
    val theme: ResolvedTheme,
    val resources: List<LovelaceResource>,
    val accessToken: () -> String?,
    val haConfig: () -> JSONObject = { JSONObject() },
    val callService: (domain: String, service: String, data: JSONObject?, target: JSONObject?) -> Unit,
    val callWs: (msg: JSONObject, done: (success: Boolean, result: Any?, error: JSONObject?) -> Unit) -> Unit = { _, done -> done(false, null, null) },
    val navigate: (path: String) -> Unit,
    val openMoreInfo: (entityId: String) -> Unit,
    val fetchHistory: (entityIds: List<String>, hours: Int, done: (Map<String, List<HistoryPoint>>) -> Unit) -> Unit,
    val subscribeTemplate: (template: String, onRendered: (String) -> Unit) -> () -> Unit,
    /** Loopback micro-host server + GeckoView [Page] factory for Tier 3 Custom Card Islands (zero WebView). */
    val islandServer: VitroIslandServer? = null,
    val openIslandPage: ((url: String) -> Page?)? = null,
) {
    val dp: Float = context.resources.displayMetrics.density
    val main: Handler = Handler(Looper.getMainLooper())

    fun px(dpVal: Float): Int = (dpVal * dp).toInt()

    /** Dispatches a standard HA Lovelace `tap_action` / `hold_action`. */
    fun performAction(action: ActionConfig, defaultEntityId: String?) {
        when (action.action) {
            "none" -> Unit
            "toggle" -> {
                val id = defaultEntityId ?: return
                val domain = id.substringBefore('.')
                val targetDomain = if (domain in setOf("light", "switch", "fan", "input_boolean", "cover", "automation", "lock")) domain else "homeassistant"
                val cur = store[id]
                // Optimistic local state flip for instant native responsiveness.
                if (cur != null && cur.state in setOf("on", "off")) {
                    store.putLocal(cur.copy(state = if (cur.state == "on") "off" else "on"))
                }
                callService(targetDomain, "toggle", JSONObject().put("entity_id", id), null)
            }
            "more-info" -> defaultEntityId?.let(openMoreInfo)
            "navigate" -> action.navigationPath?.let(navigate)
            "call-service", "perform-action" -> {
                val svc = action.service ?: return
                val domain = svc.substringBefore('.', "")
                val name = svc.substringAfter('.', "")
                if (domain.isNotEmpty() && name.isNotEmpty()) {
                    callService(domain, name, action.serviceData, action.target)
                }
            }
            "url" -> action.urlPath?.let(navigate)
        }
    }

    /** Loads an HA-hosted image asynchronously on a shared daemon pool and delivers on the main thread. */
    fun loadImage(rawUrl: String, done: (Bitmap?) -> Unit) {
        val fullUrl = when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("/") -> haBase.trimEnd('/') + rawUrl
            else -> haBase.trimEnd('/') + "/" + rawUrl
        }
        IMAGE_POOL.execute {
            val bmp = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    accessToken()?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    if (conn.responseCode in 200..299) conn.inputStream.use(BitmapFactory::decodeStream) else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            main.post { done(bmp) }
        }
    }

    companion object {
        private val IMAGE_POOL = Executors.newFixedThreadPool(3) { r -> Thread(r, "vitro-img").apply { isDaemon = true } }
    }
}

/**
 * Base rounded surface for every native Lovelace card, styled directly from [ResolvedTheme]
 * (including `ha-card-background`, border radius/width/color, and container `card-mod` properties).
 */
open class CardSurface(protected val ctx: VitroCardContext) : FrameLayout(ctx.context) {
    private val subscriptions = ArrayList<() -> Unit>()

    init {
        applyTheme(ctx.theme)
    }

    fun applyTheme(theme: ResolvedTheme) {
        val bg = GradientDrawable().apply {
            cornerRadius = theme.cardBorderRadiusDp * ctx.dp
            setColor(theme.cardBackgroundColor)
            val strokePx = (theme.cardBorderWidthDp * ctx.dp).toInt()
            if (strokePx > 0) setStroke(strokePx, theme.cardBorderColor)
        }
        background = bg
        theme.cardMod.opacity?.let { alpha = it }
        clipToOutline = true
    }

    protected fun observe(entityId: String, onState: (EntityState?) -> Unit) {
        onState(ctx.store[entityId])
        val unsub = ctx.store.subscribe(entityId) { s ->
            ctx.main.post { onState(s) }
        }
        subscriptions += unsub
    }

    protected fun track(unsub: () -> Unit) {
        subscriptions += unsub
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        subscriptions.forEach { runCatching { it() } }
        subscriptions.clear()
    }

    companion object {
        /** Computes a soft tinted pill/circle color (e.g. 20% alpha of active domain color). */
        fun withAlpha(color: Int, alphaFraction: Float): Int {
            val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}
