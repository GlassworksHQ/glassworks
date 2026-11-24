/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.LruCache
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.ForecastDay
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

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
    /** One-shot `weather/subscribe_forecast` read (`daily`, `hourly` or `twice_daily`). */
    val fetchForecast: (entityId: String, forecastType: String, done: (List<ForecastDay>) -> Unit) -> Unit = { _, _, _ -> },
    /** HA's `auth/current_user` result (`id`, `name`, `is_admin`, `is_owner`), empty until it arrives. */
    val haUser: () -> JSONObject = { JSONObject() },
    /**
     * Opens HA's live MJPEG feed for a camera and returns its stop handle. Null (tests, no engine) means there's no
     * stream, so [startCameraStream] reports a fallback straight away and cards poll snapshots instead.
     */
    val cameraStream: ((entityId: String, entityPicture: String?, onFrame: (Bitmap) -> Unit, onFallback: () -> Unit) -> () -> Unit)? = null,
) {
    val dp: Float = context.resources.displayMetrics.density
    val main: Handler = Handler(Looper.getMainLooper())

    fun px(dpVal: Float): Int = (dpVal * dp).toInt()

    /** [sp] scaled by the theme's text size ([ResolvedTheme.fontScale]); pass the result to `setTextSize(SP, …)`. */
    fun scaledSp(sp: Float): Float = sp * theme.fontScale

    /**
     * Dispatches a standard HA Lovelace `tap_action` / `hold_action` / `double_tap_action`. When the action carries
     * a `confirmation`, a native dialog asks first and the action only runs on Confirm.
     */
    fun performAction(action: ActionConfig, defaultEntityId: String?) {
        val prompt = action.confirmation
        if (prompt != null && action.action != "none") {
            confirmThen(prompt) { runAction(action, defaultEntityId) }
            return
        }
        runAction(action, defaultEntityId)
    }

    private fun confirmThen(message: String, onConfirm: () -> Unit) {
        runCatching {
            AlertDialog.Builder(context)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm") { _, _ -> onConfirm() }
                .show()
        }
    }

    private fun runAction(action: ActionConfig, defaultEntityId: String?) {
        when (action.action) {
            "none" -> Unit
            "toggle" -> defaultEntityId?.let(::toggleEntity)
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

    /**
     * Streams [entityId]'s live MJPEG feed: decoded frames reach [onFrame] on the main thread, and [onFallback] runs
     * on the main thread if the stream can't start or drops. Returns the stop handle; call it on detach or close.
     * Cards never see the stream address or the token.
     */
    fun startCameraStream(
        entityId: String,
        entityPicture: String?,
        onFrame: (Bitmap) -> Unit,
        onFallback: () -> Unit,
    ): () -> Unit {
        val impl = cameraStream
        if (impl == null) {
            onFallback()
            return {}
        }
        return impl(entityId, entityPicture, onFrame, onFallback)
    }

    /** HA's `toggle` for one entity: the domain's own service, with an optimistic flip for plain on/off states. */
    fun toggleEntity(entityId: String) {
        val domain = entityId.substringBefore('.')
        val cur = store[entityId]
        val (targetDomain, service) = when (domain) {
            "lock" -> "lock" to if (cur?.state == "locked") "unlock" else "lock"
            "script" -> "script" to "turn_on"
            in TOGGLE_DOMAINS -> domain to "toggle"
            else -> "homeassistant" to "toggle"
        }
        // Optimistic local state flip for instant native responsiveness.
        if (cur != null && domain != "script" && cur.state in setOf("on", "off")) {
            store.putLocal(cur.copy(state = if (cur.state == "on") "off" else "on"))
        }
        callService(targetDomain, service, JSONObject().put("entity_id", entityId), null)
    }

    /**
     * Loads an HA-hosted image asynchronously on a shared daemon pool and delivers on the main thread.
     * Images are downsampled to at most [MAX_IMAGE_DIM] px on the long edge and decoded as RGB_565, and kept
     * in a shared 12 MB LRU cache. URLs carrying a `t=` query parameter (live camera polls) are never cached.
     */
    fun loadImage(rawUrl: String, done: (Bitmap?) -> Unit) {
        val fullUrl = absoluteUrl(rawUrl)
        val cacheable = !LIVE_PARAM.containsMatchIn(fullUrl)
        if (cacheable) {
            IMAGE_CACHE.get(fullUrl)?.takeIf { !it.isRecycled }?.let { hit ->
                main.post { done(hit) }
                return
            }
        }
        IMAGE_POOL.execute {
            val bmp = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    accessToken()?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    if (conn.responseCode in 200..299) decodeDownsampled(conn.inputStream.use { it.readBytes() }) else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (bmp != null && cacheable) IMAGE_CACHE.put(fullUrl, bmp)
            main.post { done(bmp) }
        }
    }

    /**
     * `GET`s an HA REST path (e.g. `/api/calendars/calendar.home?start=…&end=…`) with the panel's bearer token on
     * the shared daemon pool and delivers the parsed [JSONObject] / [JSONArray] on the main thread, or null on any
     * failure (network, non-2xx, bad JSON).
     */
    fun fetchJson(path: String, done: (Any?) -> Unit) {
        val fullUrl = absoluteUrl(path)
        IMAGE_POOL.execute {
            val parsed = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/json")
                    accessToken()?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    if (conn.responseCode in 200..299) {
                        JSONTokener(conn.inputStream.bufferedReader().use { it.readText() }).nextValue()
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            main.post { done(parsed) }
        }
    }

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> haBase.trimEnd('/') + raw
        else -> haBase.trimEnd('/') + "/" + raw
    }

    companion object {
        /** Domains HA's `toggle` action (and a tile's icon) can switch. */
        val TOGGLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "cover", "lock",
            "automation", "script", "humidifier", "siren", "valve",
        )

        fun isToggleable(entityId: String?): Boolean = entityId != null && entityId.substringBefore('.') in TOGGLE_DOMAINS

        private val IMAGE_POOL = Executors.newFixedThreadPool(3) { r -> Thread(r, "vitro-img").apply { isDaemon = true } }

        /** Long-edge cap in px: plenty for a 1440x720 panel, 4x-8x less memory than full-size ARGB_8888. */
        private const val MAX_IMAGE_DIM = 720
        private const val IMAGE_CACHE_KB = 12 * 1024
        private val LIVE_PARAM = Regex("[?&]t=")

        private val IMAGE_CACHE = object : LruCache<String, Bitmap>(IMAGE_CACHE_KB) {
            override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)
        }

        private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > MAX_IMAGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
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
        elevation = theme.cardElevationDp * ctx.dp
    }

    /**
     * Subtle tactile press feedback: scales to 0.975 over 70 ms on touch-down and springs back to 1.0
     * over 120 ms on release/cancel. The touch listener returns false, so clicks still fire normally.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun enablePressFeedback() {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(70L)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    /** Wires HA's `hold_action` on this card: a long press with haptic feedback. */
    fun bindHoldAction(action: ActionConfig, entityId: String?) {
        bindHoldAction(ctx, action, entityId)
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
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        subscriptions.forEach { runCatching { it() } }
        subscriptions.clear()
    }

    companion object {
        private const val PRESS_SCALE = 0.975f

        /** Computes a soft tinted pill/circle color (e.g. 20% alpha of active domain color). */
        fun withAlpha(color: Int, alphaFraction: Float): Int {
            val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}

/** Wires HA's `hold_action` on any view: a long press with haptic feedback runs [action]. `none` leaves it off. */
fun View.bindHoldAction(ctx: VitroCardContext, action: ActionConfig, entityId: String?) {
    if (action.action == "none") {
        setOnLongClickListener(null)
        isLongClickable = false
        return
    }
    setOnLongClickListener { v ->
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ctx.performAction(action, entityId)
        true
    }
}
