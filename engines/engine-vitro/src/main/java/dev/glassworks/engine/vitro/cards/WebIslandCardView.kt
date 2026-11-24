/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * Tier 3 Custom Card Escape Hatch ("GeckoView Micro-Web Island").
 *
 * Uses zero `android.webkit.WebView`: every Tier 3 island runs through Glassworks' wrapped
 * `Engine` (GeckoView) pointed at Vitro's loopback [VitroIslandServer], keeping CSS/JS rendering
 * 100% consistent across Android 6 (Mirror), Android 10 (S1 Plus), and Android 15+ while skipping
 * Home Assistant's 10+ MB frontend SPA.
 *
 * Communicates over a loopback WebSocket (`/__vitro/ws/<slotId>`) with a dependency-tracking
 * `hass.states` Proxy, `hass.callWS`, `hass.callService`, `hass.config`, and theme `:root` vars.
 */
class WebIslandCardView(
    ctx: VitroCardContext,
    private val config: CardConfig,
    private val fillViewport: Boolean = false,
) : CardSurface(ctx) {
    private val watchedEntities = CopyOnWriteArraySet<String>()
    private var slotId: String? = null
    private var islandPage: Page? = null
    @Volatile private var sendToIsland: ((JSONObject) -> Unit)? = null

    // An inline island sits in Vitro's vertical scroller: a vertical drag belongs to the scroller, while taps and
    // horizontal drags (sliders, carousels, charts) stay inside the island.
    private val touchSlop = ViewConfiguration.get(ctx.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureAxis = AXIS_NONE

    init {
        if (fillViewport) dropSurface()

        val server = ctx.islandServer
        val opener = ctx.openIslandPage
        if (server != null && opener != null) {
            lateinit var idRef: String
            // Open bridge sockets: a replacement can connect before the old one has finished closing.
            var connections = 0
            val handler = object : VitroIslandServer.SlotHandler {
                override val html: String
                    get() = buildIslandHtml(ctx, config, fillViewport, slotId = idRef)

                override fun onConnected(send: (JSONObject) -> Unit) {
                    synchronized(this) { connections++ }
                    sendToIsland = send
                    seedInitialStates(send)
                }

                override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
                    handleIslandMessage(msg, send)
                }

                override fun onDisconnected() {
                    // Only the last socket going clears the sender: an old one closing must not cut off its replacement.
                    synchronized(this) { if (--connections <= 0) sendToIsland = null }
                }
            }
            val (id, url) = server.registerSlot(handler)
            idRef = id
            slotId = id

            val page = opener(url)
            if (page != null) {
                islandPage = page
                val h = if (fillViewport) LayoutParams.MATCH_PARENT else ctx.px(160f)
                addView(page.view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, h))
                page.load(url)

                track(ctx.store.subscribeAny { changed ->
                    val send = sendToIsland ?: return@subscribeAny
                    patchFor(changed)?.let(send)
                })
            } else {
                server.unregisterSlot(id)
                slotId = null
                showPlaceholder()
            }
        } else {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        val note = TextView(ctx.context).apply {
            text = "Custom card island: ${config.type}"
            setTextColor(ctx.theme.secondaryTextColor)
            setPadding(ctx.px(14f), ctx.px(14f), ctx.px(14f), ctx.px(14f))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Whether the card picks its entities at runtime (`auto-entities` filters, `button-card` JS templates, a card
     * that names none up front): such a card follows every entity in the store, not only the ones it read.
     */
    internal val followsAllEntities: Boolean by lazy {
        config.raw.has("filter") || referencedEntities().isEmpty()
    }

    private fun referencedEntities(): Set<String> {
        val syntheticView = ViewConfig(0, "", null, null, "panel", null, null, 1, emptyList(), listOf(config), emptyList())
        return CardRouter.referencedEntities(syntheticView)
    }

    /**
     * The first `patch` an island gets when its socket connects: every entity in the store, as HA's own frontend
     * hands every card the whole `hass.states`, so filter and template cards find what they look for.
     */
    internal fun seedInitialStates(send: (JSONObject) -> Unit) {
        watchedEntities.addAll(referencedEntities())
        val seed = JSONObject()
        for ((id, state) in ctx.store.all()) seed.put(id, state.toHassJson())
        if (seed.length() > 0) {
            send(JSONObject().put("type", "patch").put("states", seed))
        }
    }

    /**
     * The `patch` for a batch of [changed] entities, or null when none concern this island. A card that follows
     * every entity gets them all (a removed one as `null`); any other card only the ones it watches.
     */
    internal fun patchFor(changed: Set<String>): JSONObject? {
        val patch = JSONObject()
        for (eid in changed) {
            if (!followsAllEntities && eid !in watchedEntities) continue
            val state = ctx.store[eid]
            // A removed entity goes as null, for a watched one too, so the card doesn't keep a stale state.
            patch.put(eid, state?.toHassJson() ?: JSONObject.NULL)
        }
        if (patch.length() == 0) return null
        return JSONObject().put("type", "patch").put("states", patch)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!fillViewport) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    gestureAxis = AXIS_NONE
                }
                MotionEvent.ACTION_MOVE -> if (gestureAxis == AXIS_NONE) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        gestureAxis = if (dx > dy) AXIS_HORIZONTAL else AXIS_VERTICAL
                        // Horizontal: keep the scroller out. Vertical: let it take the gesture over.
                        super.requestDisallowInterceptTouchEvent(gestureAxis == AXIS_HORIZONTAL)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestureAxis = AXIS_NONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The island's engine view may ask to keep every gesture; a vertical drag of an inline island is not its to keep. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (!fillViewport && disallowIntercept && gestureAxis == AXIS_VERTICAL) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    internal fun handleIslandMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
        when (msg.optString("type")) {
            "watch" -> {
                val id = msg.optString("entity_id", "")
                if (id.isNotBlank() && watchedEntities.add(id)) {
                    ctx.store[id]?.let { state ->
                        send(JSONObject().put("type", "patch").put("states", JSONObject().put(id, state.toHassJson())))
                    }
                }
            }
            "resize" -> {
                if (fillViewport) return
                val cssH = msg.optInt("height", 160).coerceIn(48, 1200)
                val pxH = ctx.px(cssH.toFloat())
                ctx.main.post {
                    val v = islandPage?.view ?: return@post
                    if (v.layoutParams?.height != pxH) {
                        v.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH)
                    }
                }
            }
            "callService" -> {
                val domain = msg.optString("domain", "")
                val service = msg.optString("service", "")
                if (domain.isNotEmpty() && service.isNotEmpty()) {
                    ctx.callService(domain, service, msg.optJSONObject("data"), null)
                }
            }
            "callWs" -> {
                val callId = msg.optInt("id", -1)
                val inner = msg.optJSONObject("msg") ?: return
                ctx.callWs(inner) { ok, result, error ->
                    val reply = JSONObject().put("type", "wsReply").put("id", callId).put("ok", ok)
                    if (ok) reply.put("result", result ?: JSONObject.NULL)
                    else reply.put("error", error ?: JSONObject().put("message", "callWS failed"))
                    send(reply)
                }
            }
            "moreInfo" -> {
                val id = msg.optString("entity_id", "")
                if (id.isNotBlank()) ctx.main.post { ctx.openMoreInfo(id) }
            }
            "action" -> {
                // An action with a `confirmation`: the native dialog asks first, as it does for native cards.
                val action = msg.optJSONObject("action") ?: return
                val entity = msg.optString("entity", "").ifEmpty { null }
                ctx.main.post { ctx.performAction(ActionConfig.parse(action), entity) }
            }
            "navigate" -> {
                // A card's `navigate` action / `history.pushState` + `location-changed` inside the island:
                // the path is a dashboard path (`/lovelace/1`), handed to Vitro's own navigation.
                val path = msg.optString("path", "").trim()
                if (path.isNotEmpty() && !path.startsWith("/__vitro/")) ctx.main.post { ctx.navigate(path) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sendToIsland = null
        slotId?.let { ctx.islandServer?.unregisterSlot(it) }
        slotId = null
        islandPage = null
    }

    companion object {
        private const val AXIS_NONE = 0
        private const val AXIS_HORIZONTAL = 1
        private const val AXIS_VERTICAL = 2
        private val VALID_BASE64 = Regex("""^[A-Za-z0-9+/=\s]+$""")

        /** Decodes a `data:` URI inline Lovelace JS resource into raw module source, or null for normal URLs. */
        fun decodeInlineModule(url: String): String? {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma).lowercase()
            val payload = url.substring(comma + 1)
            return runCatching {
                if (";base64" in meta) {
                    if (payload.isBlank() || !VALID_BASE64.matches(payload)) return null
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    // Percent-decoding only: a literal `+` in a data: URI is a plus, not a space.
                    java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
                }
            }.getOrNull()
        }

        /** A double-quoted HTML attribute value. `JSONObject.quote` would escape `/` as `\/` and break `/local/…` URLs. */
        internal fun htmlAttr(s: String): String =
            "\"" + s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;") + "\""

        /**
         * Builds the ~5 KB self-contained Lovelace card Micro-Host HTML for [config].
         * Runs inside GeckoView over [VitroIslandServer] with zero HA SPA overhead.
         */
        /**
         * `hass.user` for an island: the signed-in HA user's `id`, `name`, `is_admin` and `is_owner` (nothing else,
         * so no credentials reach card code), or null while `auth/current_user` hasn't answered, which leaves the
         * island on its anonymous `{ id: 'vitro', name: 'Panel' }` user.
         */
        fun islandUser(user: JSONObject): JSONObject? {
            val id = user.optString("id", "")
            if (id.isEmpty()) return null
            return JSONObject()
                .put("id", id)
                .put("name", user.optString("name", "").ifEmpty { "Panel" })
                .put("is_admin", user.optBoolean("is_admin", false))
                .put("is_owner", user.optBoolean("is_owner", false))
        }

        fun buildIslandHtml(
            ctx: VitroCardContext,
            config: CardConfig,
            fillViewport: Boolean = false,
            slotId: String = "s0",
        ): String {
            val tag = config.type.removePrefix("custom:")
            val cssVars = ctx.theme.toCssRootBlock()
            val resourceScripts = ctx.resources.joinToString("\n") { r ->
                val inlineJs = decodeInlineModule(r.url)
                when {
                    inlineJs != null -> "<script type=\"module\">\n${inlineJs.replace("</script", "<\\/script")}\n</script>"
                    r.type == "css" -> """<link rel="stylesheet" href=${htmlAttr(r.url)}>"""
                    r.type == "module" -> """<script type="module" src=${htmlAttr(r.url)}></script>"""
                    else -> """<script src=${htmlAttr(r.url)}></script>"""
                }
            }
            val cfgJson = config.raw.toString()
            val haCfgJson = ctx.haConfig().toString()
            val haUserJson = islandUser(ctx.haUser())?.toString() ?: "null"
            val tokenJson = JSONObject.quote(ctx.accessToken().orEmpty())
            val heightRule = if (fillViewport) "height: 100vh; width: 100vw;" else ""
            return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
$cssVars
html, body { margin: 0; padding: 0; background: transparent; color: var(--primary-text-color); font-family: Inter, system-ui, sans-serif; overflow: hidden; $heightRule }
#mount { width: 100%; ${if (fillViewport) "height: 100vh;" else ""} }
ha-card { display: block; background: var(--ha-card-background, var(--card-background-color)); border-radius: var(--ha-card-border-radius, 12px); color: var(--primary-text-color); }
ha-card.vitro-fallback { padding: 12px 16px; }
.vitro-title { font-size: 1.15em; font-weight: 500; padding-bottom: 8px; }
.vitro-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 6px 0; }
.vitro-state { color: var(--secondary-text-color); }
</style>
<script>
// Minimal HA custom-element shims for GeckoView Micro-Islands (zero HA SPA).
if (!customElements.get('ha-card')) {
  customElements.define('ha-card', class extends HTMLElement {});
}
if (!customElements.get('ha-icon')) {
  const iconCache = new Map();
  customElements.define('ha-icon', class extends HTMLElement {
    static get observedAttributes() { return ['icon']; }
    connectedCallback() { this._render(); }
    attributeChangedCallback() { this._render(); }
    set icon(v) { this.setAttribute('icon', v || ''); }
    get icon() { return this.getAttribute('icon') || ''; }
    _render() {
      const sz = getComputedStyle(this).getPropertyValue('--mdc-icon-size') || '24px';
      this.style.display = 'inline-flex';
      this.style.alignItems = 'center';
      this.style.justifyContent = 'center';
      this.style.width = sz;
      this.style.height = sz;
      const name = (this.getAttribute('icon') || 'mdi:home').replace(/^mdi:/, '');
      const cached = iconCache.get(name);
      if (cached) { this.innerHTML = cached; return; }
      fetch('/__vitro/icon/' + encodeURIComponent(name))
        .then((r) => r.ok ? r.text() : '')
        .then((svg) => {
          if (svg) {
            const styled = svg.replace('<svg ', '<svg style="width:100%;height:100%" ');
            iconCache.set(name, styled);
            this.innerHTML = styled;
          }
        })
        .catch(() => {});
    }
  });
}
// More HA elements upstream HACS cards render or extend (button-card, mini-media-player, …). Each is defined only
// when nothing else did, so a card bundle that ships its own wins.
const vitroDefine = (name, cls) => { if (!customElements.get(name)) customElements.define(name, cls); };
function vitroReflect(cls, props) {
  // A property per attribute, both ways, as Lit and Polymer elements behave.
  for (const p of props) {
    Object.defineProperty(cls.prototype, p, {
      configurable: true,
      get() { return this.getAttribute(p); },
      set(v) { if (v === null || v === undefined || v === false) this.removeAttribute(p); else this.setAttribute(p, v === true ? '' : String(v)); },
    });
  }
}
// A property set before the element upgraded sits on the instance and hides the class accessor: move it over.
function vitroUpgrade(el, props) {
  for (const p of props) {
    if (Object.prototype.hasOwnProperty.call(el, p)) { const v = el[p]; delete el[p]; el[p] = v; }
  }
}
const VITRO_DOMAIN_ICONS = {
  light: 'mdi:lightbulb', switch: 'mdi:flash', input_boolean: 'mdi:flash', fan: 'mdi:fan', cover: 'mdi:blinds',
  climate: 'mdi:thermostat', humidifier: 'mdi:air-humidifier', media_player: 'mdi:cast', lock: 'mdi:lock',
  person: 'mdi:account', device_tracker: 'mdi:account', sensor: 'mdi:eye', binary_sensor: 'mdi:checkbox-blank-circle',
  camera: 'mdi:video', weather: 'mdi:weather-partly-cloudy',
};
vitroDefine('iron-icon', class extends (customElements.get('ha-icon')) {});
vitroDefine('ha-state-icon', class extends HTMLElement {
  static get observedAttributes() { return ['icon']; }
  connectedCallback() { vitroUpgrade(this, ['icon', 'stateObj', 'hass']); this._render(); }
  attributeChangedCallback() { this._render(); }
  set icon(v) { this._icon = v || ''; this._render(); }
  get icon() { return this._icon || this.getAttribute('icon') || ''; }
  set stateObj(v) { this._stateObj = v; this._render(); }
  get stateObj() { return this._stateObj; }
  set hass(v) { this._hass = v; }
  get hass() { return this._hass; }
  _resolve() {
    if (this.icon) return this.icon;
    const st = this._stateObj;
    if (st && st.attributes && st.attributes.icon) return st.attributes.icon;
    const domain = st && st.entity_id ? String(st.entity_id).split('.')[0] : '';
    return VITRO_DOMAIN_ICONS[domain] || 'mdi:home';
  }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    let inner = this.firstElementChild;
    if (!inner || inner.localName !== 'ha-icon') {
      this.textContent = '';
      inner = document.createElement('ha-icon');
      this.appendChild(inner);
    }
    const icon = this._resolve();
    if (inner.getAttribute('icon') !== icon) inner.setAttribute('icon', icon);
  }
});
class VitroIconButton extends HTMLElement {
  static get observedAttributes() { return ['icon', 'path', 'disabled', 'label']; }
  connectedCallback() { vitroUpgrade(this, ['icon', 'path', 'label', 'disabled']); this._render(); }
  attributeChangedCallback() { this._render(); }
  set path(v) { this._path = v || ''; this._render(); }
  get path() { return this._path || this.getAttribute('path') || ''; }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    if (!this._button) {
      const kept = Array.from(this.childNodes);
      const b = document.createElement('button');
      b.type = 'button';
      b.style.cssText = 'border:0;background:transparent;color:inherit;cursor:pointer;padding:6px;display:inline-flex;align-items:center;justify-content:center;border-radius:50%;font:inherit';
      kept.forEach((n) => b.appendChild(n));
      this._slotted = kept;
      this._button = b;
      this.appendChild(b);
    }
    const b = this._button;
    b.disabled = this.hasAttribute('disabled');
    const label = this.getAttribute('label');
    if (label) b.setAttribute('aria-label', label); else b.removeAttribute('aria-label');
    const icon = this.icon;
    const path = this.path;
    if (icon) {
      b.textContent = '';
      const i = document.createElement('ha-icon');
      i.setAttribute('icon', icon);
      b.appendChild(i);
    } else if (path) {
      b.textContent = '';
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.style.cssText = 'width:var(--mdc-icon-size,24px);height:var(--mdc-icon-size,24px);fill:currentColor';
      const p = document.createElementNS(ns, 'path');
      p.setAttribute('d', path);
      svg.appendChild(p);
      b.appendChild(svg);
    } else if (this._slotted && !b.childNodes.length) {
      this._slotted.forEach((n) => b.appendChild(n));
    }
  }
}
vitroReflect(VitroIconButton, ['icon', 'label']);
Object.defineProperty(VitroIconButton.prototype, 'disabled', {
  configurable: true,
  get() { return this.hasAttribute('disabled'); },
  set(v) { if (v) this.setAttribute('disabled', ''); else this.removeAttribute('disabled'); },
});
vitroDefine('ha-icon-button', VitroIconButton);
vitroDefine('paper-icon-button', class extends VitroIconButton {});
class VitroSlider extends HTMLElement {
  static get observedAttributes() { return ['min', 'max', 'step', 'value', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['min', 'max', 'step', 'value', 'disabled']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'block';
    const i = document.createElement('input');
    i.type = 'range';
    i.style.cssText = 'width:100%;margin:0;accent-color:var(--slider-color, var(--primary-color))';
    const relay = (type) => (ev) => {
      ev.stopPropagation();
      this._value = Number(i.value);
      this.dispatchEvent(new Event(type, { bubbles: true, composed: true }));
    };
    i.addEventListener('input', relay('input'));
    i.addEventListener('change', relay('change'));
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() {
    const i = this._ensure();
    i.min = String(this.min);
    i.max = String(this.max);
    i.step = String(this.step);
    i.value = String(this.value);
    i.disabled = this.disabled;
  }
  _num(name, fallback) { const a = this.getAttribute(name); return a === null || a === '' || isNaN(Number(a)) ? fallback : Number(a); }
  get min() { return this._min !== undefined ? this._min : this._num('min', 0); }
  set min(v) { this._min = Number(v); this._sync(); }
  get max() { return this._max !== undefined ? this._max : this._num('max', 100); }
  set max(v) { this._max = Number(v); this._sync(); }
  get step() { return this._step !== undefined ? this._step : this._num('step', 1); }
  set step(v) { this._step = Number(v); this._sync(); }
  get value() { return this._value !== undefined ? this._value : this._num('value', 0); }
  set value(v) { this._value = Number(v); this._sync(); }
  get disabled() { return this._disabled !== undefined ? this._disabled : this.hasAttribute('disabled'); }
  set disabled(v) { this._disabled = !!v; this._sync(); }
}
vitroDefine('paper-slider', VitroSlider);
vitroDefine('ha-slider', class extends VitroSlider {});
vitroDefine('ha-switch', class extends HTMLElement {
  static get observedAttributes() { return ['checked', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['checked', 'disabled']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'inline-flex';
    const i = document.createElement('input');
    i.type = 'checkbox';
    i.style.cssText = 'margin:0;accent-color:var(--switch-checked-color, var(--primary-color))';
    i.addEventListener('change', (ev) => {
      ev.stopPropagation();
      this._checked = i.checked;
      this.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    });
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() { const i = this._ensure(); i.checked = this.checked; i.disabled = this.disabled; }
  get checked() { return this._checked !== undefined ? this._checked : this.hasAttribute('checked'); }
  set checked(v) { this._checked = !!v; this._sync(); }
  get disabled() { return this._disabled !== undefined ? this._disabled : this.hasAttribute('disabled'); }
  set disabled(v) { this._disabled = !!v; this._sync(); }
});
function vitroRelativeTime(when, now) {
  const t = when instanceof Date ? when.getTime() : (typeof when === 'number' ? when : Date.parse(String(when)));
  if (!isFinite(t)) return '';
  const delta = Math.round(((now === undefined ? Date.now() : now) - t) / 1000);
  const ahead = delta < 0;
  const abs = Math.abs(delta);
  if (abs < 45) return 'just now';
  const steps = [[60, 'second'], [3600, 'minute'], [86400, 'hour'], [604800, 'day'], [2592000, 'week'], [31536000, 'month']];
  let n = abs;
  let unit = 'year';
  let div = 31536000;
  for (let k = 1; k < steps.length; k++) {
    if (abs < steps[k][0]) { unit = steps[k][1]; div = steps[k - 1][0]; break; }
  }
  if (unit === 'year' && abs < 31536000) { unit = 'month'; div = 2592000; }
  n = Math.max(1, Math.round(abs / div));
  const label = n + ' ' + unit + (n === 1 ? '' : 's');
  return ahead ? 'in ' + label : label + ' ago';
}
window.vitroRelativeTime = vitroRelativeTime;
vitroDefine('ha-relative-time', class extends HTMLElement {
  static get observedAttributes() { return ['datetime']; }
  connectedCallback() { vitroUpgrade(this, ['datetime', 'hass']); this._render(); this._timer = setInterval(() => this._render(), 60000); }
  disconnectedCallback() { clearInterval(this._timer); }
  attributeChangedCallback() { this._render(); }
  set datetime(v) { this._datetime = v; this._render(); }
  get datetime() { return this._datetime !== undefined ? this._datetime : this.getAttribute('datetime'); }
  set hass(v) { this._hass = v; }
  get hass() { return this._hass; }
  _render() {
    const v = this.datetime;
    this.textContent = v === null || v === undefined || v === '' ? '' : vitroRelativeTime(v);
  }
});
vitroDefine('ha-ripple', class extends HTMLElement {});
vitroDefine('ha-spinner', class extends HTMLElement {});
vitroDefine('ha-formfield', class extends HTMLElement {});
vitroDefine('mwc-menu', class extends HTMLElement {});
vitroDefine('mwc-list-item', class extends HTMLElement {});
vitroDefine('ha-input', class extends HTMLElement {
  static get observedAttributes() { return ['value', 'placeholder', 'type', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['value']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'inline-block';
    const i = document.createElement('input');
    i.style.cssText = 'width:100%;box-sizing:border-box;font:inherit;color:inherit';
    i.addEventListener('input', () => { this._value = i.value; });
    i.addEventListener('change', () => { this._value = i.value; });
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() {
    const i = this._ensure();
    i.type = this.getAttribute('type') || 'text';
    i.placeholder = this.getAttribute('placeholder') || '';
    i.disabled = this.hasAttribute('disabled');
    i.value = this.value;
  }
  get value() { return this._value !== undefined ? this._value : (this.getAttribute('value') || ''); }
  set value(v) { this._value = v === null || v === undefined ? '' : String(v); this._sync(); }
});
// The latest hass object, for elements cards create through loadCardHelpers().
window.__vitroHass = null;
function vitroFallbackCard(c) {
  // A built-in card nested in a custom one (stack-in-card, auto-entities, mod-card): a calm summary, not HA's frontend.
  const card = document.createElement('ha-card');
  card.className = 'vitro-fallback';
  // Inline styles: inside another card's shadow root the page's own rules don't reach this card.
  card.style.cssText = 'padding:12px 16px;display:block';
  let cfg = c || {};
  const ids = () => {
    const list = [];
    if (cfg.entity) list.push(cfg.entity);
    for (const e of (cfg.entities || [])) {
      const id = typeof e === 'string' ? e : (e && e.entity);
      if (id) list.push(id);
    }
    return list;
  };
  const render = () => {
    const hass = card._hass;
    const list = ids();
    // Text goes in through textContent only: names and states never become markup.
    card.innerHTML = (cfg.title ? '<div class="vitro-title" style="font-size:1.15em;font-weight:500;padding-bottom:8px"></div>' : '') +
      list.map(() => '<div class="vitro-row" style="display:flex;justify-content:space-between;align-items:center;gap:12px;padding:6px 0"><span></span><span class="vitro-state" style="color:var(--secondary-text-color)"></span></div>').join('');
    if (cfg.title) card.querySelector('.vitro-title').textContent = cfg.title;
    const rowEls = card.querySelectorAll('.vitro-row');
    list.forEach((id, i) => {
      const st = hass && hass.states ? hass.states[id] : undefined;
      rowEls[i].children[0].textContent = (st && st.attributes && st.attributes.friendly_name) || id;
      rowEls[i].children[1].textContent = st && hass ? hass.formatEntityState(st) : '';
    });
    if (!cfg.title && !rowEls.length) card.textContent = cfg.type || 'card';
  };
  card.setConfig = (next) => { cfg = next || {}; render(); };
  Object.defineProperty(card, 'hass', { get() { return card._hass; }, set(h) { card._hass = h; render(); } });
  card.getCardSize = () => 1 + ids().length;
  render();
  return card;
}
function vitroCreateCardElement(c) {
  const cfg = c || {};
  const type = String(cfg.type || '');
  if (!type.startsWith('custom:')) {
    const el = vitroFallbackCard(cfg);
    if (window.__vitroHass) el.hass = window.__vitroHass;
    return el;
  }
  const t = type.slice(7);
  const el = document.createElement(t);
  const configure = () => {
    try { if (typeof el.setConfig === 'function') el.setConfig(cfg); } catch (e) { console.warn('setConfig', t, e); }
    if (window.__vitroHass && !el.hass) el.hass = window.__vitroHass;
  };
  if (customElements.get(t)) configure();
  else customElements.whenDefined(t).then(() => { if (customElements.upgrade) customElements.upgrade(el); configure(); });
  return el;
}
window.loadCardHelpers = () => Promise.resolve({
  createCardElement: vitroCreateCardElement,
  createHuiElement: (c) => vitroCreateCardElement(c),
  importMoreInfoControl: () => Promise.resolve(),
});
</script>
$resourceScripts
</head>
<body>
<div id="mount"></div>
<script type="module">
const rawStates = {};
const haConfig = $haCfgJson;
const haUser = $haUserJson;
const accessToken = $tokenJson;
const wsPending = new Map();
const outQueue = [];
let wsId = 1;
let sock = null;

function sendBridge(obj) {
  const line = JSON.stringify(obj) + '\n';
  if (sock && sock.readyState === WebSocket.OPEN) { sock.send(line); return true; }
  outQueue.push(line);
  return false;
}

const statesProxy = new Proxy(rawStates, {
  get(target, prop) {
    if (typeof prop === 'string' && prop.includes('.')) {
      sendBridge({ type: 'watch', entity_id: prop });
    }
    return target[prop];
  }
});

function vitroTitle(s) {
  const t = String(s == null ? '' : s).replace(/_/g, ' ');
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
}

function vitroLocalize(key, ...args) {
  if (!key) return '';
  const k = String(key);
  const m = k.match(/^component\.[^.]+\.entity_component\._\.state\.(.+)$/);
  let out = vitroTitle(m ? m[1] : k.split('.').pop());
  // HA passes placeholders as ('name', value, …) pairs or one { name: value } object; {0} is positional.
  const values = {};
  if (args.length === 1 && args[0] && typeof args[0] === 'object') Object.assign(values, args[0]);
  else for (let i = 0; i + 1 < args.length; i += 2) values[args[i]] = args[i + 1];
  args.forEach((a, i) => { if (typeof a !== 'object') values[String(i)] = a; });
  return out.replace(/\{([^}]+)\}/g, (all, name) => (name in values ? String(values[name]) : all));
}

function vitroFormatState(stateObj, state) {
  const s = state !== undefined && state !== null ? state : (stateObj ? stateObj.state : '');
  const str = String(s == null ? '' : s);
  const unit = stateObj && stateObj.attributes ? stateObj.attributes.unit_of_measurement : undefined;
  if (unit && str !== 'unavailable' && str !== 'unknown') return str + (unit === '%' ? '%' : ' ' + unit);
  return vitroTitle(str);
}

function vitroFormatAttribute(stateObj, attr, value) {
  const v = value !== undefined ? value : (stateObj && stateObj.attributes ? stateObj.attributes[attr] : undefined);
  if (v === undefined || v === null) return '';
  if (Array.isArray(v)) return v.map((x) => (typeof x === 'string' ? vitroTitle(x) : String(x))).join(', ');
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v === 'object') return JSON.stringify(v);
  return vitroTitle(v);
}

function vitroCallWS(msg) {
  return new Promise((resolve, reject) => {
    const id = wsId++;
    const entry = { resolve, reject, sent: false };
    wsPending.set(id, entry);
    entry.sent = sendBridge({ type: 'callWs', id, msg: msg || {} });
  });
}

// Same-origin: the island server proxies /api/... to Home Assistant with the panel's Bearer token.
function vitroCallApiRaw(method, path, parameters, headers) {
  const init = { method: String(method || 'GET').toUpperCase(), headers: Object.assign({}, headers || {}) };
  if (parameters !== undefined && parameters !== null && init.method !== 'GET') {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(parameters);
  }
  return fetch('/api/' + String(path || '').replace(/^\/+/, ''), init);
}

async function vitroCallApi(method, path, parameters, headers) {
  const r = await vitroCallApiRaw(method, path, parameters, headers);
  let body = null;
  if (r.status !== 204 && r.status !== 205) {
    const ct = r.headers.get('content-type') || '';
    const text = await r.text();
    body = text;
    if (ct.includes('application/json') && text) { try { body = JSON.parse(text); } catch (e) {} }
  }
  if (!r.ok) throw { error: (body && body.message) || ('Request failed: ' + r.status), status_code: r.status, body };
  return body;
}

const hassBase = {
  config: haConfig,
  language: 'en',
  selectedLanguage: null,
  translationMetadata: { fragments: [], translations: { en: { nativeName: 'English', isRTL: false } } },
  locale: { language: 'en', number_format: 'language', time_format: 'language' },
  themes: { darkMode: ${ctx.theme.dark}, default_theme: ${JSONObject.quote(ctx.theme.name)}, themes: {} },
  selectedTheme: null,
  user: { id: 'vitro', name: 'Panel', is_admin: false, is_owner: false },
  areas: {},
  devices: {},
  entities: {},
  floors: {},
  services: {},
  panels: {},
  resources: {},
  auth: { data: { access_token: accessToken, hassUrl: location.origin }, accessToken },
  connected: true,
  hassUrl: (path) => path ? new URL(path, location.origin).href : location.origin,
  // HA's own call_service over the bridge: the promise settles with Home Assistant's answer.
  callService(domain, service, data, target, notifyOnError, returnResponse) {
    const msg = { type: 'call_service', domain, service, service_data: data || {} };
    if (target) msg.target = target;
    if (returnResponse) msg.return_response = true;
    return vitroCallWS(msg);
  },
  callWS(msg) { return vitroCallWS(msg); },
  callApi: vitroCallApi,
  callApiRaw: vitroCallApiRaw,
  fetchWithAuth(path, init) { return fetch(path, init || {}); },
  localize: vitroLocalize,
  formatEntityState: vitroFormatState,
  formatEntityAttributeValue: vitroFormatAttribute,
  formatEntityAttributeName(stateObj, attr) { return vitroTitle(attr); },
  connection: { sendMessagePromise: (msg) => vitroCallWS(msg) },
};
if (haUser) hassBase.user = haUser;

// A new object on every patch: Lit cards compare hass by reference to decide whether to re-render.
function makeHass() {
  const h = Object.assign({}, hassBase, { states: statesProxy });
  window.__vitroHass = h;
  return h;
}

const tag = ${JSONObject.quote(tag)};
const cfg = $cfgJson;
let cardEl = null;

function connectBridge() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  sock = new WebSocket(proto + '//' + location.host + '/__vitro/ws/' + ${JSONObject.quote(slotId)});
  sock.onopen = () => {
    while (outQueue.length) sock.send(outQueue.shift());
    for (const entry of wsPending.values()) entry.sent = true;
  };
  sock.onmessage = (ev) => {
    const lines = String(ev.data || '').split('\n').filter(Boolean);
    for (const line of lines) {
      let m;
      try { m = JSON.parse(line); } catch (e) { continue; }
      if (m.type === 'patch' && m.states) {
        for (const id of Object.keys(m.states)) {
          if (m.states[id] === null) delete rawStates[id];
          else rawStates[id] = m.states[id];
        }
        if (cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'wsReply') {
        const entry = wsPending.get(m.id);
        if (!entry) continue;
        wsPending.delete(m.id);
        if (m.ok) entry.resolve(m.result);
        else entry.reject(m.error || new Error('callWS failed'));
      }
    }
  };
  sock.onclose = () => {
    // A call that went out on this socket gets no answer now: reject it (never resend, HA may have run it).
    for (const [id, entry] of wsPending) {
      if (entry.sent) { wsPending.delete(id); entry.reject(new Error('Connection lost')); }
    }
    setTimeout(connectBridge, 1500);
  };
}

async function boot() {
  connectBridge();
  try { await Promise.race([customElements.whenDefined(tag), new Promise(r => setTimeout(r, 4000))]); } catch (e) {}
  cardEl = document.createElement(tag);
  if (typeof cardEl.setConfig === 'function') cardEl.setConfig(cfg);
  cardEl.hass = makeHass();
  document.getElementById('mount').appendChild(cardEl);
  wireEvents();
  wireResize();
}

function moreInfo(id) {
  if (id) sendBridge({ type: 'moreInfo', entity_id: id });
}

function navigateTo(path) {
  // Never the island's own address: only a dashboard path a card navigated to.
  if (path && typeof path === 'string' && !path.startsWith('/__vitro/')) sendBridge({ type: 'navigate', path });
}

// HA's handleAction for a `hass-action` event: the card says which gesture, its config says what it does.
function runAction(config, gesture) {
  if (!config) return;
  const action = config[(gesture || 'tap') + '_action'] || (gesture === 'tap' || !gesture ? { action: 'more-info' } : null);
  if (!action) return;
  const entity = action.entity || config.entity;
  // A confirmation is asked natively, as for native cards: Cancel sends nothing.
  if (action.confirmation && action.action !== 'none') {
    sendBridge({ type: 'action', action, entity: entity || '' });
    return;
  }
  switch (action.action) {
    case 'more-info': moreInfo(entity); break;
    case 'navigate': navigateTo(action.navigation_path); break;
    case 'toggle': if (entity) hassBase.callService('homeassistant', 'toggle', { entity_id: entity }); break;
    case 'call-service':
    case 'perform-action': {
      const svc = String(action.perform_action || action.service || '');
      const dot = svc.indexOf('.');
      if (dot > 0) hassBase.callService(svc.slice(0, dot), svc.slice(dot + 1), action.data || action.service_data, action.target);
      break;
    }
    case 'url': if (action.url_path) window.open(action.url_path, '_blank'); break;
    default: break;
  }
}

function wireEvents() {
  // HA's fireEvent bubbles and is composed, so window hears what any card or nested card sends.
  window.addEventListener('hass-more-info', (ev) => {
    const id = ev && ev.detail && ev.detail.entityId;
    if (id) sendBridge({ type: 'moreInfo', entity_id: id });
  });
  window.addEventListener('hass-action', (ev) => {
    const d = (ev && ev.detail) || {};
    runAction(d.config, d.action);
  });
  // HA's navigate(): history.pushState, then `location-changed` on window.
  window.addEventListener('location-changed', (ev) => {
    const p = (ev && ev.detail && ev.detail.path) || (location.pathname + location.search);
    navigateTo(p);
  });
  window.addEventListener('hass-navigate', (ev) => {
    const p = ev && ev.detail && ev.detail.path;
    navigateTo(p);
  });
}

function wireResize() {
  const mount = document.getElementById('mount');
  let timer = null;
  let lastH = -1;
  const reportSize = () => {
    timer = null;
    const h = Math.ceil(mount.getBoundingClientRect().height || 0);
    // 0 px is a card that has not rendered yet: reporting it would collapse the island and flash.
    if (h <= 0 || h === lastH) return;
    lastH = h;
    sendBridge({ type: 'resize', height: h });
  };
  const scheduleResize = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(reportSize, 50);
  };
  if (window.ResizeObserver) new ResizeObserver(scheduleResize).observe(mount);
  scheduleResize();
}
boot();
</script>
</body>
</html>
""".trimIndent()
        }
    }
}
